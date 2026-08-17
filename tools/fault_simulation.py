#!/usr/bin/env python3
"""TailTune lifecycle fault model.

This is a deterministic model-based stress test, not an Android emulator. It
exercises the invariants implemented by RemoteServerService: generation-based
stale-start rejection, sticky recovery, binding recovery, watchdog restart and
explicit user-stop semantics.
"""
from __future__ import annotations
from dataclasses import dataclass
import argparse
import json
import random
from pathlib import Path


@dataclass
class Model:
    enabled: bool = False
    remote_alive: bool = False
    bind_requested: bool = False
    playback_alive: bool = False
    playback_initializing: bool = False
    playback_ready: bool = False
    server_starting: bool = False
    server_alive: bool = False
    server_generation: int = 0
    pending_generation: int | None = None
    port_free: bool = True
    sticky_restart_due: int = 0
    retry_due: int = 0
    init_due: int = 0
    watchdog_due: int = 0

    def start(self) -> None:
        self.enabled = True
        if not self.remote_alive:
            self.remote_alive = True
        self._ensure_bind()

    def stop(self) -> None:
        self.enabled = False
        self.remote_alive = False
        self._stop_server()
        self.bind_requested = False
        self.playback_alive = False
        self.playback_initializing = False
        self.playback_ready = False
        self.retry_due = self.init_due = self.sticky_restart_due = 0

    def _ensure_bind(self) -> None:
        if not (self.enabled and self.remote_alive) or self.bind_requested:
            return
        self.bind_requested = True
        self.playback_alive = True
        self.playback_initializing = True
        self.playback_ready = False
        self.init_due = 1

    def _playback_success(self) -> None:
        if not (self.bind_requested and self.playback_alive):
            return
        self.playback_initializing = False
        self.playback_ready = True
        self._start_server()

    def playback_failure(self) -> None:
        if not self.playback_alive:
            return
        self.playback_initializing = False
        self.playback_ready = False
        self.playback_alive = False
        self.bind_requested = False
        self._stop_server()
        if self.enabled and self.remote_alive:
            self.retry_due = 1

    def playback_disconnect(self) -> None:
        self.playback_alive = False
        self.playback_initializing = False
        self.playback_ready = False
        self.bind_requested = False
        self._stop_server()
        if self.enabled and self.remote_alive:
            self.retry_due = 1

    def bind_timeout(self) -> None:
        if self.bind_requested and not self.playback_ready:
            self.playback_failure()

    def _start_server(self) -> None:
        if not (self.enabled and self.remote_alive and self.playback_ready):
            return
        if self.server_alive or self.server_starting:
            return
        self.server_starting = True
        self.pending_generation = self.server_generation

    def complete_server_start(self) -> None:
        if not self.server_starting:
            return
        generation = self.pending_generation
        self.server_starting = False
        self.pending_generation = None
        stale = (
            generation != self.server_generation
            or not self.enabled
            or not self.remote_alive
            or not self.playback_ready
        )
        if not stale and self.port_free:
            self.server_alive = True
            self.watchdog_due = 3
        elif not stale:
            self.retry_due = 1

    def _stop_server(self) -> None:
        self.server_generation += 1
        self.server_alive = False
        # A start already executing is allowed to finish, but generation makes
        # its completion stale and therefore unable to attach.

    def listener_dies(self) -> None:
        if self.server_alive:
            self.server_alive = False
            self.watchdog_due = 1

    def process_kill(self) -> None:
        # Whole-process kill takes both same-process services and sockets.
        self.remote_alive = False
        self.bind_requested = False
        self.playback_alive = False
        self.playback_initializing = False
        self.playback_ready = False
        self._stop_server()
        self.server_starting = False
        self.pending_generation = None
        if self.enabled:
            self.sticky_restart_due = 1

    def tick(self) -> None:
        if self.sticky_restart_due:
            self.sticky_restart_due -= 1
            if self.sticky_restart_due == 0 and self.enabled:
                self.remote_alive = True
                self._ensure_bind()

        if self.retry_due:
            self.retry_due -= 1
            if self.retry_due == 0 and self.enabled and self.remote_alive:
                if not self.playback_ready:
                    self._ensure_bind()
                else:
                    self._start_server()

        if self.init_due:
            self.init_due -= 1
            if self.init_due == 0 and self.playback_initializing:
                self._playback_success()

        if self.server_starting:
            self.complete_server_start()

        if self.watchdog_due:
            self.watchdog_due -= 1
            if self.watchdog_due == 0 and self.enabled and self.remote_alive:
                if self.playback_ready and not self.server_alive:
                    self._start_server()
                self.watchdog_due = 3

    def assert_invariants(self) -> None:
        if self.server_alive:
            assert self.enabled, "server alive while remote disabled"
            assert self.remote_alive, "server alive while remote service dead"
            assert self.playback_ready, "server alive without ready playback"
            assert self.bind_requested, "server alive without playback binding"
        if self.playback_ready:
            assert self.playback_alive and self.bind_requested
        if not self.enabled:
            assert not self.server_alive, "explicit user stop left server alive"
        if self.server_starting:
            assert self.pending_generation is not None


