import Foundation

/// 파일 첫 줄 `session` 에 들어가는 앱·기기 정보.
struct SessionInfo: Sendable, Equatable {
    let platform: String
    let app: String
    let os: String
    let phone: String
}

/// `Vehicle.events` 의 `VehicleStatus` 를 화면과 로그가 쓰는 문자열로 옮긴 값. 값은 Kotlin enum 이름(`LOCKED`, `PARK` …)이다.
struct StatusSnapshot: Sendable, Equatable {
    var lock = "LOCKED"
    var gear = "PARK"
    var presence = "NOT_PRESENT"
    var sleep = "AWAKE"
    var frontDriverDoor = "CLOSED"
    var frontPassengerDoor = "CLOSED"
    var rearDriverDoor = "CLOSED"
    var rearPassengerDoor = "CLOSED"
    var frunk = "CLOSED"
    var trunk = "CLOSED"
    var chargePort = "CLOSED"
}

/// `VehicleEvent` 를 Kotlin 타입 없이 다루기 위한 값. 매핑은 `MonitorEvent.init(_:)`(Kotlin 쪽) 한 곳에서만 한다.
struct MonitorEvent: Sendable, Equatable {
    enum Kind: Sendable, Equatable {
        case status(StatusSnapshot)
        case authenticationRequest(level: String, reasons: [String])
        case unrecognized
    }

    let kind: Kind
    /// 받은 프레임 전체(VIN 가리기 전).
    let raw: [UInt8]
    let unknownFields: [String]
}

/// 로그 한 줄(JSON Lines). 줄 형식의 정본은 `samples/documents/md/04-monitor-app.md` §Log format 이고 Android 앱과 같은 이름·순서를 쓴다.
/// 만들 때 VIN 모양 텍스트와 원본 바이트의 VIN 은 이미 가려진다(`VinMask`).
struct LogLine: Sendable {
    private enum Value: Sendable {
        case string(String)
        case strings([String])
        case bool(Bool)
        case int(Int)
    }

    let kind: String
    /// `unrecognized: true` 인 event 줄인지(Log 탭의 카운터용).
    let unrecognized: Bool
    private let fields: [(String, Value)]

    static let memoMax = 80

    private init(kind: String, fields: [(String, Value)], unrecognized: Bool = false) {
        self.kind = kind
        self.fields = fields
        self.unrecognized = unrecognized
    }

    private static func make(_ ts: String, _ kind: String, _ rest: [(String, Value?)], unrecognized: Bool = false) -> LogLine {
        var fields: [(String, Value)] = [("ts", .string(ts)), ("kind", .string(kind))]
        for (name, value) in rest { if let value { fields.append((name, value)) } }
        return LogLine(kind: kind, fields: fields, unrecognized: unrecognized)
    }

    /// 한 줄 JSON(줄바꿈 없음). 필드 순서는 만든 순서 그대로다.
    func toJSON() -> String {
        var out = "{"
        for (i, field) in fields.enumerated() {
            if i > 0 { out += "," }
            out += Self.quote(field.0) + ":"
            switch field.1 {
            case .string(let s): out += Self.quote(s)
            case .strings(let list): out += "[" + list.map(Self.quote).joined(separator: ",") + "]"
            case .bool(let b): out += b ? "true" : "false"
            case .int(let n): out += String(n)
            }
        }
        return out + "}"
    }

    // MARK: - 줄 종류

    /// 파일의 첫 줄.
    static func session(ts: String, info: SessionInfo) -> LogLine {
        make(ts, "session", [("platform", .string(info.platform)), ("app", .string(info.app)), ("os", .string(info.os)), ("phone", .string(info.phone))])
    }

