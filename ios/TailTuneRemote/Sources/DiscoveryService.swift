import Foundation
import Network

@MainActor
final class DiscoveryService: ObservableObject {
    @Published private(set) var devices: [DiscoveredTailTune] = []
    @Published private(set) var errorMessage: String?

    private let queue = DispatchQueue(label: "TailTune.BonjourBrowser")
    private var browser: NWBrowser?
    private var endpointsByID: [String: NWEndpoint] = [:]

    func start() {
        guard browser == nil else { return }
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = true
        let browser = NWBrowser(
            for: .bonjourWithTXTRecord(type: "_tailtune._tcp", domain: nil),
            using: parameters
        )
        self.browser = browser

        browser.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                guard let self else { return }
                switch state {
                case .failed(let error):
                    self.errorMessage = "Bonjour failed: \(error.localizedDescription)"
                    self.stop()
                case .ready:
                    self.errorMessage = nil
                default:
                    break
                }
            }
        }

        browser.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor in
                self?.apply(results: results)
            }
        }
        browser.start(queue: queue)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        endpointsByID.removeAll()
        devices.removeAll()
    }

    func endpoint(for device: DiscoveredTailTune) -> NWEndpoint? {
        endpointsByID[device.id]
    }

    private func apply(results: Set<NWBrowser.Result>) {
        var endpoints: [String: NWEndpoint] = [:]
        var list: [DiscoveredTailTune] = []
        for result in results {
            let id = result.endpoint.debugDescription
            let name: String
            switch result.endpoint {
            case .service(let serviceName, _, _, _):
                name = serviceName
            default:
                name = result.endpoint.debugDescription
            }
            endpoints[id] = result.endpoint
            list.append(
                DiscoveredTailTune(
                    id: id,
                    name: name,
                    endpointDescription: result.endpoint.debugDescription
                )
            )
        }
        endpointsByID = endpoints
        devices = list.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
}
