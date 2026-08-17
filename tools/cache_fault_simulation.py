#!/usr/bin/env python3
"""Model-based fault simulation for TailTune's cache/offline invariants.

This is intentionally independent of Android APIs. It stress-tests the logical
properties that the SQLite/file implementation is supposed to preserve across
remote syncs, shared songs, file corruption, removals and reconciliation.
"""
from __future__ import annotations
import argparse
import json
import random
from dataclasses import dataclass, field
from pathlib import Path

@dataclass
class Song:
    id: str
    size: int
    file_size: int | None = None

@dataclass
class Playlist:
    id: str
    songs: list[str] = field(default_factory=list)
    remote_present: bool = True
    offline_requested: bool = False
    offline_complete: bool = False

class Model:
    def __init__(self, rng: random.Random):
        self.rng = rng
        self.playlists: dict[str, Playlist] = {}
        self.songs: dict[str, Song] = {}
        self.remote_ids: set[str] = set()

    def ensure_song(self, sid: str | None = None) -> Song:
        sid = sid or f"s{self.rng.randrange(80)}"
        song = self.songs.get(sid)
        if song is None:
            song = Song(sid, self.rng.randint(1, 10_000))
            self.songs[sid] = song
        return song

    def remote_summary_sync(self, ids: set[str]):
        # Mirrors TailTune's safety rule: an empty server response does not prune
        # a useful cache. A non-empty response may prune only non-offline rows.
        if not ids:
            return
        self.remote_ids = set(ids)
        for p in self.playlists.values():
            p.remote_present = p.id in ids
        for pid in ids:
            self.playlists.setdefault(pid, Playlist(pid)).remote_present = True
        for pid in list(self.playlists):
            p = self.playlists[pid]
            if not p.remote_present and not p.offline_requested:
                del self.playlists[pid]
        # Immediately after a non-empty summary sync, anything retained despite
        # being absent remotely must be retained because it was requested offline.
        assert all(p.remote_present or p.offline_requested for p in self.playlists.values())
        self.cleanup_orphans()

    def save_details(self, pid: str):
        p = self.playlists.setdefault(pid, Playlist(pid))
        p.remote_present = True
        count = self.rng.randrange(0, 16)
        p.songs = [self.ensure_song().id for _ in range(count)]
        # Deduplicate while preserving order: Navidrome song IDs are the stable key.
        p.songs = list(dict.fromkeys(p.songs))
        self.update_completion(p)
        self.cleanup_orphans()

    def request_offline(self, pid: str):
        p = self.playlists.setdefault(pid, Playlist(pid))
        if not p.songs:
            self.save_details(pid)
            p = self.playlists[pid]
        p.offline_requested = True
        self.update_completion(p)

    def download_one(self, pid: str):
        p = self.playlists.get(pid)
        if not p or not p.songs:
            return
        sid = self.rng.choice(p.songs)
        song = self.ensure_song(sid)
        song.file_size = song.size
        self.recompute_all_completion()

    def corrupt_one(self):
        candidates = [s for s in self.songs.values() if s.file_size is not None]
        if not candidates:
            return
        song = self.rng.choice(candidates)
        mode = self.rng.choice(["missing", "short", "oversize"])
        if mode == "missing":
            song.file_size = None
        elif mode == "short":
            song.file_size = max(0, song.size - self.rng.randint(1, song.size))
        else:
            song.file_size = song.size + self.rng.randint(1, 1000)
        # Before reconciliation metadata could transiently look stale. The model
        # calls reconcile separately, as the real maintenance executor does.

    def reconcile(self):
        for song in self.songs.values():
            if song.file_size is not None and song.file_size != song.size:
                song.file_size = None
        self.recompute_all_completion()

    def remove_offline(self, pid: str):
        p = self.playlists.get(pid)
        if not p:
            return
        p.offline_requested = False
        p.offline_complete = False
        # Delete a song file only when no *other* offline playlist references it.
        for sid in list(p.songs):
            if not any(
                other.id != pid and other.offline_requested and sid in other.songs
                for other in self.playlists.values()
            ):
                song = self.songs.get(sid)
                if song:
                    song.file_size = None
        self.cleanup_orphans()
        self.recompute_all_completion()

    def cleanup_orphans(self):
        referenced = {sid for p in self.playlists.values() for sid in p.songs}
        for sid in list(self.songs):
            s = self.songs[sid]
            if sid not in referenced and s.file_size is None:
                del self.songs[sid]

    def update_completion(self, p: Playlist):
        p.offline_complete = bool(p.songs) and all(
            (sid in self.songs and self.songs[sid].file_size == self.songs[sid].size)
            for sid in p.songs
        )

    def recompute_all_completion(self):
        for p in self.playlists.values():
            self.update_completion(p)

    def assert_invariants(self):
        # Every relation resolves to a song.
        for p in self.playlists.values():
            assert all(sid in self.songs for sid in p.songs), (p.id, p.songs)

        # Offline complete is exact, never optimistic.
        for p in self.playlists.values():
            expected = bool(p.songs) and all(
                self.songs[sid].file_size == self.songs[sid].size for sid in p.songs
            )
            assert p.offline_complete == expected, (p.id, p.offline_complete, expected)

        # A finalized usable file has positive exact size.
        for s in self.songs.values():
            if s.file_size is not None:
                assert s.file_size > 0



