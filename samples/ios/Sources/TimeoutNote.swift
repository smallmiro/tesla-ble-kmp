import Foundation

// `Timeout(afterSend)` 를 사람에게 설명하는 문구(SFR-018 명령 결과, SFR-023 상태 진단). 라이브러리(Kotlin) 타입이 없는 순수 규칙이다.
// 정본: `documents/md/16-lib-errors.md` 의 `Timeout(afterSend)` 행, §Retries and timeouts, §"Sent" means the write was started (ADR-0010).
//   - `afterSend == true`: 요청의 첫 BLE 쓰기가 시작된 뒤에 시간이 다했다. `Uncertain` 이고 `mayHaveSucceeded == true`.
//     명령은 차가 실행했을 수 있으므로 다시 보내지 않고 상태를 다시 조회한다. 백그라운드에서는 쓰기가 느려 이렇게 끝나는 일이 늘 수 있다.
//   - `afterSend == false`: 쓰기를 시작하기 전(앞선 명령·링크를 기다리는 중, 보내기 전 확인), 또는 차가 재시도를 요청한 뒤 기다리는 중에
//     시간이 다했다(핸드셰이크 시간 초과도 `afterSend == false`). `Failure` 이고 실행되지 않았다.

enum TimeoutNote {
    /// 명령이 "보냈을 수 있음" 일 때의 안내(차가 실행했을 수 있다).
    static let mayHaveBeenSent = "보냈을 수 있음 — 차가 실행했을 수 있으니 상태를 다시 조회하세요"
    /// 조회는 차를 바꾸지 않으므로 실행 여부가 아니라 값이 확인되지 않았다는 점을 말한다.
    static let readMayHaveBeenSent = "보냈을 수 있음 — 값은 확인되지 않았습니다. 조회는 차를 바꾸지 않으니 다시 조회하세요"
    static let backgroundSlow = "앱이 백그라운드에 있으면 BLE 쓰기가 느려져 시간이 다할 수 있습니다"
    static let notExecuted = "실행되지 않았습니다: 쓰기를 시작하기 전, 차가 재시도를 요청한 뒤 기다리는 중, 또는 핸드셰이크 중에 시간이 다했습니다"

    /// 명령(`lock`, `unlock`, 키 추가·삭제 등)의 `Timeout` 안내.
    static func command(afterSend: Bool) -> String {
        afterSend ? "\(mayHaveBeenSent). \(backgroundSlow)" : notExecuted
    }

    /// 조회(`getState` 등)의 `Timeout` 안내.
    static func read(afterSend: Bool) -> String {
        afterSend ? "\(readMayHaveBeenSent). \(backgroundSlow)" : notExecuted
    }

    /// 결과 한 줄: 라이브러리 `message` 에 `Timeout` 안내를 붙인다(명령이면 `command`, 조회(`read: true`)면 `read`).
    /// `Timeout` 이 아니면(`nil`) `message` 그대로.
    static func line(message: String, timeoutAfterSend: Bool?, read: Bool = false) -> String {
        guard let afterSend = timeoutAfterSend else { return message }
        return "\(message). \(read ? Self.read(afterSend: afterSend) : command(afterSend: afterSend))"
    }
}
