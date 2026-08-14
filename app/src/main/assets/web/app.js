"use strict";

const state = {
  playlists: [],
  playback: null,
  downloads: new Map(),
  storage: null,
  sync: { state: "idle", syncing: false, online: false },
  server: null,
  view: "playlists",
  openPlaylist: null,
  search: "",
  socketConnected: false,
  visibleSongLimit: 150
};

const $ = selector => document.querySelector(selector);
const content = $("#content");
const errorBox = $("#error");
const downloadBanner = $("#download-banner");
const seek = $("#seek");

let accessToken = readAccessToken();
let socket = null;
let reconnectDelay = 750;
let reconnectTimer = null;
let fallbackTimer = null;
let heartbeatTimer = null;
let bootstrapPromise = null;
let renderQueued = false;
let seekDragging = false;
let mutationChain = Promise.resolve();
let playlistRequestSequence = 0;

function readAccessToken() {
  const fragment = new URLSearchParams(
    location.hash.replace(/^#/, "")
  );

  const token = fragment.get("token") || "";

  if (isValidToken(token)) {
    // Deliberately do NOT read or write localStorage.
    // The secure URL shown by the Samsung is the
    // single source of truth.
    return token;
  }

  return "";
}

function isValidToken(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{32}$/.test(value);
}

async function api(path, body, timeoutMs = 12_000) {
  if (!accessToken) throw new Error("Open the secure TailTune URL shown on the Samsung.");
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  const options = {
    method: body === undefined ? "GET" : "POST",
    cache: "no-store",
    credentials: "omit",
    signal: controller.signal,
    headers: { "X-TailTune-Token": accessToken }
  };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }

  try {
    const response = await fetch(path, options);
    const text = await response.text();
    let data = {};
    if (text) {
      try {
        data = JSON.parse(text);
      } catch (_) {
        throw new Error("The Samsung returned an invalid response.");
      }
    }
    if (!response.ok) {
      if (response.status === 401) {
        console.warn("TailTune HTTP authorization rejected");
        throw new Error(
          "HTTP authentication failed. Reopen the secure TailTune URL if this persists."
        );
      }

      throw new Error(data.error || `Request failed (${response.status})`);
    }
    return data;
  } catch (error) {
    if (error?.name === "AbortError") throw new Error("The Samsung took too long to respond.");
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

function clearStoredToken() {
  accessToken = "";
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
  state.playlists = Array.isArray(data.playlists) ? data.playlists : [];
  state.sync = data.sync || state.sync;
  state.storage = data.storage || state.storage;
  updateConnectionText();
  renderDownloadBanner();
}

function applyDownloads(data) {
  state.storage = data.storage || state.storage;
  const downloads = Array.isArray(data.downloads) ? data.downloads : [];
  state.downloads = new Map(downloads.map(item => [item.playlistId, item]));
  renderDownloadBanner();
}

function applyPlayback(incoming) {
  if (!incoming || typeof incoming !== "object") return;
  const previous = state.playback || {};
  state.playback = {
    ...previous,
    ...incoming,
    queue: Array.isArray(incoming.queue) ? incoming.queue : (previous.queue || [])
  };
}

function applySnapshot(data) {
  state.server = data.server || state.server;
  applyLibrary(data.library || {});
  applyPlayback(data.playback);
  applyDownloads(data.downloads || {});
  scheduleRender();
}

async function loadBootstrap() {
  if (bootstrapPromise) return bootstrapPromise;
  bootstrapPromise = (async () => {
    try {
      applySnapshot(await api("/api/bootstrap"));
      clearError();
    } catch (error) {
      state.socketConnected = false;
      updateConnectionText();
      showError(error);
    } finally {
      bootstrapPromise = null;
    }
  })();
  return bootstrapPromise;
}

function connectWebSocket() {
  if (!accessToken || document.hidden) return;
  if (socket && [WebSocket.OPEN, WebSocket.CONNECTING].includes(socket.readyState)) return;

  clearTimeout(reconnectTimer);
  reconnectTimer = null;

  const protocol = location.protocol === "https:" ? "wss:" : "ws:";

  // Authentication happens using the first WebSocket message.
  // No query-string or Sec-WebSocket-Protocol token is used.
  // Snapshot the credential for this connection.
  // Do not read the mutable global again from the async open handler.
  const socketToken = accessToken;

  const ws = new WebSocket(`${protocol}//${location.host}/ws`);
  socket = ws;

  ws.addEventListener("open", () => {
    if (socket !== ws) {
      try { ws.close(); } catch (_) { /* stale connection */ }
      return;
    }

    try {
      ws.send(JSON.stringify({
        type: "auth",
        token: socketToken
      }));
    } catch (_) {
      try { ws.close(); } catch (_) { /* close handler reconnects */ }
    }
  });

  ws.addEventListener("message", event => {
    if (socket !== ws) return;

    try {
      const message = JSON.parse(event.data);

      if (message.type === "authorization_ok") {
        state.socketConnected = true;
        reconnectDelay = 750;

        stopFallbackPolling();
        startHeartbeat();
        clearError();
        updateConnectionText();

        return;
      }

      if (message.type === "authorization_error") {
        state.socketConnected = false;

        try {
          ws.close();
        } catch (_) {
          /* close handler enables HTTP fallback */
        }

        showError(
          new Error(
            "Live connection authentication failed. Using HTTP fallback."
          )
        );

        return;
      }

      switch (message.type) {
        case "snapshot":
          applySnapshot(message.data || {});
          break;

        case "playback":
          applyPlayback(message.data);
          renderPlayer();

          if (state.view === "queue") {
            scheduleRender();
          }
          break;

        case "library":
          applyLibrary(message.data || {});

          if (
            state.view === "playlists" &&
            !state.openPlaylist
          ) {
            scheduleRender();
          }
          break;

        case "downloads":
          applyDownloads(message.data || {});
          scheduleRender();
          break;
      }
    } catch (error) {
      showError(error);
    }
  });

  ws.addEventListener("close", () => {
    if (socket !== ws) return;

    stopHeartbeat();

    state.socketConnected = false;
    socket = null;

    updateConnectionText();
    startFallbackPolling();
    scheduleReconnect();
  });

  ws.addEventListener("error", () => {
    try {
      ws.close();
    } catch (_) {
      /* close handler reconnects */
    }
  });
}

function startHeartbeat() {
  stopHeartbeat();
  heartbeatTimer = setInterval(() => {
    if (socket?.readyState === WebSocket.OPEN) {
      try {
        socket.send(JSON.stringify({
          type: "request",
          requestId: `heartbeat-${Date.now()}`,
          operation: "ping"
        }));
      } catch (_) { /* close handler reconnects */ }
    }
  }, 25_000);
}

function stopHeartbeat() {
  if (!heartbeatTimer) return;
  clearInterval(heartbeatTimer);
  heartbeatTimer = null;
}

function scheduleReconnect() {
  if (reconnectTimer || document.hidden || !accessToken) return;
  const delay = reconnectDelay;
  reconnectDelay = Math.min(reconnectDelay * 1.8, 15_000);
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connectWebSocket();
  }, delay);
}

