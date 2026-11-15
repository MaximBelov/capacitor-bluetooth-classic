package io.github.maximbelov.bluetoothclassic

/** What a [Link] reports, from its own thread. */
interface LinkListener {
    fun onConnected()

    /** The connection could not be opened. */
    fun onConnectFailed(message: String)

    /** An open connection ended: the device went away, or the socket was closed. */
    fun onConnectionLost(message: String)

    /** Bytes as one read from the socket returned them. */
    fun onData(bytes: ByteArray)
}

/** One connection to a device: an RFCOMM socket in the app, a fake in the tests. */
interface Link {
    val connected: Boolean

    /** Starts connecting; the result arrives on the listener. */
    fun connect()

    /** Sends the bytes; false when the link is not connected or the write failed. */
    fun write(bytes: ByteArray): Boolean

    /** Closes the socket. A connect still in progress fails; an open connection ends. */
    fun stop()
}

fun interface LinkFactory {
    fun create(address: String, uuid: String, secure: Boolean, listener: LinkListener): Link
}
