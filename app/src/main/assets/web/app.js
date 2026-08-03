const state = {
  playlists: [],
  playback: null,
  downloads: new Map(),
  storage: null,
  sync: { state: "idle", syncing: false, online: false },
  view: "playlists",
  openPlaylist: null,
  search: "",
  socketConnected: false
};

const $ = selector => document.querySelector(selector);
const content = $("#content");
const errorBox = $("#error");
const downloadBanner = $("#download-banner");
let socket = null;
let reconnectDelay = 500;
let fallbackTimer = null;

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

function applyLibrary(data) {
  state.playlists = data.playlists || [];
  state.sync = data.sync || state.sync;
  state.storage = data.storage || state.storage;
  updateConnectionText();
}

function applyDownloads(data) {
  state.storage = data.storage || state.storage;
  state.downloads = new Map((data.downloads || []).map(item => [item.playlistId, item]));
  renderDownloadBanner();
}

function applySnapshot(data) {
  applyLibrary(data.library || {});
  state.playback = data.playback || state.playback;
  applyDownloads(data.downloads || {});
  render();
}

async function loadBootstrap() {
  clearError();
  try {
    applySnapshot(await api("/api/bootstrap"));
  } catch (error) {
    $("#connection").textContent = "Samsung not reachable";
    showError(error);
  }
}

function connectWebSocket() {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) return;
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  socket = new WebSocket(`${protocol}//${location.host}/ws`);

  socket.addEventListener("open", () => {
    state.socketConnected = true;
    reconnectDelay = 500;
    stopFallbackPolling();
    updateConnectionText();
  });

  socket.addEventListener("message", event => {
    try {
      const message = JSON.parse(event.data);
      switch (message.type) {
        case "snapshot":
          applySnapshot(message.data || {});
          break;
        case "playback":
          state.playback = message.data;
          renderPlayer();
          if (state.view === "queue") renderQueueOnly();
          break;
        case "library":
          applyLibrary(message.data || {});
          render();
          break;
        case "downloads":
          applyDownloads(message.data || {});
          render();
          break;
      }
    } catch (error) {
      showError(error);
    }
  });

  socket.addEventListener("close", () => {
    state.socketConnected = false;
    updateConnectionText();
    startFallbackPolling();
    const delay = reconnectDelay;
    reconnectDelay = Math.min(reconnectDelay * 2, 10_000);
    setTimeout(connectWebSocket, delay);
  });

  socket.addEventListener("error", () => socket.close());
}

function startFallbackPolling() {
  if (fallbackTimer) return;
  fallbackTimer = setInterval(loadBootstrap, 4_000);
}

function stopFallbackPolling() {
  if (!fallbackTimer) return;
  clearInterval(fallbackTimer);
  fallbackTimer = null;
}

function updateConnectionText() {
  const live = state.socketConnected ? "Live" : "Reconnecting";
  let serverMode = "Cached library";
  if (state.sync?.syncing) {
    const progress = state.sync.total > 0 ? ` ${state.sync.completed}/${state.sync.total}` : "";
    serverMode = `Syncing${progress}`;
  } else if (state.sync?.online) {
    serverMode = "Navidrome online";
  } else if (state.playlists.length) {
    serverMode = "Offline/cached";
  }
  $("#connection").textContent = `${state.playlists.length} playlists · ${serverMode} · ${live}`;
}

async function sendControl(action, extra = {}) {
  clearError();
  try {
    state.playback = await api("/api/control", { action, ...extra });
    renderPlayer();
    if (state.view === "queue") renderQueueOnly();
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
    state.openPlaylist = null;
    showError(error);
    render();
  }
}

function renderPlayer() {
  const playback = state.playback;
  const current = playback?.current;
  $("#now-title").textContent = current?.title || "Nothing playing";
  const source = current?.offline ? " · Offline" : "";
  $("#now-artist").textContent = current
    ? `${current.artist || "Unknown artist"}${source}`
    : "Choose a playlist";
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
  renderDownloadBanner();
  updateConnectionText();
}

function renderQueueOnly() {
  if (state.view !== "queue") return;
  content.replaceChildren();
  renderQueue();
  renderPlayer();
}

function downloadFor(playlistId) {
  return state.downloads.get(playlistId) || null;
}

function renderPlaylistList() {
  const query = state.search.trim().toLowerCase();
  const playlists = state.playlists.filter(item => item.name.toLowerCase().includes(query));
  if (!playlists.length) {
    if (state.sync?.syncing) return empty("Library is syncing in the background…");
    return empty("No matching playlists");
  }

  playlists.forEach(playlist => {
    const card = document.createElement("article");
    card.className = "playlist-card";

    const open = document.createElement("button");
    open.className = "playlist-open";
    const title = document.createElement("strong");
    title.textContent = playlist.name;
    const meta = document.createElement("span");
    const download = downloadFor(playlist.id);
    const offlineText = download?.complete
      ? " · Downloaded"
      : download && download.downloadedCount > 0
        ? ` · ${download.downloadedCount}/${download.totalCount} offline`
        : "";
    meta.textContent = `${playlist.songCount} tracks${playlist.owner ? ` · ${playlist.owner}` : ""}${offlineText}`;
    open.append(title, meta);
    open.addEventListener("click", () => openPlaylist(playlist.id));

    const downloadButton = makeDownloadButton(playlist);

    const play = document.createElement("button");
    play.className = "play-button";
    play.textContent = "▶";
    play.setAttribute("aria-label", `Play ${playlist.name}`);
    play.addEventListener("click", () => sendControl("playPlaylist", {
      playlistId: playlist.id,
      startIndex: 0
    }));

    card.append(open, downloadButton, play);
    content.append(card);
  });
}

