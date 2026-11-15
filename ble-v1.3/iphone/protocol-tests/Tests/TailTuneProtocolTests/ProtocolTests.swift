import XCTest

@testable import TailTuneProtocol

final class ProtocolTests: XCTestCase {
  func testFrameHeaderRoundTrip() throws {
    let frame = TailTuneFrame(
      messageID: 0x0102_0304, chunkIndex: 1, chunkCount: 3, payload: Data([9, 8, 7]))
    let encoded = frame.encoded()
    XCTAssertEqual(
      encoded,
      Data([
        0x54, 0x54, 0x01, 0x00, 0x01, 0x02, 0x03, 0x04, 0x00, 0x01, 0x00, 0x03, 0x09, 0x08, 0x07,
      ]))
    let decoded = try TailTuneFrame.decode(encoded)
    XCTAssertEqual(decoded, frame)
  }

  func testMessageRoundTripInOrder() throws {
    let message = Data((0..<10_000).map { UInt8($0 & 0xff) })
    let frames = try TailTuneFrameCodec.encodeMessage(message, messageID: 77, maxPacketBytes: 185)
    let assembler = TailTuneMessageAssembler()
    var complete: Data?
    for packet in frames {
      if let result = try assembler.ingest(packet) { complete = result.data }
    }
    XCTAssertEqual(complete, message)
  }

  func testMessageRoundTripOutOfOrder() throws {
    let message = Data((0..<4_000).map { UInt8(($0 * 31) & 0xff) })
    let frames = try TailTuneFrameCodec.encodeMessage(message, messageID: 88, maxPacketBytes: 120)
    let assembler = TailTuneMessageAssembler()
    var complete: Data?
    for packet in frames.reversed() {
      if let result = try assembler.ingest(packet) { complete = result.data }
    }
    XCTAssertEqual(complete, message)
  }

  func testDuplicateChunkIsIdempotent() throws {
    let message = Data(repeating: 42, count: 600)
    let frames = try TailTuneFrameCodec.encodeMessage(message, messageID: 99, maxPacketBytes: 80)
    let assembler = TailTuneMessageAssembler()
    _ = try assembler.ingest(frames[0])
    _ = try assembler.ingest(frames[0])
    var complete: Data?
    for packet in frames.dropFirst() {
      if let result = try assembler.ingest(packet) { complete = result.data }
    }
    XCTAssertEqual(complete, message)
  }

  func testConflictingDuplicateFails() throws {
    let first = TailTuneFrame(messageID: 2, chunkIndex: 0, chunkCount: 2, payload: Data([1]))
      .encoded()
    let conflicting = TailTuneFrame(messageID: 2, chunkIndex: 0, chunkCount: 2, payload: Data([2]))
      .encoded()
    let assembler = TailTuneMessageAssembler()
    _ = try assembler.ingest(first)
    XCTAssertThrowsError(try assembler.ingest(conflicting))
  }

  func testRPCJSONRoundTrip() throws {
    let request = TailTuneRPCRequest(
      id: 5, op: "control", args: ["action": .string("seek"), "value": .int(1234)])
    let data = try JSONEncoder().encode(request)
    XCTAssertEqual(try JSONDecoder().decode(TailTuneRPCRequest.self, from: data), request)
  }

  func testEmptyMessageRoundTrip() throws {
    let frames = try TailTuneFrameCodec.encodeMessage(Data(), messageID: 100, maxPacketBytes: 30)
    XCTAssertEqual(frames.count, 1)
    let result = try TailTuneMessageAssembler().ingest(frames[0])
    XCTAssertEqual(result?.data, Data())
  }

  func testRandomizedStressRoundTrips() throws {
    var rng = SeededGenerator(seed: 0x5441_494C_5455_4E45)
    let rounds = ProcessInfo.processInfo.environment["TAILTUNE_STRESS"] == "full" ? 10_000 : 600
    for iteration in 0..<rounds {
      let size = Int.random(in: 0...24_000, using: &rng)
      let packetSize = Int.random(in: 20...517, using: &rng)
      var bytes = [UInt8]()
      bytes.reserveCapacity(size)
      for _ in 0..<size { bytes.append(UInt8.random(in: .min ... .max, using: &rng)) }
      let message = Data(bytes)
      var frames = try TailTuneFrameCodec.encodeMessage(
        message,
        messageID: UInt32(truncatingIfNeeded: iteration + 1),
        maxPacketBytes: packetSize
      )
      frames.shuffle(using: &rng)
      if !frames.isEmpty && Bool.random(using: &rng) {
        frames.insert(frames[0], at: Int.random(in: 0...frames.count, using: &rng))
      }
      let assembler = TailTuneMessageAssembler()
      var completed: Data?
      for frame in frames {
        if let result = try assembler.ingest(frame) { completed = result.data }
      }
      XCTAssertEqual(completed, message, "stress iteration \(iteration)")
    }
  }

