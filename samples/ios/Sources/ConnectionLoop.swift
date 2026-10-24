import Foundation

/// 연결 시도 한 번의 결과. 실패 이유는 사람이 읽는 문장이다(로그의 `link.reason`).
enum ConnectOutcome<V: Sendable>: Sendable {
    case connected(V)
    case failed(String)
}

/// 자동 재연결 상태 기계의 상태.
enum LinkState<V: Sendable & Equatable>: Sendable, Equatable {
    /// 아직 아무것도 시작하지 않았다.
    case idle
    /// `attempt` 번째 연결 시도 중(시작이나 링크 끊김 뒤 1부터).
    case connecting(Int)
    case connected(V)
    /// 연속 `attempt` 번째 실패(링크 끊김 포함) 뒤 `delay` 초 쉬는 중.
    case backoff(attempt: Int, delay: TimeInterval, reason: String?)
    /// 사용자가 끊었다. 다시 연결 버튼을 누를 때까지 재연결하지 않는다(SFR-012).
    case stopped
}

/// 2초에서 시작해 두 배씩 늘어 최대 30초(SFR-012).
struct BackoffPolicy: Sendable {
    var initial: TimeInterval = 2
    var max: TimeInterval = 30

    /// 연속 `failures` 번째 실패(1부터) 뒤의 대기 시간.
    func delay(forFailures failures: Int) -> TimeInterval {
        var d = initial
        for _ in 0..<Swift.max(0, failures - 1) { d = Swift.min(d * 2, max) }
        return Swift.min(d, max)
    }
}

/// 한 번만 열리는 문(재연결 대기와 주기 조회 대기가 쓴다). `fire()` 가 `wait()` 보다 먼저 와도 기억한다.
final class Gate: @unchecked Sendable {
    private let lock = NSLock()
    private var fired = false
    private var continuation: CheckedContinuation<Void, Never>?

    func fire() {
        lock.lock()
        fired = true
        let c = continuation
        continuation = nil
        lock.unlock()
        c?.resume()
    }

    func wait() async {
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            lock.lock()
            if fired {
                lock.unlock()
                c.resume()
            } else {
                continuation = c
                lock.unlock()
            }
        }
    }
}

/// 연결 유지 상태 기계(SFR-012). 차량 타입 `V` 는 모른다: 연결은 `connect` 가, 링크 끊김 대기는 `awaitLoss` 가, 정리는 `release` 가 한다
/// (실제 앱에서는 `TeslaBle.connect`, `Vehicle.connection` 이 `Disconnected` 가 될 때까지 기다리기, `Vehicle.disconnect()`).
/// Android 앱의 `ConnectionLoop` 와 같은 동작이다.
///
/// - `start()`: 연결 버튼과 앱 시작 자동 연결. 쉬는 중이면 기다리지 않고 바로 시도한다. 연결 중이거나 연결돼 있으면 아무 일도 없다.
/// - `stop()`: 연결 끊기 버튼. 진행 중인 시도를 취소하고 차량을 `release` 한 뒤 `.stopped`. 이후 `start()` 를 누를 때까지 재연결하지 않는다.
/// - 연결이 `stableAfter` 이상 유지됐다가 끊기면 대기 시간이 처음(2초)부터 다시 시작한다. 그보다 빨리 끊기면 이어서 늘어난다.
/// - 이전 차량의 `release` 가 끝나기 전에는 새로 연결하지 않는다(차의 연결 슬롯은 3개뿐이다).
/// - 시계(`now`)와 쉬기(`sleep`)는 바꿔 끼울 수 있다. 시험은 가짜 시계로 실제 시간을 기다리지 않는다.
final class ConnectionLoop<V: Sendable & Equatable>: @unchecked Sendable {
    typealias Connect = @Sendable () async throws -> ConnectOutcome<V>
    typealias AwaitLoss = @Sendable (V) async -> String?
    typealias Release = @Sendable (V) async -> Void

