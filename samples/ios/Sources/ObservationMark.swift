import Foundation

/// 관찰 목록에 넣는 사람 메모 줄(M4 Task 1B). 차량 요청은 보내지 않는다.
/// 줄 머리 `[HH:mm:ss +12.3s]`는 자동 관찰 줄과 같은 모양이다.
enum ObservationMark {
    static let maxLength = 80

    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    // 17자 VIN 패턴. 대소문자 모두 가린다(메모에 소문자로 적어도 새지 않게).
    private static let vin = try! NSRegularExpression(pattern: "[A-HJ-NPR-Z0-9]{17}", options: [.caseInsensitive])

    static func prefix(now: Date, since start: Date) -> String {
        "[\(clock.string(from: now)) +\(String(format: "%.1f", now.timeIntervalSince(start)))s]"
    }

    /// 빈 메모는 nil. 제어 문자는 공백으로, VIN 패턴은 앞 3자·뒤 4자만 남기고 가린 뒤 80자로 자른다.
    static func line(memo: String, now: Date, since start: Date) -> String? {
        let flat = String(memo.unicodeScalars.map { CharacterSet.controlCharacters.contains($0) || $0.value < 0x20 ? " " : Character($0) })
        let trimmed = flat.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        let text = String(mask(trimmed).prefix(maxLength))
        return "\(prefix(now: now, since: start)) [mark] \(text)"
    }

    private static func mask(_ text: String) -> String {
        var result = text
        let ns = text as NSString
        for m in vin.matches(in: text, range: NSRange(location: 0, length: ns.length)).reversed() {
            let s = ns.substring(with: m.range)
            let masked = String(s.prefix(3)) + String(repeating: "*", count: 10) + String(s.suffix(4))
            result = (result as NSString).replacingCharacters(in: m.range, with: masked)
        }
        return result
    }
}