function makeDownloadButton(playlist) {
  const status = downloadFor(playlist.id);
  const button = document.createElement("button");
  button.className = "download-button";

  if (status?.state === "downloading" || status?.state === "queued") {
    const percent = status.totalCount > 0
      ? Math.round((status.downloadedCount / status.totalCount) * 100)
      : 0;
    button.textContent = `${percent}%`;
    button.disabled = true;
    button.setAttribute("aria-label", `Downloading ${playlist.name}`);
  } else if (status?.complete) {
    button.textContent = "✓";
    button.classList.add("downloaded");
    button.setAttribute("aria-label", `Remove offline copy of ${playlist.name}`);
    button.addEventListener("click", () => removeDownload(playlist.id, playlist.name));
  } else {
    button.textContent = status?.state === "failed" ? "↻" : "⇩";
    button.setAttribute("aria-label", `Download ${playlist.name}`);
    button.disabled = !state.sync?.online;
    button.addEventListener("click", () => startDownload(playlist.id));
  }
  return button;
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
  const status = downloadFor(playlist.id);
  meta.textContent = `${data.songs.length} tracks${status?.complete ? " · Available offline" : ""}`;
  titleBox.append(title, meta);

  const downloadButton = makeDownloadButton(playlist);
  const playAll = document.createElement("button");
  playAll.className = "play-all";
  playAll.textContent = "▶ Play all";
  playAll.addEventListener("click", () => sendControl("playPlaylist", {
    playlistId: playlist.id,
    startIndex: 0
  }));

  header.append(back, titleBox, downloadButton, playAll);
  content.append(header);

  const songs = data.songs.filter(song =>
    `${song.title} ${song.artist} ${song.album}`.toLowerCase().includes(query)
  );
  if (!songs.length) return empty("No matching tracks", false);

  songs.forEach(song => {
    const row = document.createElement("article");
    row.className = "row";

    const main = document.createElement("button");
    main.className = "row-main";
    const songTitle = document.createElement("strong");
    songTitle.textContent = `${song.index + 1}. ${song.title}`;
    const songMeta = document.createElement("span");
    songMeta.textContent = `${song.artist} · ${song.album}${song.offlineAvailable ? " · Offline" : ""}`;
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
    meta.textContent = `${item.artist} · ${item.album}${item.offline ? " · Offline" : ""}`;
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

async function startDownload(playlistId) {
  clearError();
  try {
    applyDownloads(await api("/api/download", { playlistId }));
    render();
  } catch (error) {
    showError(error);
  }
}

async function removeDownload(playlistId, name) {
  if (!confirm(`Remove the offline copy of ${name}?`)) return;
  clearError();
  try {
    applyDownloads(await api("/api/download/remove", { playlistId }));
    if (state.openPlaylist?.playlist?.id === playlistId) state.openPlaylist = null;
    render();
  } catch (error) {
    showError(error);
  }
}

function renderDownloadBanner() {
  const active = [...state.downloads.values()].find(item =>
    item.state === "downloading" || item.state === "queued"
  );
  const failed = [...state.downloads.values()].find(item => item.state === "failed");

  if (active) {
    const count = `${active.downloadedCount}/${active.totalCount}`;
    const bytes = active.currentTotalBytes > 0
      ? ` · ${formatBytes(active.currentBytes)}/${formatBytes(active.currentTotalBytes)}`
      : "";
    downloadBanner.textContent = `Downloading ${active.name}: ${count}${bytes}${active.currentSong ? ` · ${active.currentSong}` : ""}`;
    downloadBanner.hidden = false;
  } else if (failed) {
    downloadBanner.textContent = `Download failed for ${failed.name}: ${failed.error || "Unknown error"}`;
    downloadBanner.hidden = false;
  } else if (state.sync?.syncing) {
    const progress = state.sync.total > 0 ? `${state.sync.completed}/${state.sync.total}` : "starting";
    downloadBanner.textContent = `Refreshing Navidrome library: ${progress}${state.sync.currentPlaylist ? ` · ${state.sync.currentPlaylist}` : ""}`;
    downloadBanner.hidden = false;
  } else {
    downloadBanner.hidden = true;
    downloadBanner.textContent = "";
  }
}

function formatBytes(value) {
  if (!Number.isFinite(value) || value <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / Math.pow(1024, index)).toFixed(index > 1 ? 1 : 0)} ${units[index]}`;
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

function empty(message, replace = true) {
  if (replace) content.replaceChildren();
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
    applyLibrary(await api("/api/sync", {}));
    render();
  } catch (error) {
    showError(error);
  }
});

loadBootstrap();
connectWebSocket();
