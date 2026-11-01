import XCTest
@testable import TeslableSample

/// SFR-020: 키를 바꾸는 동안 다른 탭의 동작·연결이 옛 키를 잡지 못하게 하는 엔진 수준의 문(`KeyOperationGate`)과 키 핸들 캐시.
final class KeyOperationGateTests: XCTestCase {
    func testOperationsRunTogetherAndAreCounted() {
        let gate = KeyOperationGate()
        XCTAssertTrue(gate.beginOperation())
        XCTAssertTrue(gate.beginOperation())
        XCTAssertEqual(gate.activeOperations, 2)
        gate.endOperation()
        gate.endOperation()
        gate.endOperation() // 넘치게 끝내도 음수가 되지 않는다
        XCTAssertEqual(gate.activeOperations, 0)
    }

    func testReplaceIsRefusedWhileAnyOperationRuns() {
        let gate = KeyOperationGate()
        XCTAssertTrue(gate.beginOperation())
        XCTAssertFalse(gate.beginReplace(), "Control 탭의 등록·명령이 도는 동안은 키를 바꾸지 않는다")
        XCTAssertFalse(gate.isReplacing)
        gate.endOperation()
        XCTAssertTrue(gate.beginReplace())
    }

    func testOperationsAreRefusedWhileReplacing() {
        let gate = KeyOperationGate()
        XCTAssertTrue(gate.beginReplace())
        XCTAssertTrue(gate.isReplacing)
        XCTAssertFalse(gate.beginOperation())
        XCTAssertFalse(gate.beginReplace(), "두 번째 교체도 안 된다")
        gate.endReplace()
        XCTAssertFalse(gate.isReplacing)
        XCTAssertTrue(gate.beginOperation())
    }

    func testEndReplaceIsIdempotent() {
        let gate = KeyOperationGate()
        XCTAssertTrue(gate.beginReplace())
        gate.endReplace()
        gate.endReplace()
        XCTAssertTrue(gate.beginOperation())
        XCTAssertFalse(gate.isReplacing)
    }
}

final class KeyHandleCacheTests: XCTestCase {
    func testStoresAndReturnsTheHandle() {
        let cache = KeyHandleCache<Int>()
        XCTAssertNil(cache.get())
        XCTAssertTrue(cache.set(7, ifEpoch: cache.epoch))
        XCTAssertEqual(cache.get(), 7)
    }

    func testForgetDropsTheHandle() {
        let cache = KeyHandleCache<Int>()
        _ = cache.set(7, ifEpoch: cache.epoch)
        cache.forget()
        XCTAssertNil(cache.get())
    }

    func testAReadThatStartedBeforeForgetCannotCacheTheDeletedKey() {
        let cache = KeyHandleCache<Int>()
        let epochWhenTheReadStarted = cache.epoch // 연결 루프의 getOrCreate 가 시작됐다
        cache.forget() // 키 교체가 핸들을 버렸다
        XCTAssertFalse(cache.set(7, ifEpoch: epochWhenTheReadStarted), "늦게 끝난 읽기는 지워질 옛 키를 캐시에 올리지 못한다")
        XCTAssertNil(cache.get())
        XCTAssertTrue(cache.set(8, ifEpoch: cache.epoch))
        XCTAssertEqual(cache.get(), 8)
    }
}

/// 엔진 수준의 보호(Kotlin 호출 전에 끝나는 가지만 시험한다: 차량이나 키체인 없이).
final class MonitorEngineKeyGuardTests: XCTestCase {
    private var folder: URL!
    private var suite = ""

    override func setUp() {
        super.setUp()
        folder = FileManager.default.temporaryDirectory.appendingPathComponent("engine-guard-\(UUID().uuidString)", isDirectory: true)
        suite = "MonitorEngineKeyGuardTests-\(UUID().uuidString)"
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: folder)
        UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    private func makeEngine() -> MonitorEngine {
        let engine = MonitorEngine(defaults: UserDefaults(suiteName: suite)!, logFolder: folder)
        _ = engine.saveVin("5YJS0000000000000") // 가짜 VIN(형식만 같다)
        return engine
    }

    func testReplaceIsBusyWhileAnotherTabRunsAnOperation() async {
        let engine = makeEngine()
        XCTAssertTrue(engine.keyGate.beginOperation()) // Control 탭의 등록·명령
        let outcome = await engine.replaceKey()
        XCTAssertEqual(outcome, .busy)
        XCTAssertFalse(engine.keyGate.isReplacing)
        engine.keyGate.endOperation()
    }

    func testConnectIsRefusedWhileTheKeyIsBeingReplaced() {
        let engine = makeEngine()
        XCTAssertTrue(engine.keyGate.beginReplace())
        XCTAssertFalse(engine.connectNow(), "교체 중에 Live/Control 탭에서 누른 연결이 옛 핸들을 잡으면 안 된다")
        XCTAssertEqual(engine.link, .idle)
        engine.keyGate.endReplace()
    }

    func testEnsureKeyIsRefusedWhileTheKeyIsBeingReplaced() async {
        let engine = makeEngine()
        XCTAssertTrue(engine.keyGate.beginReplace())
        let outcome = await engine.ensureKey()
        guard case .failed = outcome else { return XCTFail("expected failed, got \(outcome)") }
        XCTAssertNil(engine.currentKey(), "지워질 키는 캐시에 오르지 않는다")
        engine.keyGate.endReplace()
    }
}