  func testRandomGarbagePacketsNeverCrash() {
    var rng = SeededGenerator(seed: 0x4352_4153_4846_555A)
    let assembler = TailTuneMessageAssembler()
    for _ in 0..<5_000 {
      let size = Int.random(in: 0...600, using: &rng)
      var bytes = [UInt8]()
      bytes.reserveCapacity(size)
      for _ in 0..<size { bytes.append(UInt8.random(in: .min ... .max, using: &rng)) }
      do {
        _ = try assembler.ingest(Data(bytes))
      } catch {
        // Rejection is expected. This fuzz case asserts malformed input never traps/crashes.
      }
    }
  }

  func testReservedFlagsRejected() throws {
    var bytes = try TailTuneFrameCodec.encodeMessage(Data([0x01, 0x02]), messageID: 3, maxPacketBytes: 40)[0]
    bytes[3] = 0x80
    XCTAssertThrowsError(try TailTuneFrame.decode(bytes))
  }

  func testInterleavedResponseAndEventFrames() throws {
    let response = Data(repeating: 0x41, count: 240)
    let event = Data(repeating: 0x42, count: 210)
    let responseFrames = try TailTuneFrameCodec.encodeMessage(response, messageID: 3, maxPacketBytes: 52)
    let eventFrames = try TailTuneFrameCodec.encodeMessage(event, messageID: 0x8000_0001, maxPacketBytes: 52)
    let assembler = TailTuneMessageAssembler()
    var received: [UInt32: Data] = [:]
    for i in 0..<max(responseFrames.count, eventFrames.count) {
      if i < responseFrames.count, let value = try assembler.ingest(responseFrames[i]) {
        received[value.messageID] = value.data
      }
      if i < eventFrames.count, let value = try assembler.ingest(eventFrames[i]) {
        received[value.messageID] = value.data
      }
    }
    XCTAssertEqual(received[3], response)
    XCTAssertEqual(received[0x8000_0001], event)
  }

  func testOversizedMessageRejected() {
    XCTAssertThrowsError(try TailTuneFrameCodec.encodeMessage(
      Data(count: TailTuneFrame.maxMessageBytes + 1), messageID: 4, maxPacketBytes: 185))
  }

  func testZeroChunkCountRejected() throws {
    var frame = try TailTuneFrameCodec.encodeMessage(Data([1]), messageID: 4, maxPacketBytes: 40)[0]
    frame[10] = 0; frame[11] = 0
    XCTAssertThrowsError(try TailTuneFrame.decode(frame))
  }

  func testExpiredPartialMessageDoesNotComplete() throws {
    let fragments = try TailTuneFrameCodec.encodeMessage(Data(repeating: 8, count: 90),
      messageID: 24, maxPacketBytes: 50)
    let assembler = TailTuneMessageAssembler(timeout: 1)
    _ = try assembler.ingest(fragments[0], now: 10)
    XCTAssertNil(try assembler.ingest(fragments[1], now: 20))
  }

  func testAssemblerActiveMessageCap() throws {
    let assembler = TailTuneMessageAssembler(maxConcurrentMessages: 2)
    for id: UInt32 in [3, 4] {
      let frame = TailTuneFrame(messageID: id, chunkIndex: 0, chunkCount: 2,
        payload: Data([UInt8(id)])).encoded()
      XCTAssertNil(try assembler.ingest(frame, now: 10))
    }
    let third = TailTuneFrame(messageID: 5, chunkIndex: 0, chunkCount: 2,
      payload: Data([5])).encoded()
    XCTAssertThrowsError(try assembler.ingest(third, now: 10))
  }

  func testTooSmallPacketLimitRejected() {
    XCTAssertThrowsError(
      try TailTuneFrameCodec.encodeMessage(
        Data([1]), messageID: 1, maxPacketBytes: TailTuneFrame.headerSize))
  }
}

private struct SeededGenerator: RandomNumberGenerator {
  private var state: UInt64
  init(seed: UInt64) { self.state = seed }
  mutating func next() -> UInt64 {
    state &+= 0x9E37_79B9_7F4A_7C15
    var z = state
    z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
    z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
    return z ^ (z >> 31)
  }
}
