package io.github.maximbelov.bluetoothclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// What finding devices asks for, by Android version, the way the Cordova plugin decided it.
class PermissionPolicyTest {
    @Test
    fun android12AndLaterAsksForNearbyDevicesOnly() {
        assertEquals(listOf("bluetooth"), PermissionPolicy.requiredAliases(sdk = 34, targetSdk = 36, backgroundLocation = true))
        assertTrue(PermissionPolicy.bluetoothIsRuntime(31, 31))
        assertNull(PermissionPolicy.locationAlias(31, 36))
    }

    @Test
    fun anAppTargetingBelow31AsksForLocationEvenOnAndroid12() {
        assertEquals(listOf("location"), PermissionPolicy.requiredAliases(sdk = 33, targetSdk = 30, backgroundLocation = false))
        assertFalse(PermissionPolicy.bluetoothIsRuntime(33, 30))
    }

    @Test
    fun android10And11AskForPreciseLocationAndBackgroundOnlyWhenConfigured() {
        assertEquals(listOf("location"), PermissionPolicy.requiredAliases(sdk = 30, targetSdk = 36, backgroundLocation = false))
        assertEquals(
            listOf("location", "backgroundLocation"),
            PermissionPolicy.requiredAliases(sdk = 29, targetSdk = 36, backgroundLocation = true),
        )
        assertEquals("location", PermissionPolicy.locationAlias(29, 36))
    }

    @Test
    fun android9AndEarlierAskForApproximateLocation() {
        assertEquals(listOf("coarseLocation"), PermissionPolicy.requiredAliases(sdk = 28, targetSdk = 36, backgroundLocation = true))
        assertEquals("coarseLocation", PermissionPolicy.locationAlias(28, 36))
    }
}
