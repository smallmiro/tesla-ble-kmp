import Foundation

// SFR-020: 모니터 키(`teslable-monitor`)를 지우고 새로 만든다. 라이브러리(Kotlin) 타입이 없는 순수 규칙이라 차량 없이 시험한다.
// 근거: `13-lib-pairing.md` §Deleting or replacing a key locally, §Key accessibility(Locked 이면 지우거나 다시 만들지 않는다).
// 규칙의 정본: `samples/documents/md/05-key-control-tab.md` §Delete and recreate the key.

/// 지우기 전에 키 저장소를 읽어 본 결과(`keys.get(alias)`).
enum KeyProbe: Sendable, Equatable {
    case present
    case absent
    /// 폰 잠금 때문에 읽지 못했다(키는 멀쩡하다).
    case locked
    /// `KeyStoreError.PlatformFailure` 의 `stored key is corrupted`: 저장된 키가 손상됐다(`13-lib-pairing.md`, `16-lib-errors.md`).
    case corrupted
    case failed(String)
}

/// 손상된 키를 알아보는 규칙(문서에 적힌 `PlatformFailure` 의 `stored key is corrupted` 문구).
enum KeyStoreFailure {
    static let corruptedText = "stored key is corrupted"

    /// `PlatformFailure` 의 `cause` 와 `message` 중 하나라도 문서의 문구를 담으면 손상이다.
    static func isCorrupted(_ texts: String...) -> Bool {
        texts.contains { $0.contains(corruptedText) }
    }
}

enum KeyDeleteResult: Sendable, Equatable {
    case deleted
    case locked
    case failed(String)
}

enum KeyCreateResult: Sendable, Equatable {
    case created
    case locked
    case failed(String)
}

enum KeyReplaceOutcome: Sendable, Equatable {
    /// 옛 키를 지우고 새 키를 만들었다. 새 키는 차량에 등록되어 있지 않다.
    case replaced
    /// 폰 잠금: 아무것도 바꾸지 않았다.
    case locked
    /// 키를 지우지 못했다(또는 지울 수 있는지 읽지 못했다). 옛 키는 그대로다. 새 키를 만들지 않았다.
    case notDeleted(String)
    /// 옛 키는 지워졌는데 새 키를 만들지 못했다. 1단계에서 다시 만든다(조용히 다시 만들지 않는다).
    case notCreated(String)
    /// 지우기는 실패했다고 했는데 키 저장소에 키가 없다. 새 키를 만들지 않았고 다시 연결하지 않았다(연결하면 키가 조용히 만들어진다).
    case lost(String)
    /// 지우기가 실패했고 지금 키 상태도 읽지 못했다. 다시 연결하지 않았다.
    case unverified(String)

    var line: String {
        switch self {
        case .replaced:
            return "새 키를 만들었습니다. 차량에는 옛 공개키가 남아 있으니 필요하면 차량에서 지우세요. 3단계에서 새 키를 등록하세요"
        case .locked:
            return "키 저장소가 잠겨 있어 아무것도 바꾸지 않았습니다. 폰을 잠금 해제한 뒤 다시 누르세요"
        case .notDeleted(let why):
            return "키를 지우지 못했습니다(\(why)). 옛 키는 그대로이고 새 키를 만들지 않았습니다"
        case .notCreated(let why):
            return "옛 키는 지웠지만 새 키를 만들지 못했습니다(\(why)). 1단계에서 키를 만드세요"
        case .lost(let why):
            return "키를 지우지 못했다고 했지만(\(why)) 키 저장소에 키가 없습니다. 연결을 다시 만들지 않았습니다. 1단계에서 키를 만드세요"
        case .unverified(let why):
            return "키를 지우지 못했고(\(why)) 지금 키 상태도 읽지 못했습니다. 연결을 다시 만들지 않았습니다. 1단계의 '다시 불러오기'로 확인하세요"
        }
    }

    var tone: KeyControlModel.ResultLine.Tone {
        switch self {
        case .replaced: return .ok
        case .locked: return .warn
        case .notDeleted, .notCreated, .lost, .unverified: return .fail
        }
    }

    /// 등록 상태를 이 값으로 바꾼다(새 키는 차량에 없다). 바뀐 것이 없으면 `nil`.
    var enrollmentAfter: Enrollment? {
        if case .replaced = self { return .notEnrolled }
        return nil
    }

    /// 옛 키로 만든 연결 상태(상태 카드, 진행 줄)를 버려야 한다: 키를 한 번이라도 지웠다.
    var dropsConnectionState: Bool {
        switch self {
        case .replaced, .notCreated, .lost, .unverified: return true
        case .locked, .notDeleted: return false
        }
    }
}

