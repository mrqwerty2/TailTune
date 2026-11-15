import Foundation

struct Track: Codable, Identifiable, Hashable, Sendable {
  let id: String
  let title: String
  let artist: String?
  let album: String?
  let durationMs: Int?
  let downloaded: Bool?
}

struct Playlist: Codable, Identifiable, Hashable, Sendable {
  let id: String
  let name: String
  let songCount: Int?
}

struct PlaybackInfo: Codable, Equatable, Sendable {
  let isPlaying: Bool
  let positionMs: Int
  let durationMs: Int
  let volume: Int?
  let shuffle: Bool?
  let repeatMode: String?
  let current: Track?
}

struct DeviceInfo: Codable, Equatable, Sendable {
  let name: String?
  let batteryPercent: Int?
  let charging: Bool?
  let navidromeOnline: Bool?
}

struct Snapshot: Codable, Equatable, Sendable {
  let device: DeviceInfo?
  let playback: PlaybackInfo
  let queueRevision: Int?
  let error: String?
}

struct TrackPage: Codable, Equatable, Sendable {
  let items: [Track]
  let offset: Int
  let total: Int?
  let hasMore: Bool
}

struct PlaylistPage: Codable, Equatable, Sendable {
  let items: [Playlist]
  let offset: Int
  let total: Int?
  let hasMore: Bool
}
