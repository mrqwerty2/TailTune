import XCTest
@testable import TailTuneRemote

final class ModelDecodeTests: XCTestCase {
    func testPlaybackDecodesMissingQueue() throws {
        let data = #"{"playing":false,"positionMs":0,"durationMs":0,"current":null,"battery":{"percent":42,"charging":false}}"#.data(using: .utf8)!
        let playback = try JSONDecoder().decode(TailTunePlayback.self, from: data)
        XCTAssertFalse(playback.playing)
        XCTAssertEqual(playback.battery?.percent, 42)
        XCTAssertNil(playback.queue)
    }
}
