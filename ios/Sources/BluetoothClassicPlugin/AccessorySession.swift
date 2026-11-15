import Foundation
import ExternalAccessory
#if canImport(BluetoothClassicCore)
import BluetoothClassicCore
#endif

/// One External Accessory session: an accessory and one of its protocol strings, with the bytes read from it and the
/// bytes still to be written. Keyed by the accessory's `connectionID` AND the protocol, so that two accessories that
/// speak the same protocol (two readers of one model) each have their own session.
///
/// Everything here runs on the main thread: the streams are scheduled on the main run loop, and the plugin hops there
/// before it touches a session.
final class AccessorySession: NSObject, StreamDelegate {
    let accessory: EAAccessory
    let connectionId: Int
    let protocolString: String

    private(set) var session: EASession?
    let receiveBuffer = ReceiveBuffer()
    private var writeBuffer = Data()

    /// The delimiter of the `data` subscription, nil while there is none.
    var delimiter: String?
    /// Whether the `rawData` subscription is on.
    var rawSubscribed = false

    var onMessage: ((String) -> Void)?
    var onRawData: ((Data) -> Void)?

    private static let readChunk = 128

    init(accessory: EAAccessory, protocolString: String) {
        self.accessory = accessory
        self.connectionId = accessory.connectionID
        self.protocolString = protocolString
    }

    var isOpen: Bool { session != nil }

    func open() -> Bool {
        guard let session = EASession(accessory: accessory, forProtocol: protocolString) else {
            return false
        }
        self.session = session
        for stream in [session.inputStream as Stream?, session.outputStream as Stream?].compactMap({ $0 }) {
            stream.delegate = self
            stream.schedule(in: .main, forMode: .default)
            stream.open()
        }
        return true
    }

    func close() {
        if let session {
            for stream in [session.inputStream as Stream?, session.outputStream as Stream?].compactMap({ $0 }) {
                stream.remove(from: .main, forMode: .default)
                stream.delegate = nil
                stream.close()
            }
        }
        session = nil
        receiveBuffer.clear()
        writeBuffer.removeAll()
        delimiter = nil
        rawSubscribed = false
    }

    /// Queues the bytes and writes what the stream takes now; the rest goes when it has space again.
    func write(_ data: Data) -> Bool {
        writeBuffer.append(data)
        return flush()
    }

    private func flush() -> Bool {
        guard let output = session?.outputStream else { return false }
        while output.hasSpaceAvailable && !writeBuffer.isEmpty {
            let written = writeBuffer.withUnsafeBytes { raw -> Int in
                guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return output.write(base, maxLength: writeBuffer.count)
            }
            if written < 0 {
                return false
            }
            if written == 0 {
                break
            }
            writeBuffer.removeSubrange(writeBuffer.startIndex..<writeBuffer.startIndex + written)
        }
        return true
    }

    /// Empties the input stream as well as the buffer, as the Cordova plugin did.
    func clear() {
        if let input = session?.inputStream {
            var chunk = [UInt8](repeating: 0, count: Self.readChunk)
            while input.hasBytesAvailable && input.read(&chunk, maxLength: chunk.count) > 0 {}
        }
        receiveBuffer.clear()
    }

    private func readAvailable() {
        guard let input = session?.inputStream else { return }
        var chunk = [UInt8](repeating: 0, count: Self.readChunk)
        var read = Data()
        while input.hasBytesAvailable {
            let count = input.read(&chunk, maxLength: chunk.count)
            if count <= 0 {
                break
            }
            read.append(chunk, count: count)
        }
        guard !read.isEmpty else { return }
        receiveBuffer.append(read)
        if rawSubscribed {
            onRawData?(read)
        }
        if let delimiter {
            for message in receiveBuffer.readMessages(delimiter) {
                onMessage?(message)
            }
        }
    }

    func stream(_ stream: Stream, handle eventCode: Stream.Event) {
        switch eventCode {
        case .errorOccurred, .endEncountered:
            // As before: the stream is closed and the session counts as not open. The connection's end is reported when
            // iOS says the accessory disconnected.
            stream.close()
            stream.remove(from: .main, forMode: .default)
            session = nil
        case .hasBytesAvailable:
            readAvailable()
        case .hasSpaceAvailable:
            _ = flush()
        default:
            break
        }
    }
}
