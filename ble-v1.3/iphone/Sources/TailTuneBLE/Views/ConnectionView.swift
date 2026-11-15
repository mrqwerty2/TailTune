import SwiftUI

struct ConnectionView: View {
  @EnvironmentObject private var model: AppModel

  var body: some View {
    List {
      Section("Status") {
        Label(
          model.ble.connectionState.title,
          systemImage: model.ble.connectionState.isReady
            ? "checkmark.circle.fill" : "antenna.radiowaves.left.and.right")
        if let error = model.ble.lastTransportError {
          Text(error).font(.caption).foregroundStyle(.secondary)
        }
      }

      if model.ble.connectionState.isReady {
        Section("Samsung status") {
          if let device = model.snapshot?.device {
            Label(device.batteryPercent.map { "Battery \($0)%" } ?? "Battery unavailable",
                  systemImage: device.charging == true ? "battery.100percent.bolt" : "battery.100percent")
            Text(device.charging == true ? "Charging" : "Not charging")
              .font(.caption).foregroundStyle(.secondary)
          } else {
            Text("Checking Samsung battery…")
          }
          Button("Refresh device status") { Task { await model.refreshSnapshot() } }
        }
        Section {
          Button("Disconnect", role: .destructive) { model.ble.disconnect() }
        }
      } else {
        Section("Nearby TailTune devices") {
          if model.ble.candidates.isEmpty {
            Text("No TailTune Samsung found yet.")
              .foregroundStyle(.secondary)
          }
          ForEach(model.ble.candidates) { candidate in
            Button {
              model.ble.connect(to: candidate)
            } label: {
              HStack {
                VStack(alignment: .leading) {
                  Text(candidate.name).foregroundStyle(.primary)
                  Text("Signal \(candidate.rssi) dBm").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
              }
            }
          }
        }

        Section {
          Button {
            model.ble.scan()
          } label: {
            Label("Scan again", systemImage: "arrow.clockwise")
          }
        }
      }

      Section("How it works") {
        Text(
          "The iPhone is the BLE central. Your Samsung advertises the TailTune service and remains the actual music player. Audio never streams through Bluetooth; only control, library, queue and playback-state data do."
        )
        .font(.footnote)
        .foregroundStyle(.secondary)
      }
    }
    .navigationTitle("Bluetooth")
  }
}