    static var systemNow: @Sendable () -> TimeInterval { { ProcessInfo.processInfo.systemUptime } }
    static var systemSleep: @Sendable (TimeInterval) async throws -> Void { { try await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) } }

    private let connect: Connect
    private let awaitLoss: AwaitLoss
    private let release: Release
    private let backoff: BackoffPolicy
    private let now: @Sendable () -> TimeInterval
    private let sleep: @Sendable (TimeInterval) async throws -> Void
    private let stableAfter: TimeInterval
    private let onTransition: @Sendable (LinkState<V>) -> Void

    private let lock = NSLock()
    private let transitionLock = NSLock()
    private var current: LinkState<V> = .idle
    private var job: Task<Void, Never>?
    /// 취소했지만 아직 정리(`release`)가 끝나지 않았을 수 있는 이전 루프들. 새 루프는 이것이 끝난 뒤에야 연결한다.
    private var teardown: Task<Void, Never>?
    private var gate: Gate?

    init(
        connect: @escaping Connect,
        awaitLoss: @escaping AwaitLoss,
        release: @escaping Release,
        backoff: BackoffPolicy = BackoffPolicy(),
        now: @escaping @Sendable () -> TimeInterval = ConnectionLoop.systemNow,
        sleep: @escaping @Sendable (TimeInterval) async throws -> Void = ConnectionLoop.systemSleep,
        stableAfter: TimeInterval = 10,
        onTransition: @escaping @Sendable (LinkState<V>) -> Void = { _ in }
    ) {
        self.connect = connect
        self.awaitLoss = awaitLoss
        self.release = release
        self.backoff = backoff
        self.now = now
        self.sleep = sleep
        self.stableAfter = stableAfter
        self.onTransition = onTransition
    }

    var state: LinkState<V> {
        lock.lock(); defer { lock.unlock() }
        return current
    }

    func start() {
        lock.lock()
        if job != nil {
            // 쉬기 문이 놓여 있으면(Backoff 를 알리기 직전 포함) 기다리지 않고 바로 시도한다.
            let waiting = gate
            lock.unlock()
            waiting?.fire()
            return
        }
        let previous = teardown
        job = Task { [self] in
            // 이전 차량의 disconnect() 가 끝나기 전에 새로 연결하지 않는다.
            await previous?.value
            if Task.isCancelled { return }
            await run()
        }
        lock.unlock()
    }

    func stop() async {
        let waiting: Task<Void, Never>? = lock.withLock {
            let running = job
            job = nil
            if let running {
                running.cancel()
                let older = teardown
                teardown = Task {
                    await older?.value
                    await running.value
                }
            }
            return teardown
        }
        await waiting?.value
        // 정리하는 동안 사용자가 연결을 다시 눌렀으면(start() 가 새 루프를 띄웠다) 그 루프의 상태를 덮어쓰지 않는다.
        // 확인과 발행을 한 덩어리로 해서(transitionLock) 새 루프의 `connecting` 이 `stopped` 사이에 끼어들지 못하게 한다.
        publish(.stopped, onlyIfNoRunningLoop: true)
    }

    /// 상태 바꿈과 콜백을 한 줄로 세운다(콜백은 짧고 이 객체를 다시 부르지 않는다).
    private func publish(_ next: LinkState<V>, onlyIfNoRunningLoop: Bool = false) {
        transitionLock.lock()
        defer { transitionLock.unlock() }
        let allowed: Bool = lock.withLock {
            if onlyIfNoRunningLoop && job != nil { return false }
            // 겹친 stop() 두 번(끊기 + VIN 지우기)이 `stopped` 를 두 번 알리지 않게 한다.
            if current == next, case .stopped = next { return false }
            current = next
            return true
        }
        if allowed { onTransition(next) }
    }

    private func run() async {
        var tries = 0
        var failures = 0
        while !Task.isCancelled {
            tries += 1
            publish(.connecting(tries))
            var reason: String?
            switch await attempt() {
            case .failed(let why):
                failures += 1
                reason = why
            case .connected(let vehicle):
                if Task.isCancelled {
                    await releaseUnstoppable(vehicle)
                    return
                }
                publish(.connected(vehicle))
                let since = now()
                reason = await awaitLoss(vehicle)
                // 링크가 끊겨도, 사용자가 끊어도 차량은 반드시 정리한다(Vehicle.disconnect()).
                await releaseUnstoppable(vehicle)
                if Task.isCancelled { return }
                failures = (now() - since >= stableAfter) ? 1 : failures + 1
                tries = 0
            }
            if Task.isCancelled { return }
            let delay = backoff.delay(forFailures: failures)
            await rest(delay: delay, attempt: failures, reason: reason)
        }
    }

    /// 취소돼도 끝까지 가야 하는 정리. 새 Task 는 부모의 취소를 물려받지 않는다.
    private func releaseUnstoppable(_ vehicle: V) async {
        let release = self.release
        await Task { await release(vehicle) }.value
    }

    /// 쉬는 동안(`Backoff`). `start()` 가 문을 열면 기다림을 끝낸다.
    private func rest(delay: TimeInterval, attempt: Int, reason: String?) async {
        let door = Gate()
        lock.withLock { gate = door }
        publish(.backoff(attempt: attempt, delay: delay, reason: reason))
        let sleep = self.sleep
        let timer = Task {
            try? await sleep(delay)
            door.fire()
        }
        await withTaskCancellationHandler { await door.wait() } onCancel: { door.fire() }
        timer.cancel()
        lock.withLock { gate = nil }
    }

    /// `connect` 가 던진 오류(취소 제외)는 실패로 바꿔 루프가 죽지 않게 한다.
    private func attempt() async -> ConnectOutcome<V> {
        do {
            return try await connect()
        } catch is CancellationError {
            return .failed("cancelled")
        } catch {
            return .failed(error.localizedDescription)
        }
    }
}
