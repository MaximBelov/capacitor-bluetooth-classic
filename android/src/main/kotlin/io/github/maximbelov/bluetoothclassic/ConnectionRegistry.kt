package io.github.maximbelov.bluetoothclassic

/** What the registry reports to JavaScript. */
interface RegistryEvents {
    fun onData(address: String, interfaceId: String, text: String)

    fun onRawData(address: String, interfaceId: String, bytes: ByteArray)

    fun onDisconnected(address: String, interfaceId: String, message: String)
}

/**
 * The connections, one per device address, each with its receive buffer and its subscriptions. A connection made for an
 * address that has one already replaces it: the old one is stopped without a `disconnected` event, and a connect of it
 * still in progress fails.
 */
class ConnectionRegistry(private val events: RegistryEvents, private val links: LinkFactory) {
    private val lock = Any()
    private val connections = HashMap<String, Connection>()

    private inner class Connection(val address: String, val uuid: String) : LinkListener {
        var link: Link? = null
        val buffer = ReceiveBuffer()
        var delimiter: String? = null
        var dataInterfaceId: String = address
        var rawInterfaceId: String? = null
        var pending: ((Throwable?) -> Unit)? = null
        var connected = false

        /** Replaced or closed by the app: nothing it reports from now on reaches JavaScript. */
        var ended = false

        override fun onConnected() {
            val done = synchronized(lock) {
                if (ended) return
                connected = true
                pending.also { pending = null }
            }
            done?.invoke(null)
        }

        override fun onConnectFailed(message: String) {
            val done = synchronized(lock) {
                if (ended) return
                ended = true
                if (connections[address] === this) connections.remove(address)
                pending.also { pending = null }
            }
            done?.invoke(BluetoothClassicException(ErrorCode.CONNECT_FAILED, message))
        }

        override fun onConnectionLost(message: String) {
            synchronized(lock) {
                if (ended || !connected) return
                ended = true
                connected = false
                if (connections[address] === this) connections.remove(address)
            }
            events.onDisconnected(address, uuid, message)
        }

        override fun onData(bytes: ByteArray) {
            val messages: List<String>
            val dataId: String
            val rawId: String?
            synchronized(lock) {
                if (ended) return
                buffer.append(String(bytes, Charsets.UTF_8))
                messages = delimiter?.let { buffer.readMessages(it) } ?: emptyList()
                dataId = dataInterfaceId
                rawId = rawInterfaceId
            }
            // The text first, then the bytes, in the order the Cordova plugin delivered them.
            messages.forEach { events.onData(address, dataId, it) }
            if (rawId != null) events.onRawData(address, rawId, bytes)
        }

        /** Stops the link; a connect still in progress fails. Returns whether the connection was open. */
        fun end(): Boolean {
            val wasConnected: Boolean
            val done: ((Throwable?) -> Unit)?
            synchronized(lock) {
                wasConnected = connected
                ended = true
                connected = false
                done = pending.also { pending = null }
            }
            link?.stop()
            done?.invoke(BluetoothClassicException(ErrorCode.CONNECT_FAILED, Message.UNABLE_TO_CONNECT))
            return wasConnected
        }
    }

    /**
     * Connects to [address] with the RFCOMM service [uuid]. [done] is called once, with null when the connection is open
     * or with the failure.
     */
    fun connect(address: String, uuid: String, secure: Boolean, done: (Throwable?) -> Unit) {
        val connection = Connection(address, uuid)
        connection.pending = done
        val previous = synchronized(lock) { connections.put(address, connection) }
        previous?.end()
        val link = try {
            links.create(address, uuid, secure, connection)
        } catch (e: Throwable) {
            synchronized(lock) {
                connection.ended = true
                connection.pending = null
                if (connections[address] === connection) connections.remove(address)
            }
            done(e)
            return
        }
        connection.link = link
        link.connect()
    }

    /** Closes the connection; when it was open, a `disconnected` event says so. False when there was none. */
    fun disconnect(address: String): Boolean {
        val connection = synchronized(lock) { connections.remove(address) } ?: return false
        if (connection.end()) {
            events.onDisconnected(address, connection.uuid, Message.MANUALLY_DISCONNECTED)
        }
        return true
    }

    fun disconnectAll() {
        val addresses = synchronized(lock) { connections.keys.toList() }
        addresses.forEach { disconnect(it) }
    }

    /** Stops every connection without an event: the plugin is going away. */
    fun closeAll() {
        val all = synchronized(lock) { connections.values.toList().also { connections.clear() } }
        all.forEach { it.end() }
    }

    fun isConnected(address: String): Boolean = synchronized(lock) { connections[address]?.connected == true }

    /** Sends the bytes. Dropped, as before, when the connection exists but is not open. */
    fun write(address: String, bytes: ByteArray) {
        val connection = synchronized(lock) { connections[address] }
            ?: throw BluetoothClassicException(ErrorCode.NO_CONNECTION, Message.NO_INTERFACE)
        connection.link?.write(bytes)
    }

    fun subscribe(address: String, interfaceId: String, delimiter: String) = synchronized(lock) {
        val connection = connections[address] ?: throw BluetoothClassicException(ErrorCode.NO_CONNECTION, Message.NO_INTERFACE)
        connection.delimiter = delimiter
        connection.dataInterfaceId = interfaceId
    }

    fun unsubscribe(address: String) = synchronized(lock) {
        connections[address]?.delimiter = null
    }

    fun subscribeRaw(address: String, interfaceId: String) = synchronized(lock) {
        val connection = connections[address] ?: throw BluetoothClassicException(ErrorCode.NO_CONNECTION, Message.NO_INTERFACE)
        connection.rawInterfaceId = interfaceId
    }

    fun unsubscribeRaw(address: String) = synchronized(lock) {
        connections[address]?.rawInterfaceId = null
    }

    fun available(address: String): Int = buffer(address)?.available() ?: 0

    fun read(address: String): String = buffer(address)?.read() ?: ""

    fun readUntil(address: String, delimiter: String): String = buffer(address)?.readUntil(delimiter) ?: ""

    fun clear(address: String) {
        buffer(address)?.clear()
    }

    private fun buffer(address: String): ReceiveBuffer? = synchronized(lock) { connections[address]?.buffer }
}
