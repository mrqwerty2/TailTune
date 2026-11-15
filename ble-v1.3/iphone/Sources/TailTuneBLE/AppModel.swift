import Combine
import Foundation

@MainActor
final class AppModel: ObservableObject {
  @Published var snapshot: Snapshot?
  @Published var queue: [Track] = []
  @Published var playlists: [Playlist] = []
  @Published var searchResults: [Track] = []
  @Published var selectedPlaylistTracks: [Track] = []
  @Published var selectedPlaylist: Playlist?
  @Published var playlistHasMore = false
  @Published var searchText = ""
  @Published var isBusy = false
  @Published var errorMessage: String?

  let ble = BLEManager()
  private var cancellables: Set<AnyCancellable> = []
  private var progressTimer: Timer?
  private var deviceStatusTimer: Timer?
  private var localPositionMs = 0
  private var lastPositionTick = Date()

  // Cache only Navidrome metadata; audio files remain on the Samsung.
  private struct CachedPlaylist: Codable {
    let tracks: [Track]
    let hasMore: Bool
    let songCount: Int?
    var fetchedAt: Date
  }

  private struct DiskPlaylistCache: Codable {
    let playlists: [Playlist]
    let entries: [String: CachedPlaylist]
  }

  private var playlistCache: [String: CachedPlaylist] = [:]
  private let playlistCacheFreshness: TimeInterval = 5 * 60
  private var playlistRequestID = UUID()
  private var pendingPlaylistPages = Set<String>()
  @Published var playlistIsRefreshing = false

  private var playlistCacheURL: URL? {
    guard let support = FileManager.default.urls(
      for: .applicationSupportDirectory, in: .userDomainMask
    ).first else { return nil }
    return support.appendingPathComponent("TailTune", isDirectory: true)
      .appendingPathComponent("playlist-metadata-v1.json")
  }

  private func restorePlaylistCache() {
    guard let url = playlistCacheURL,
          let data = try? Data(contentsOf: url),
          let cache = try? JSONDecoder().decode(DiskPlaylistCache.self, from: data)
    else { return }
    playlists = cache.playlists
    playlistCache = cache.entries
  }

  private func savePlaylistCache() {
    guard let url = playlistCacheURL else { return }
    do {
      try FileManager.default.createDirectory(
        at: url.deletingLastPathComponent(), withIntermediateDirectories: true
      )
      let data = try JSONEncoder().encode(
        DiskPlaylistCache(playlists: playlists, entries: playlistCache)
      )
      try data.write(to: url, options: .atomic)
    } catch {
      NSLog("TailTune playlist metadata cache could not be saved: %@", String(describing: error))
    }
  }

  init() {
    restorePlaylistCache()
    ble.onReady = { [weak self] in
      guard let self else { return }
      Task { @MainActor in
        self.startDeviceStatusTimer()
        await self.refreshAll()
      }
    }
    ble.onEvent = { [weak self] event in
      guard let self else { return }
      Task { @MainActor in self.handle(event: event) }
    }

    ble.$connectionState
      .receive(on: DispatchQueue.main)
      .sink { [weak self] state in
        guard let self else { return }
        if !state.isReady {
          self.stopProgressTimer()
          self.stopDeviceStatusTimer()
          self.snapshot = nil
          self.queue = []
          // Keep cached playlist metadata visible while the Samsung is offline.
          self.searchResults = []
        }
      }
      .store(in: &cancellables)
  }

  var displayedPositionMs: Int {
    guard let playback = snapshot?.playback else { return 0 }
    if playback.isPlaying { return min(localPositionMs, max(playback.durationMs, 0)) }
    return playback.positionMs
  }

