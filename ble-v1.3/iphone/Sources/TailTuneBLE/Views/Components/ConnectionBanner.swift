import SwiftUI

struct ConnectionBanner: View {
  let text: String

  var body: some View {
    Text(text)
      .font(.caption.weight(.semibold))
      .padding(.horizontal, 12)
      .padding(.vertical, 7)
      .background(.ultraThinMaterial, in: Capsule())
      .shadow(radius: 2, y: 1)
      .accessibilityLabel("Bluetooth status: \(text)")
  }
}