/// 순서 규칙. 연결과 세션 캐시는 옛 키의 것이라 먼저 끊고(`13-lib-pairing.md`: 키를 지우거나 바꾸면 다음 `connect` 가 옛 캐시를 버리고
/// 다시 핸드셰이크한다), 키 핸들을 버린 뒤 지우고 만든다. 지우기에 실패하면 새 키를 만들지 않고 옛 키를 다시 읽는다.
enum KeyReplacer {
    static func run(
        wasActive: Bool,
        probe: () async -> KeyProbe,
        disconnect: () async -> (),
        forgetHandle: () -> (),
        delete: () async -> KeyDeleteResult,
        create: () async -> KeyCreateResult,
        restore: () async -> KeyProbe,
        reconnect: () -> ()
    ) async -> KeyReplaceOutcome {
        // 1) 잠겨 있거나 읽지 못하면 아무것도 건드리지 않는다(연결도 끊지 않는다).
        switch await probe() {
        case .locked: return .locked
        case .failed(let why): return .notDeleted(why)
        case .present, .absent, .corrupted: break // 손상된 키도 지우고 다시 만들 수 있다(읽을 수 없어도 된다)
        }
        // 2) 연결(과 그 세션)이 옛 키 것이라 먼저 끊는다. 쓰던 연결이 없으면 건드리지 않는다.
        if wasActive { await disconnect() }
        forgetHandle()
        // 3) 지운다. 실패하면 새 키를 만들지 않고 옛 키를 다시 읽어 상태를 되돌린다.
        let deleteFailure: (outcome: KeyReplaceOutcome, why: String)
        switch await delete() {
        case .deleted:
            deleteFailure = (.replaced, "")
        case .locked:
            deleteFailure = (.locked, "폰이 잠겨 있습니다")
        case .failed(let why):
            deleteFailure = (.notDeleted(why), why)
        }
        if deleteFailure.outcome != .replaced {
            // 실제로 키가 어떻게 됐는지 다시 읽고 그대로 알린다. 키가 없으면 다시 연결하지 않는다(`getOrCreate` 가 조용히 새 키를 만든다).
            switch await restore() {
            case .present:
                if wasActive { reconnect() }
                return deleteFailure.outcome
            case .locked:
                if wasActive { reconnect() }
                return .locked
            case .absent:
                return .lost(deleteFailure.why)
            case .failed, .corrupted:
                return .unverified(deleteFailure.why)
            }
        }
        // 4) 새 키를 한 번만 만든다. 실패해도 다시 시도하지 않는다.
        switch await create() {
        case .created:
            break
        case .locked:
            return .notCreated("폰이 잠겨 있습니다. 잠금 해제 뒤")
        case .failed(let why):
            return .notCreated(why)
        }
        // 5) 새 키로 다시 연결한다(새 연결은 옛 세션 캐시를 버린다).
        if wasActive { reconnect() }
        return .replaced
    }
}

enum KeyReplacement {
    /// 손상된 키용 확인 문구: 옛 키를 읽을 수 없고 지우면 복구할 수 없다.
    static func confirmation(corrupted: Bool) -> ControlButton.Confirmation {
        guard corrupted else { return confirmation }
        return ControlButton.Confirmation(
            title: "손상된 키를 삭제하고 다시 만들까요?",
            message: """
            이 폰에 저장된 모니터 키가 손상되어 읽을 수 없습니다. 지우면 옛 키는 복구할 수 없습니다(영구 삭제).
            차량에는 옛 공개키가 그대로 남아 있습니다. 차량에서 지우기 전까지 목록에 보입니다.
            새 키는 차량에 등록되어 있지 않아, 키카드로 다시 등록해야 합니다(3단계).
            쓰던 차량 연결은 끊고 새 키로 다시 연결합니다.
            """,
            action: "삭제 후 다시 만들기"
        )
    }

    /// 누르기 전에 보여 주는 확인 문구.
    static let confirmation = ControlButton.Confirmation(
        title: "키를 삭제하고 다시 만들까요?",
        message: """
        이 폰의 모니터 키를 영구히 지웁니다. 되돌릴 수 없습니다.
        차량에는 옛 공개키가 그대로 남아 있습니다. 차량에서 지우기 전까지 목록에 보입니다.
        새 키는 차량에 등록되어 있지 않아, 키카드로 다시 등록해야 합니다(3단계).
        쓰던 차량 연결은 끊고 새 키로 다시 연결합니다.
        """,
        action: "삭제 후 다시 만들기"
    )
}
