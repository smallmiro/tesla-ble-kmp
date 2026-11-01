import Foundation

/// 연결 직후 모니터의 첫 조회(`vehicleStatus`·`keySummary`·`listKeys`)가 끝날 때까지 다른 `listKeys()` 를 늦추는 문.
/// 연결마다 `arm()` 하고 첫 조회가 끝나면 `open()` 한다. 연결이 끝나는 쪽에서도 `open()` 해서 기다리는 쪽이 매달리지 않게 한다.
/// `arm()` 하지 않았거나 이미 열렸으면 `wait()` 는 바로 돌아온다.
final class InitialQueryGate: @unchecked Sendable {
    private let lock = NSLock()
    private var pending: Gate?

    /// 새 연결의 시작. 옛 연결을 기다리던 쪽은 풀어 준다.
    func arm() {
        let old: Gate? = lock.withLock {
            let previous = pending
            pending = Gate()
            return previous
        }
        old?.fire()
    }

    func open() {
        let current: Gate? = lock.withLock {
            let current = pending
            pending = nil
            return current
        }
        current?.fire()
    }

    func wait() async {
        let current = lock.withLock { pending }
        await current?.wait()
    }
}
