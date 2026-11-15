import SwiftUI

@main
struct TailTuneBLEApp: App {
  @StateObject private var model = AppModel()

  var body: some Scene {
    WindowGroup {
      RootView()
        .environmentObject(model)
        .alert(
          "TailTune",
          isPresented: Binding(
            get: { model.errorMessage != nil },
            set: { if !$0 { model.dismissError() } }
          )
        ) {
          Button("OK", role: .cancel) { model.dismissError() }
        } message: {
          Text(model.errorMessage ?? "Unknown error")
        }
    }
  }
}
