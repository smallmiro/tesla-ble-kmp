import Foundation

// 키 등록 흐름(`addKeyRequest` + 등록 확인)이 화면에 알리는 변화. 라이브러리(Kotlin) 타입이 없어 차량 없이 시험한다.
// Control 탭은 `line` 을 결과 줄로 쓰고, 키·제어 탭은 이 값으로 진행 팝업을 그린다(`05-key-control-tab.md` §Enrollment progress popup).

/// 등록 흐름의 한 단계. `BleClient.pair` 가 순서대로 보낸다.
enum EnrollmentUpdate: Sendable, Equatable {
    /// 시작하지 못했다(연결이나 키가 없다). 문장은 그대로 결과 줄이다.
    case blocked(String)
    case sent
    case waitingForTap
    case accepted
    case rejected(String)
    /// 모르는 응답(16진수 문자열).
    case unknown(String)
    case failed(String)
    /// 등록 요청이 시간 안에 끝나지 않았다. 등록 여부를 확인하러 넘어간다.
    case timedOut
    /// 등록이 확인됐다(`line` 은 결과 줄).
    case confirmed(String)
    /// 확인 시간(2분) 안에 등록이 확인되지 않았다.
    case notConfirmed(String)

    /// Control 탭 결과 줄에 쓰는 한 문장.
    var line: String {
        switch self {
        case .blocked(let text): return text
        case .sent: return "요청 보냄 — 중앙 콘솔에 키카드를 대고 화면에서 승인하세요"
        case .waitingForTap: return "카드 태그 대기 중"
        case .accepted: return "차량이 승인함 — Infotainment 동기화 확인 중"
        case .rejected(let why): return "거부: \(why)"
        case .unknown(let hex): return "모르는 응답 \(hex)"
        case .failed(let why): return "실패: \(why)"
        case .timedOut: return "시간 초과 — 등록 여부를 확인합니다"
        case .confirmed(let line), .notConfirmed(let line): return line
        }
    }
}
