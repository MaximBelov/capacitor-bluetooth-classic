package io.github.maximbelov.bluetoothclassic

/**
 * Which of the plugin's permission aliases finding and listing devices needs, by Android version, as the Cordova plugin
 * decided it:
 *
 * - Android 12 (API 31) and later, in an app that targets it: Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`).
 * - Android 10 and 11 (API 29, 30): precise location, and background location only when the app asks for it with
 *   `accessBackgroundLocation` (off by default).
 * - Android 9 and earlier: approximate location.
 */
object PermissionPolicy {
    const val BLUETOOTH = "bluetooth"
    const val LOCATION = "location"
    const val COARSE_LOCATION = "coarseLocation"
    const val BACKGROUND_LOCATION = "backgroundLocation"

    fun requiredAliases(sdk: Int, targetSdk: Int, backgroundLocation: Boolean): List<String> = when {
        sdk >= 31 && targetSdk >= 31 -> listOf(BLUETOOTH)
        sdk >= 29 && targetSdk >= 29 -> if (backgroundLocation) listOf(LOCATION, BACKGROUND_LOCATION) else listOf(LOCATION)
        else -> listOf(COARSE_LOCATION)
    }

    /** The alias whose answer `location` reports in `checkPermissions`, or null where location is not needed. */
    fun locationAlias(sdk: Int, targetSdk: Int): String? = when {
        sdk >= 31 && targetSdk >= 31 -> null
        sdk >= 29 && targetSdk >= 29 -> LOCATION
        else -> COARSE_LOCATION
    }

    /** Whether `bluetooth` in `checkPermissions` is a runtime permission here at all. */
    fun bluetoothIsRuntime(sdk: Int, targetSdk: Int): Boolean = sdk >= 31 && targetSdk >= 31
}