  func refreshAll() async {
    guard ble.connectionState.isReady else { return }
    isBusy = true
    defer { isBusy = false }
    do {
      async let snapshotValue = ble.request(op: "snapshot")
      async let queueValue = ble.request(
        op: "queue", args: ["offset": .int(0), "limit": .int(100)])
      async let playlistValue = ble.request(
        op: "list_playlists", args: ["offset": .int(0), "limit": .int(100)])
      let (snapshotJSON, queueJSON, playlistsJSON) = try await (
        snapshotValue, queueValue, playlistValue
      )
      snapshot = try snapshotJSON.decode(Snapshot.self)
      queue = try queueJSON.decode(TrackPage.self).items
      let firstPlaylists = try playlistsJSON.decode(PlaylistPage.self)
      playlists = firstPlaylists.items
      if firstPlaylists.hasMore { await fetchRemainingPlaylists(start: firstPlaylists.items.count) }
      savePlaylistCache()
      syncLocalProgress()
      errorMessage = snapshot?.error
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func refreshSnapshot() async {
    guard ble.connectionState.isReady else { return }
    do {
      snapshot = try await ble.request(op: "snapshot").decode(Snapshot.self)
      syncLocalProgress()
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func control(_ action: String, value: Int? = nil) async {
    var args: [String: JSONValue] = ["action": .string(action)]
    if let value { args["value"] = .int(value) }
    do {
      _ = try await ble.request(op: "control", args: args)
      await refreshSnapshot()
      if ["next", "previous"].contains(action) { await refreshQueue() }
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func seek(to milliseconds: Int) async {
    await control("seek", value: max(0, milliseconds))
  }

  func setVolume(_ percent: Int) async {
    await control("volume", value: min(100, max(0, percent)))
  }

  func refreshQueue() async {
    do {
      let page: TrackPage = try await ble.request(
        op: "queue",
        args: ["offset": .int(0), "limit": .int(100)]
      ).decode(TrackPage.self)
      queue = page.items
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func loadPlaylist(_ playlist: Playlist) async {
    // Invalidate results from an older selection, including A -> B -> A races.
    playlistRequestID = UUID()
    let requestID = playlistRequestID
    selectedPlaylist = playlist
    let cached = playlistCache[playlist.id]
    selectedPlaylistTracks = cached?.tracks ?? []
    playlistHasMore = cached?.hasMore ?? false
    playlistIsRefreshing = false

    // Cached metadata remains useful even when the Samsung disconnects.
    guard ble.connectionState.isReady else { return }
    if let cached,
       cached.songCount == playlist.songCount,
       Date().timeIntervalSince(cached.fetchedAt) < playlistCacheFreshness {
      return
    }

    playlistIsRefreshing = true
    defer {
      if playlistRequestID == requestID { playlistIsRefreshing = false }
    }
    do {
      let page: TrackPage = try await ble.request(
        op: "playlist_tracks",
        args: ["id": .string(playlist.id), "offset": .int(0), "limit": .int(200)]
      ).decode(TrackPage.self)
      guard selectedPlaylist?.id == playlist.id,
            playlistRequestID == requestID,
            !Task.isCancelled else { return }

      // Keep already-loaded additional pages only if the refreshed first page
      // has exactly the same ordered track IDs, duplicates included.
      let previous = playlistCache[playlist.id]?.tracks ?? []
      let samePrefix = previous.count > page.items.count &&
        zip(previous.prefix(page.items.count), page.items)
          .allSatisfy { $0.0.id == $0.1.id }
      let tracks: [Track]
      if page.hasMore && samePrefix {
        tracks = page.items + Array(previous.dropFirst(page.items.count))
      } else {
        tracks = page.items
      }
      selectedPlaylistTracks = tracks
      playlistHasMore = samePrefix
        ? (playlistCache[playlist.id]?.hasMore ?? page.hasMore)
        : page.hasMore
      playlistCache[playlist.id] = CachedPlaylist(
        tracks: tracks, hasMore: playlistHasMore,
        songCount: playlist.songCount, fetchedAt: Date()
      )
      savePlaylistCache()
    } catch {
      guard selectedPlaylist?.id == playlist.id,
            playlistRequestID == requestID else { return }
      // Preserve usable cached tracks when a background refresh fails.
      errorMessage = error.localizedDescription
    }
  }

  func loadMorePlaylist() async {
    guard let playlist = selectedPlaylist, playlistHasMore,
          ble.connectionState.isReady,
          !pendingPlaylistPages.contains(playlist.id) else { return }
    let offset = selectedPlaylistTracks.count
    pendingPlaylistPages.insert(playlist.id)
    defer { pendingPlaylistPages.remove(playlist.id) }
    do {
      let page: TrackPage = try await ble.request(
        op: "playlist_tracks",
        args: ["id": .string(playlist.id), "offset": .int(offset), "limit": .int(200)]
      ).decode(TrackPage.self)
      guard selectedPlaylist?.id == playlist.id,
            selectedPlaylistTracks.count == offset,
            !Task.isCancelled else { return }
      selectedPlaylistTracks.append(contentsOf: page.items)
      playlistHasMore = page.hasMore
      playlistCache[playlist.id] = CachedPlaylist(
        tracks: selectedPlaylistTracks, hasMore: page.hasMore,
        songCount: playlist.songCount, fetchedAt: Date()
      )
      savePlaylistCache()
    } catch {
      guard selectedPlaylist?.id == playlist.id else { return }
      errorMessage = error.localizedDescription
    }
  }

  func playPlaylist(_ playlist: Playlist, index: Int = 0) async {
    guard ble.connectionState.isReady else {
      errorMessage = "Connect to your Samsung to start playback."
      return
    }
    do {
      _ = try await ble.request(
        op: "play_playlist",
        args: ["id": .string(playlist.id), "index": .int(max(0, index))]
      )
      await refreshAll()
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func playTrack(_ track: Track) async {
    do {
      _ = try await ble.request(op: "play_track", args: ["id": .string(track.id)])
      await refreshAll()
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func addToQueue(_ track: Track) async {
    do {
      _ = try await ble.request(op: "queue_add", args: ["id": .string(track.id)])
      await refreshQueue()
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func search() async {
    let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !query.isEmpty else {
      searchResults = []
      return
    }
    do {
      let page: TrackPage = try await ble.request(
        op: "search",
        args: ["q": .string(query), "offset": .int(0), "limit": .int(100)]
      ).decode(TrackPage.self)
      guard query == searchText.trimmingCharacters(in: .whitespacesAndNewlines) else { return }
      searchResults = page.items
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func dismissError() {
    errorMessage = nil
  }

  private func handle(event: TailTuneRPCEvent) {
    do {
      switch event.event {
      case "state":
        if let data = event.data {
          snapshot = try data.decode(Snapshot.self)
          syncLocalProgress()
        }
      case "queue_changed":
        Task { @MainActor in await refreshQueue() }
      case "library_changed":
        // Keep cached songs visible, but refresh next time a playlist opens.
        playlistCache = playlistCache.mapValues { entry in
          var stale = entry
          stale.fetchedAt = .distantPast
          return stale
        }
        Task { @MainActor in await refreshPlaylists() }
      default:
        break
      }
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  private func refreshPlaylists() async {
    do {
      let page: PlaylistPage = try await ble.request(
        op: "list_playlists", args: ["offset": .int(0), "limit": .int(100)]
      ).decode(PlaylistPage.self)
      playlists = page.items
      if page.hasMore { await fetchRemainingPlaylists(start: page.items.count) }
      savePlaylistCache()
    } catch { errorMessage = error.localizedDescription }
  }

  private func fetchRemainingPlaylists(start: Int) async {
    var offset = start
    while offset < 300 && ble.connectionState.isReady {
      do {
        let page: PlaylistPage = try await ble.request(
          op: "list_playlists", args: ["offset": .int(offset), "limit": .int(100)]
        ).decode(PlaylistPage.self)
        guard !page.items.isEmpty else { break }
        playlists.append(contentsOf: page.items)
        offset += page.items.count
        if !page.hasMore { break }
      } catch { errorMessage = error.localizedDescription; break }
    }
  }

  private func startDeviceStatusTimer() {
    stopDeviceStatusTimer()
    deviceStatusTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
      Task { @MainActor in
        guard let self, self.ble.connectionState.isReady else { return }
        await self.refreshSnapshot()
      }
    }
  }

  private func stopDeviceStatusTimer() {
    deviceStatusTimer?.invalidate()
    deviceStatusTimer = nil
  }

  private func syncLocalProgress() {
    localPositionMs = snapshot?.playback.positionMs ?? 0
    lastPositionTick = Date()
    if snapshot?.playback.isPlaying == true { startProgressTimer() } else { stopProgressTimer() }
  }

  private func startProgressTimer() {
    if progressTimer != nil { return }
    progressTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
      guard let self else { return }
      Task { @MainActor in
        guard self.snapshot?.playback.isPlaying == true else { return }
        let now = Date()
        let elapsed = Int(now.timeIntervalSince(self.lastPositionTick) * 1000)
        self.lastPositionTick = now
        self.localPositionMs += max(elapsed, 0)
        self.objectWillChange.send()
      }
    }
  }

  private func stopProgressTimer() {
    progressTimer?.invalidate()
    progressTimer = nil
  }
}
