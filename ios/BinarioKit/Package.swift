// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "BinarioKit",
    products: [
        .library(name: "BinarioKit", targets: ["BinarioKit"])
    ],
    targets: [
        .target(
            name: "BinarioKit",
            path: "Sources/BinarioKit"
        ),
        .testTarget(
            name: "BinarioKitTests",
            dependencies: ["BinarioKit"],
            path: "Tests/BinarioKitTests"
        )
    ],
    swiftLanguageModes: [.v6]
)
