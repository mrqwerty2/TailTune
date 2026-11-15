import SwiftUI

struct QueueView: View {
  @EnvironmentObject private var model: AppModel

  var body: some View {
    List {
      if model.queue.isEmpty {
        ContentUnavailableView(
          "Queue is empty",
          systemImage: "text.line.first.and.arrowtriangle.forward",
          description: Text(
            model.ble.connectionState.isReady
              ? "Add a song from Library." : "Connect to your Samsung first.")
        )
      }
      ForEach(model.queue) { track in
        TrackRow(track: track)
      }
    }
    .navigationTitle("Queue")
    .refreshable { await model.refreshQueue() }
  }
}
