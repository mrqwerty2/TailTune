import Foundation

enum JSONValue: Codable, Equatable, Sendable {
  case string(String)
  case int(Int)
  case double(Double)
  case bool(Bool)
  case object([String: JSONValue])
  case array([JSONValue])
  case null

  init(from decoder: Decoder) throws {
    let container = try decoder.singleValueContainer()
    if container.decodeNil() {
      self = .null
    } else if let value = try? container.decode(Bool.self) {
      self = .bool(value)
    } else if let value = try? container.decode(Int.self) {
      self = .int(value)
    } else if let value = try? container.decode(Double.self) {
      self = .double(value)
    } else if let value = try? container.decode(String.self) {
      self = .string(value)
    } else if let value = try? container.decode([String: JSONValue].self) {
      self = .object(value)
    } else if let value = try? container.decode([JSONValue].self) {
      self = .array(value)
    } else {
      throw DecodingError.dataCorruptedError(
        in: container, debugDescription: "Unsupported JSON value")
    }
  }

  func encode(to encoder: Encoder) throws {
    var container = encoder.singleValueContainer()
    switch self {
    case .string(let value): try container.encode(value)
    case .int(let value): try container.encode(value)
    case .double(let value): try container.encode(value)
    case .bool(let value): try container.encode(value)
    case .object(let value): try container.encode(value)
    case .array(let value): try container.encode(value)
    case .null: try container.encodeNil()
    }
  }

  func decode<T: Decodable>(_ type: T.Type, using decoder: JSONDecoder = JSONDecoder()) throws -> T
  {
    let data = try JSONEncoder().encode(self)
    return try decoder.decode(T.self, from: data)
  }
}

struct TailTuneRPCRequest: Codable, Equatable, Sendable {
  let v: Int
  let id: UInt32
  let op: String
  let args: [String: JSONValue]

  init(id: UInt32, op: String, args: [String: JSONValue] = [:]) {
    self.v = 1
    self.id = id
    self.op = op
    self.args = args
  }
}

struct TailTuneRPCErrorPayload: Codable, Equatable, Error, LocalizedError, Sendable {
  let code: String
  let message: String

  var errorDescription: String? { message }
}

struct TailTuneRPCResponse: Codable, Equatable, Sendable {
  let v: Int
  let id: UInt32
  let ok: Bool
  let data: JSONValue?
  let error: TailTuneRPCErrorPayload?
}

struct TailTuneRPCEvent: Codable, Equatable, Sendable {
  let v: Int
  let event: String
  let data: JSONValue?
}

struct TailTuneRPCProbe: Codable {
  let id: UInt32?
  let event: String?
}
