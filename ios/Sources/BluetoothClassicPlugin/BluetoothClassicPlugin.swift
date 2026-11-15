import Foundation
import UIKit
import Capacitor
import CoreBluetooth
import ExternalAccessory
#if canImport(BluetoothClassicCore)
import BluetoothClassicCore
#endif

/// What shows the system accessory picker: the one iOS provides in an app, a fake in the tests.
protocol AccessoryPicker {
    func showPicker(completion: @escaping (Error?) -> Void)
}

struct SystemAccessoryPicker: AccessoryPicker {
    func showPicker(completion: @escaping (Error?) -> Void) {
        EAAccessoryManager.shared().showBluetoothAccessoryPicker(withNameFilter: nil) { error in
            completion(error)
        }
    }
}

/// Bluetooth Classic on iOS, which is the External Accessory framework: MFi accessories, each reached through a
/// session per protocol string the app declares in `UISupportedExternalAccessoryProtocols`.
///
/// Calls arrive on the bridge's queue; every one hops to the main thread, where the sessions live and their streams are
/// scheduled.
@objc(BluetoothClassicPlugin)
public class BluetoothClassicPlugin: CAPPlugin, CAPBridgedPlugin, CBCentralManagerDelegate {
    public let identifier = "BluetoothClassicPlugin"
    public let jsName = "BluetoothClassic"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "list", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "discoverUnpaired", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disconnect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disconnectAll", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isConnected", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isEnabled", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "enable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "showBluetoothSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "subscribe", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "unsubscribe", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "subscribeRaw", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "unsubscribeRaw", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "write", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "available", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "read", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "readUntil", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clear", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getBluetoothState", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise)
    ]

    var sessions: [AccessorySession] = []
    var accessoryPicker: AccessoryPicker = SystemAccessoryPicker()

    /// Created on first need, not at load: creating one raises the Bluetooth permission prompt.
    private var centralManager: CBCentralManager?
    private var stateWaiters: [() -> Void] = []
    private static let stateTimeout: TimeInterval = 2

    override public func load() {
        NotificationCenter.default.addObserver(self, selector: #selector(accessoryConnected(_:)),
                                               name: .EAAccessoryDidConnect, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(accessoryDisconnected(_:)),
                                               name: .EAAccessoryDidDisconnect, object: nil)
        EAAccessoryManager.shared().registerForLocalNotifications()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    // MARK: - Devices

    @objc func list(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            call.resolve(["devices": self.connectedAccessories()])
        }
    }

    @objc func discoverUnpaired(_ call: CAPPluginCall) {
        // The picker is a UIKit presentation, so it is shown from the main thread.
        DispatchQueue.main.async {
            self.giveTheAppDelegateAWindow()
            self.accessoryPicker.showPicker { error in
                DispatchQueue.main.async {
                    if let failure = BluetoothClassicPlugin.pickerFailure(error) {
                        call.reject(failure.message, failure.code)
                        return
                    }
                    // Selected and connected (no error), or an accessory that was connected already, which iOS reports as
                    // an error and gives no accessory for: either way the answer is the accessories connected now.
                    call.resolve(["devices": self.connectedAccessories()])
                }
            }
        }
    }

    // MARK: - Connections

    @objc func connect(_ call: CAPPluginCall) {
        guard let connectionId = Self.deviceId(call) else {
            call.reject("deviceId is required", BluetoothClassicErrorCode.invalidArgument)
            return
        }
        let protocols = (call.getArray("interfaces", String.self) ?? []).filter { !$0.isEmpty }
        guard !protocols.isEmpty else {
            call.reject("interfaces is required", BluetoothClassicErrorCode.invalidArgument)
            return
        }
        DispatchQueue.main.async {
            let error = self.openSessions(connectionId: connectionId, protocols: protocols)
            if let error {
                call.reject(error.message, error.code, nil, ["id": connectionId, "protocolStrings": protocols])
            } else {
                call.resolve()
            }
        }
    }

    @objc func disconnect(_ call: CAPPluginCall) {
        withInterface(call) { connectionId, protocolString in
            self.close(self.sessions(connectionId: connectionId, protocolString: protocolString),
                       message: BluetoothClassicMessage.manuallyDisconnected)
            call.resolve()
        }
    }

    @objc func disconnectAll(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.close(self.sessions, message: BluetoothClassicMessage.manuallyDisconnected)
            call.resolve()
        }
    }

    @objc func isConnected(_ call: CAPPluginCall) {
        withInterface(call) { connectionId, protocolString in
            let session = self.session(connectionId: connectionId, protocolString: protocolString)
            call.resolve(["connected": session?.accessory.isConnected ?? false])
        }
    }

    // MARK: - Bluetooth

    @objc func isEnabled(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            self.whenStateKnown {
                call.resolve(["enabled": self.centralManager?.state == .poweredOn])
            }
        }
    }

    @objc func enable(_ call: CAPPluginCall) {
        call.unimplemented("iOS gives an app no way to switch Bluetooth on.")
    }

    @objc func showBluetoothSettings(_ call: CAPPluginCall) {
        call.unimplemented("iOS gives an app no way to open the Bluetooth settings.")
    }

    @objc func getBluetoothState(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            let protocols = Bundle.main.object(forInfoDictionaryKey: "UISupportedExternalAccessoryProtocols") as? [String] ?? []
            call.resolve([
                "authorization": Self.authorizationName(CBManager.authorization),
                "supportedProtocols": protocols,
                "connectedAccessories": EAAccessoryManager.shared().connectedAccessories.count,
                "systemVersion": UIDevice.current.systemVersion
            ])
        }
    }

    @objc override public func checkPermissions(_ call: CAPPluginCall) {
        call.resolve(Self.permissionStatus(CBManager.authorization))
    }

    @objc override public func requestPermissions(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard CBManager.authorization == .notDetermined else {
                call.resolve(Self.permissionStatus(CBManager.authorization))
                return
            }
            // The prompt is raised by creating the manager; its first state update comes once the user has answered.
            self.whenStateKnown(timeout: nil) {
                call.resolve(Self.permissionStatus(CBManager.authorization))
            }
        }
    }

    // MARK: - Data

    @objc func subscribe(_ call: CAPPluginCall) {
        guard let delimiter = call.getString("delimiter") else {
            call.reject("delimiter is required", BluetoothClassicErrorCode.invalidArgument)
            return
        }
        withSession(call, mustBeOpen: false, notFound: BluetoothClassicMessage.noSession) { session in
            session.delimiter = delimiter
            call.resolve()
        }
    }

    @objc func unsubscribe(_ call: CAPPluginCall) {
        withInterface(call) { connectionId, protocolString in
            self.session(connectionId: connectionId, protocolString: protocolString)?.delimiter = nil
            call.resolve()
        }
    }

    @objc func subscribeRaw(_ call: CAPPluginCall) {
        withSession(call, mustBeOpen: false, notFound: BluetoothClassicMessage.noSession) { session in
            session.rawSubscribed = true
            call.resolve()
        }
    }

    @objc func unsubscribeRaw(_ call: CAPPluginCall) {
        withInterface(call) { connectionId, protocolString in
            self.session(connectionId: connectionId, protocolString: protocolString)?.rawSubscribed = false
            call.resolve()
        }
    }

    @objc func write(_ call: CAPPluginCall) {
        guard let encoded = call.getString("data"), let data = Data(base64Encoded: encoded) else {
            call.reject("data is required, base64 encoded", BluetoothClassicErrorCode.invalidArgument)
            return
        }
        withSession(call, mustBeOpen: true, notFound: BluetoothClassicMessage.notOpen) { session in
            if session.write(data) {
                call.resolve()
            } else {
                call.reject(BluetoothClassicMessage.writeFailed, BluetoothClassicErrorCode.writeFailed)
            }
        }
    }

    @objc func available(_ call: CAPPluginCall) {
        withInterface(call) { connectionId, protocolString in
            let session = self.session(connectionId: connectionId, protocolString: protocolString)
            call.resolve(["available": session?.receiveBuffer.count ?? 0])
        }
    }

    @objc func read(_ call: CAPPluginCall) {
        withSession(call, mustBeOpen: true, notFound: BluetoothClassicMessage.notOpen) { session in
            call.resolve(["data": session.receiveBuffer.readAll()])
        }
    }

    @objc func readUntil(_ call: CAPPluginCall) {
        guard let delimiter = call.getString("delimiter") else {
            call.reject("delimiter is required", BluetoothClassicErrorCode.invalidArgument)
            return
        }
        withSession(call, mustBeOpen: true, notFound: BluetoothClassicMessage.notOpen) { session in
            call.resolve(["data": session.receiveBuffer.readUntil(delimiter)])
        }
    }

    @objc func clear(_ call: CAPPluginCall) {
        withInterface(call) { connectionId, protocolString in
            self.session(connectionId: connectionId, protocolString: protocolString)?.clear()
            call.resolve()
        }
    }

    // MARK: - Sessions

    /// Opens a session for each protocol on the connected accessory with this connection id that speaks all of them. A
    /// protocol whose session is already open keeps it: iOS refuses a second session for one accessory and protocol.
    func openSessions(connectionId: Int, protocols: [String]) -> BluetoothClassicError? {
        let accessory = EAAccessoryManager.shared().connectedAccessories.first { candidate in
            candidate.connectionID == connectionId && protocols.allSatisfy { candidate.protocolStrings.contains($0) }
        }
        guard let accessory else {
            return BluetoothClassicError(BluetoothClassicErrorCode.connectFailed, BluetoothClassicMessage.noAccessory)
        }
        var opened: [AccessorySession] = []
        for protocolString in protocols {
            if let existing = session(connectionId: connectionId, protocolString: protocolString), existing.isOpen {
                continue
            }
            sessions.removeAll { $0.connectionId == connectionId && $0.protocolString == protocolString }
            let session = AccessorySession(accessory: accessory, protocolString: protocolString)
            guard session.open() else {
                opened.forEach { $0.close() }
                sessions.removeAll { candidate in opened.contains { $0 === candidate } }
                return BluetoothClassicError(BluetoothClassicErrorCode.connectFailed, BluetoothClassicMessage.notAllOpened)
            }
            wire(session)
            sessions.append(session)
            opened.append(session)
        }
        return nil
    }

    private func wire(_ session: AccessorySession) {
        let deviceId = session.connectionId
        let interfaceId = session.protocolString
        session.onMessage = { [weak self] message in
            self?.notifyListeners("data", data: ["deviceId": deviceId, "interfaceId": interfaceId, "data": message])
        }
        session.onRawData = { [weak self] bytes in
            self?.notifyListeners("rawData", data: [
                "deviceId": deviceId, "interfaceId": interfaceId, "data": bytes.base64EncodedString()
            ])
        }
    }

    /// Closes the sessions, forgets them, and reports the end of each.
    func close(_ closing: [AccessorySession], message: String) {
        for session in closing {
            session.close()
            notifyListeners("disconnected", data: [
                "deviceId": session.connectionId, "interfaceId": session.protocolString, "message": message
            ])
        }
        sessions.removeAll { candidate in closing.contains { $0 === candidate } }
    }

    func session(connectionId: Int, protocolString: String) -> AccessorySession? {
        sessions.first { $0.connectionId == connectionId && $0.protocolString == protocolString }
    }

    func sessions(connectionId: Int, protocolString: String) -> [AccessorySession] {
        sessions.filter { $0.connectionId == connectionId && $0.protocolString == protocolString }
    }

    private func connectedAccessories() -> [[String: Any]] {
        EAAccessoryManager.shared().connectedAccessories.map(Self.details)
    }

    private func withInterface(_ call: CAPPluginCall, _ body: @escaping (Int, String) -> Void) {
        guard let connectionId = Self.deviceId(call) else {
            call.reject("deviceId is required", BluetoothClassicErrorCode.invalidArgument)
            return
        }
        guard let protocolString = call.getString("interfaceId") else {
            call.reject("interfaceId is required", BluetoothClassicErrorCode.invalidArgument)
            return
        }
        DispatchQueue.main.async {
            body(connectionId, protocolString)
        }
    }

    private func withSession(_ call: CAPPluginCall, mustBeOpen: Bool, notFound: String,
                             _ body: @escaping (AccessorySession) -> Void) {
        withInterface(call) { connectionId, protocolString in
            guard let session = self.session(connectionId: connectionId, protocolString: protocolString),
                  !mustBeOpen || session.isOpen else {
                call.reject(notFound, mustBeOpen ? BluetoothClassicErrorCode.notOpen : BluetoothClassicErrorCode.noConnection)
                return
            }
            body(session)
        }
    }

    // MARK: - Accessory notifications

    /// An accessory came up (picked in the picker, or a paired one that connected by itself). It is announced once, unless
    /// every one of its protocols has a session already.
    @objc func accessoryConnected(_ notification: Notification) {
        guard let accessory = notification.userInfo?[EAAccessoryKey] as? EAAccessory else { return }
        let known = !accessory.protocolStrings.isEmpty && accessory.protocolStrings.allSatisfy { protocolString in
            self.session(connectionId: accessory.connectionID, protocolString: protocolString)?.accessory.isConnected == true
        }
        if !known {
            notifyListeners("deviceDiscovered", data: Self.details(accessory))
        }
    }

    @objc func accessoryDisconnected(_ notification: Notification) {
        guard let accessory = notification.userInfo?[EAAccessoryKey] as? EAAccessory else { return }
        let lost = sessions.filter { $0.connectionId == accessory.connectionID }
        close(lost, message: BluetoothClassicMessage.connectionLost)
    }

    // MARK: - Bluetooth state

    private func whenStateKnown(timeout: TimeInterval? = BluetoothClassicPlugin.stateTimeout, _ answer: @escaping () -> Void) {
        if centralManager == nil {
            centralManager = CBCentralManager(delegate: self, queue: nil, options: nil)
        }
        guard let manager = centralManager, manager.state == .unknown || manager.state == .resetting else {
            answer()
            return
        }
        var answered = false
        let once = {
            if !answered {
                answered = true
                answer()
            }
        }
        stateWaiters.append(once)
        if let timeout {
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout, execute: once)
        }
    }

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let waiters = stateWaiters
        stateWaiters.removeAll()
        waiters.forEach { $0() }
    }

    // MARK: - Mapping

    static func deviceId(_ call: CAPPluginCall) -> Int? {
        if let number = call.getInt("deviceId") {
            return number
        }
        if let text = call.getString("deviceId") {
            return Int(text)
        }
        return nil
    }

    static func details(_ accessory: EAAccessory) -> [String: Any] {
        [
            "id": accessory.connectionID,
            "address": accessory.connectionID,
            "class": "",
            "manufacturer": accessory.manufacturer,
            "name": accessory.name,
            "modelNumber": accessory.modelNumber,
            "serialNumber": accessory.serialNumber,
            "firmwareRevision": accessory.firmwareRevision,
            "hardwareRevision": accessory.hardwareRevision,
            "protocols": accessory.protocolStrings
        ]
    }

    /// The failure an accessory picker result is, or nil for a selection (no error, or an accessory already connected).
    static func pickerFailure(_ error: Error?) -> BluetoothClassicError? {
        guard let error = error as NSError? else { return nil }
        switch error.code {
        case EABluetoothAccessoryPickerError.Code.resultCancelled.rawValue:
            return BluetoothClassicError(BluetoothClassicErrorCode.pickerCancelled, "Cancelled")
        case EABluetoothAccessoryPickerError.Code.resultNotFound.rawValue:
            return BluetoothClassicError(BluetoothClassicErrorCode.pickerNotFound, "Device not found")
        case EABluetoothAccessoryPickerError.Code.resultFailed.rawValue:
            return BluetoothClassicError(BluetoothClassicErrorCode.pickerFailed, "Device selection failed")
        default:
            return nil
        }
    }

    static func authorizationName(_ authorization: CBManagerAuthorization) -> String {
        switch authorization {
        case .notDetermined: return "notDetermined"
        case .restricted: return "restricted"
        case .denied: return "denied"
        case .allowedAlways: return "allowedAlways"
        @unknown default: return "unknown"
        }
    }

    static func permissionStatus(_ authorization: CBManagerAuthorization) -> [String: Any] {
        let bluetooth: String
        switch authorization {
        case .allowedAlways: bluetooth = "granted"
        case .notDetermined: bluetooth = "prompt"
        default: bluetooth = "denied"
        }
        // Location plays no part in Bluetooth Classic on iOS.
        return ["bluetooth": bluetooth, "location": "granted"]
    }

    // MARK: - The accessory picker's window

    /// The system picker presents in the application delegate's `window` and, when the delegate has that property and it
    /// is nil, shows nothing and never calls back. An app on the scene lifecycle (Capacitor 8's template, among others)
    /// never sets it, because the scene delegate owns the window; so the key window of the active scene is given to the
    /// delegate first. A delegate with no `window` property is left alone.
    private func giveTheAppDelegateAWindow() {
        var keyWindow = bridge?.viewController?.view.window
        if keyWindow == nil {
            for scene in UIApplication.shared.connectedScenes {
                guard let windowScene = scene as? UIWindowScene, scene.activationState == .foregroundActive else { continue }
                keyWindow = windowScene.windows.first { $0.isKeyWindow } ?? keyWindow
            }
        }
        Self.giveDelegate(UIApplication.shared.delegate, theWindow: keyWindow)
    }

    @discardableResult
    static func giveDelegate(_ delegate: NSObjectProtocol?, theWindow window: UIWindow?) -> Bool {
        let getter = NSSelectorFromString("window")
        let setter = NSSelectorFromString("setWindow:")
        guard let window, let delegate, delegate.responds(to: getter), delegate.responds(to: setter) else {
            return false
        }
        if delegate.perform(getter)?.takeUnretainedValue() != nil {
            return false
        }
        _ = delegate.perform(setter, with: window)
        return true
    }
}
