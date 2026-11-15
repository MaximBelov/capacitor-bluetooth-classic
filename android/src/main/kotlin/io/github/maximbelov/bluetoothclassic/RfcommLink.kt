package io.github.maximbelov.bluetoothclassic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

/**
 * An RFCOMM connection to one device, on a thread of its own: it connects, then reads until the socket ends. A port of
 * the BluetoothChat-based service the Cordova plugin used, with its fallback to channel 1 for devices that do not answer
 * a service lookup.
 *
 * The caller holds `BLUETOOTH_CONNECT` on Android 12 and later; without it the connect fails.
 */
@SuppressLint("MissingPermission")
class RfcommLink(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val uuid: UUID,
    private val secure: Boolean,
    private val listener: LinkListener,
) : Link {
    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var stopped = false

    @Volatile
    override var connected = false
        private set

    override fun connect() {
        Thread(::run, "bluetooth-classic-${device.address}").apply { isDaemon = true }.start()
    }

    override fun write(bytes: ByteArray): Boolean {
        val open = socket?.takeIf { connected } ?: return false
        return try {
            open.outputStream.write(bytes)
            true
        } catch (e: IOException) {
            Log.e(TAG, "write failed", e)
            false
        }
    }

    override fun stop() {
        stopped = true
        connected = false
        socket?.let(::close)
    }

    private fun run() {
        val open = try {
            open()
        } catch (e: SecurityException) {
            Log.e(TAG, "connect refused", e)
            listener.onConnectFailed(Message.CONNECT_DENIED)
            return
        }
        if (open == null) {
            listener.onConnectFailed(Message.UNABLE_TO_CONNECT)
            return
        }
        connected = true
        listener.onConnected()
        val buffer = ByteArray(1024)
        try {
            val input = open.inputStream
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) listener.onData(buffer.copyOf(count))
            }
        } catch (e: IOException) {
            Log.i(TAG, "disconnected: ${e.message}")
        }
        connected = false
        close(open)
        listener.onConnectionLost(Message.CONNECTION_LOST)
    }

    /** The open socket, or null when neither the service lookup nor channel 1 answered, or the link was stopped. */
    private fun open(): BluetoothSocket? {
        // A discovery in progress slows a connect down a great deal.
        adapter.cancelDiscovery()
        val first = try {
            if (secure) device.createRfcommSocketToServiceRecord(uuid) else device.createInsecureRfcommSocketToServiceRecord(uuid)
        } catch (e: IOException) {
            Log.e(TAG, "socket create failed", e)
            null
        }
        if (first != null && attempt(first)) return first
        if (stopped) return null
        // Some devices answer only on channel 1: https://github.com/don/BluetoothSerial/issues/89
        val fallback = try {
            device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
        } catch (e: ReflectiveOperationException) {
            Log.e(TAG, "fallback socket create failed", e)
            return null
        }
        return fallback.takeIf { attempt(it) }
    }

    private fun attempt(candidate: BluetoothSocket): Boolean {
        socket = candidate
        if (stopped) {
            close(candidate)
            return false
        }
        return try {
            candidate.connect()
            if (stopped) {
                close(candidate)
                false
            } else {
                true
            }
        } catch (e: IOException) {
            Log.e(TAG, "connect failed: ${e.message}")
            close(candidate)
            false
        }
    }

    private fun close(target: BluetoothSocket) {
        try {
            target.close()
        } catch (e: IOException) {
            Log.e(TAG, "close failed", e)
        }
    }

    private companion object {
        const val TAG = "BluetoothClassic"
    }
}
