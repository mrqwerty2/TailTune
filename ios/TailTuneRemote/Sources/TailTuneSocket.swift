import Foundation
import Network

/// Resilient native WebSocket transport for the TailTune protocol.
///
/// All mutable networking state is confined to `queue`. iOS is free to suspend
/// the app in the background; `resume()` reconnects to the last in-memory target
/// when the app becomes active again.
final class TailTuneSocket {
    enum SocketError: LocalizedError {
        case notConnected
        case invalidMessage
        case timedOut
        case remote(String)
        case connection(String)

        var errorDescription: String? {
            switch self {
            case .notConnected: return "TailTune is not connected."
            case .invalidMessage: return "TailTune returned an invalid message."
            case .timedOut: return "The Samsung took too long to respond."
            case .remote(let message): return message
            case .connection(let message): return message
            }
        }
    }

    enum Event {
        case snapshot(Data)
        case playback(Data)
        case library(Data)
        case downloads(Data)
        case authorizationFailed(String)
        case state(TailTuneConnectionState)
    }

    var onEvent: ((Event) -> Void)?

    private struct Target {
        let endpoint: NWEndpoint
        let token: String
    }

    private struct PendingRequest {
        let generation: Int
        let completion: (Result<Data, Error>) -> Void
    }

    private let queue = DispatchQueue(label: "TailTune.WebSocket")
    private var connection: NWConnection?
    private var desiredTarget: Target?
    private var heartbeat: DispatchSourceTimer?
    private var reconnectWorkItem: DispatchWorkItem?
    private var pending: [String: PendingRequest] = [:]
    private var generation = 0
    private var reconnectAttempt = 0
    private var ready = false

    func connect(endpoint: NWEndpoint, token: String) {
        queue.async { [weak self] in
            guard let self else { return }
            self.desiredTarget = Target(endpoint: endpoint, token: token)
            self.reconnectAttempt = 0
            self.connectDesiredTargetLocked()
        }
    }

    func connect(host: String, port: UInt16 = 8787, token: String) throws {
        let trimmedHost = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedHost.isEmpty,
              let nwPort = NWEndpoint.Port(rawValue: port) else {
            throw SocketError.connection("Invalid host or port")
        }
        connect(
            endpoint: .hostPort(host: NWEndpoint.Host(trimmedHost), port: nwPort),
            token: token
        )
    }

    /// Called when the app returns to the foreground after iOS suspension.
    func resume() {
        queue.async { [weak self] in
            guard let self, self.desiredTarget != nil else { return }
            if self.connection == nil || !self.ready {
                self.reconnectAttempt = 0
                self.connectDesiredTargetLocked()
            }
        }
    }

    func disconnect() {
        queue.async { [weak self] in
            self?.disconnectLocked(clearTarget: true, emitState: true)
        }
    }

    func request(
        operation: String,
        fields: [String: Any] = [:],
        completion: @escaping (Result<Data, Error>) -> Void
    ) {
        queue.async { [weak self] in
            guard let self, let connection = self.connection, self.ready else {
                completion(.failure(SocketError.notConnected))
                return
            }

            let requestID = UUID().uuidString
            let requestGeneration = self.generation
            var object: [String: Any] = [
                "type": "request",
                "requestId": requestID,
                "operation": operation
            ]
            fields.forEach { object[$0.key] = $0.value }
            guard JSONSerialization.isValidJSONObject(object),
                  let data = try? JSONSerialization.data(withJSONObject: object),
                  data.count <= Self.maximumMessageBytes else {
                completion(.failure(SocketError.invalidMessage))
                return
            }

            self.pending[requestID] = PendingRequest(
                generation: requestGeneration,
                completion: completion
            )
            self.queue.asyncAfter(deadline: .now() + Self.requestTimeoutSeconds) { [weak self] in
                guard let self,
                      let pending = self.pending[requestID],
                      pending.generation == requestGeneration else { return }
                self.pending.removeValue(forKey: requestID)
                pending.completion(.failure(SocketError.timedOut))
            }

            let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
            let context = NWConnection.ContentContext(
                identifier: "tailtune-request-\(requestID)",
                metadata: [metadata]
            )
            connection.send(
                content: data,
                contentContext: context,
                isComplete: true,
                completion: .contentProcessed { [weak self] error in
                    guard let self, let error else { return }
                    self.queue.async {
                        guard let request = self.pending.removeValue(forKey: requestID) else { return }
                        request.completion(.failure(SocketError.connection(error.localizedDescription)))
                    }
                }
            )
        }
    }

