// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "TailTuneBLE",
    platforms: [.iOS(.v17)],
    products: [.library(name: "TailTuneBLE", targets: ["TailTuneBLE"])],
    targets: [
        .target(name: "TailTuneBLE", path: "Sources/TailTuneBLE")
    ],
    swiftLanguageModes: [.v5]
)
