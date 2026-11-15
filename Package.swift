// swift-tools-version:5.9
// Written by tools/release/prepare.sh (ADR-0017). Do not edit by hand: tools/release/publish.sh publishes only
// the Package.swift that prepare.sh writes, with the checksum of the zip that prepare.sh built and kept.
import PackageDescription

let package = Package(
    name: "Teslable",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "Teslable", targets: ["Teslable"]),
    ],
    targets: [
        .binaryTarget(
            name: "Teslable",
            url: "https://github.com/smallmiro/tesla-ble-kmp/releases/download/v1.0.0/Teslable.xcframework.zip",
            checksum: "bcaed3dea92b5932ec7f4173b93df588bc043ad62e44114d3c55777dc64495c7"
        ),
    ]
)
