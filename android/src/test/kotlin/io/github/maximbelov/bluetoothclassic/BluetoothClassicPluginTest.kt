package io.github.maximbelov.bluetoothclassic

import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

// The JavaScript reaches the plugin by the name in @CapacitorPlugin and each method by its name; a rename on either side
// fails only at run time on a phone, so both are pinned here, with the permission aliases the policy names.
class BluetoothClassicPluginTest {
    @Test
    fun isRegisteredUnderTheNameTheJavaScriptUses() {
        val annotation = BluetoothClassicPlugin::class.java.getAnnotation(CapacitorPlugin::class.java)
        assertNotNull(annotation)
        assertEquals("BluetoothClassic", annotation!!.name)
    }

    @Test
    fun exposesExactlyTheMethodsOfTheApi() {
        val methods = BluetoothClassicPlugin::class.java.declaredMethods
            .filter { it.isAnnotationPresent(PluginMethod::class.java) }
            .map { it.name }
            .sorted()
        assertEquals(
            listOf(
                "available", "checkPermissions", "clear", "connect", "disconnect", "disconnectAll", "discoverUnpaired",
                "enable", "getBluetoothState", "isConnected", "isEnabled", "list", "read", "readUntil",
                "requestPermissions", "showBluetoothSettings", "subscribe", "subscribeRaw", "unsubscribe",
                "unsubscribeRaw", "write",
            ),
            methods,
        )
        methods.forEach { BluetoothClassicPlugin::class.java.getMethod(it, PluginCall::class.java) }
    }

    @Test
    fun declaresEveryPermissionAliasThePolicyAsksFor() {
        val annotation = BluetoothClassicPlugin::class.java.getAnnotation(CapacitorPlugin::class.java)!!
        assertEquals(
            listOf("backgroundLocation", "bluetooth", "coarseLocation", "location"),
            annotation.permissions.map { it.alias }.sorted(),
        )
    }
}
