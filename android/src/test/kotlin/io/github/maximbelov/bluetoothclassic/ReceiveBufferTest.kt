package io.github.maximbelov.bluetoothclassic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The buffer a connection keeps: what a subscription delivers, and what read and readUntil take, are cut from it.
class ReceiveBufferTest {
    private fun buffer(text: String) = ReceiveBuffer().apply { append(text) }

    @Test
    fun readUntilTakesUpToAndIncludingTheDelimiter() {
        val buffer = buffer("LA982123766228067\r\nLA98")
        assertEquals("LA982123766228067\r\n", buffer.readUntil("\r\n"))
        assertEquals(4, buffer.available())
    }

    @Test
    fun readUntilLeavesTheBufferAloneWhenTheDelimiterHasNotArrived() {
        val buffer = buffer("LA98212")
        assertNull(buffer.readUntil("\n"))
        assertEquals(7, buffer.available())
    }

    @Test
    fun anEmptyDelimiterReadsNothing() {
        val buffer = buffer("abc")
        assertNull(buffer.readUntil(""))
        assertEquals(emptyList<String>(), buffer.readMessages(""))
        assertEquals(3, buffer.available())
    }

    @Test
    fun readMessagesSplitsEveryCompleteMessageInOrder() {
        val buffer = buffer("Date: 01/02\r\nTime: 10:00\r\nTAGs:\r\npartial")
        assertEquals(listOf("Date: 01/02\r\n", "Time: 10:00\r\n", "TAGs:\r\n"), buffer.readMessages("\r\n"))
        assertEquals("partial", buffer.read())
    }

    @Test
    fun readAndClearEmptyTheBuffer() {
        val buffer = buffer("abc")
        assertEquals("abc", buffer.read())
        assertEquals("", buffer.read())
        buffer.append("def")
        buffer.clear()
        assertEquals(0, buffer.available())
    }
}
