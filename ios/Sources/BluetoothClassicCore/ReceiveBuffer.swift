import Foundation

/// What a session has read from an accessory and not handed on yet: the bytes, kept until they are read whole, read up
/// to a delimiter, or cleared. Text is UTF-8; a sequence that is not valid UTF-8 is replaced rather than dropped.
public final class ReceiveBuffer {
    public private(set) var bytes = Data()

    public init() {}

    /// How many bytes are waiting.
    public var count: Int { bytes.count }

    public func append(_ data: Data) {
        bytes.append(data)
    }

    /// Everything waiting, as text, and an empty buffer.
    public func readAll() -> String {
        let text = String(decoding: bytes, as: UTF8.self)
        bytes.removeAll()
        return text
    }

    /// The bytes up to and including the first `delimiter`, as text, taken out of the buffer; an empty string, and the
    /// buffer untouched, when the delimiter has not arrived or is empty. The delimiter is looked for as bytes, so a
    /// character of several bytes before it does not shift the cut.
    public func readUntil(_ delimiter: String) -> String {
        let marker = Data(delimiter.utf8)
        guard !marker.isEmpty, let range = bytes.range(of: marker) else {
            return ""
        }
        let end = range.upperBound
        let message = bytes.subdata(in: bytes.startIndex..<end)
        bytes.removeSubrange(bytes.startIndex..<end)
        bytes = Data(bytes)
        return String(decoding: message, as: UTF8.self)
    }

    /// Every complete message waiting, in order: what a subscription delivers after each read.
    public func readMessages(_ delimiter: String) -> [String] {
        var messages: [String] = []
        while true {
            let message = readUntil(delimiter)
            if message.isEmpty {
                return messages
            }
            messages.append(message)
        }
    }

    public func clear() {
        bytes.removeAll()
    }
}
