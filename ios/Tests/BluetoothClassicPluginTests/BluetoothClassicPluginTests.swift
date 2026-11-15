import XCTest
import UIKit
import Capacitor
import CoreBluetooth
import ExternalAccessory
import BluetoothClassicCore
@testable import BluetoothClassicPlugin

/// The JavaScript reaches the plugin by `jsName` and each method by its name: a rename on either side fails only at run
/// time on a phone, so both are pinned here, with the mappings that need no accessory.
final class BluetoothClassicPluginTests: XCTestCase {
    func testIsRegisteredUnderTheNameTheJavaScriptUses() {
        let plugin = BluetoothClassicPlugin()
        XCTAssertEqual(plugin.jsName, "BluetoothClassic")
        XCTAssertEqual(plugin.identifier, "BluetoothClassicPlugin")
    }

    func testRegistersExactlyTheMethodsOfTheApi() {
        let names = BluetoothClassicPlugin().pluginMethods.map(\.name).sorted()
        XCTAssertEqual(names, [
            "available", "checkPermissions", "clear", "connect", "disconnect", "disconnectAll", "discoverUnpaired", "enable",
            "getBluetoothState", "isConnected", "isEnabled", "list", "read", "readUntil", "requestPermissions",
            "showBluetoothSettings", "subscribe", "subscribeRaw", "unsubscribe", "unsubscribeRaw", "write"
        ])
        for name in names {
            XCTAssertTrue(BluetoothClassicPlugin().responds(to: NSSelectorFromString("\(name):")), "no @objc \(name)(_:)")
        }
    }

    func testAPickerSelectionIsNoFailure() {
        XCTAssertNil(BluetoothClassicPlugin.pickerFailure(nil))
        let alreadyConnected = NSError(domain: EABluetoothAccessoryPickerErrorDomain,
                                       code: EABluetoothAccessoryPickerError.Code.alreadyConnected.rawValue)
        XCTAssertNil(BluetoothClassicPlugin.pickerFailure(alreadyConnected))
    }

    func testAPickerFailureRejectsWithTheMessagesTheCordovaPluginUsed() {
        let error = { (code: EABluetoothAccessoryPickerError.Code) in
            NSError(domain: EABluetoothAccessoryPickerErrorDomain, code: code.rawValue)
        }
        XCTAssertEqual(BluetoothClassicPlugin.pickerFailure(error(.resultCancelled)),
                       BluetoothClassicError(BluetoothClassicErrorCode.pickerCancelled, "Cancelled"))
        XCTAssertEqual(BluetoothClassicPlugin.pickerFailure(error(.resultNotFound)),
                       BluetoothClassicError(BluetoothClassicErrorCode.pickerNotFound, "Device not found"))
        XCTAssertEqual(BluetoothClassicPlugin.pickerFailure(error(.resultFailed)),
                       BluetoothClassicError(BluetoothClassicErrorCode.pickerFailed, "Device selection failed"))
    }

    func testThePermissionStatusFollowsTheBluetoothAuthorization() {
        XCTAssertEqual(BluetoothClassicPlugin.permissionStatus(.allowedAlways)["bluetooth"] as? String, "granted")
        XCTAssertEqual(BluetoothClassicPlugin.permissionStatus(.notDetermined)["bluetooth"] as? String, "prompt")
        XCTAssertEqual(BluetoothClassicPlugin.permissionStatus(.denied)["bluetooth"] as? String, "denied")
        XCTAssertEqual(BluetoothClassicPlugin.permissionStatus(.restricted)["bluetooth"] as? String, "denied")
        XCTAssertEqual(BluetoothClassicPlugin.permissionStatus(.denied)["location"] as? String, "granted")
        XCTAssertEqual(BluetoothClassicPlugin.authorizationName(.allowedAlways), "allowedAlways")
    }

    /// The picker shows nothing, and never answers, when the delegate has a nil `window`.
    func testTheAppDelegateGetsTheWindowOnlyWhenItHasANilOne() {
        final class SceneAppDelegate: NSObject, UIApplicationDelegate {
            var window: UIWindow?
        }
        final class PlainDelegate: NSObject, UIApplicationDelegate {}

        let window = UIWindow()
        let empty = SceneAppDelegate()
        XCTAssertTrue(BluetoothClassicPlugin.giveDelegate(empty, theWindow: window))
        XCTAssertTrue(empty.window === window)

        let other = UIWindow()
        XCTAssertFalse(BluetoothClassicPlugin.giveDelegate(empty, theWindow: other))
        XCTAssertTrue(empty.window === window)

        XCTAssertFalse(BluetoothClassicPlugin.giveDelegate(PlainDelegate(), theWindow: window))
        XCTAssertFalse(BluetoothClassicPlugin.giveDelegate(SceneAppDelegate(), theWindow: nil))
    }

    func testANumberOrANumericStringIsADeviceId() throws {
        let number = try XCTUnwrap(CAPPluginCall(callbackId: "1", methodName: "isConnected", options: ["deviceId": 250001],
                                                 success: { _, _ in }, error: { _ in }))
        XCTAssertEqual(BluetoothClassicPlugin.deviceId(number), 250001)
        let text = try XCTUnwrap(CAPPluginCall(callbackId: "2", methodName: "isConnected", options: ["deviceId": "17"],
                                               success: { _, _ in }, error: { _ in }))
        XCTAssertEqual(BluetoothClassicPlugin.deviceId(text), 17)
        let none = try XCTUnwrap(CAPPluginCall(callbackId: "3", methodName: "isConnected", options: [:],
                                               success: { _, _ in }, error: { _ in }))
        XCTAssertNil(BluetoothClassicPlugin.deviceId(none))
    }

    /// iOS has no "switch Bluetooth on" and no way into the Bluetooth settings: both say so instead of hanging.
    func testTheAndroidOnlyMethodsRejectAsUnimplemented() throws {
        let plugin = BluetoothClassicPlugin()
        for method in ["enable", "showBluetoothSettings"] {
            var code: String?
            let call = try XCTUnwrap(CAPPluginCall(callbackId: method, methodName: method, options: [:],
                                                   success: { _, _ in }, error: { error in code = error?.code }))
            plugin.perform(NSSelectorFromString("\(method):"), with: call)
            XCTAssertEqual(code, "UNIMPLEMENTED", method)
        }
    }
}
