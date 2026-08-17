import Foundation

struct TailTunePlaylist: Codable, Identifiable, Hashable {
    let id: String
    var name: String
    var owner: String?
    var songCount: Int
    var durationSeconds: Int64?
    var downloadedCount: Int?
    var offlineTotal: Int?
    var offlineComplete: Bool?
    var offlineKnown: Bool?
}

struct TailTuneTrack: Codable, Identifiable, Hashable {
    let id: String
    var index: Int?
    var title: String
    var artist: String
    var album: String
    var offline: Bool?
    var offlineAvailable: Bool?
    var durationSeconds: Int64?
}

struct TailTuneBattery: Codable, Equatable {
    var percent: Int
    var charging: Bool
}

struct TailTunePlayback: Codable, Equatable {
    var playing: Bool
    var playbackState: Int?
    var positionMs: Int64
    var durationMs: Int64
    var bufferedPositionMs: Int64?
    var current: TailTuneTrack?
    var queue: [TailTuneTrack]?
    var queueRevision: Int?
    var error: String?
    var battery: TailTuneBattery?
}

struct TailTuneSync: Codable, Equatable {
    var state: String?
    var syncing: Bool?
    var online: Bool?
    var completed: Int?
    var total: Int?
    var currentPlaylist: String?
    var lastSyncAt: Int64?
    var error: String?
}

struct TailTuneStorage: Codable, Equatable {
    var path: String?
    var databasePath: String?
    var removable: Bool?
    var available: Bool?
    var usedBytes: Int64?
    var usableBytes: Int64?
}

struct TailTuneLibrary: Codable, Equatable {
    var playlists: [TailTunePlaylist]
    var sync: TailTuneSync?
    var storage: TailTuneStorage?
}

struct TailTuneServerState: Codable, Equatable {
    var running: Bool?
    var error: String?
}

struct TailTuneSnapshot: Codable, Equatable {
    var version: String?
    var server: TailTuneServerState?
    var library: TailTuneLibrary
    var playback: TailTunePlayback
}

struct TailTunePlaylistDetail: Codable, Equatable, Identifiable {
    var playlist: TailTunePlaylist
    var songs: [TailTuneTrack]
    var id: String { playlist.id }
}

struct DiscoveredTailTune: Identifiable, Hashable {
    let id: String
    let name: String
    let endpointDescription: String
}

enum TailTuneConnectionState: Equatable {
    case disconnected
    case connecting
    case connected
    case waiting(String)
    case failed(String)

    var label: String {
        switch self {
        case .disconnected: return "Disconnected"
        case .connecting: return "Connecting…"
        case .connected: return "Connected"
        case .waiting(let message): return message
        case .failed(let message): return message
        }
    }
}
