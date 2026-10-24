import Foundation

/// VIN 가리기(`04-monitor-app.md` §Log format 규칙). `[A-HJ-NPR-Z0-9]` 문자가 17자 이상 이어지는 ASCII 구간을 같은 길이로 바꾸되
/// 구간의 앞 3자와 뒤 4자는 남기고 나머지는 `*` 로 한다. 파일에 쓰기 전에 원본 바이트와 텍스트 모두에 적용한다.
/// 앞뒤로 VIN 문자가 더 붙어 있어도 구간 전체를 한 덩어리로 본다. Android 앱의 `VinMask` 와 같은 규칙이다.
enum VinMask {
    private static let vinLength = 17
    private static let keepHead = 3
    private static let keepTail = 4
    private static let mask = UInt8(ascii: "*")

    private static func isVinChar(_ b: UInt32) -> Bool {
        (b >= 0x30 && b <= 0x39) || (b >= 0x41 && b <= 0x5a && b != 0x49 && b != 0x4f && b != 0x51) // 0-9, A-Z 에서 I·O·Q 제외
    }

    /// 텍스트 안의 VIN 모양을 가린다.
    static func maskText(_ text: String) -> String {
        let scalars = Array(text.unicodeScalars)
        var out = String.UnicodeScalarView()
        var i = 0
        while i < scalars.count {
            guard isVinChar(scalars[i].value) else {
                out.append(scalars[i])
                i += 1
                continue
            }
            var end = i
            while end < scalars.count, isVinChar(scalars[end].value) { end += 1 }
            let run = scalars[i..<end]
            if run.count >= vinLength {
                for (k, s) in run.enumerated() {
                    let keep = k < keepHead || k >= run.count - keepTail
                    out.append(keep ? s : Unicode.Scalar(mask))
                }
            } else {
                out.append(contentsOf: run)
            }
            i = end
        }
        return String(out)
    }

    /// 원본 바이트에서 VIN 모양의 ASCII 구간을 같은 길이로 가린다. 입력은 바꾸지 않고 새 배열을 돌려준다.
    static func maskBytes(_ bytes: [UInt8]) -> [UInt8] {
        var out = bytes
        var i = 0
        while i < bytes.count {
            guard isVinChar(UInt32(bytes[i])) else {
                i += 1
                continue
            }
            var end = i
            while end < bytes.count, isVinChar(UInt32(bytes[end])) { end += 1 }
            if end - i >= vinLength {
                for k in (i + keepHead)..<(end - keepTail) { out[k] = mask }
            }
            i = end
        }
        return out
    }
}
