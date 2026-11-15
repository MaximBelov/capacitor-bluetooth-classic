package io.github.maximbelov.bluetoothclassic

/**
 * The codes a call rejects with. The TypeScript API declares the same list as `BluetoothClassicErrorCode`, and iOS has the
 * same constants: a test compares the three.
 */
object ErrorCode {
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val BLUETOOTH_UNAVAILABLE = "BLUETOOTH_UNAVAILABLE"
    const val BLUETOOTH_DISABLED = "BLUETOOTH_DISABLED"
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val NO_CONNECTION = "NO_CONNECTION"
    const val NOT_OPEN = "NOT_OPEN"
    const val CONNECT_FAILED = "CONNECT_FAILED"
    const val WRITE_FAILED = "WRITE_FAILED"
    const val DISCOVERY_FAILED = "DISCOVERY_FAILED"
    const val PICKER_CANCELLED = "PICKER_CANCELLED"
    const val PICKER_NOT_FOUND = "PICKER_NOT_FOUND"
    const val PICKER_FAILED = "PICKER_FAILED"
}

/** The messages the Cordova plugin this one replaces used, kept so that what an app logs reads the same. */
object Message {
    const val CONNECTION_LOST = "Device connection was lost"
    const val MANUALLY_DISCONNECTED = "Device was manually disconnected"
    const val UNABLE_TO_CONNECT = "Unable to connect to device"
    const val NO_INTERFACE = "No Interface"
    const val BLUETOOTH_DISABLED = "Bluetooth is disabled."
    const val NOT_ENABLED_BY_USER = "User did not enable Bluetooth"
    const val LOCATION_DENIED = "Location permission not granted."
    const val SCAN_DENIED = "Bluetooth scan permission not granted."
    const val CONNECT_DENIED = "Bluetooth Connect permission not granted."
}

/** A failure with the code and the message the call rejects with. */
class BluetoothClassicException(val code: String, message: String) : Exception(message)