function startFallbackPolling() {
  if (fallbackTimer || !accessToken) return;
  fallbackTimer = setInterval(() => {
    if (!document.hidden && !state.socketConnected) loadBootstrap();
  }, 10_000);
}

function stopFallbackPolling() {
  if (!fallbackTimer) return;
  clearInterval(fallbackTimer);
  fallbackTimer = null;
}

function updateConnectionText() {
  const live = state.socketConnected ? "Live" : "Reconnecting";
  let mode = "Cached library";
  if (state.sync?.syncing) {
    const progress = state.sync.total > 0 ? ` ${state.sync.completed}/${state.sync.total}` : "";
    mode = `Syncing${progress}`;
  } else if (state.sync?.online) {
    mode = "Navidrome online";
  } else if (state.playlists.length) {
    mode = "Offline/cached";
  }
  $("#connection").textContent = `${state.playlists.length} playlists · ${mode} · ${live}`;
}

function enqueueMutation(task) {
  const run = mutationChain.then(task, task);
  // Keep the chain alive after an individual command fails.
  mutationChain = run.catch(() => undefined);
  return run;
}

function sendControl(action, extra = {}) {
  return enqueueMutation(async () => {
    clearError();
    try {
      const timeout = ["playPlaylist", "addFromPlaylist"].includes(action) ? 35_000 : 12_000;
      applyPlayback(await api("/api/control", { action, ...extra }, timeout));
      renderPlayer();
      if (state.view === "queue") scheduleRender();
    } catch (error) {
      showError(error);
    }
  });
}

