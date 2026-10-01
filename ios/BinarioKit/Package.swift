// swift-tools-version: 6.0
import PackageDescription

// pkg-config on macOS can prepend Homebrew's macOS-only dylibs to an iOS
// simulator link. Apple SDKs already provide sqlite3 and zlib for iOS/watchOS.
#if os(Linux)
let sqlitePkgConfig: String? = "sqlite3"
let zlibPkgConfig: String? = "zlib"
#else
let sqlitePkgConfig: String? = nil
let zlibPkgConfig: String? = nil
#endif

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
            pkgConfig: sqlitePkgConfig,
            providers: [
                .apt(["libsqlite3-dev"]),
                .brew(["sqlite3"]),
            ]
        ),
        .systemLibrary(
            name: "CZlib",
            path: "Sources/CZlib",
            pkgConfig: zlibPkgConfig,
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
