const state = {
  playlists: [],
  playback: null,
  view: "playlists",
  openPlaylist: null,
  search: ""
};

const $ = (selector) => document.querySelector(selector);
const content = $("#content");
const errorBox = $("#error");

async function api(path, body) {
  const options = body ? {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  } : {};
  const response = await fetch(path, options);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `Request failed (${response.status})`);
  return data;
}

function showError(error) {
  errorBox.hidden = false;
  errorBox.textContent = error?.message || String(error);
}

function clearError() {
  errorBox.hidden = true;
  errorBox.textContent = "";
}

async function load() {
  clearError();
  try {
    const config = await api("/api/config");
    if (!config.configured) {
      $("#connection").textContent = "Configure Navidrome on the Samsung";
      empty("Open TailTune on the Samsung and enter the same Navidrome account used by Substreamer.");
      return;
    }
    const [playlistData, playback] = await Promise.all([
      api("/api/playlists"),
      api("/api/state")
    ]);
    state.playlists = playlistData.playlists || [];
    state.playback = playback;
    $("#connection").textContent = `${state.playlists.length} Navidrome playlists`;
    render();
  } catch (error) {
    $("#connection").textContent = "Connection failed";
    showError(error);
  }
}

async function sendControl(action, extra = {}) {
  clearError();
  try {
    state.playback = await api("/api/control", { action, ...extra });
    renderPlayer();
    if (state.view === "queue") render();
  } catch (error) {
    showError(error);
  }
}

async function openPlaylist(id) {
  content.innerHTML = '<div class="loading">Loading playlist…</div>';
  clearError();
  try {
    state.openPlaylist = await api(`/api/playlist?id=${encodeURIComponent(id)}`);
    render();
  } catch (error) {
    showError(error);
  }
}

function renderPlayer() {
  const playback = state.playback;
  const current = playback?.current;
  $("#now-title").textContent = current?.title || "Nothing playing";
  $("#now-artist").textContent = current?.artist || "Choose a playlist";
  $("#toggle").textContent = playback?.playing ? "⏸" : "▶";
  const duration = Math.max(1, playback?.durationMs || 1);
  $("#seek").value = Math.round(((playback?.positionMs || 0) / duration) * 1000);
  if (playback?.error) showError(new Error(playback.error));
}

function render() {
  document.querySelectorAll(".tab").forEach(button => {
    button.classList.toggle("active", button.dataset.view === state.view);
  });
  content.replaceChildren();

  if (state.view === "playlists") {
    if (state.openPlaylist) renderPlaylistDetail();
    else renderPlaylistList();
  } else {
    renderQueue();
  }
  renderPlayer();
}

function renderPlaylistList() {
  const query = state.search.trim().toLowerCase();
  const playlists = state.playlists.filter(item => item.name.toLowerCase().includes(query));
  if (!playlists.length) return empty("No matching playlists");

  playlists.forEach(playlist => {
    const card = document.createElement("article");
    card.className = "playlist-card";

    const open = document.createElement("button");
    open.className = "playlist-open";
    const title = document.createElement("strong");
    title.textContent = playlist.name;
    const meta = document.createElement("span");
    meta.textContent = `${playlist.songCount} tracks${playlist.owner ? ` · ${playlist.owner}` : ""}`;
    open.append(title, meta);
    open.addEventListener("click", () => openPlaylist(playlist.id));

    const play = document.createElement("button");
    play.className = "play-button";
    play.textContent = "▶";
    play.setAttribute("aria-label", `Play ${playlist.name}`);
    play.addEventListener("click", () => sendControl("playPlaylist", {
      playlistId: playlist.id,
      startIndex: 0
    }));

    card.append(open, play);
    content.append(card);
  });
}

