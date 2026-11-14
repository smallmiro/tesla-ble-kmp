import XCTest
import Teslable

// Smoke tests of the release Teslable.xcframework as an app developer links it through Swift Package Manager.
// They need no car and no Bluetooth. They run inside the app bundle (TEST_HOST), because a plain test binary on the
// simulator cannot use the Keychain (documents/md/17-lib-platform-notes.md §Key storage).
//
// Doc sources (documents/md): 12-lib-getting-started.md §Swift (iOS), 13-lib-pairing.md §Key store API,
// 17-lib-platform-notes.md §iOS and §Swift (pass every argument, onEnum(of:), .companion).
final class ConsumerSmokeTests: XCTestCase {
    private static let alias = "consumer-smoke-key"
    private var ble: TeslaBle!

    override func setUp() async throws {
        // 12-lib-getting-started.md §Swift: one TeslaBle per process, TeslaBle.companion.create(platform:config:).
        ble = TeslaBle.companion.create(platform: PlatformContext(), config: TeslaBleConfig.companion.default(logger: DebugLogger()))
        try await deleteTestKey()
    }

    override func tearDown() async throws {
        // Leave no key behind in the simulator Keychain (13-lib-pairing.md: delete succeeds when the key does not exist).
        try await deleteTestKey()
        try await ble.close()
        ble = nil
    }

    private func deleteTestKey() async throws {
        let result = try await ble.keys.delete(alias: Self.alias)
        guard case .success = onEnum(of: result) else {
            XCTFail("could not delete the test key: \(result)")
            return
        }
    }

    // SKIE turns a suspend function into async throws (17-lib-platform-notes.md §iOS).
    func testAsyncCallCompletes() async throws {
        // 13-lib-pairing.md: get(alias) returns Success(null) when no key exists.
        let result = try await ble.keys.get(alias: Self.alias)
        guard case .success(let success) = onEnum(of: result) else {
            return XCTFail("expected Success for a missing key, got \(result)")
        }
        XCTAssertNil(success.value)
    }

    // SKIE turns a Flow into an AsyncSequence. The documented Flow that needs no car is ble.scan(vin:): the simulator has no
    // Bluetooth, so the first value is a ScanEvent.Failed right away. The test checks that a Flow value reaches a
    // Swift `for await` and is one of the two documented ScanEvent cases (12-lib-getting-started.md §Swift step 2). It does
    // not assert the error text: the docs say only that scan can emit Failed (18-lib-troubleshooting.md).
    func testFlowYieldsFirstValue() async throws {
        // Kotlin objects are not Sendable in Swift 6 mode; this test is their only user, so opt out for the Task hand-off.
        nonisolated(unsafe) let vin = try XCTUnwrap(Vin.companion.parseOrNull(value: "5YJS0000000000000"))   // masked test VIN
        nonisolated(unsafe) let ble = self.ble!
        let scan = Task { () -> String? in
            for await event in ble.scan(vin: vin) {
                switch onEnum(of: event) {
                case .found: return "found"
                case .failed: return "failed"
                }
            }
            return nil   // the Flow ended without a value
        }
        // The caller owns the time limit (12-lib-getting-started.md: "set a time limit by cancelling the Task").
        let watchdog = Task { try await Task.sleep(nanoseconds: 15_000_000_000); scan.cancel() }
        let first = await scan.value
        watchdog.cancel()
        XCTAssertNotNil(first, "scan(vin:) gave no first value within 15 s")
    }

    // SKIE turns the sealed VehicleResult into an enum behind onEnum(of:). No default: the compiler proves every case is handled.
    func testSealedResultSwitch() async throws {
        let result = try await ble.keys.get(alias: Self.alias)
        let label: String
        switch onEnum(of: result) {
        case .success: label = "success"
        case .uncertain: label = "uncertain"
        case .failure: label = "failure"
        }
        XCTAssertEqual(label, "success")
    }

    // Creating a key runs the library's iOS key code (Security.framework, and the Swift code behind it) through the
    // release binary, so a missing system library or Swift runtime symbol shows up here. 13-lib-pairing.md §Key store API: publicKey is 0x04 ‖ X ‖ Y, 65 bytes.
    func testKeyCreationLinksCryptoKit() async throws {
        // SOFTWARE_ONLY: the simulator has no Secure Enclave (13-lib-pairing.md §Policies and protection levels).
        let result = try await ble.keys.getOrCreate(alias: Self.alias, policy: .softwareOnly, accessibility: .whenUnlocked)
        guard case .success(let success) = onEnum(of: result), let key = success.value as? VehicleKey else {
            return XCTFail("key creation failed: \(result)")
        }
        XCTAssertEqual(key.protection, .software)
        XCTAssertEqual(key.publicKey.toByteArray().size, 65)
    }
}
