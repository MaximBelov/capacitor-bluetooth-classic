package io.github.maximbelov.bluetoothclassic

/**
 * What a connection has received and not handed on yet, as text: kept until it is read whole, read up to a delimiter, or
 * cleared. Each read from the socket is decoded as UTF-8 on its own and appended, as the Cordova plugin did.
 */
class ReceiveBuffer {
    private val text = StringBuilder()

    @Synchronized
    fun append(received: String) {
        text.append(received)
    }

    /** How many characters are waiting. */
    @Synchronized
    fun available(): Int = text.length

    /** Everything waiting, and an empty buffer. */
    @Synchronized
    fun read(): String {
        val all = text.toString()
        text.setLength(0)
        return all
    }

    /**
     * The text up to and including the first [delimiter], taken out of the buffer; null, and the buffer untouched, when
     * the delimiter has not arrived or is empty.
     */
    @Synchronized
    fun readUntil(delimiter: String): String? {
        if (delimiter.isEmpty()) return null
        val index = text.indexOf(delimiter)
        if (index < 0) return null
        val end = index + delimiter.length
        val message = text.substring(0, end)
        text.delete(0, end)
        return message
    }

    /** Every complete message waiting, in order: what a subscription delivers after each read. */
    @Synchronized
    fun readMessages(delimiter: String): List<String> {
        val messages = mutableListOf<String>()
        while (true) {
            messages.add(readUntil(delimiter) ?: return messages)
        }
    }

    @Synchronized
    fun clear() {
        text.setLength(0)
    }
}
