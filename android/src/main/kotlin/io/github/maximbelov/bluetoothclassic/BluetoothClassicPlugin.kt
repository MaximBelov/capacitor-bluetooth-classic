package io.github.maximbelov.bluetoothclassic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResult
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import java.util.Base64
import java.util.UUID

/**
 * Bluetooth Classic on Android: RFCOMM connections (the serial port profile), the bonded devices, and discovery. The
 * connections themselves are in [ConnectionRegistry]; this class reads the calls, asks for permissions, and turns what
 * the registry reports into events.
 *
 * Connections are kept when the web view loads a page, so a reloaded page finds them up (`isConnected`) and can connect
 * again to take over their events.
 */
@CapacitorPlugin(
    name = "BluetoothClassic",
    permissions = [
        Permission(alias = PermissionPolicy.BLUETOOTH, strings = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT]),
        Permission(alias = PermissionPolicy.LOCATION, strings = [Manifest.permission.ACCESS_FINE_LOCATION]),
        Permission(alias = PermissionPolicy.COARSE_LOCATION, strings = [Manifest.permission.ACCESS_COARSE_LOCATION]),
        Permission(alias = PermissionPolicy.BACKGROUND_LOCATION, strings = [Manifest.permission.ACCESS_BACKGROUND_LOCATION]),
    ],
)
@SuppressLint("MissingPermission")
class BluetoothClassicPlugin : Plugin() {
    private lateinit var registry: ConnectionRegistry

    private val discoveryLock = Any()
    private val discoveryCalls = mutableListOf<PluginCall>()
    private var discoveryReceiver: BroadcastReceiver? = null
    private var discovered = JSArray()

    override fun load() {
        registry = ConnectionRegistry(
            object : RegistryEvents {
                override fun onData(address: String, interfaceId: String, text: String) {
                    notifyListeners("data", JSObject().put("deviceId", address).put("interfaceId", interfaceId).put("data", text))
                }

                override fun onRawData(address: String, interfaceId: String, bytes: ByteArray) {
                    notifyListeners(
                        "rawData",
                        JSObject()
                            .put("deviceId", address)
                            .put("interfaceId", interfaceId)
                            .put("data", Base64.getEncoder().encodeToString(bytes)),
                    )
                }

                override fun onDisconnected(address: String, interfaceId: String, message: String) {
                    notifyListeners(
                        "disconnected",
                        JSObject().put("deviceId", address).put("interfaceId", interfaceId).put("message", message),
                    )
                }
            },
        ) { address, uuid, secure, listener ->
            val adapter = adapter() ?: throw BluetoothClassicException(ErrorCode.BLUETOOTH_UNAVAILABLE, "This device has no Bluetooth.")
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (e: IllegalArgumentException) {
                throw BluetoothClassicException(ErrorCode.INVALID_ARGUMENT, "Could not connect to $address")
            }
            val service = try {
                UUID.fromString(uuid)
            } catch (e: IllegalArgumentException) {
                throw BluetoothClassicException(ErrorCode.INVALID_ARGUMENT, "$uuid is not a UUID")
            }
            RfcommLink(adapter, device, service, secure, listener)
        }
    }

    override fun handleOnDestroy() {
        if (::registry.isInitialized) registry.closeAll()
        finishDiscovery(null)
        super.handleOnDestroy()
    }

    // Devices

    @PluginMethod
    fun list(call: PluginCall) = withPermissions(call, "listAfterPermissions") { listBonded(call) }

    @PermissionCallback
    private fun listAfterPermissions(call: PluginCall) = afterPermissions(call) { listBonded(call) }

    @PluginMethod
    fun discoverUnpaired(call: PluginCall) = withPermissions(call, "discoverAfterPermissions") { discover(call) }

    @PermissionCallback
    private fun discoverAfterPermissions(call: PluginCall) = afterPermissions(call) { discover(call) }

    // Connections

    @PluginMethod
    fun connect(call: PluginCall) = guarded(call) {
        val address = deviceId(call)
        val uuid = call.getArray("interfaces")?.toList<Any>()?.firstOrNull()?.toString()
            ?: throw BluetoothClassicException(ErrorCode.INVALID_ARGUMENT, "interfaces is required")
        val adapter = adapter() ?: throw BluetoothClassicException(ErrorCode.BLUETOOTH_UNAVAILABLE, "This device has no Bluetooth.")
        if (!adapter.isEnabled) throw BluetoothClassicException(ErrorCode.BLUETOOTH_DISABLED, Message.BLUETOOTH_DISABLED)
        registry.connect(address, uuid, !call.getBoolean("insecure", false)!!) { error ->
            if (error == null) call.resolve() else reject(call, error)
        }
    }