def run(seed: int, trials: int, steps: int) -> dict:
    rng = random.Random(seed)
    operations = 0
    for trial in range(trials):
        model = Model(random.Random(rng.randrange(2**63)))
        # Seed a useful cache to specifically exercise empty remote responses.
        for pid in ["p0", "p1", "p2"]:
            model.save_details(pid)
        model.request_offline("p0")
        before_empty = set(model.playlists)
        model.remote_summary_sync(set())
        assert set(model.playlists) == before_empty

        for _ in range(steps):
            op = model.rng.randrange(8)
            pid = f"p{model.rng.randrange(30)}"
            if op == 0:
                ids = {f"p{model.rng.randrange(30)}" for _ in range(model.rng.randrange(1, 12))}
                model.remote_summary_sync(ids)
            elif op == 1:
                # Explicitly exercise empty response safety.
                before = set(model.playlists)
                model.remote_summary_sync(set())
                assert set(model.playlists) == before
            elif op == 2:
                model.save_details(pid)
            elif op == 3:
                model.request_offline(pid)
            elif op == 4:
                model.download_one(pid)
            elif op == 5:
                model.remove_offline(pid)
            elif op == 6:
                model.corrupt_one()
                model.reconcile()
            else:
                model.reconcile()
            model.assert_invariants()
            operations += 1

    # Dedicated shared-file retention scenario.
    for _ in range(100_000):
        m = Model(random.Random(rng.randrange(2**63)))
        shared = m.ensure_song("shared")
        shared.file_size = shared.size
        m.playlists["a"] = Playlist("a", ["shared"], True, True, True)
        m.playlists["b"] = Playlist("b", ["shared"], True, True, True)
        m.remove_offline("a")
        assert m.songs["shared"].file_size == shared.size
        assert m.playlists["b"].offline_complete
        operations += 1

    return {
        "seed": seed,
        "random_trials": trials,
        "steps_per_trial": steps,
        "total_modeled_operations": operations,
        "dedicated_shared_file_cases": 100_000,
        "result": "PASS",
        "note": "Logical model simulation; Android SQLite/filesystem behavior still requires device tests."
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=0x5441494C)
    parser.add_argument("--trials", type=int, default=3000)
    parser.add_argument("--steps", type=int, default=300)
    parser.add_argument("--output")
    args = parser.parse_args()
    report = run(args.seed, args.trials, args.steps)
    text = json.dumps(report, indent=2)
    print(text)
    if args.output:
        path = Path(args.output)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
