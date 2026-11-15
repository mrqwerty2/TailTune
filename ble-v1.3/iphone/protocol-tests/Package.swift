// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "TailTuneProtocol",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "TailTuneProtocol", targets: ["TailTuneProtocol"])
    ],
    targets: [
        .target(name: "TailTuneProtocol"),
        .testTarget(name: "TailTuneProtocolTests", dependencies: ["TailTuneProtocol"])
    ]
)
