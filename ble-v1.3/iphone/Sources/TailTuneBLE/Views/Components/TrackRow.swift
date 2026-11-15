import SwiftUI

struct TrackRow: View {
  let track: Track
  var trailingSystemImage: String? = nil
  var action: (() -> Void)? = nil

  var body: some View {
    HStack(spacing: 12) {
      RoundedRectangle(cornerRadius: 8)
        .fill(.quaternary)
        .frame(width: 44, height: 44)
        .overlay { Image(systemName: "music.note").foregroundStyle(.secondary) }

      VStack(alignment: .leading, spacing: 3) {
        Text(track.title)
          .font(.body.weight(.medium))
          .lineLimit(1)
        Text([track.artist, track.album].compactMap { $0 }.joined(separator: " • "))
          .font(.caption)
          .foregroundStyle(.secondary)
          .lineLimit(1)
      }
      Spacer(minLength: 8)
      if track.downloaded == true {
        Image(systemName: "arrow.down.circle.fill")
          .foregroundStyle(.secondary)
          .accessibilityLabel("Downloaded")
      }
      if let trailingSystemImage, let action {
        Button(action: action) { Image(systemName: trailingSystemImage) }
          .buttonStyle(.borderless)
      }
    }
    .contentShape(Rectangle())
  }
}