    /// 브로드캐스트 이벤트 한 줄. `raw` 는 VIN 을 가린 뒤의 소문자 16진수다.
    static func event(ts: String, _ event: MonitorEvent) -> LogLine {
        let (type, summary) = describe(event)
        var unrecognized = !event.unknownFields.isEmpty
        if case .unrecognized = event.kind { unrecognized = true }
        return make(ts, "event", [
            ("type", .string(type)),
            ("summary", .string(VinMask.maskText(summary))),
            ("unknownFields", .strings(event.unknownFields)),
            ("unrecognized", .bool(unrecognized)),
            ("raw", .string(hex(VinMask.maskBytes(event.raw)))),
        ], unrecognized: unrecognized)
    }

    /// 라이브러리 `observe` 줄. 라이브러리가 이미 VIN 을 가렸지만 한 번 더 가린다.
    static func observe(ts: String, text: String) -> LogLine {
        make(ts, "observe", [("line", .string(VinMask.maskText(text)))])
    }

    static func query(ts: String, call: String, result: String, summary: String) -> LogLine {
        make(ts, "query", [("call", .string(call)), ("result", .string(result)), ("summary", .string(VinMask.maskText(summary)))])
    }

    /// `state`: connecting, connected, disconnected, backoff, stopped. `reason`·`attempt`·`delayMs` 는 해당 없으면 뺀다.
    static func link(ts: String, state: String, reason: String? = nil, attempt: Int? = nil, delayMs: Int? = nil) -> LogLine {
        make(ts, "link", [
            ("state", .string(state)),
            ("reason", reason.map { .string(VinMask.maskText($0)) }),
            ("attempt", attempt.map { .int($0) }),
            ("delayMs", delayMs.map { .int($0) }),
        ])
    }

    /// 메모. 앞뒤 공백을 뗀 뒤 VIN 을 가리고 80자로 자른다(가린 뒤에 잘라야 잘린 VIN 조각이 남지 않는다). 비어 있으면 `nil`.
    static func mark(ts: String, memo: String) -> LogLine? {
        let text = String(VinMask.maskText(memo.trimmingCharacters(in: .whitespacesAndNewlines)).prefix(memoMax))
        return text.isEmpty ? nil : make(ts, "mark", [("memo", .string(text))])
    }

    // MARK: - 요약

    /// 이벤트의 `Status` 요약(`vehicleStatus()` 조회 요약과 같은 모양). 앞의 세 항목 순서는 04-monitor-app.md 예시와 같다.
    static func statusSummary(_ s: StatusSnapshot) -> String {
        "lock=\(s.lock) gear=\(s.gear) presence=\(s.presence) sleep=\(s.sleep) "
            + "doors=\(s.frontDriverDoor)/\(s.frontPassengerDoor)/\(s.rearDriverDoor)/\(s.rearPassengerDoor) "
            + "frunk=\(s.frunk) trunk=\(s.trunk) chargePort=\(s.chargePort)"
    }

    /// Kotlin `List.toString()` 모양(`[a, b]`). Android 로그와 같은 글자를 쓴다.
    static func listText(_ items: [String]) -> String { "[" + items.joined(separator: ", ") + "]" }

    private static func describe(_ event: MonitorEvent) -> (String, String) {
        switch event.kind {
        case .status(let s): return ("Status", statusSummary(s))
        case .authenticationRequest(let level, let reasons): return ("AuthenticationRequest", "level=\(level) reasons=\(listText(reasons))")
        case .unrecognized: return ("Unrecognized", "unknownFields=\(listText(event.unknownFields))")
        }
    }

    private static func hex(_ bytes: [UInt8]) -> String {
        let digits = Array("0123456789abcdef")
        var out = ""
        out.reserveCapacity(bytes.count * 2)
        for b in bytes {
            out.append(digits[Int(b >> 4)])
            out.append(digits[Int(b & 0x0f)])
        }
        return out
    }

    /// JSON 문자열 리터럴. 따옴표, 역슬래시, 줄바꿈, 제어 문자를 이스케이프한다(한 줄을 유지하려면 필수).
    private static func quote(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 { out += String(format: "\\u%04x", scalar.value) } else { out.unicodeScalars.append(scalar) }
            }
        }
        return out + "\""
    }
}
