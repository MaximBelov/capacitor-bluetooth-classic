import Foundation

/// The codes a call rejects with. The TypeScript API declares the same list as `BluetoothClassicErrorCode`, and Android
/// has the same constants: a test compares the three.
public enum BluetoothClassicErrorCode {
    public static let invalidArgument = "INVALID_ARGUMENT"
    public static let bluetoothUnavailable = "BLUETOOTH_UNAVAILABLE"
    public static let bluetoothDisabled = "BLUETOOTH_DISABLED"
    public static let permissionDenied = "PERMISSION_DENIED"
    public static let noConnection = "NO_CONNECTION"
    public static let notOpen = "NOT_OPEN"
    public static let connectFailed = "CONNECT_FAILED"
    public static let writeFailed = "WRITE_FAILED"
    public static let discoveryFailed = "DISCOVERY_FAILED"
    public static let pickerCancelled = "PICKER_CANCELLED"
    public static let pickerNotFound = "PICKER_NOT_FOUND"
    public static let pickerFailed = "PICKER_FAILED"
}

/// A failure with the code and the message the call rejects with.
public struct BluetoothClassicError: Error, Equatable {
    public let code: String
    public let message: String

    public init(_ code: String, _ message: String) {
        self.code = code
        self.message = message
    }
}

/// The messages the Cordova plugin this one replaces used, kept so that what an app logs reads the same.
public enum BluetoothClassicMessage {
    public static let connectionLost = "Device connection was lost"
    public static let manuallyDisconnected = "Device was manually disconnected"
    public static let notOpen = "The communication session for this protocol is not open on the device."
    public static let noSession = "No matching session found."
    public static let writeFailed = "An error occurred writing the session data."
    public static let noAccessory = "Could not find accessory with matching connectionID and protocol string"
    public static let notAllOpened = "Could not open a communication session for all the protocols supplied."
}
