import XCTest
@testable import Minis

final class XunfeiTTSWebSocketSchemeTests: XCTestCase {
    func testSignedTTSWebSocketURL() throws {
        let provider = XunfeiVoiceProvider(providerId: "xunfei", appId: "app", apiKey: "key", apiSecret: "secret")
        let date = Date(timeIntervalSince1970: 0)
        let url = try provider.ttsWebSocketURL(date: date)
        let signed = provider.buildSignedURL(host: "tts-api.xfyun.cn", path: "/v2/tts", date: date)
        let queryMarker = try XCTUnwrap(signed.range(of: "?"))

        XCTAssertTrue(signed.hasPrefix("https://tts-api.xfyun.cn/v2/tts?"))
        XCTAssertEqual(url.scheme, "wss")
        XCTAssertEqual(url.host, "tts-api.xfyun.cn")
        XCTAssertEqual(url.path, "/v2/tts")
        XCTAssertEqual(url.query, String(signed[queryMarker.upperBound...]))
    }
}
