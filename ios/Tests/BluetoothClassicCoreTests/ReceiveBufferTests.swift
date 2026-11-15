import XCTest
@testable import BluetoothClassicCore

/// The buffer a session keeps: what a subscription delivers, and what read and readUntil take, are cut from it.
final class ReceiveBufferTests: XCTestCase {
    private func buffer(_ text: String) -> ReceiveBuffer {
        let buffer = ReceiveBuffer()
        buffer.append(Data(text.utf8))
        return buffer
    }

    func testReadUntilTakesUpToAndIncludingTheDelimiter() {
        let buffer = buffer("LA982123766228067\r\nLA98")
        XCTAssertEqual(buffer.readUntil("\r\n"), "LA982123766228067\r\n")
        XCTAssertEqual(buffer.count, 4)
    }

    func testReadUntilLeavesTheBufferAloneWhenTheDelimiterHasNotArrived() {
        let buffer = buffer("LA98212")
        XCTAssertEqual(buffer.readUntil("\n"), "")
        XCTAssertEqual(buffer.count, 7)
    }

    func testAnEmptyDelimiterReadsNothing() {
        let buffer = buffer("abc")
        XCTAssertEqual(buffer.readUntil(""), "")
        XCTAssertEqual(buffer.count, 3)
    }

    /// A BLE-style notification can hold several lines; a Classic read can too. Each comes out on its own, in order.
    func testReadMessagesSplitsEveryCompleteMessage() {
        let buffer = buffer("Date: 01/02\r\nTime: 10:00\r\nTAGs:\r\npartial")
        XCTAssertEqual(buffer.readMessages("\r\n"), ["Date: 01/02\r\n", "Time: 10:00\r\n", "TAGs:\r\n"])
        XCTAssertEqual(buffer.readAll(), "partial")
    }

    func testAMessageSplitAcrossReadsIsDeliveredOnceComplete() {
        let buffer = ReceiveBuffer()
        buffer.append(Data("9820001".utf8))
        XCTAssertEqual(buffer.readMessages("\n"), [])
        buffer.append(Data("23\n".utf8))
        XCTAssertEqual(buffer.readMessages("\n"), ["982000123\n"])
        XCTAssertEqual(buffer.count, 0)
    }

    /// The cut is made on bytes: a two-byte character before the delimiter does not leave a byte of it behind.
    func testTheCutIsMadeOnBytes() {
        let buffer = buffer("Mère\nx")
        XCTAssertEqual(buffer.readUntil("\n"), "Mère\n")
        XCTAssertEqual(buffer.readAll(), "x")
    }

    func testReadAllEmptiesTheBuffer() {
        let buffer = buffer("abc")
        XCTAssertEqual(buffer.readAll(), "abc")
        XCTAssertEqual(buffer.count, 0)
        XCTAssertEqual(buffer.readAll(), "")
    }

    func testClearEmptiesTheBuffer() {
        let buffer = buffer("abc")
        buffer.clear()
        XCTAssertEqual(buffer.count, 0)
    }

    func testInvalidUtf8IsReplacedNotDropped() {
        let buffer = ReceiveBuffer()
        buffer.append(Data([0x41, 0xFF, 0x0A]))
        XCTAssertEqual(buffer.readUntil("\n"), "A\u{FFFD}\n")
    }
}