    @PluginMethod
    fun disconnect(call: PluginCall) = guarded(call) {
        registry.disconnect(deviceId(call))
        call.resolve()
    }

    @PluginMethod
    fun disconnectAll(call: PluginCall) = guarded(call) {
        registry.disconnectAll()
        call.resolve()
    }

    @PluginMethod
    fun isConnected(call: PluginCall) = guarded(call) {
        call.resolve(JSObject().put("connected", registry.isConnected(deviceId(call))))
    }

    // Bluetooth

    @PluginMethod
    fun isEnabled(call: PluginCall) = guarded(call) {
        call.resolve(JSObject().put("enabled", adapter()?.isEnabled == true))
    }

    @PluginMethod
    fun enable(call: PluginCall) = guarded(call) {
        startActivityForResult(call, Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), "enableAnswered")
    }

    @ActivityCallback
    private fun enableAnswered(call: PluginCall?, result: ActivityResult) {
        call ?: return
        if (result.resultCode == Activity.RESULT_OK) {
            call.resolve()
        } else {
            call.reject(Message.NOT_ENABLED_BY_USER, ErrorCode.BLUETOOTH_DISABLED)
        }
    }

    @PluginMethod
    fun showBluetoothSettings(call: PluginCall) = guarded(call) {
        activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        call.resolve()
    }

    @PluginMethod
    fun getBluetoothState(call: PluginCall) {
        call.unimplemented("getBluetoothState is iOS only.")
    }

    @PluginMethod
    override fun checkPermissions(call: PluginCall) {
        call.resolve(permissionStatus())
    }

    @PluginMethod
    override fun requestPermissions(call: PluginCall) {
        val missing = missingAliases(includeOptional = true)
        if (missing.isEmpty()) {
            call.resolve(permissionStatus())
        } else {
            requestPermissionForAliases(missing.toTypedArray(), call, "permissionsAnswered")
        }
    }

    @PermissionCallback
    private fun permissionsAnswered(call: PluginCall) {
        call.resolve(permissionStatus())
    }

    // Data

    @PluginMethod
    fun subscribe(call: PluginCall) = guarded(call) {
        val delimiter = call.getString("delimiter") ?: throw BluetoothClassicException(ErrorCode.INVALID_ARGUMENT, "delimiter is required")
        registry.subscribe(deviceId(call), interfaceId(call), delimiter)
        call.resolve()
    }

    @PluginMethod
    fun unsubscribe(call: PluginCall) = guarded(call) {
        registry.unsubscribe(deviceId(call))
        call.resolve()
    }

    @PluginMethod
    fun subscribeRaw(call: PluginCall) = guarded(call) {
        registry.subscribeRaw(deviceId(call), interfaceId(call))
        call.resolve()
    }

    @PluginMethod
    fun unsubscribeRaw(call: PluginCall) = guarded(call) {
        registry.unsubscribeRaw(deviceId(call))
        call.resolve()
    }

    @PluginMethod
    fun write(call: PluginCall) = guarded(call) {
        val address = deviceId(call)
        val encoded = call.getString("data") ?: throw BluetoothClassicException(ErrorCode.INVALID_ARGUMENT, "data is required")
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw BluetoothClassicException(ErrorCode.INVALID_ARGUMENT, "data is not base64")
        }
        registry.write(address, bytes)
        call.resolve()
    }

    @PluginMethod
    fun available(call: PluginCall) = guarded(call) {
        call.resolve(JSObject().put("available", registry.available(deviceId(call))))
    }

    @PluginMethod
    fun read(call: PluginCall) = guarded(call) {
        call.resolve(JSObject().put("data", registry.read(deviceId(call))))
    }

    @PluginMethod
    fun readUntil(call: PluginCall) = guarded(call) {
        val delimiter = call.getString("delimiter") ?: throw BluetoothClassicException(ErrorCode.INVALID_ARGUMENT, "delimiter is required")
        call.resolve(JSObject().put("data", registry.readUntil(deviceId(call), delimiter)))
    }

    @PluginMethod
    fun clear(call: PluginCall) = guarded(call) {
        registry.clear(deviceId(call))
        call.resolve()
    }

    // Permissions

    private fun targetSdk(): Int = context.applicationInfo.targetSdkVersion

    private fun backgroundLocation(): Boolean = config.getBoolean("accessBackgroundLocation", false)

    /** The aliases still to grant. Background location is optional: asked for when configured, never required. */
    private fun missingAliases(includeOptional: Boolean): List<String> =
        PermissionPolicy.requiredAliases(Build.VERSION.SDK_INT, targetSdk(), backgroundLocation() && includeOptional)
            .filter { getPermissionState(it) != PermissionState.GRANTED }

    private fun withPermissions(call: PluginCall, callbackName: String, then: () -> Unit) {
        val missing = missingAliases(includeOptional = true)
        if (missing.isEmpty()) {
            guarded(call, then)
        } else {
            requestPermissionForAliases(missing.toTypedArray(), call, callbackName)
        }
    }

    private fun afterPermissions(call: PluginCall, then: () -> Unit) {
        val denied = deniedMessage()
        if (denied != null) {
            call.reject(denied, ErrorCode.PERMISSION_DENIED)
        } else {
            guarded(call, then)
        }
    }

    /** What the Cordova plugin said for the first required permission that is not granted, or null. */
    private fun deniedMessage(): String? = when (missingAliases(includeOptional = false).firstOrNull()) {
        null -> null
        PermissionPolicy.BLUETOOTH -> if (granted(Manifest.permission.BLUETOOTH_SCAN)) Message.CONNECT_DENIED else Message.SCAN_DENIED
        else -> Message.LOCATION_DENIED
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun permissionStatus(): JSObject {
        val sdk = Build.VERSION.SDK_INT
        val target = targetSdk()
        val bluetooth = if (PermissionPolicy.bluetoothIsRuntime(sdk, target)) getPermissionState(PermissionPolicy.BLUETOOTH) else PermissionState.GRANTED
        val location = PermissionPolicy.locationAlias(sdk, target)?.let { getPermissionState(it) } ?: PermissionState.GRANTED
        return JSObject().put("bluetooth", bluetooth.toString()).put("location", location.toString())
    }

    // Devices, continued

    private fun listBonded(call: PluginCall) {
        val adapter = adapter() ?: throw BluetoothClassicException(ErrorCode.BLUETOOTH_UNAVAILABLE, "This device has no Bluetooth.")
        val devices = JSArray()
        adapter.bondedDevices.orEmpty().forEach { devices.put(toJs(it)) }
        call.resolve(JSObject().put("devices", devices))
    }

    /** One discovery at a time: a call made while one runs gets the same answer. */
    private fun discover(call: PluginCall) {
        val adapter = adapter() ?: throw BluetoothClassicException(ErrorCode.BLUETOOTH_UNAVAILABLE, "This device has no Bluetooth.")
        synchronized(discoveryLock) {
            discoveryCalls.add(call)
            if (discoveryReceiver != null) return
            discovered = JSArray()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                ?: return
                            val js = try {
                                toJs(device)
                            } catch (e: SecurityException) {
                                return
                            }
                            synchronized(discoveryLock) { discovered.put(js) }
                            notifyListeners("deviceDiscovered", js)
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> finishDiscovery(null)
                    }
                }
            }
            discoveryReceiver = receiver
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            // The broadcasts come from the system Bluetooth process, so the receiver has to be exported.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }
        val started = try {
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            false
        }
        if (!started) finishDiscovery("Discovery could not be started.")
    }

    /** Answers every waiting discovery call, with the devices found or with [failure]. */
    private fun finishDiscovery(failure: String?) {
        val calls: List<PluginCall>
        val devices: JSArray
        synchronized(discoveryLock) {
            discoveryReceiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (e: IllegalArgumentException) {
                    // already gone
                }
            }
            discoveryReceiver = null
            calls = discoveryCalls.toList()
            discoveryCalls.clear()
            devices = discovered
        }
        calls.forEach {
            if (failure == null) it.resolve(JSObject().put("devices", devices)) else it.reject(failure, ErrorCode.DISCOVERY_FAILED)
        }
    }

    // Helpers

    private fun adapter(): BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun deviceId(call: PluginCall): String =
        call.data.opt("deviceId")?.takeIf { it != org.json.JSONObject.NULL }?.toString()
            ?: throw BluetoothClassicException(ErrorCode.INVALID_ARGUMENT, "deviceId is required")

    private fun interfaceId(call: PluginCall): String = call.getString("interfaceId") ?: deviceId(call)

    private inline fun guarded(call: PluginCall, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            reject(call, e)
        }
    }

    private fun reject(call: PluginCall, error: Throwable) {
        when (error) {
            is BluetoothClassicException -> call.reject(error.message, error.code)
            is SecurityException -> call.reject(Message.CONNECT_DENIED, ErrorCode.PERMISSION_DENIED)
            else -> call.reject(error.message ?: error.toString(), ErrorCode.CONNECT_FAILED)
        }
    }

    companion object {
        /** A device as the Cordova plugin reported it: name (absent when unknown), address twice, and the class. */
        @SuppressLint("MissingPermission")
        fun toJs(device: BluetoothDevice): JSObject {
            val js = JSObject()
            device.name?.let { js.put("name", it) }
            js.put("address", device.address)
            js.put("id", device.address)
            device.bluetoothClass?.let { js.put("class", it.deviceClass) }
            return js
        }
    }
}
