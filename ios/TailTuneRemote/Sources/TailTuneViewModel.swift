import Foundation
import Network

@MainActor
final class TailTuneViewModel: ObservableObject {
    @Published var connectionState: TailTuneConnectionState = .disconnected
    @Published var playlists: [TailTunePlaylist] = []
    @Published var playback = TailTunePlayback(
        playing: false,
        positionMs: 0,
        durationMs: 0,
        current: nil,
        queue: []
    )
    @Published var selectedPlaylist: TailTunePlaylistDetail?
    @Published var errorMessage: String?
    @Published var tokenInput = ""
    @Published var manualHost = ""

    let discovery = DiscoveryService()
    private let socket = TailTuneSocket()
    private let decoder = JSONDecoder()
    private var selectedDeviceID: String?

    init() {
        manualHost = UserDefaults.standard.string(forKey: Self.lastManualHostKey).orEmpty
        tokenInput = KeychainStore.loadToken(account: Self.lastTokenAccount).orEmpty
        socket.onEvent = { [weak self] event in
            // TailTuneSocket delivers events on the main queue.
            self?.handle(event)
        }
    }

    func start() {
        discovery.start()
        socket.resume()
    }

    func stop() {
        discovery.stop()
        socket.disconnect()
    }

    func connect(to device: DiscoveredTailTune) {
        guard let endpoint = discovery.endpoint(for: device) else {
            errorMessage = "That TailTune device is no longer available."
            return
        }
        guard let token = normalizedToken(tokenInput)
                ?? KeychainStore.loadToken(account: device.id)
                ?? KeychainStore.loadToken(account: Self.lastTokenAccount) else {
            errorMessage = "Paste the 32-character TailTune access token first."
            return
        }

        selectedDeviceID = device.id
        tokenInput = token
        saveToken(token, deviceAccount: device.id)
        errorMessage = nil
        socket.connect(endpoint: endpoint, token: token)
    }

    func connectManual() {
        let parsed = parseSecureURL(tokenInput)
        let host = manualHost.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedHost = host.isEmpty ? parsed?.host.orEmpty : host
        let port: UInt16 = parsed?.port.flatMap { UInt16(exactly: $0) } ?? 8787

        guard !resolvedHost.isEmpty else {
            errorMessage = "Enter the Samsung LAN/Tailscale IP, or paste its full secure TailTune URL."
            return
        }
        guard let token = normalizedToken(tokenInput)
                ?? KeychainStore.loadToken(account: "manual:\(resolvedHost)")
                ?? KeychainStore.loadToken(account: Self.lastTokenAccount) else {
            errorMessage = "Paste a valid TailTune access token."
            return
        }

        do {
            try socket.connect(host: resolvedHost, port: port, token: token)
            manualHost = resolvedHost
            UserDefaults.standard.set(resolvedHost, forKey: Self.lastManualHostKey)
            saveToken(token, deviceAccount: "manual:\(resolvedHost)")
            tokenInput = token
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func disconnect() { socket.disconnect() }

    func loadPlaylist(_ playlist: TailTunePlaylist) {
        request("playlist", ["playlistId": playlist.id], decode: TailTunePlaylistDetail.self) {
            self.selectedPlaylist = $0
        }
    }

    func play(_ playlist: TailTunePlaylist, index: Int = 0) {
        request(
            "playPlaylist",
            ["playlistId": playlist.id, "startIndex": index],
            decode: TailTunePlayback.self
        ) {
            self.mergePlayback($0)
        }
    }

    func control(_ action: String) {
        request("control", ["action": action], decode: TailTunePlayback.self) {
            self.mergePlayback($0)
        }
    }

    func seek(to positionMs: Int64) {
        request(
            "control",
            ["action": "seek", "positionMs": positionMs],
            decode: TailTunePlayback.self
        ) {
            self.mergePlayback($0)
        }
    }

    func refreshLibrary(full: Bool = false) {
        request("sync", ["full": full], decode: TailTuneLibrary.self) {
            self.playlists = $0.playlists
        }
    }

    private func handle(_ event: TailTuneSocket.Event) {
        do {
            switch event {
            case .state(let state):
                connectionState = state
            case .authorizationFailed(let message):
                errorMessage = message
                tokenInput = ""
                if let selectedDeviceID { KeychainStore.deleteToken(account: selectedDeviceID) }
                KeychainStore.deleteToken(account: Self.lastTokenAccount)
            case .snapshot(let data):
                let snapshot = try decoder.decode(TailTuneSnapshot.self, from: data)
                playlists = snapshot.library.playlists
                playback = snapshot.playback
                errorMessage = snapshot.server?.error ?? snapshot.playback.error
            case .playback(let data):
                mergePlayback(try decoder.decode(TailTunePlayback.self, from: data))
            case .library(let data):
                playlists = try decoder.decode(TailTuneLibrary.self, from: data).playlists
            case .downloads:
                break
            }
        } catch {
            errorMessage = "Could not read TailTune state: \(error.localizedDescription)"
        }
    }

    private func mergePlayback(_ incoming: TailTunePlayback) {
        var value = incoming
        if value.queue == nil { value.queue = playback.queue }
        playback = value
        if let error = value.error { errorMessage = error }
    }

    private func request<T: Decodable>(
        _ operation: String,
        _ fields: [String: Any] = [:],
        decode type: T.Type,
        apply: @escaping (T) -> Void
    ) {
        socket.request(operation: operation, fields: fields) { [weak self] result in
            DispatchQueue.main.async {
                guard let self else { return }
                switch result {
                case .success(let data):
                    do {
                        apply(try self.decoder.decode(T.self, from: data))
                        self.errorMessage = nil
                    } catch {
                        self.errorMessage = "Invalid response: \(error.localizedDescription)"
                    }
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                }
            }
        }
    }

    private func saveToken(_ token: String, deviceAccount: String) {
        try? KeychainStore.saveToken(token, account: deviceAccount)
        try? KeychainStore.saveToken(token, account: Self.lastTokenAccount)
    }

    private func normalizedToken(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.range(of: "^[A-Za-z0-9_-]{32}$", options: .regularExpression) != nil {
            return trimmed
        }
        return parseSecureURL(trimmed)?.token
    }

    private func parseSecureURL(_ raw: String) -> (host: String, port: Int?, token: String)? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              let host = components.host,
              let fragment = components.fragment,
              let token = URLComponents(string: "x://x/?\(fragment)")?
                .queryItems?
                .first(where: { $0.name == "token" })?
                .value,
              token.range(of: "^[A-Za-z0-9_-]{32}$", options: .regularExpression) != nil else {
            return nil
        }
        return (host, components.port, token)
    }

    private static let lastTokenAccount = "last-used"
    private static let lastManualHostKey = "TailTune.lastManualHost"
}

private extension Optional where Wrapped == String {
    var orEmpty: String { self ?? "" }
}
