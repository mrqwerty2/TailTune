import SwiftUI

struct PlayerView: View {
  @EnvironmentObject private var model: AppModel
  @State private var dragPosition: Double?
  @State private var volume: Double = 70

  private var playback: PlaybackInfo? { model.snapshot?.playback }

  var body: some View {
    ScrollView {
      VStack(spacing: 24) {
        RoundedRectangle(cornerRadius: 28)
          .fill(.quaternary)
          .aspectRatio(1, contentMode: .fit)
          .overlay {
            Image(systemName: "music.note")
              .font(.system(size: 72, weight: .light))
              .foregroundStyle(.secondary)
          }
          .padding(.top, 12)

        VStack(spacing: 5) {
          Text(playback?.current?.title ?? "Nothing playing")
            .font(.title2.bold())
            .lineLimit(2)
            .multilineTextAlignment(.center)
          Text(playback?.current?.artist ?? "TailTune")
            .foregroundStyle(.secondary)
        }

        VStack(spacing: 6) {
          Slider(
            value: Binding(
              get: { dragPosition ?? Double(model.displayedPositionMs) },
              set: { dragPosition = $0 }
            ),
            in: 0...Double(max(playback?.durationMs ?? 1, 1)),
            onEditingChanged: { editing in
              if !editing, let value = dragPosition {
                dragPosition = nil
                Task { await model.seek(to: Int(value)) }
              }
            }
          )
          HStack {
            Text(formatTime(Int(dragPosition ?? Double(model.displayedPositionMs))))
            Spacer()
            Text(formatTime(playback?.durationMs ?? 0))
          }
          .font(.caption.monospacedDigit())
          .foregroundStyle(.secondary)
        }

        HStack(spacing: 34) {
          Button {
            Task { await model.control("previous") }
          } label: {
            Image(systemName: "backward.fill").font(.title2)
          }
          Button {
            Task { await model.control(playback?.isPlaying == true ? "pause" : "play") }
          } label: {
            Image(
              systemName: playback?.isPlaying == true ? "pause.circle.fill" : "play.circle.fill"
            )
            .font(.system(size: 66))
          }
          Button {
            Task { await model.control("next") }
          } label: {
            Image(systemName: "forward.fill").font(.title2)
          }
        }
        .buttonStyle(.plain)
        .disabled(!model.ble.connectionState.isReady)

        HStack(spacing: 12) {
          Image(systemName: "speaker.fill")
          Slider(
            value: $volume, in: 0...100, step: 1,
            onEditingChanged: { editing in
              if !editing { Task { await model.setVolume(Int(volume)) } }
            })
          Image(systemName: "speaker.wave.3.fill")
        }
        .onAppear { volume = Double(playback?.volume ?? 70) }
        .onChange(of: playback?.volume) { _, newValue in
          if let newValue { volume = Double(newValue) }
        }

        if let device = model.snapshot?.device {
          HStack {
            Label(
              device.navidromeOnline == false ? "Offline library" : "Library online",
              systemImage: device.navidromeOnline == false ? "icloud.slash" : "icloud")
            Spacer()
            Label(
              device.batteryPercent.map { "Samsung \($0)%" } ?? "Samsung battery unavailable",
              systemImage: device.charging == true ? "battery.100percent.bolt" : "battery.100percent")
          }
          .font(.caption)
          .foregroundStyle(.secondary)
        }
      }
      .padding()
    }
    .navigationTitle("Now Playing")
    .toolbar {
      ToolbarItem(placement: .topBarTrailing) {
        Button {
          Task { await model.refreshAll() }
        } label: {
          Image(systemName: "arrow.clockwise")
        }
        .disabled(!model.ble.connectionState.isReady || model.isBusy)
      }
    }
  }

  private func formatTime(_ milliseconds: Int) -> String {
    let seconds = max(milliseconds, 0) / 1000
    return String(format: "%d:%02d", seconds / 60, seconds % 60)
  }
}
