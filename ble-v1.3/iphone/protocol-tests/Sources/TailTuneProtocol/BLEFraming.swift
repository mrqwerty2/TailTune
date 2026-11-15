import Foundation

enum TailTuneFrameError: Error, LocalizedError, Equatable {
  case packetTooSmall
  case invalidMagic
  case unsupportedFlags
  case unsupportedVersion(UInt8)
  case invalidChunkCount
  case invalidChunkIndex
  case packetLimitTooSmall
  case messageTooLarge
  case inconsistentMessage

  var errorDescription: String? {
    switch self {
    case .packetTooSmall: return "BLE packet is shorter than the TailTune frame header."
    case .invalidMagic: return "BLE packet is not a TailTune frame."
    case .unsupportedFlags: return "Unknown BLE frame flags."
    case .unsupportedVersion(let version):
      return "Unsupported TailTune BLE frame version: \(version)."
    case .invalidChunkCount: return "BLE frame has an invalid chunk count."
    case .invalidChunkIndex: return "BLE frame has an invalid chunk index."
    case .packetLimitTooSmall: return "The negotiated BLE packet size is too small."
    case .messageTooLarge: return "BLE message exceeds the safety size limit."
    case .inconsistentMessage: return "BLE message chunks are inconsistent."
    }
  }
}

struct TailTuneFrame: Equatable {
  static let magic0: UInt8 = 0x54  // T
  static let magic1: UInt8 = 0x54  // T
  static let version: UInt8 = 1
  static let headerSize = 12
  static let maxMessageBytes = 512 * 1024
  static let maxChunks = 4096

  let messageID: UInt32
  let chunkIndex: UInt16
  let chunkCount: UInt16
  let payload: Data

  func encoded() -> Data {
    var data = Data(capacity: Self.headerSize + payload.count)
    data.append(Self.magic0)
    data.append(Self.magic1)
    data.append(Self.version)
    data.append(0)  // flags reserved for protocol v2+
    data.append(contentsOf: messageID.bigEndianBytes)
    data.append(contentsOf: chunkIndex.bigEndianBytes)
    data.append(contentsOf: chunkCount.bigEndianBytes)
    data.append(payload)
    return data
  }

  static func decode(_ data: Data) throws -> TailTuneFrame {
    guard data.count >= headerSize else { throw TailTuneFrameError.packetTooSmall }
    let bytes = [UInt8](data)
    guard bytes[0] == magic0, bytes[1] == magic1 else { throw TailTuneFrameError.invalidMagic }
    guard bytes[2] == version else { throw TailTuneFrameError.unsupportedVersion(bytes[2]) }
    guard bytes[3] == 0 else { throw TailTuneFrameError.unsupportedFlags }

    let messageID = UInt32(bigEndianBytes: bytes[4...7])
    let chunkIndex = UInt16(bigEndianBytes: bytes[8...9])
    let chunkCount = UInt16(bigEndianBytes: bytes[10...11])

    guard chunkCount > 0, Int(chunkCount) <= maxChunks else {
      throw TailTuneFrameError.invalidChunkCount
    }
    guard chunkIndex < chunkCount else { throw TailTuneFrameError.invalidChunkIndex }

    return TailTuneFrame(
      messageID: messageID,
      chunkIndex: chunkIndex,
      chunkCount: chunkCount,
      payload: data.dropFirst(headerSize)
    )
  }
}

enum TailTuneFrameCodec {
  static func encodeMessage(_ message: Data, messageID: UInt32, maxPacketBytes: Int) throws
    -> [Data]
  {
    guard message.count <= TailTuneFrame.maxMessageBytes else {
      throw TailTuneFrameError.messageTooLarge
    }
    let payloadLimit = maxPacketBytes - TailTuneFrame.headerSize
    guard payloadLimit >= 1 else { throw TailTuneFrameError.packetLimitTooSmall }

    let chunkCount = max(1, Int(ceil(Double(message.count) / Double(payloadLimit))))
    guard chunkCount <= TailTuneFrame.maxChunks, chunkCount <= Int(UInt16.max) else {
      throw TailTuneFrameError.messageTooLarge
    }

    if message.isEmpty {
      return [
        TailTuneFrame(messageID: messageID, chunkIndex: 0, chunkCount: 1, payload: Data()).encoded()
      ]
    }

    var frames: [Data] = []
    frames.reserveCapacity(chunkCount)
    var offset = 0
    var index = 0
    while offset < message.count {
      let end = min(message.count, offset + payloadLimit)
      let payload = message.subdata(in: offset..<end)
      frames.append(
        TailTuneFrame(
          messageID: messageID,
          chunkIndex: UInt16(index),
          chunkCount: UInt16(chunkCount),
          payload: payload
        ).encoded()
      )
      offset = end
      index += 1
    }
    return frames
  }
}

