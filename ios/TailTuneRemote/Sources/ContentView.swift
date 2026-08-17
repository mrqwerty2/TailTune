import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: TailTuneViewModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            List {
                Section("Connection") {
                    HStack {
                        Circle()
                            .frame(width: 9, height: 9)
                            .foregroundStyle(model.connectionState == .connected ? .green : .secondary)
                        Text(model.connectionState.label)
                    }
                    SecureField("32-character access token or secure TailTune URL", text: $model.tokenInput)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    ForEach(model.discovery.devices) { device in
                        Button {
                            model.connect(to: device)
                        } label: {
                            VStack(alignment: .leading) {
                                Text(device.name)
                                Text(device.endpointDescription)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }

                    HStack {
                        TextField("Tailscale/LAN IP", text: $model.manualHost)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Button("Connect") { model.connectManual() }
                    }
                }

                if let error = model.errorMessage, !error.isEmpty {
                    Section {
                        Text(error).foregroundStyle(.red)
                    }
                }

                Section("Now Playing") {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(model.playback.current?.title ?? "Nothing playing")
                            .font(.headline)
                        Text(model.playback.current?.artist ?? "")
                            .foregroundStyle(.secondary)
                        HStack(spacing: 28) {
                            Button { model.control("previous") } label: { Image(systemName: "backward.fill") }
                            Button { model.control("toggle") } label: {
                                Image(systemName: model.playback.playing ? "pause.fill" : "play.fill")
                                    .font(.title2)
                            }
                            Button { model.control("next") } label: { Image(systemName: "forward.fill") }
                        }
                        .buttonStyle(.borderless)
                    }
                    if let battery = model.playback.battery, battery.percent >= 0 {
                        Label(
                            "Samsung \(battery.percent)%\(battery.charging ? " · charging" : "")",
                            systemImage: battery.charging ? "battery.100percent.bolt" : "battery.75percent"
                        )
                        .font(.caption)
                    }
                }

                Section {
                    HStack {
                        Button("Refresh") { model.refreshLibrary() }
                        Spacer()
                        Text("\(model.playlists.count) playlists")
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Playlists") {
                    ForEach(model.playlists) { playlist in
                        HStack {
                            Button {
                                model.loadPlaylist(playlist)
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(playlist.name)
                                    Text("\(playlist.songCount) songs")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button {
                                model.play(playlist)
                            } label: {
                                Image(systemName: "play.circle.fill")
                                    .font(.title2)
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }
            }
            .navigationTitle("TailTune")
            .sheet(item: $model.selectedPlaylist) { detail in
                PlaylistSheet(detail: detail)
                    .environmentObject(model)
            }
            .onAppear { model.start() }
            .onChange(of: scenePhase) { phase in
                if phase == .active { model.start() }
            }
        }
    }
}

private struct PlaylistSheet: View {
    @EnvironmentObject private var model: TailTuneViewModel
    let detail: TailTunePlaylistDetail
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(Array(detail.songs.enumerated()), id: \.element.id) { index, song in
                Button {
                    model.play(detail.playlist, index: index)
                    dismiss()
                } label: {
                    VStack(alignment: .leading) {
                        Text(song.title)
                        Text(song.artist)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(detail.playlist.name)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
