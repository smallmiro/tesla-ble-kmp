import XCTest
@testable import TeslableSample

/// 차 없이 상태 기계만 시험한다. 차량 `V`는 문자열로 대신한다. 시간은 `ManualClock`이다(실제로 기다리지 않는다).
final class ConnectionLoopTests: XCTestCase {
    private struct TestError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private final class Fixture: @unchecked Sendable {
        let clock = ManualClock()
        let transitions = Locked<[LinkState<String>]>([])
        let connectCalls = Locked<[TimeInterval]>([])
        let released = Locked<[String]>([])
        let losses = Mailbox<String?>()
        private let outcomes: Locked<[ConnectOutcome<String>]>
        var loop: ConnectionLoop<String>!

        init(_ outcomes: [ConnectOutcome<String>]) {
            self.outcomes = Locked(outcomes)
            let clock = self.clock
            loop = ConnectionLoop<String>(
                connect: { [self] in
                    connectCalls.mutate { $0.append(clock.now) }
                    var next: ConnectOutcome<String>?
                    self.outcomes.mutate { if !$0.isEmpty { next = $0.removeFirst() } }
                    return next ?? .failed("no car")
                },
                awaitLoss: { [losses] _ in await losses.receive() ?? nil },
                release: { [self] vehicle in released.mutate { $0.append(vehicle) } },
                now: { clock.now },
                sleep: { try await clock.sleep($0) },
                onTransition: { [self] state in transitions.mutate { $0.append(state) } }
            )
        }

        var state: LinkState<String> { loop.state }

        var backoffs: [(attempt: Int, delay: TimeInterval, reason: String?)] {
            transitions.value.compactMap { if case let .backoff(a, d, r) = $0 { return (a, d, r) } else { return nil } }
        }

        func waitForState(_ what: String, file: StaticString = #filePath, line: UInt = #line, _ match: @escaping @Sendable (LinkState<String>) -> Bool) async {
            await waitUntil(what, file: file, line: line) { match(self.loop.state) }
        }

        func waitForBackoffCount(_ n: Int, file: StaticString = #filePath, line: UInt = #line) async {
            await waitUntil("\(n) backoffs", file: file, line: line) { self.backoffs.count >= n }
        }
    }

    private func isBackoff(_ s: LinkState<String>) -> Bool { if case .backoff = s { return true } else { return false } }

    func testBackoffDoublesTo30s() async {
        let f = Fixture([]) // 계속 실패
        f.loop.start()
        for n in 1...6 {
            await f.waitForBackoffCount(n)
            if n < 6 { await f.clock.advanceToNextSleeper() }
        }

        XCTAssertEqual(f.backoffs.prefix(6).map(\.delay), [2, 4, 8, 16, 30, 30])
        XCTAssertEqual(f.backoffs.prefix(6).map(\.attempt), [1, 2, 3, 4, 5, 6])
        XCTAssertEqual(f.backoffs.first?.reason, "no car")
        // 연결 시도는 0, 2, 6, 14, 30, 60초에 일어난다
        XCTAssertEqual(f.connectCalls.value, [0, 2, 6, 14, 30, 60])
        await f.loop.stop()
    }

    func testStartsIdleAndConnectingAttemptsCountUp() async {
        let f = Fixture([])
        XCTAssertEqual(f.state, .idle)
        f.loop.start()
        await f.waitForBackoffCount(1)
        XCTAssertEqual(Array(f.transitions.value.prefix(2)), [.connecting(1), .backoff(attempt: 1, delay: 2, reason: "no car")])

        await f.clock.advanceToNextSleeper()
        await f.waitForBackoffCount(2)
        XCTAssertEqual(f.transitions.value[2], .connecting(2))
        await f.loop.stop()
    }

    func testReconnectsAfterLinkLoss() async {
        let f = Fixture([.connected("car1"), .connected("car2")])
        f.loop.start()
        await f.waitForState("car1 connected") { $0 == .connected("car1") }

        f.losses.send("link lost")
        await f.waitForState("backoff") { if case .backoff = $0 { return true } else { return false } }

        // 끊긴 차량은 반드시 release(= Vehicle.disconnect()) 한다
        XCTAssertEqual(f.released.value, ["car1"])
        XCTAssertEqual(f.state, .backoff(attempt: 1, delay: 2, reason: "link lost"))

        f.clock.advance(by: 1)
        await settle()
        XCTAssertTrue(isBackoff(f.state))
        await f.clock.advanceToNextSleeper()
        await f.waitForState("car2 connected") { $0 == .connected("car2") }
        XCTAssertEqual(f.connectCalls.value.count, 2)
        await f.loop.stop()
    }

