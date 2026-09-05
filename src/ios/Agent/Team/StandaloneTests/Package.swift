// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MinisTeamHarness",
    platforms: [.macOS(.v13)],
    products: [.library(name: "TeamHarness", targets: ["TeamHarness"])],
    targets: [
        .target(name: "TeamHarness"),
        .testTarget(name: "TeamHarnessTests", dependencies: ["TeamHarness"])
    ]
)
