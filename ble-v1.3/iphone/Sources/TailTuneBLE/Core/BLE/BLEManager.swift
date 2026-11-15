import Combine
import CoreBluetooth
import Foundation

@MainActor
final class BLEManager: NSObject, ObservableObject {
  enum ConnectionState: Equatable {
    case unavailable(String)
    case idle
    case scanning
    case connecting(String)
    case discovering(String)
    case ready(String)
    case reconnecting(String)

    var title: String {
      switch self {
      case .unavailable(let text): return text
      case .idle: return "Disconnected"
      case .scanning: return "Scanning…"
      case .connecting(let name): return "Connecting to \(name)…"
      case .discovering(let name): return "Preparing \(name)…"
      case .ready(let name): return "Connected to \(name)"
      case .reconnecting(let name): return "Reconnecting to \(name)…"
      }
    }

    var isReady: Bool {
      if case .ready = self { return true }
      return false
    }
  }

  struct Candidate: Identifiable, Equatable {
    let id: UUID
    let name: String
    let rssi: Int
  }

  enum BLEError: Error, LocalizedError {
    case notReady
    case encodeFailed
    case timeout
    case disconnected
    case transport(String)
    case protocolError(String)

    var errorDescription: String? {
      switch self {
      case .notReady: return "TailTune is not connected."
      case .encodeFailed: return "Could not encode the BLE request."
      case .timeout: return "The Samsung did not respond in time."
      case .disconnected: return "Bluetooth disconnected before the request completed."
      case .transport(let message): return message
      case .protocolError(let message): return message
      }
    }
  }

  @Published private(set) var connectionState: ConnectionState = .idle
  @Published private(set) var candidates: [Candidate] = []
  @Published private(set) var lastTransportError: String?

  var onReady: (() -> Void)?
  var onEvent: ((TailTuneRPCEvent) -> Void)?

  private var central: CBCentralManager!
  private var peripheralsByID: [UUID: CBPeripheral] = [:]
  private var peripheral: CBPeripheral?
  private var rxCharacteristic: CBCharacteristic?
  private var txCharacteristic: CBCharacteristic?
  private let assembler = TailTuneMessageAssembler()

  private var nextMessageID: UInt32 = 1
  private struct PendingRequest {
    let continuation: CheckedContinuation<JSONValue, Error>
    let deadline: TimeInterval
  }
  private var pending: [UInt32: PendingRequest] = [:]

  private var writeQueue: [Data] = []
  private var writeInFlight = false
  private var reconnectAttempt = 0
  private var intentionalDisconnect = false
  private let defaults = UserDefaults.standard
  private let savedPeripheralKey = "TailTuneBLE.savedPeripheralID"

  override init() {
    super.init()
    central = CBCentralManager(
      delegate: self,
      queue: nil,
      options: [CBCentralManagerOptionRestoreIdentifierKey: "dev.tailtune.ios.central"]
    )
  }

