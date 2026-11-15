import SwiftUI

struct RootView: View {
  @EnvironmentObject private var model: AppModel

  var body: some View {
    TabView {
      NavigationStack { PlayerView() }
        .tabItem { Label("Now Playing", systemImage: "play.circle.fill") }

      NavigationStack { LibraryView() }
        .tabItem { Label("Library", systemImage: "music.note.list") }

      NavigationStack { QueueView() }
        .tabItem { Label("Queue", systemImage: "text.line.first.and.arrowtriangle.forward") }

      NavigationStack { ConnectionView() }
        .tabItem { Label("Connect", systemImage: "antenna.radiowaves.left.and.right") }
    }
    .overlay(alignment: .top) {
      if !model.ble.connectionState.isReady {
        ConnectionBanner(text: model.ble.connectionState.title)
          .padding(.top, 4)
      }
    }
  }
}