    private func connectDesiredTargetLocked() {
        guard let target = desiredTarget else { return }
        disconnectLocked(clearTarget: false, emitState: false)

        generation += 1
        let currentGeneration = generation
        ready = false

        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = true
        let websocket = NWProtocolWebSocket.Options(.version13)
        websocket.autoReplyPing = true
        websocket.maximumMessageSize = Self.maximumMessageBytes
        websocket.setAdditionalHeaders([("X-TailTune-Token", target.token)])
        parameters.defaultProtocolStack.applicationProtocols.insert(websocket, at: 0)

        let connection = NWConnection(to: target.endpoint, using: parameters)
        self.connection = connection
        emit(.state(.connecting))

        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self,
                  let connection,
                  self.generation == currentGeneration,
                  self.connection === connection else { return }

            switch state {
            case .ready:
                self.ready = true
                self.reconnectAttempt = 0
                self.cancelReconnectLocked()
                self.emit(.state(.connected))
                self.receiveLoop(connection: connection, generation: currentGeneration)
                self.startHeartbeatLocked()
                self.request(operation: "snapshot") { _ in }

            case .waiting(let error):
                self.ready = false
                self.emit(.state(.waiting("Waiting: \(error.localizedDescription)")))

            case .failed(let error):
                self.handleConnectionFailureLocked(
                    SocketError.connection(error.localizedDescription),
                    generation: currentGeneration
                )

            case .cancelled:
                if self.generation == currentGeneration && self.connection === connection {
                    self.ready = false
                    self.connection = nil
                    self.stopHeartbeatLocked()
                    self.failAllLocked(SocketError.notConnected)
                    if self.desiredTarget != nil {
                        self.scheduleReconnectLocked()
                    } else {
                        self.emit(.state(.disconnected))
                    }
                }

            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func receiveLoop(connection: NWConnection, generation: Int) {
        connection.receiveMessage { [weak self, weak connection] data, _, _, error in
            guard let self,
                  let connection,
                  self.generation == generation,
                  self.connection === connection else { return }

            if let data { self.handleLocked(data) }
            if let error {
                self.handleConnectionFailureLocked(
                    SocketError.connection(error.localizedDescription),
                    generation: generation
                )
                return
            }
            self.receiveLoop(connection: connection, generation: generation)
        }
    }

    private func handleLocked(_ data: Data) {
        guard data.count <= Self.maximumMessageBytes,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = object["type"] as? String else {
            return
        }

        if type == "authorization_error" {
            let message = object["error"] as? String ?? "TailTune authorization failed."
            desiredTarget = nil // A bad token must not reconnect forever.
            emit(.authorizationFailed(message))
            disconnectLocked(clearTarget: true, emitState: false)
            emit(.state(.failed(message)))
            return
        }

        if type == "response" {
            guard let requestID = object["requestId"] as? String,
                  let request = pending.removeValue(forKey: requestID) else { return }
            if object["ok"] as? Bool == true {
                let payload = object["data"] ?? [:]
                guard JSONSerialization.isValidJSONObject(payload),
                      let encoded = try? JSONSerialization.data(withJSONObject: payload) else {
                    request.completion(.failure(SocketError.invalidMessage))
                    return
                }
                request.completion(.success(encoded))
            } else {
                request.completion(
                    .failure(SocketError.remote(object["error"] as? String ?? "Remote request failed"))
                )
            }
            return
        }

        guard let payload = object["data"],
              JSONSerialization.isValidJSONObject(payload),
              let encoded = try? JSONSerialization.data(withJSONObject: payload) else { return }
        switch type {
        case "snapshot": emit(.snapshot(encoded))
        case "playback": emit(.playback(encoded))
        case "library": emit(.library(encoded))
        case "downloads": emit(.downloads(encoded))
        default: break
        }
    }

    private func startHeartbeatLocked() {
        stopHeartbeatLocked()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 20, repeating: 20)
        timer.setEventHandler { [weak self] in
            guard let self, self.ready else { return }
            self.request(operation: "ping") { [weak self] result in
                guard case .failure = result else { return }
                self?.queue.async { [weak self] in
                    guard let self, self.ready else { return }
                    self.handleConnectionFailureLocked(
                        SocketError.timedOut,
                        generation: self.generation
                    )
                }
            }
        }
        heartbeat = timer
        timer.resume()
    }

    private func stopHeartbeatLocked() {
        heartbeat?.setEventHandler {}
        heartbeat?.cancel()
        heartbeat = nil
    }

    private func handleConnectionFailureLocked(_ error: Error, generation: Int) {
        guard self.generation == generation else { return }
        ready = false
        failAllLocked(error)
        stopHeartbeatLocked()
        self.generation += 1 // invalidate all callbacks from this connection
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        emit(.state(.failed(error.localizedDescription)))
        scheduleReconnectLocked()
    }

    private func scheduleReconnectLocked() {
        guard desiredTarget != nil, reconnectWorkItem == nil else { return }
        reconnectAttempt += 1
        let exponent = min(max(reconnectAttempt - 1, 0), 5)
        let delay = min(pow(2.0, Double(exponent)), 15.0)
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.reconnectWorkItem = nil
            guard self.desiredTarget != nil else { return }
            self.connectDesiredTargetLocked()
        }
        reconnectWorkItem = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func cancelReconnectLocked() {
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
    }

    private func disconnectLocked(clearTarget: Bool, emitState: Bool) {
        generation += 1
        ready = false
        cancelReconnectLocked()
        stopHeartbeatLocked()
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        failAllLocked(SocketError.notConnected)
        if clearTarget { desiredTarget = nil }
        if emitState { emit(.state(.disconnected)) }
    }

    private func failAllLocked(_ error: Error) {
        let callbacks = pending.values
        pending.removeAll()
        callbacks.forEach { $0.completion(.failure(error)) }
    }

    private func emit(_ event: Event) {
        DispatchQueue.main.async { [weak self] in self?.onEvent?(event) }
    }

    private static let maximumMessageBytes = 64 * 1024
    private static let requestTimeoutSeconds: TimeInterval = 12
}