async function openPlaylist(id) {
  const requestSequence = ++playlistRequestSequence;
  state.visibleSongLimit = 150;
  content.replaceChildren(makeStatusBlock("Loading playlist…", "loading"));
  clearError();
  try {
    const playlist = await api(`/api/playlist?id=${encodeURIComponent(id)}`, undefined, 35_000);
    if (requestSequence === playlistRequestSequence) state.openPlaylist = playlist;
  } catch (error) {
    if (requestSequence === playlistRequestSequence) {
      state.openPlaylist = null;
      showError(error);
    }
  }
  if (requestSequence === playlistRequestSequence) scheduleRender();
}

function renderBattery(battery) {
  const element = $("#battery-status");
  if (!battery || typeof battery.percent !== "number" || battery.percent < 0) {
    element.textContent = "🔋 --%";
    element.classList.remove("low", "charging");
    return;
  }
  const charging = Boolean(battery.charging);
  element.textContent = `${charging ? "⚡" : "🔋"} ${battery.percent}%`;
  element.classList.toggle("low", battery.percent <= 20 && !charging);
  element.classList.toggle("charging", charging);
  element.setAttribute(
    "aria-label",
    `Samsung battery ${battery.percent} percent${charging ? ", charging" : ""}`
  );
}

function renderPlayer() {
  const playback = state.playback;
  const current = playback?.current;
  $("#now-title").textContent = current?.title || "Nothing playing";
  $("#now-artist").textContent = current
    ? `${current.artist || "Unknown artist"}${current.offline ? " · Offline" : ""}`
    : "Choose a playlist";
  $("#toggle").textContent = playback?.playing ? "⏸" : "▶";
  if (!seekDragging) {
    const duration = Math.max(1, playback?.durationMs || 1);
    seek.value = String(Math.round(((playback?.positionMs || 0) / duration) * 1000));
  }
  renderBattery(playback?.battery);
  if (playback?.error) showError(new Error(playback.error));
}

function scheduleRender() {
  if (renderQueued) return;
  renderQueued = true;
  requestAnimationFrame(() => {
    renderQueued = false;
    render();
  });
}

function render() {
  renderPlayer();
  updateConnectionText();
  renderDownloadBanner();
  document.querySelectorAll(".tab").forEach(button => {
    button.classList.toggle("active", button.dataset.view === state.view);
  });
  content.replaceChildren();
  if (!accessToken) {
    content.append(makeStatusBlock("Open the secure TailTune URL shown in the Samsung app."));
  } else if (state.view === "queue") {
    renderQueue();
  } else if (state.openPlaylist) {
    renderPlaylistDetail();
  } else {
    renderPlaylists();
  }
}

function downloadFor(playlistId) {
  return state.downloads.get(playlistId);
}

function renderPlaylists() {
  const query = state.search.trim().toLocaleLowerCase();
  const playlists = state.playlists.filter(playlist =>
    `${playlist.name || ""} ${playlist.owner || ""}`.toLocaleLowerCase().includes(query)
  );
  if (!playlists.length) {
    content.append(makeStatusBlock(
      state.sync?.syncing ? "Refreshing cached library…" : "No matching cached playlists"
    ));
    return;
  }

  const fragment = document.createDocumentFragment();
  playlists.forEach(playlist => {
    const card = document.createElement("article");
    card.className = "playlist-card";

    const open = document.createElement("button");
    open.type = "button";
    open.className = "playlist-open";
    const title = document.createElement("strong");
    title.textContent = playlist.name || "Untitled playlist";
    const meta = document.createElement("span");
    const download = downloadFor(playlist.id);
    const offlineText = download?.complete
      ? " · Downloaded"
      : download && download.downloadedCount > 0
        ? ` · ${download.downloadedCount}/${download.totalCount} offline`
        : "";
    meta.textContent = `${playlist.songCount || 0} tracks${playlist.owner ? ` · ${playlist.owner}` : ""}${offlineText}`;
    open.append(title, meta);
    open.addEventListener("click", () => openPlaylist(playlist.id));

    const downloadButton = makeDownloadButton(playlist);
    const play = document.createElement("button");
    play.type = "button";
    play.className = "play-button";
    play.textContent = "▶";
    play.setAttribute("aria-label", `Play ${playlist.name}`);
    play.addEventListener("click", () => sendControl("playPlaylist", {
      playlistId: playlist.id,
      startIndex: 0
    }));

    card.append(open, downloadButton, play);
    fragment.append(card);
  });
  content.append(fragment);
}