    func testStableConnectionResetsBackoff() async {
        let f = Fixture([.failed("a"), .failed("b"), .failed("c"), .connected("car1")])
        f.loop.start()
        for n in 1...3 {
            await f.waitForBackoffCount(n)
            await f.clock.advanceToNextSleeper()
        }
        await f.waitForState("car1 connected") { $0 == .connected("car1") }

        f.clock.advance(by: 60) // 10초 넘게 안정적으로 연결돼 있었다
        f.losses.send(nil)
        await f.waitForBackoffCount(4)

        XCTAssertEqual(f.backoffs.last?.delay, 2)
        XCTAssertEqual(f.backoffs.last?.attempt, 1)
        XCTAssertNil(f.backoffs.last?.reason)
        await f.loop.stop()
    }

    func testQuickLossKeepsBackoffGrowing() async {
        let f = Fixture([.failed("a"), .connected("car1")])
        f.loop.start()
        await f.waitForBackoffCount(1)
        await f.clock.advanceToNextSleeper()
        await f.waitForState("car1 connected") { $0 == .connected("car1") }

        f.clock.advance(by: 1) // 1초 만에 다시 끊김: 안정적이지 않다
        f.losses.send("flap")
        await f.waitForBackoffCount(2)

        XCTAssertEqual(f.backoffs.last?.delay, 4)
        XCTAssertEqual(f.backoffs.last?.attempt, 2)
        await f.loop.stop()
    }

    func testConnectingAttemptsRestartAfterALinkDrop() async {
        let f = Fixture([.failed("a"), .connected("car1")])
        f.loop.start()
        await f.waitForBackoffCount(1)
        await f.clock.advanceToNextSleeper()
        await f.waitForState("car1 connected") { $0 == .connected("car1") }
        f.losses.send("lost")
        await f.waitForBackoffCount(2)
        await f.clock.advanceToNextSleeper()
        await waitUntil("connecting again") { f.transitions.value.filter { $0 == .connecting(1) }.count >= 2 }

        // 연결 시도 번호는 처음과 링크가 끊긴 뒤 1부터 다시 센다
        let connecting = f.transitions.value.compactMap { s -> Int? in if case let .connecting(n) = s { return n } else { return nil } }
        XCTAssertEqual(connecting, [1, 2, 1])
        await f.loop.stop()
    }

    func testManualDisconnectStopsReconnect() async {
        let f = Fixture([.connected("car1")])
        f.loop.start()
        await f.waitForState("car1 connected") { $0 == .connected("car1") }

        await f.loop.stop()

        XCTAssertEqual(f.state, .stopped)
        XCTAssertEqual(f.released.value, ["car1"])
        // 5분이 지나도 다시 연결하지 않는다
        let calls = f.connectCalls.value.count
        f.clock.advance(by: 300)
        await settle()
        XCTAssertEqual(f.connectCalls.value.count, calls)
        XCTAssertEqual(f.state, .stopped)
    }

    func testManualDisconnectDuringBackoffStopsReconnect() async {
        let f = Fixture([])
        f.loop.start()
        await f.waitForState("backoff") { if case .backoff = $0 { return true } else { return false } }

        await f.loop.stop()
        f.clock.advance(by: 300)
        await settle()

        XCTAssertEqual(f.connectCalls.value.count, 1)
        XCTAssertEqual(f.state, .stopped)
    }

    func testManualConnectSkipsBackoff() async {
        let f = Fixture([])
        f.loop.start()
        for n in 1...5 {
            await f.waitForBackoffCount(n)
            if n < 5 { await f.clock.advanceToNextSleeper() }
        }
        XCTAssertEqual(f.backoffs.last?.delay, 30)
        let before = f.connectCalls.value.count
        let at = f.clock.now

        f.loop.start() // 연결 버튼
        await f.waitForBackoffCount(6)

        XCTAssertEqual(f.connectCalls.value.count, before + 1)
        XCTAssertEqual(f.connectCalls.value.last, at) // 기다리지 않고 바로 시도했다
        await f.loop.stop()
    }

