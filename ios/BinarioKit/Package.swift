// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "BinarioKit",
    platforms: [.macOS(.v10_15), .iOS(.v17), .watchOS(.v10)],
    products: [
        .library(name: "BinarioKit", targets: ["BinarioKit"])
    ],
    targets: [
        .systemLibrary(
            name: "CSQLite",
            path: "Sources/CSQLite",
            pkgConfig: "sqlite3",
            providers: [
                .apt(["libsqlite3-dev"]),
                .brew(["sqlite3"]),
            ]
        ),
        .systemLibrary(
            name: "CZlib",
            path: "Sources/CZlib",
            pkgConfig: "zlib",
            providers: [
                .apt(["zlib1g-dev"]),
                .brew(["zlib"]),
            ]
        ),
        .target(
            name: "BinarioKit",
            dependencies: ["CSQLite", "CZlib"],
            path: "Sources/BinarioKit"
        ),
        .testTarget(
            name: "BinarioKitTests",
            dependencies: ["BinarioKit", "CSQLite", "CZlib"],
            path: "Tests/BinarioKitTests"
        )
    ],
    swiftLanguageModes: [.v6]
)