function renderPlaylistDetail() {
  const data = state.openPlaylist;
  const playlist = data.playlist;
  const query = state.search.trim().toLowerCase();

  const header = document.createElement("div");
  header.className = "detail-header";

  const back = document.createElement("button");
  back.textContent = "‹ Back";
  back.addEventListener("click", () => {
    state.openPlaylist = null;
    render();
  });

  const titleBox = document.createElement("div");
  titleBox.className = "detail-title";
  const title = document.createElement("strong");
  title.textContent = playlist.name;
  const meta = document.createElement("span");
  meta.textContent = `${data.songs.length} tracks`;
  titleBox.append(title, meta);

  const playAll = document.createElement("button");
  playAll.className = "play-all";
  playAll.textContent = "▶ Play all";
  playAll.addEventListener("click", () => sendControl("playPlaylist", {
    playlistId: playlist.id,
    startIndex: 0
  }));

  header.append(back, titleBox, playAll);
  content.append(header);

  const songs = data.songs.filter(song =>
    `${song.title} ${song.artist} ${song.album}`.toLowerCase().includes(query)
  );
  if (!songs.length) return empty("No matching tracks");

  songs.forEach(song => {
    const row = document.createElement("article");
    row.className = "row";

    const main = document.createElement("button");
    main.className = "row-main";
    const songTitle = document.createElement("strong");
    songTitle.textContent = `${song.index + 1}. ${song.title}`;
    const songMeta = document.createElement("span");
    songMeta.textContent = `${song.artist} · ${song.album}`;
    main.append(songTitle, songMeta);
    main.addEventListener("click", () => sendControl("playPlaylist", {
      playlistId: playlist.id,
      startIndex: song.index
    }));

    const actions = document.createElement("div");
    actions.className = "row-actions";
    const add = document.createElement("button");
    add.textContent = "＋";
    add.setAttribute("aria-label", `Add ${song.title} to queue`);
    add.addEventListener("click", () => sendControl("addFromPlaylist", {
      playlistId: playlist.id,
      index: song.index
    }));
    actions.append(add);

    row.append(main, actions);
    content.append(row);
  });
}

function renderQueue() {
  const queue = state.playback?.queue || [];
  const query = state.search.trim().toLowerCase();
  const filtered = queue.filter(item =>
    `${item.title} ${item.artist} ${item.album}`.toLowerCase().includes(query)
  );
  if (!filtered.length) return empty(queue.length ? "No matching queue items" : "Queue is empty");

  filtered.forEach(item => {
    const row = document.createElement("article");
    row.className = "row";
    if (item.index === state.playback?.current?.index) row.classList.add("current-row");

    const main = document.createElement("button");
    main.className = "row-main";
    const title = document.createElement("strong");
    title.textContent = `${item.index + 1}. ${item.title}`;
    const meta = document.createElement("span");
    meta.textContent = `${item.artist} · ${item.album}`;
    main.append(title, meta);
    main.addEventListener("click", () => sendControl("jump", { index: item.index }));

    const actions = document.createElement("div");
    actions.className = "row-actions";

    const up = document.createElement("button");
    up.textContent = "↑";
    up.disabled = item.index === 0;
    up.addEventListener("click", () => queueMove(item.index, item.index - 1));

    const down = document.createElement("button");
    down.textContent = "↓";
    down.disabled = item.index === queue.length - 1;
    down.addEventListener("click", () => queueMove(item.index, item.index + 1));

    const remove = document.createElement("button");
    remove.textContent = "×";
    remove.addEventListener("click", () => queueRemove(item.index));

    actions.append(up, down, remove);
    row.append(main, actions);
    content.append(row);
  });
}

async function queueMove(from, to) {
  clearError();
  try {
    state.playback = await api("/api/queue", { action: "move", from, to });
    render();
  } catch (error) {
    showError(error);
  }
}

async function queueRemove(index) {
  clearError();
  try {
    state.playback = await api("/api/queue", { action: "remove", index });
    render();
  } catch (error) {
    showError(error);
  }
}

function empty(message) {
  content.replaceChildren();
  const block = document.createElement("div");
  block.className = "empty";
  block.textContent = message;
  content.append(block);
}

document.querySelectorAll("[data-control]").forEach(button => {
  button.addEventListener("click", () => sendControl(button.dataset.control));
});

$("#toggle").addEventListener("click", () => sendControl("toggle"));

$("#seek").addEventListener("change", event => {
  const duration = state.playback?.durationMs || 0;
  sendControl("seek", {
    positionMs: Math.round(duration * Number(event.target.value) / 1000)
  });
});

document.querySelectorAll(".tab").forEach(button => {
  button.addEventListener("click", () => {
    state.view = button.dataset.view;
    if (state.view !== "playlists") state.openPlaylist = null;
    render();
  });
});

$("#search").addEventListener("input", event => {
  state.search = event.target.value;
  render();
});

$("#refresh").addEventListener("click", async () => {
  clearError();
  try {
    const result = await api("/api/refresh", {});
    state.playlists = result.playlists || [];
    state.openPlaylist = null;
    $("#connection").textContent = `${state.playlists.length} Navidrome playlists`;
    render();
  } catch (error) {
    showError(error);
  }
});

load();
setInterval(async () => {
  try {
    state.playback = await api("/api/state");
    renderPlayer();
    if (state.view === "queue") render();
  } catch (_) {
    // A temporary Wi-Fi interruption should not erase the current screen.
  }
}, 1200);