    func testConnectAfterStopStartsFreshWithShortBackoff() async {
        let f = Fixture([])
        f.loop.start()
        for n in 1...3 {
            await f.waitForBackoffCount(n)
            await f.clock.advanceToNextSleeper()
        }
        await f.waitForBackoffCount(4)
        XCTAssertGreaterThanOrEqual(f.backoffs.last?.delay ?? 0, 8)

        await f.loop.stop()
        f.loop.start()
        await waitUntil("fresh backoff") { f.backoffs.count >= 5 }

        XCTAssertEqual(f.backoffs.last?.delay, 2)
        XCTAssertEqual(f.backoffs.last?.attempt, 1)
        await f.loop.stop()
    }

    func testConnectDuringStopWaitsForTheOldVehicleToBeReleased() async {
        let releaseStarted = Locked(false)
        let gate = Mailbox<Void>()
        let calls = Locked(0)
        let neverLost = Mailbox<String?>()
        let loop = ConnectionLoop<String>(
            connect: {
                var n = 0
                calls.mutate { $0 += 1; n = $0 }
                return .connected("car\(n)")
            },
            awaitLoss: { _ in await neverLost.receive() ?? nil },
            release: { _ in
                releaseStarted.value = true
                _ = await gate.receive() // 정리가 끝나기 전에 사용자가 연결을 다시 누른 상황
            }
        )
        loop.start()
        await waitUntil("car1 connected") { loop.state == .connected("car1") }
        let stopping = Task { await loop.stop() }
        await waitUntil("release started") { releaseStarted.value }

        loop.start()
        await settle()
        // 이전 차량을 아직 정리하는 동안에는 새로 연결하지 않는다(차의 연결 슬롯은 3개뿐이다)
        XCTAssertEqual(calls.value, 1)
        gate.send(())
        await stopping.value

        await waitUntil("car2 connected") { loop.state == .connected("car2") }
        XCTAssertEqual(calls.value, 2)
        // 두 번째 차량의 정리도 끝낸다
        let finishing = Task { await loop.stop() }
        await waitUntil("second release started") { releaseStarted.value }
        gate.send(())
        await finishing.value
    }

    func testStartWhileConnectedDoesNothing() async {
        let f = Fixture([.connected("car1")])
        f.loop.start()
        await f.waitForState("car1 connected") { $0 == .connected("car1") }

        f.loop.start()
        await settle()

        XCTAssertEqual(f.connectCalls.value.count, 1)
        XCTAssertEqual(f.state, .connected("car1"))
        await f.loop.stop()
    }

    func testAThrowingConnectBecomesAFailureAndTheLoopGoesOn() async {
        let clock = ManualClock()
        let calls = Locked(0)
        let states = Locked<[LinkState<String>]>([])
        let neverLost = Mailbox<String?>()
        let loop = ConnectionLoop<String>(
            connect: {
                var n = 0
                calls.mutate { $0 += 1; n = $0 }
                if n == 1 { throw TestError(message: "boom") }
                return .connected("car")
            },
            awaitLoss: { _ in await neverLost.receive() ?? nil },
            release: { _ in },
            now: { clock.now },
            sleep: { try await clock.sleep($0) },
            onTransition: { s in states.mutate { $0.append(s) } }
        )
        loop.start()
        await waitUntil("backoff") { states.value.contains { if case .backoff = $0 { return true } else { return false } } }
        await clock.advanceToNextSleeper()
        await waitUntil("connected") { loop.state == .connected("car") }

        let first = states.value.compactMap { s -> String? in if case let .backoff(_, _, r) = s { return r } else { return nil } }.first
        XCTAssertEqual(first, "boom")
        await loop.stop()
    }

    func testBackoffPolicy() {
        let p = BackoffPolicy()
        XCTAssertEqual((1...7).map { p.delay(forFailures: $0) }, [2, 4, 8, 16, 30, 30, 30])
        XCTAssertEqual(p.delay(forFailures: 0), 2)
    }
}