function makeDownloadButton(playlist) {
  const status = downloadFor(playlist.id);
  const button = document.createElement("button");
  button.type = "button";
  button.className = "download-button";

  if (["downloading", "queued"].includes(status?.state)) {
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
    button.addEventListener("click", event => {
      event.stopPropagation();
      removeDownload(playlist.id, playlist.name);
    });
  } else {
    button.textContent = status?.state === "failed" ? "↻" : "⇩";
    button.setAttribute("aria-label", `Download ${playlist.name}`);
    button.disabled = !state.sync?.online;
    button.addEventListener("click", event => {
      event.stopPropagation();
      startDownload(playlist.id);
    });
  }
  return button;
}

function renderPlaylistDetail() {
  const data = state.openPlaylist;
  const playlist = data.playlist;
  const query = state.search.trim().toLocaleLowerCase();

  const header = document.createElement("div");
  header.className = "detail-header";
  const back = document.createElement("button");
  back.type = "button";
  back.textContent = "‹ Back";
  back.addEventListener("click", () => {
    playlistRequestSequence += 1;
    state.openPlaylist = null;
    state.visibleSongLimit = 150;
    scheduleRender();
  });

  const titleBox = document.createElement("div");
  titleBox.className = "detail-title";
  const title = document.createElement("strong");
  title.textContent = playlist.name || "Untitled playlist";
  const meta = document.createElement("span");
  const status = downloadFor(playlist.id);
  meta.textContent = `${data.songs.length} tracks${status?.complete ? " · Available offline" : ""}`;
  titleBox.append(title, meta);

  const downloadButton = makeDownloadButton(playlist);
  const playAll = document.createElement("button");
  playAll.type = "button";
  playAll.className = "play-all";
  playAll.textContent = "▶ Play all";
  playAll.addEventListener("click", () => sendControl("playPlaylist", {
    playlistId: playlist.id,
    startIndex: 0
  }));
  header.append(back, titleBox, downloadButton, playAll);
  content.append(header);

  const filtered = data.songs.filter(song =>
    `${song.title || ""} ${song.artist || ""} ${song.album || ""}`
      .toLocaleLowerCase().includes(query)
  );
  if (!filtered.length) {
    content.append(makeStatusBlock("No matching tracks"));
    return;
  }

  const shown = filtered.slice(0, state.visibleSongLimit);
  const fragment = document.createDocumentFragment();
  shown.forEach(song => fragment.append(makeSongRow(song, playlist.id)));
  content.append(fragment);

  if (shown.length < filtered.length) {
    const more = document.createElement("button");
    more.type = "button";
    more.className = "load-more";
    more.textContent = `Show ${Math.min(150, filtered.length - shown.length)} more`;
    more.addEventListener("click", () => {
      state.visibleSongLimit += 150;
      scheduleRender();
    });
    content.append(more);
  }
}

function makeSongRow(song, playlistId) {
  const row = document.createElement("article");
  row.className = "row";
  const main = document.createElement("button");
  main.type = "button";
  main.className = "row-main";
  const title = document.createElement("strong");
  title.textContent = `${song.index + 1}. ${song.title}`;
  const meta = document.createElement("span");
  meta.textContent = `${song.artist || "Unknown artist"} · ${song.album || "Unknown album"}${song.offlineAvailable ? " · Offline" : ""}`;
  main.append(title, meta);
  main.addEventListener("click", () => sendControl("playPlaylist", {
    playlistId,
    startIndex: song.index
  }));

  const actions = document.createElement("div");
  actions.className = "row-actions";
  const add = document.createElement("button");
  add.type = "button";
  add.textContent = "＋";
  add.setAttribute("aria-label", `Add ${song.title} to queue`);
  add.addEventListener("click", () => sendControl("addFromPlaylist", {
    playlistId,
    index: song.index
  }));
  actions.append(add);
  row.append(main, actions);
  return row;
}