def settle_healthy(m: Model, ticks: int = 20) -> None:
    m.port_free = True
    if m.enabled and not m.remote_alive:
        m.sticky_restart_due = 1
    for _ in range(ticks):
        m.tick()
        m.assert_invariants()
    if m.enabled:
        assert m.remote_alive and m.playback_ready and m.server_alive, (
            "healthy enabled system did not self-recover", m
        )
    else:
        assert not m.server_alive


def run(seed: int, trials: int, steps: int) -> dict:
    rng = random.Random(seed)
    transitions = 0
    events = [
        "start", "stop", "tick", "tick", "tick", "process_kill",
        "playback_failure", "playback_disconnect", "bind_timeout",
        "listener_dies", "port_busy", "port_free", "stale_start_race"
    ]

    for _ in range(trials):
        m = Model()
        for _ in range(steps):
            event = rng.choice(events)
            transitions += 1
            if event == "start":
                m.start()
            elif event == "stop":
                m.stop()
            elif event == "tick":
                m.tick()
            elif event == "process_kill":
                m.process_kill()
            elif event == "playback_failure":
                m.playback_failure()
            elif event == "playback_disconnect":
                m.playback_disconnect()
            elif event == "bind_timeout":
                m.bind_timeout()
            elif event == "listener_dies":
                m.listener_dies()
            elif event == "port_busy":
                m.port_free = False
                if m.server_alive:
                    # Existing listener owns the port; an unrelated later busy
                    # condition cannot evict it.
                    pass
            elif event == "port_free":
                m.port_free = True
                if m.enabled and m.remote_alive and m.playback_ready and not m.server_alive:
                    m.retry_due = 1
            elif event == "stale_start_race":
                if m.enabled and m.remote_alive and m.playback_ready and not m.server_alive:
                    m._start_server()
                    old_generation = m.pending_generation
                    m._stop_server()
                    m.complete_server_start()
                    assert not m.server_alive
                    assert old_generation != m.server_generation
            m.assert_invariants()

        # End each trial in either explicitly stopped or a healthy environment
        # and verify liveness/recovery rather than only safety invariants.
        if rng.random() < 0.75:
            m.start()
        else:
            m.stop()
        settle_healthy(m)

    # Dedicated stale-start stress: a server socket completing after stop/reload
    # must never reattach across a generation change.
    stale_races = 100_000
    for _ in range(stale_races):
        m = Model()
        m.start(); m.tick()  # playback ready + server start may complete
        m.server_alive = False
        m._start_server()
        old = m.pending_generation
        m._stop_server()
        m.complete_server_start()
        assert not m.server_alive
        assert old != m.server_generation
        m.assert_invariants()

    return {
        "seed": seed,
        "random_trials": trials,
        "steps_per_trial": steps,
        "random_transitions": transitions,
        "dedicated_stale_start_races": stale_races,
        "total_modeled_operations": transitions + stale_races,
        "result": "PASS",
        "note": "Model-based lifecycle simulation; not a substitute for Android device/emulator testing."
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=0x5441494C)
    parser.add_argument("--trials", type=int, default=5_000)
    parser.add_argument("--steps", type=int, default=300)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = run(args.seed, args.trials, args.steps)
    text = json.dumps(report, indent=2)
    print(text)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
