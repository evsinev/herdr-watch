// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "HerdrWatchTray",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "HerdrWatchTray",
            path: "Sources/HerdrWatchTray"
        )
    ]
)
