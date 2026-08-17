import SwiftUI

@main
struct TailTuneRemoteApp: App {
    @StateObject private var model = TailTuneViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
        }
    }
}
