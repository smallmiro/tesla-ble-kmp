import Foundation
import XCTest

/// 손으로 움직이는 시계. `sleep`은 시간이 흐를 때까지 멈춘다(취소하면 `CancellationError`).
/// 연결 루프 시험이 실제 시간을 기다리지 않게 한다.
final class ManualClock: @unchecked Sendable {
    private struct Sleeper {
        let id: Int
        let deadline: TimeInterval
        let continuation: CheckedContinuation<Void, Error>
    }

    private let lock = NSLock()
    private var current: TimeInterval = 0
    private var sleepers: [Sleeper] = []
    private var nextID = 0
    private var cancelledEarly = Set<Int>()

    var now: TimeInterval {
        lock.lock(); defer { lock.unlock() }
        return current
    }

    var sleeperCount: Int {
        lock.lock(); defer { lock.unlock() }
        return sleepers.count
    }

    func sleep(_ seconds: TimeInterval) async throws {
        let id = lock.withLock { () -> Int in nextID += 1; return nextID }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                lock.lock()
                if cancelledEarly.remove(id) != nil {
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                } else {
                    sleepers.append(Sleeper(id: id, deadline: current + seconds, continuation: continuation))
                    lock.unlock()
                }
            }
        } onCancel: {
            lock.lock()
            if let index = sleepers.firstIndex(where: { $0.id == id }) {
                let sleeper = sleepers.remove(at: index)
                lock.unlock()
                sleeper.continuation.resume(throwing: CancellationError())
            } else {
                cancelledEarly.insert(id)
                lock.unlock()
            }
        }
    }

    /// 시간을 [seconds]만큼 보내고 그동안 만기가 된 잠자는 쪽을 깨운다.
    func advance(by seconds: TimeInterval) {
        lock.lock()
        current += seconds
        let due = sleepers.filter { $0.deadline <= current }
        sleepers.removeAll { $0.deadline <= current }
        lock.unlock()
        due.sorted { $0.deadline < $1.deadline }.forEach { $0.continuation.resume() }
    }

    /// 잠자는 쪽이 생길 때까지 기다렸다가 가장 이른 만기까지 시간을 보낸다.
    func advanceToNextSleeper(file: StaticString = #filePath, line: UInt = #line) async {
        await waitUntil("a sleeper", file: file, line: line) { self.sleeperCount > 0 }
        let delta = lock.withLock { max(0, (sleepers.map(\.deadline).min() ?? current) - current) }
        advance(by: delta)
    }
}

/// 조건이 참이 될 때까지 짧게 쉬며 기다린다(최대 5초). 시험이 실제 시간에 기대지 않고 동작이 끝나기만 기다린다.
func waitUntil(
    _ what: String = "condition",
    timeout: TimeInterval = 5,
    file: StaticString = #filePath,
    line: UInt = #line,
    _ condition: @escaping @Sendable () -> Bool
) async {
    let deadline = Date().addingTimeInterval(timeout)
    while !condition() {
        if Date() > deadline {
            XCTFail("timed out waiting for \(what)", file: file, line: line)
            return
        }
        try? await Task.sleep(nanoseconds: 1_000_000)
    }
}

/// 아무 일도 일어나지 않았음을 확인하기 전에 다른 작업이 돌 시간을 준다.
func settle() async {
    for _ in 0..<5 { try? await Task.sleep(nanoseconds: 10_000_000) }
}

/// 값을 보내면 기다리는 쪽이 받는 우편함. 기다리는 쪽이 취소되면 `nil`을 돌려준다.
final class Mailbox<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [T] = []
    private var waiter: (id: Int, continuation: CheckedContinuation<T?, Never>)?
    private var nextID = 0
    private var cancelledEarly = Set<Int>()

    func send(_ value: T) {
        lock.lock()
        if let w = waiter {
            waiter = nil
            lock.unlock()
            w.continuation.resume(returning: value)
        } else {
            items.append(value)
            lock.unlock()
        }
    }

    func receive() async -> T? {
        let id = lock.withLock { () -> Int in nextID += 1; return nextID }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<T?, Never>) in
                lock.lock()
                if !items.isEmpty {
                    let value = items.removeFirst()
                    lock.unlock()
                    continuation.resume(returning: value)
                } else if cancelledEarly.remove(id) != nil {
                    lock.unlock()
                    continuation.resume(returning: nil)
                } else {
                    waiter = (id, continuation)
                    lock.unlock()
                }
            }
        } onCancel: {
            lock.lock()
            if let w = waiter, w.id == id {
                waiter = nil
                lock.unlock()
                w.continuation.resume(returning: nil)
            } else {
                cancelledEarly.insert(id)
                lock.unlock()
            }
        }
    }
}

/// 여러 스레드에서 쓰는 값 상자(시험용).
final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: Value

    init(_ value: Value) { stored = value }

    var value: Value {
        get { lock.lock(); defer { lock.unlock() }; return stored }
        set { lock.lock(); defer { lock.unlock() }; stored = newValue }
    }

    func mutate(_ body: (inout Value) -> Void) {
        lock.lock(); defer { lock.unlock() }
        body(&stored)
    }
}
