// swift-tools-version:5.9
// Local twin of the repository-root Package.swift: same product and target, but the binary target points at the
// XCFramework that tools/release/build-xcframework.sh just built (no URL, no checksum).
import PackageDescription

let package = Package(
    name: "Teslable",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "Teslable", targets: ["Teslable"]),
    ],
    targets: [
        .binaryTarget(name: "Teslable", path: "../../../../build/release/Teslable.xcframework"),
    ]
)
