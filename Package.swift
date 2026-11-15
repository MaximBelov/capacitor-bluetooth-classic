// swift-tools-version: 5.9
import Foundation
import PackageDescription

// The receive buffer lives in BluetoothClassicCore, which needs only Foundation; BluetoothClassicPlugin is the Capacitor
// and External Accessory layer on top. Capacitor needs UIKit and ExternalAccessory exists only on iOS, so on a Mac
// `BLUETOOTH_CLASSIC_CORE_ONLY=1 swift test` builds and tests the core alone. An app never sets it.
let coreOnly = ProcessInfo.processInfo.environment["BLUETOOTH_CLASSIC_CORE_ONLY"] != nil

var targets: [Target] = [
    .target(
        name: "BluetoothClassicCore",
        path: "ios/Sources/BluetoothClassicCore"),
    .testTarget(
        name: "BluetoothClassicCoreTests",
        dependencies: ["BluetoothClassicCore"],
        path: "ios/Tests/BluetoothClassicCoreTests")
]
var products: [Product] = [
    .library(name: "BluetoothClassicCore", targets: ["BluetoothClassicCore"])
]
var dependencies: [Package.Dependency] = []

if !coreOnly {
    dependencies.append(.package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0"))
    products = [
        .library(
            name: "CapacitorBluetoothClassic",
            targets: ["BluetoothClassicPlugin"])
    ]
    targets += [
        .target(
            name: "BluetoothClassicPlugin",
            dependencies: [
                "BluetoothClassicCore",
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/BluetoothClassicPlugin",
            linkerSettings: [
                .linkedFramework("ExternalAccessory"),
                .linkedFramework("CoreBluetooth")
            ]),
        .testTarget(
            name: "BluetoothClassicPluginTests",
            dependencies: ["BluetoothClassicPlugin"],
            path: "ios/Tests/BluetoothClassicPluginTests")
    ]
}

let package = Package(
    name: "CapacitorBluetoothClassic",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: products,
    dependencies: dependencies,
    targets: targets
)