final class TailTuneMessageAssembler {
  private struct Assembly {
    let chunkCount: Int
    var chunks: [Data?]
    var receivedBytes: Int
    var receivedCount: Int
    var lastUpdate: TimeInterval
  }

  private var assemblies: [UInt32: Assembly] = [:]
  private let timeout: TimeInterval
  private let maxConcurrentMessages: Int

  init(timeout: TimeInterval = 15, maxConcurrentMessages: Int = 16) {
    self.timeout = timeout
    self.maxConcurrentMessages = maxConcurrentMessages
  }

  func reset() {
    assemblies.removeAll(keepingCapacity: false)
  }

  func ingest(_ packet: Data, now: TimeInterval = ProcessInfo.processInfo.systemUptime) throws -> (
    messageID: UInt32, data: Data
  )? {
    purgeExpired(now: now)
    let frame = try TailTuneFrame.decode(packet)
    let count = Int(frame.chunkCount)
    let index = Int(frame.chunkIndex)

    if assemblies[frame.messageID] == nil {
      guard assemblies.count < maxConcurrentMessages else {
        throw TailTuneFrameError.messageTooLarge
      }
      assemblies[frame.messageID] = Assembly(
        chunkCount: count,
        chunks: Array(repeating: nil, count: count),
        receivedBytes: 0,
        receivedCount: 0,
        lastUpdate: now
      )
    }

    guard var assembly = assemblies[frame.messageID], assembly.chunkCount == count else {
      assemblies.removeValue(forKey: frame.messageID)
      throw TailTuneFrameError.inconsistentMessage
    }

    if let existing = assembly.chunks[index] {
      guard existing == frame.payload else {
        assemblies.removeValue(forKey: frame.messageID)
        throw TailTuneFrameError.inconsistentMessage
      }
      assembly.lastUpdate = now
      assemblies[frame.messageID] = assembly
      return nil
    }

    let newByteCount = assembly.receivedBytes + frame.payload.count
    guard newByteCount <= TailTuneFrame.maxMessageBytes else {
      assemblies.removeValue(forKey: frame.messageID)
      throw TailTuneFrameError.messageTooLarge
    }

    assembly.chunks[index] = frame.payload
    assembly.receivedBytes = newByteCount
    assembly.receivedCount += 1
    assembly.lastUpdate = now

    guard assembly.receivedCount == assembly.chunkCount else {
      assemblies[frame.messageID] = assembly
      return nil
    }

    var message = Data(capacity: assembly.receivedBytes)
    for chunk in assembly.chunks {
      guard let chunk else {
        assemblies.removeValue(forKey: frame.messageID)
        throw TailTuneFrameError.inconsistentMessage
      }
      message.append(chunk)
    }
    assemblies.removeValue(forKey: frame.messageID)
    return (frame.messageID, message)
  }

  private func purgeExpired(now: TimeInterval) {
    assemblies = assemblies.filter { now - $0.value.lastUpdate <= timeout }
  }
}

extension UInt16 {
  fileprivate var bigEndianBytes: [UInt8] {
    [UInt8(truncatingIfNeeded: self >> 8), UInt8(truncatingIfNeeded: self)]
  }

  fileprivate init<S: Collection>(bigEndianBytes bytes: S) where S.Element == UInt8 {
    var iterator = bytes.makeIterator()
    let b0 = UInt16(iterator.next() ?? 0)
    let b1 = UInt16(iterator.next() ?? 0)
    self = (b0 << 8) | b1
  }
}

extension UInt32 {
  fileprivate var bigEndianBytes: [UInt8] {
    [
      UInt8(truncatingIfNeeded: self >> 24),
      UInt8(truncatingIfNeeded: self >> 16),
      UInt8(truncatingIfNeeded: self >> 8),
      UInt8(truncatingIfNeeded: self),
    ]
  }

  fileprivate init<S: Collection>(bigEndianBytes bytes: S) where S.Element == UInt8 {
    var iterator = bytes.makeIterator()
    let b0 = UInt32(iterator.next() ?? 0)
    let b1 = UInt32(iterator.next() ?? 0)
    let b2 = UInt32(iterator.next() ?? 0)
    let b3 = UInt32(iterator.next() ?? 0)
    self = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
  }
}