function renderQueue() {
  const queue = state.playback?.queue || [];
  const query = state.search.trim().toLocaleLowerCase();
  const filtered = queue.filter(item =>
    `${item.title || ""} ${item.artist || ""} ${item.album || ""}`
      .toLocaleLowerCase().includes(query)
  );
  if (!filtered.length) {
    content.append(makeStatusBlock(queue.length ? "No matching queue items" : "Queue is empty"));
    return;
  }

  const fragment = document.createDocumentFragment();
  filtered.forEach(item => {
    const row = document.createElement("article");
    row.className = "row";
    if (item.index === state.playback?.current?.index) row.classList.add("current-row");

    const main = document.createElement("button");
    main.type = "button";
    main.className = "row-main";
    const title = document.createElement("strong");
    title.textContent = `${item.index + 1}. ${item.title}`;
    const meta = document.createElement("span");
    meta.textContent = `${item.artist || "Unknown artist"} · ${item.album || "Unknown album"}${item.offline ? " · Offline" : ""}`;
    main.append(title, meta);
    main.addEventListener("click", () => sendControl("jump", { index: item.index }));

    const actions = document.createElement("div");
    actions.className = "row-actions";
    const up = queueButton("↑", "Move up", item.index === 0, () => queueMove(item.index, item.index - 1));
    const down = queueButton("↓", "Move down", item.index === queue.length - 1, () => queueMove(item.index, item.index + 1));
    const remove = queueButton("×", "Remove", false, () => queueRemove(item.index));
    actions.append(up, down, remove);
    row.append(main, actions);
    fragment.append(row);
  });
  content.append(fragment);
}

function queueButton(text, label, disabled, action) {
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = text;
  button.disabled = disabled;
  button.setAttribute("aria-label", label);
  button.addEventListener("click", action);
  return button;
}

async function startDownload(playlistId) {
  clearError();
  try {
    applyDownloads(await api("/api/download", { playlistId }, 40_000));
    scheduleRender();
  } catch (error) {
    showError(error);
  }
}

async function removeDownload(playlistId, name) {
  if (!confirm(`Remove the offline copy of ${name}?`)) return;
  clearError();
  try {
    applyDownloads(await api("/api/download/remove", { playlistId }, 30_000));
    scheduleRender();
  } catch (error) {
    showError(error);
  }
}

function renderDownloadBanner() {
  const values = [...state.downloads.values()];
  const active = values.find(item => ["downloading", "queued"].includes(item.state));
  const failed = values.find(item => item.state === "failed");

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
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1);
  return `${(value / Math.pow(1024, index)).toFixed(index > 1 ? 1 : 0)} ${units[index]}`;
}

function queueMove(from, to) {
  return enqueueMutation(async () => {
    clearError();
    try {
      applyPlayback(await api("/api/queue", { action: "move", from, to }));
      scheduleRender();
    } catch (error) {
      showError(error);
    }
  });
}

function queueRemove(index) {
  return enqueueMutation(async () => {
    clearError();
    try {
      applyPlayback(await api("/api/queue", { action: "remove", index }));
      scheduleRender();
    } catch (error) {
      showError(error);
    }
  });
}

function makeStatusBlock(message, className = "empty") {
  const block = document.createElement("div");
  block.className = className;
  block.textContent = message;
  return block;
}

document.querySelectorAll("[data-control]").forEach(button => {
  button.addEventListener("click", () => sendControl(button.dataset.control));
});

$("#toggle").addEventListener("click", () => sendControl("toggle"));
seek.addEventListener("pointerdown", () => { seekDragging = true; });
seek.addEventListener("input", () => { seekDragging = true; });
seek.addEventListener("change", event => {
  const duration = state.playback?.durationMs || 0;
  seekDragging = false;
  sendControl("seek", {
    positionMs: Math.round(duration * Number(event.target.value) / 1000)
  });
});

for (const button of document.querySelectorAll(".tab")) {
  button.addEventListener("click", () => {
    state.view = button.dataset.view;
    if (state.view !== "playlists") {
      playlistRequestSequence += 1;
      state.openPlaylist = null;
    }
    scheduleRender();
  });
}

$("#search").addEventListener("input", event => {
  state.search = event.target.value;
  state.visibleSongLimit = 150;
  scheduleRender();
});

$("#refresh").addEventListener("click", async () => {
  clearError();
  try {
    applyLibrary(await api("/api/sync", { full: false }));
    scheduleRender();
  } catch (error) {
    showError(error);
  }
});

document.addEventListener("visibilitychange", () => {
  if (document.hidden) return;
  loadBootstrap();
  connectWebSocket();
  if (socket?.readyState === WebSocket.OPEN) startHeartbeat();
});

window.addEventListener("pageshow", () => {
  loadBootstrap();
  connectWebSocket();
});

window.addEventListener("online", () => {
  loadBootstrap();
  connectWebSocket();
});

if (!accessToken) {
  showError(new Error("Open the secure TailTune URL shown in the Samsung app."));
  render();
} else {
  loadBootstrap();
  connectWebSocket();
}
