package io.github.maximbelov.bluetoothclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The registry against fake links: what reaches JavaScript when a link connects, fails, delivers data and ends.
class ConnectionRegistryTest {
    private class FakeLink(val address: String, val uuid: String, val secure: Boolean, val listener: LinkListener) : Link {
        override var connected = false
        var started = false
        var stopped = false
        val written = mutableListOf<ByteArray>()

        override fun connect() {
            started = true
        }

        override fun write(bytes: ByteArray): Boolean {
            if (!connected) return false
            written.add(bytes)
            return true
        }

        override fun stop() {
            stopped = true
            connected = false
        }

        fun up() {
            connected = true
            listener.onConnected()
        }
    }

    private class Recorder : RegistryEvents {
        val events = mutableListOf<String>()
        val raw = mutableListOf<ByteArray>()

        override fun onData(address: String, interfaceId: String, text: String) {
            events.add("data $address $interfaceId $text")
        }

        override fun onRawData(address: String, interfaceId: String, bytes: ByteArray) {
            events.add("raw $address $interfaceId ${bytes.size}")
            raw.add(bytes)
        }

        override fun onDisconnected(address: String, interfaceId: String, message: String) {
            events.add("disconnected $address $interfaceId $message")
        }
    }

    private val recorder = Recorder()
    private val links = mutableListOf<FakeLink>()
    private val registry = ConnectionRegistry(recorder) { address, uuid, secure, listener ->
        FakeLink(address, uuid, secure, listener).also { links.add(it) }
    }
    private val results = mutableListOf<Throwable?>()

    private fun connect(address: String = MAC, secure: Boolean = true): FakeLink {
        registry.connect(address, SPP, secure) { results.add(it) }
        return links.last()
    }

    @Test
    fun aConnectAnswersOnceTheLinkIsUp() {
        val link = connect()
        assertTrue(link.started)
        assertTrue(results.isEmpty())
        assertFalse(registry.isConnected(MAC))

        link.up()

        assertEquals(listOf<Throwable?>(null), results)
        assertTrue(registry.isConnected(MAC))
    }

    @Test
    fun anInsecureConnectAsksTheLinkForAnInsecureSocket() {
        assertFalse(connect(secure = false).secure)
    }

