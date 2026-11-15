import SwiftUI

struct LibraryView: View {
  @EnvironmentObject private var model: AppModel

  var body: some View {
    List {
      if !model.searchText.isEmpty {
        Section("Search results") {
          if model.searchResults.isEmpty {
            ContentUnavailableView(
              "No matches", systemImage: "music.note",
              description: Text("Search your Navidrome library."))
          }
          ForEach(model.searchResults) { track in
            TrackRow(track: track, trailingSystemImage: "text.badge.plus") {
              Task { await model.addToQueue(track) }
            }
            .onTapGesture { Task { await model.playTrack(track) } }
          }
        }
      } else {
        Section("Navidrome playlists") {
          if model.playlists.isEmpty {
            Text(
              model.ble.connectionState.isReady
                ? "No playlists found." : "Connect to your Samsung to browse playlists."
            )
            .foregroundStyle(.secondary)
          }
          ForEach(model.playlists) { playlist in
            NavigationLink {
              PlaylistDetailView(playlist: playlist)
            } label: {
              HStack {
                Image(systemName: "music.note.list")
                VStack(alignment: .leading) {
                  Text(playlist.name)
                  if let count = playlist.songCount {
                    Text("\(count) songs").font(.caption).foregroundStyle(.secondary)
                  }
                }
              }
            }
          }
        }
      }
    }
    .navigationTitle("Library")
    .searchable(text: $model.searchText, prompt: "Search songs")
    .onSubmit(of: .search) { Task { await model.search() } }
    .onChange(of: model.searchText) { _, value in
      if value.isEmpty { model.searchResults = [] }
    }
    .refreshable { await model.refreshAll() }
  }
}

private struct PlaylistDetailView: View {
  @EnvironmentObject private var model: AppModel
  let playlist: Playlist

  var body: some View {
    List {
      Section {
        Button {
          Task { await model.playPlaylist(playlist) }
        } label: {
          Label("Play playlist", systemImage: "play.fill")
        }
        .disabled(model.selectedPlaylistTracks.isEmpty)
      }
      Section("Songs") {
        ForEach(Array(model.selectedPlaylistTracks.enumerated()), id: \.offset) {
          index, track in
          TrackRow(track: track, trailingSystemImage: "text.badge.plus") {
            Task { await model.addToQueue(track) }
          }
          .onTapGesture { Task { await model.playPlaylist(playlist, index: index) } }
        }
        if model.playlistHasMore {
          Button("Load more songs") { Task { await model.loadMorePlaylist() } }
        }
      }
    }
    .navigationTitle(playlist.name)
    .task { await model.loadPlaylist(playlist) }
  }
}