  func scan() {
    guard central.state == .poweredOn else { return }
    candidates.removeAll()
    peripheralsByID.removeAll()
    connectionState = .scanning
    central.scanForPeripherals(
      withServices: [TailTuneBLEUUID.service],
      options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
    )
    DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
      guard let self, self.central.isScanning else { return }
      self.central.stopScan()
      if self.peripheral == nil { self.connectionState = .idle }
    }
  }

  func connect(to candidate: Candidate) {
    guard let found = peripheralsByID[candidate.id] else { return }
    connect(found, name: candidate.name, reconnecting: false)
  }

  func disconnect() {
    intentionalDisconnect = true
    reconnectAttempt = 0
    defaults.removeObject(forKey: savedPeripheralKey)
    guard let peripheral else {
      resetConnectionState(to: .idle, error: BLEError.disconnected)
      return
    }
    central.cancelPeripheralConnection(peripheral)
  }

  func request(op: String, args: [String: JSONValue] = [:], timeout: TimeInterval = 45) async throws
    -> JSONValue
  {
    guard connectionState.isReady, let peripheral, let rxCharacteristic else {
      throw BLEError.notReady
    }
    let id = allocateMessageID()
    let request = TailTuneRPCRequest(id: id, op: op, args: args)
    let message: Data
    do { message = try JSONEncoder().encode(request) } catch { throw BLEError.encodeFailed }

    let packetLimit = max(
      TailTuneFrame.headerSize + 1, peripheral.maximumWriteValueLength(for: .withResponse))
    let frames = try TailTuneFrameCodec.encodeMessage(
      message, messageID: id, maxPacketBytes: packetLimit)

    return try await withCheckedThrowingContinuation { continuation in
      let deadline = ProcessInfo.processInfo.systemUptime + timeout
      pending[id] = PendingRequest(continuation: continuation, deadline: deadline)
      writeQueue.append(contentsOf: frames)
      pumpWrites(peripheral: peripheral, characteristic: rxCharacteristic)

      DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
        self?.expireRequest(id: id)
      }
    }
  }

  private func allocateMessageID() -> UInt32 {
    // Avoid the theoretical wraparound collision with a request that is still pending.
    repeat {
      let current = nextMessageID
      nextMessageID = nextMessageID == 0x7FFF_FFFF ? 1 : nextMessageID + 1
      if pending[current] == nil { return current }
    } while true
  }

  private func expireRequest(id: UInt32) {
    guard let item = pending[id], ProcessInfo.processInfo.systemUptime >= item.deadline else {
      return
    }
    pending.removeValue(forKey: id)
    item.continuation.resume(throwing: BLEError.timeout)
  }

  private func pumpWrites(peripheral: CBPeripheral, characteristic: CBCharacteristic) {
    guard !writeInFlight, let first = writeQueue.first else { return }
    writeInFlight = true
    peripheral.writeValue(first, for: characteristic, type: .withResponse)
  }

  private func handleIncoming(_ packet: Data) {
    do {
      guard let completed = try assembler.ingest(packet) else { return }
      let probe = try JSONDecoder().decode(TailTuneRPCProbe.self, from: completed.data)
      if let id = probe.id {
        let response = try JSONDecoder().decode(TailTuneRPCResponse.self, from: completed.data)
        guard response.v == 1, id == completed.messageID,
          let request = pending.removeValue(forKey: id) else { return }
        if response.ok {
          request.continuation.resume(returning: response.data ?? .null)
        } else {
          request.continuation.resume(
            throwing: response.error
              ?? TailTuneRPCErrorPayload(
                code: "remote_error", message: "Samsung returned an unknown error."))
        }
      } else if probe.event != nil {
        let event = try JSONDecoder().decode(TailTuneRPCEvent.self, from: completed.data)
        guard event.v == 1, completed.messageID & 0x8000_0000 != 0 else { return }
        onEvent?(event)
      }
    } catch {
      lastTransportError = error.localizedDescription
    }
  }

  private func connect(_ peripheral: CBPeripheral, name: String, reconnecting: Bool) {
    central.stopScan()
    intentionalDisconnect = false
    self.peripheral = peripheral
    peripheral.delegate = self
    rxCharacteristic = nil
    txCharacteristic = nil
    assembler.reset()
    writeQueue.removeAll()
    writeInFlight = false
    connectionState = reconnecting ? .reconnecting(name) : .connecting(name)
    central.connect(peripheral, options: [CBConnectPeripheralOptionNotifyOnDisconnectionKey: true])
  }

  private func trySavedPeripheral() -> Bool {
    guard let raw = defaults.string(forKey: savedPeripheralKey), let id = UUID(uuidString: raw)
    else { return false }
    guard let saved = central.retrievePeripherals(withIdentifiers: [id]).first else { return false }
    connect(saved, name: saved.name ?? "Samsung", reconnecting: true)
    return true
  }

  private func scheduleReconnect(name: String) {
    guard !intentionalDisconnect else {
      resetConnectionState(to: .idle, error: BLEError.disconnected)
      return
    }
    reconnectAttempt = min(reconnectAttempt + 1, 6)
    connectionState = .reconnecting(name)
    let delay = min(pow(2.0, Double(reconnectAttempt - 1)), 20.0)
    DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
      guard let self, !self.intentionalDisconnect, self.central.state == .poweredOn else { return }
      if !self.trySavedPeripheral() { self.scan() }
    }
  }

  private func resetConnectionState(to state: ConnectionState, error: Error?) {
    peripheral = nil
    rxCharacteristic = nil
    txCharacteristic = nil
    assembler.reset()
    writeQueue.removeAll()
    writeInFlight = false
    connectionState = state
    if let error { failPending(with: error) }
  }

  private func failPending(with error: Error) {
    let requests = pending.values
    pending.removeAll()
    for request in requests { request.continuation.resume(throwing: error) }
  }
}

