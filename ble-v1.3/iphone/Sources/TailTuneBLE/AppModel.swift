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

  init() {
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
          self.playlists = []
          self.searchResults = []
          self.selectedPlaylistTracks = []
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
    selectedPlaylist = playlist
    selectedPlaylistTracks = []
    playlistHasMore = false
    do {
      let page: TrackPage = try await ble.request(
        op: "playlist_tracks",
        args: ["id": .string(playlist.id), "offset": .int(0), "limit": .int(200)]
      ).decode(TrackPage.self)
      guard selectedPlaylist?.id == playlist.id else { return }
      selectedPlaylistTracks = page.items
      playlistHasMore = page.hasMore
    } catch {
      guard selectedPlaylist?.id == playlist.id else { return }
      selectedPlaylistTracks = []
      errorMessage = error.localizedDescription
    }
  }

  func loadMorePlaylist() async {
    guard let playlist = selectedPlaylist, playlistHasMore else { return }
    do {
      let page: TrackPage = try await ble.request(
        op: "playlist_tracks",
        args: ["id": .string(playlist.id), "offset": .int(selectedPlaylistTracks.count), "limit": .int(200)]
      ).decode(TrackPage.self)
      guard selectedPlaylist?.id == playlist.id else { return }
      selectedPlaylistTracks.append(contentsOf: page.items)
      playlistHasMore = page.hasMore
    } catch { errorMessage = error.localizedDescription }
  }

  func playPlaylist(_ playlist: Playlist, index: Int = 0) async {
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