    @Test
    fun aFailedConnectRejectsWithTheLinksMessageAndLeavesNoConnection() {
        val link = connect()
        link.listener.onConnectFailed(Message.UNABLE_TO_CONNECT)

        val error = results.single() as BluetoothClassicException
        assertEquals(ErrorCode.CONNECT_FAILED, error.code)
        assertEquals(Message.UNABLE_TO_CONNECT, error.message)
        assertFalse(registry.isConnected(MAC))
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun aLinkThatCannotBeMadeRejectsTheConnect() {
        val failing = ConnectionRegistry(recorder) { _, _, _, _ -> throw BluetoothClassicException(ErrorCode.INVALID_ARGUMENT, "bad") }
        failing.connect(MAC, SPP, true) { results.add(it) }
        assertEquals(ErrorCode.INVALID_ARGUMENT, (results.single() as BluetoothClassicException).code)
        assertFalse(failing.isConnected(MAC))
    }

    @Test
    fun subscribedDataArrivesAsDelimitedMessagesWithTheSubscribedInterface() {
        val link = connect().apply { up() }
        registry.subscribe(MAC, "iface", "\n")

        link.listener.onData("98200012".toByteArray())
        link.listener.onData("3\n9820004".toByteArray())
        link.listener.onData("56\nrest".toByteArray())

        assertEquals(listOf("data $MAC iface 982000123\n", "data $MAC iface 982000456\n"), recorder.events)
        assertEquals("rest", registry.read(MAC))
    }

    @Test
    fun rawDataFollowsTheTextOfTheSameRead() {
        val link = connect().apply { up() }
        registry.subscribe(MAC, MAC, "\n")
        registry.subscribeRaw(MAC, MAC)

        link.listener.onData("A\n".toByteArray())

        assertEquals(listOf("data $MAC $MAC A\n", "raw $MAC $MAC 2"), recorder.events)
    }

    @Test
    fun withoutASubscriptionDataWaitsInTheBuffer() {
        val link = connect().apply { up() }
        link.listener.onData("LA98\r\nLA97".toByteArray())

        assertTrue(recorder.events.isEmpty())
        assertEquals(10, registry.available(MAC))
        assertEquals("LA98\r\n", registry.readUntil(MAC, "\r\n"))
        assertEquals("", registry.readUntil(MAC, "\r\n"))
        registry.clear(MAC)
        assertEquals(0, registry.available(MAC))
    }

    @Test
    fun unsubscribeStopsTheEvents() {
        val link = connect().apply { up() }
        registry.subscribe(MAC, MAC, "\n")
        registry.subscribeRaw(MAC, MAC)
        registry.unsubscribe(MAC)
        registry.unsubscribeRaw(MAC)

        link.listener.onData("A\n".toByteArray())

        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun aLostConnectionIsReportedOnceAndForgotten() {
        val link = connect().apply { up() }
        link.listener.onConnectionLost(Message.CONNECTION_LOST)
        link.listener.onConnectionLost(Message.CONNECTION_LOST)

        assertEquals(listOf("disconnected $MAC $SPP ${Message.CONNECTION_LOST}"), recorder.events)
        assertFalse(registry.isConnected(MAC))
    }

    @Test
    fun disconnectStopsTheLinkAndReportsAManualDisconnect() {
        val link = connect().apply { up() }
        assertTrue(registry.disconnect(MAC))
        // The read failing after the socket closed is not reported a second time.
        link.listener.onConnectionLost(Message.CONNECTION_LOST)

        assertTrue(link.stopped)
        assertEquals(listOf("disconnected $MAC $SPP ${Message.MANUALLY_DISCONNECTED}"), recorder.events)
        assertFalse(registry.disconnect(MAC))
    }

    @Test
    fun disconnectDuringAConnectFailsTheConnectWithoutAnEvent() {
        val link = connect()
        registry.disconnect(MAC)

        assertTrue(link.stopped)
        assertEquals(ErrorCode.CONNECT_FAILED, (results.single() as BluetoothClassicException).code)
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun aSecondConnectReplacesTheFirstSilently() {
        val first = connect().apply { up() }
        val second = connect()

        assertTrue(first.stopped)
        first.listener.onConnectionLost(Message.CONNECTION_LOST)
        first.listener.onData("old\n".toByteArray())
        second.up()

        assertTrue(recorder.events.isEmpty())
        assertEquals(listOf<Throwable?>(null, null), results)
        assertTrue(registry.isConnected(MAC))
    }

    @Test
    fun disconnectAllReportsEveryOpenConnection() {
        connect("AA:AA:AA:AA:AA:AA").up()
        connect("BB:BB:BB:BB:BB:BB").up()

        registry.disconnectAll()

        assertEquals(2, recorder.events.count { it.startsWith("disconnected") })
        assertTrue(links.all { it.stopped })
    }

    @Test
    fun closeAllStopsEverythingWithoutEvents() {
        connect().up()
        registry.closeAll()

        assertTrue(links.single().stopped)
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun writeReachesTheLinkAndNeedsAConnection() {
        val link = connect().apply { up() }
        registry.write(MAC, byteArrayOf(0x41, 0x42))
        assertEquals("AB", String(link.written.single()))

        val error = runCatching { registry.write("CC:CC:CC:CC:CC:CC", byteArrayOf(1)) }.exceptionOrNull() as BluetoothClassicException
        assertEquals(ErrorCode.NO_CONNECTION, error.code)
    }

    @Test
    fun subscribingNeedsAConnectionButReadingWithoutOneReadsNothing() {
        val error = runCatching { registry.subscribe(MAC, MAC, "\n") }.exceptionOrNull() as BluetoothClassicException
        assertEquals(ErrorCode.NO_CONNECTION, error.code)
        assertEquals("", registry.read(MAC))
        assertEquals(0, registry.available(MAC))
        registry.unsubscribe(MAC)
        assertNull(results.firstOrNull())
    }

    private companion object {
        const val MAC = "00:11:22:33:44:55"
        const val SPP = "00001101-0000-1000-8000-00805F9B34FB"
    }
}
