import Foundation

// 넓은 화면의 두 영역 첫 화면(SFR-024)에 쓰는 순수 규칙. 있는 값만 보여 주고, 없는 값(배터리, 공조, 위치, 모델명 등)은 만들지 않는다.

/// 잠금 말풍선: 지금 잠금 상태로 정하는 아이콘과 누르면 보낼 명령. 상태를 모르면 명령이 없다(누를 수 없다).
struct LockCallout: Equatable {
    let symbol: String
    let label: String
    let button: ControlButton?

    /// `raw` 는 방송이나 `vehicleStatus()` 의 잠금 이름(`ClusterText.lock` 과 같은 값).
    static func of(lock raw: String?) -> LockCallout {
        switch raw {
        case "LOCKED", "INTERNAL_LOCKED":
            return LockCallout(symbol: "lock.fill", label: ControlButton.unlock.title, button: .unlock)
        case "UNLOCKED", "SELECTIVE_UNLOCKED":
            return LockCallout(symbol: "lock.open.fill", label: ControlButton.lock.title, button: .lock)
        default:
            return LockCallout(symbol: "lock.fill", label: "잠금 상태를 모릅니다", button: nil)
        }
    }
}

/// 마지막 수동 Drive 조회(SFR-023)에서 속도와 주행거리를 읽은 요약. 자동으로 조회하지 않으므로 값마다 "몇 초 전 조회"를 붙인다.
/// 속도는 원시 숫자이고 단위는 확인되지 않았다(`14-lib-reading-state.md` A8.5): km/h 로 바꾸지 않는다.
struct DriveSummary: Equatable {
    let speedText: String
    /// 속도 값이 있을 때만 "단위 미확인".
    let unitNote: String?
    /// 조회한 지 얼마나 됐는지("12초 전 조회"). 조회한 적이 없으면 `nil`.
    let ageText: String?
    /// `odometer_in_hundredths_of_a_mile` 의 원시 값. 없으면 `nil`.
    let odometerText: String?

    init(reading: StateReading?, now: Date) {
        guard let reading, reading.category == .drive, reading.outcome == .success else {
            speedText = ClusterText.none
            unitNote = nil
            ageText = nil
            odometerText = nil
            return
        }
        let raw = reading.fields.first { $0.label == "speed" }?.value
        let suffix = " (\(StateDiagnostics.unitUnknown))"
        if let raw, raw != StateDiagnostics.absent {
            speedText = raw.hasSuffix(suffix) ? String(raw.dropLast(suffix.count)) : raw
            unitNote = StateDiagnostics.unitUnknown
        } else {
            speedText = ClusterText.none
            unitNote = nil
        }
        ageText = Formatting.ago(since: reading.at, now: now) + " 조회"
        let odometer = reading.fields.first { $0.label == "odometer_in_hundredths_of_a_mile" }?.value
        odometerText = odometer.flatMap { $0 == StateDiagnostics.absent ? nil : $0 }
    }
}

/// 왼쪽 머리글: 제목과 상태 한 줄.
enum HomeHeader {
    /// 이 폰 키의 별명이 있으면 그것, 없으면 "Tesla". VIN은 쓰지 않는다.
    static func title(ownKeyNickname: String?) -> String {
        let trimmed = ownKeyNickname?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "Tesla" : trimmed
    }

    /// 아는 것만 " · " 로 잇는다: 기어에서 온 상태(P 는 주차 중, R/N/D 는 주행 중), 잠금, 수면.
    static func statusLine(gear: ClusterGear?, lockText: String, sleepText: String) -> String {
        var parts: [String] = []
        switch gear {
        case .park?: parts.append("주차 중")
        case .reverse?, .neutral?, .drive?: parts.append("주행 중")
        case nil: break
        }
        for text in [lockText, sleepText] where text != ClusterText.none { parts.append(text) }
        return parts.joined(separator: " · ")
    }
}

/// 왼쪽 메뉴 줄의 한 줄 요약. 이미 있는 상태에서만 만든다.
enum MenuSummary {
    static func query(_ result: QueryResult?, now: Date) -> String {
        guard let result else { return "아직 조회하지 않았습니다" }
        return "\(result.outcome) · \(Formatting.clockText(result.at)) (\(Formatting.ago(since: result.at, now: now)))"
    }

    static func log(totalLines: Int) -> String {
        totalLines == 0 ? "아직 쓴 줄 없음" : "쓴 줄 \(totalLines)"
    }

    static func control(connected: Bool) -> String { connected ? "연결됨" : "연결 안 됨" }

    /// 끝나지 않은 첫 단계의 안내 문구. 네 단계가 모두 되면 "등록됨".
    static func keyControl(_ steps: KeyControlSteps) -> String {
        if steps.key.state != .done { return steps.key.hint }
        if steps.connect.state != .done { return steps.connect.hint }
        if steps.enroll.state != .done { return steps.enroll.hint }
        return "등록됨 · 제어할 수 있습니다"
    }

    static func liveDetail(lastEventAt: Date?) -> String {
        lastEventAt.map { "마지막 이벤트 \(Formatting.clockText($0))" } ?? "이벤트 없음"
    }
}

/// 왼쪽 메뉴의 카드 없는 줄: 차량 상태를 한 줄씩. 값은 클러스터가 이미 가진 것이고 없으면 `—`(지어내지 않는다).
struct HomeStatusRow: Equatable, Identifiable {
    let id: String
    let title: String
    let symbol: String
    let subtitle: String
}

enum HomeStatus {
    static func rows(state: ClusterState, drive: DriveSummary) -> [HomeStatusRow] {
        [
            HomeStatusRow(id: "lock", title: "잠금", symbol: "lock.fill", subtitle: state.lockText),
            HomeStatusRow(id: "closures", title: "문·트렁크", symbol: "car.fill", subtitle: state.closureSummary),
            HomeStatusRow(id: "sleep", title: "수면", symbol: "moon.fill", subtitle: state.sleepText),
            HomeStatusRow(id: "presence", title: "재실", symbol: "person.fill", subtitle: state.presenceText),
            HomeStatusRow(id: "drive", title: "주행", symbol: "steeringwheel", subtitle: driveText(gear: state.gear, drive: drive)),
        ]
    }

    /// 기어는 방송에서, 속도는 마지막 수동 Drive 조회에서(원시 값, 단위 미확인). 둘 다 없으면 `—`.
    static func driveText(gear: ClusterGear?, drive: DriveSummary) -> String {
        let speed = drive.unitNote.map { "\(drive.speedText) (\($0))" } ?? drive.speedText
        return "기어 \(gear?.letter ?? ClusterText.none) · 속도 \(speed)"
    }
}

/// 왼쪽 바닥 줄: 앱 빌드, 그리고 수동 Drive 조회가 가져온 주행거리가 있을 때만 그 값.
enum HomeFooter {
    static func lines(version: String, drive: DriveSummary) -> [String] {
        var lines = [version]
        if let odometer = drive.odometerText {
            // 단위는 필드 이름이 말하는 것(1/100마일)을 그대로 적고 바꾸지 않는다.
            lines.append("주행거리 \(odometer) (1/100마일)" + (drive.ageText.map { " · \($0)" } ?? ""))
        }
        return lines
    }
}