extension BLEManager: @preconcurrency CBCentralManagerDelegate {
  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    switch central.state {
    case .poweredOn:
      if peripheral != nil { return } // restored connection: do not create a second central connection
      connectionState = .idle
      if !trySavedPeripheral() { scan() }
    case .poweredOff:
      resetConnectionState(to: .unavailable("Bluetooth is off"), error: BLEError.disconnected)
    case .unauthorized:
      resetConnectionState(
        to: .unavailable("Bluetooth permission is denied"), error: BLEError.disconnected)
    case .unsupported:
      resetConnectionState(
        to: .unavailable("Bluetooth LE is unavailable"), error: BLEError.disconnected)
    case .resetting:
      resetConnectionState(
        to: .unavailable("Bluetooth is restarting"), error: BLEError.disconnected)
    case .unknown:
      connectionState = .unavailable("Checking Bluetooth…")
    @unknown default:
      connectionState = .unavailable("Bluetooth is unavailable")
    }
  }

  func centralManager(
    _ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
    advertisementData: [String: Any], rssi: NSNumber
  ) {
    let id = peripheral.identifier
    peripheralsByID[id] = peripheral
    let advertised = advertisementData[CBAdvertisementDataLocalNameKey] as? String
    let name = advertised ?? peripheral.name ?? "TailTune Samsung"
    let candidate = Candidate(id: id, name: name, rssi: rssi.intValue)
    if let index = candidates.firstIndex(where: { $0.id == id }) {
      candidates[index] = candidate
    } else {
      candidates.append(candidate)
    }
    candidates.sort { $0.rssi > $1.rssi }
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    guard peripheral.identifier == self.peripheral?.identifier else { return }
    reconnectAttempt = 0
    defaults.set(peripheral.identifier.uuidString, forKey: savedPeripheralKey)
    let name = peripheral.name ?? "Samsung"
    connectionState = .discovering(name)
    peripheral.discoverServices([TailTuneBLEUUID.service])
  }

  func centralManager(
    _ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?
  ) {
    guard peripheral.identifier == self.peripheral?.identifier else { return }
    let name = peripheral.name ?? "Samsung"
    lastTransportError = error?.localizedDescription
    resetConnectionState(to: .reconnecting(name), error: BLEError.disconnected)
    scheduleReconnect(name: name)
  }

  func centralManager(
    _ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?
  ) {
    guard peripheral.identifier == self.peripheral?.identifier else { return }
    let name = peripheral.name ?? "Samsung"
    lastTransportError = error?.localizedDescription
    resetConnectionState(to: .reconnecting(name), error: BLEError.disconnected)
    scheduleReconnect(name: name)
  }

  func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
    guard
      let restored = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first
    else { return }
    peripheralsByID[restored.identifier] = restored
    restored.delegate = self
    peripheral = restored
    defaults.set(restored.identifier.uuidString, forKey: savedPeripheralKey)
    let name = restored.name ?? "Samsung"
    if restored.state == .connected {
      connectionState = .discovering(name)
      restored.discoverServices([TailTuneBLEUUID.service])
    } else {
      connect(restored, name: name, reconnecting: true)
    }
  }
}

extension BLEManager: @preconcurrency CBPeripheralDelegate {
  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    guard peripheral.identifier == self.peripheral?.identifier else { return }
    if let error {
      lastTransportError = error.localizedDescription
      central.cancelPeripheralConnection(peripheral)
      return
    }
    guard let service = peripheral.services?.first(where: { $0.uuid == TailTuneBLEUUID.service })
    else {
      lastTransportError = "TailTune BLE service was not found."
      central.cancelPeripheralConnection(peripheral)
      return
    }
    peripheral.discoverCharacteristics([TailTuneBLEUUID.rx, TailTuneBLEUUID.tx], for: service)
  }

  func peripheral(
    _ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?
  ) {
    guard peripheral.identifier == self.peripheral?.identifier else { return }
    if let error {
      lastTransportError = error.localizedDescription
      central.cancelPeripheralConnection(peripheral)
      return
    }
    for characteristic in service.characteristics ?? [] {
      if characteristic.uuid == TailTuneBLEUUID.rx { rxCharacteristic = characteristic }
      if characteristic.uuid == TailTuneBLEUUID.tx { txCharacteristic = characteristic }
    }
    guard rxCharacteristic != nil, let txCharacteristic else {
      lastTransportError = "TailTune BLE characteristics are incomplete."
      central.cancelPeripheralConnection(peripheral)
      return
    }
    peripheral.setNotifyValue(true, for: txCharacteristic)
  }

  func peripheral(
    _ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
    error: Error?
  ) {
    if let error {
      lastTransportError = error.localizedDescription
      central.cancelPeripheralConnection(peripheral)
      return
    }
    guard peripheral.identifier == self.peripheral?.identifier,
      characteristic.uuid == TailTuneBLEUUID.tx, characteristic.isNotifying,
      rxCharacteristic != nil
    else { return }
    let name = peripheral.name ?? "Samsung"
    connectionState = .ready(name)
    onReady?()
  }

  func peripheral(
    _ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?
  ) {
    if let error {
      lastTransportError = error.localizedDescription
      return
    }
    guard peripheral.identifier == self.peripheral?.identifier,
      characteristic.uuid == TailTuneBLEUUID.tx, let data = characteristic.value else { return }
    handleIncoming(data)
  }

  func peripheral(
    _ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?
  ) {
    guard peripheral.identifier == self.peripheral?.identifier,
      characteristic.uuid == TailTuneBLEUUID.rx else { return }
    writeInFlight = false
    if let error {
      lastTransportError = error.localizedDescription
      failPending(with: BLEError.transport(error.localizedDescription))
      central.cancelPeripheralConnection(peripheral)
      return
    }
    if !writeQueue.isEmpty { writeQueue.removeFirst() }
    if let rxCharacteristic { pumpWrites(peripheral: peripheral, characteristic: rxCharacteristic) }
  }
}
