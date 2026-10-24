import XCTest
@testable import TeslableSample

final class VinMaskTests: XCTestCase {
    // 실제 VIN이 아닌 가짜 값(형식만 같다). 17자, I·O·Q 없음.
    private let vin = "5YJS0000000000000"
    private let masked = "5YJ**********0000"

    private func ascii(_ s: String) -> [UInt8] { Array(s.utf8) }

    func testMasksVinInBytesKeepingLength() {
        let raw: [UInt8] = [0x0a, 0x11] + ascii(vin) + [0x00, 0x7f, 0x01]

        let out = VinMask.maskBytes(raw)

        XCTAssertEqual(out.count, raw.count)
        XCTAssertEqual(out, [0x0a, 0x11] + ascii(masked) + [0x00, 0x7f, 0x01])
    }

    func testBytesWithoutVinAreUnchanged() {
        // 16자, 그리고 I가 든 17자
        let raw: [UInt8] = [1, 2, 3, 0x00] + ascii("AAAAAAAAAAAAAAAA") + [0x00] + ascii("AAAAIAAAAAAAAAAAA")
        XCTAssertEqual(VinMask.maskBytes(raw), raw)
    }

    func testBytesAbove0x7fAreNotVinCharacters() {
        let raw: [UInt8] = ascii("5YJS000000") + [0xc3] + ascii("0000000")
        XCTAssertEqual(VinMask.maskBytes(raw), raw)
    }

    func testMasksVinInText() {
        XCTAssertEqual(VinMask.maskText("vin=\(vin) end"), "vin=\(masked) end")
    }

    func testMasksEveryVinInText() {
        XCTAssertEqual(VinMask.maskText("\(vin) \(vin)"), "\(masked) \(masked)")
    }

    func testTextWithoutVinIsUnchanged() {
        let text = "[drop:no-handler] 83B from=domain(DOMAIN_VEHICLE_SECURITY) 5YJ**********0000 lowercase5yjs0000000000000 IOQIOQIOQIOQIOQIO 한글"
        XCTAssertEqual(VinMask.maskText(text), text)
    }

    /// 앞뒤에 VIN 문자가 붙어 있어도 구간 전체를 가리고 구간의 앞 3자, 뒤 4자만 남긴다.
    private func assertRunMasked(_ input: String, _ output: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(output.count, input.count, file: file, line: line)
        XCTAssertEqual(output.prefix(3), input.prefix(3), file: file, line: line)
        XCTAssertEqual(output.suffix(4), input.suffix(4), file: file, line: line)
        let middle = output.dropFirst(3).dropLast(4)
        XCTAssertEqual(String(middle), String(repeating: "*", count: input.count - 7), file: file, line: line)
    }

    func testTextVinWithVinCharactersAroundIsMaskedAsOneRun() {
        let run = "XX\(vin)YY"
        assertRunMasked(run, VinMask.maskText(run))
        assertRunMasked("A\(vin)", VinMask.maskText("A\(vin)"))
        assertRunMasked("\(vin)Z", VinMask.maskText("\(vin)Z"))
        XCTAssertEqual(VinMask.maskText("a b \(run) c"), "a b \(VinMask.maskText(run)) c")
    }

    func testBytesVinWithVinCharactersAroundIsMaskedAsOneRun() {
        let run = "XX\(vin)YY"
        let raw: [UInt8] = [0x01] + ascii(run) + [0x02]

        let out = VinMask.maskBytes(raw)

        XCTAssertEqual(out.count, raw.count)
        XCTAssertEqual(out.first, 0x01)
        XCTAssertEqual(out.last, 0x02)
        assertRunMasked(run, String(decoding: out[1..<(out.count - 1)], as: UTF8.self))
    }

    func testARunOfExactly17IsStillMasked() {
        XCTAssertEqual(VinMask.maskText(vin), masked)
        XCTAssertEqual(VinMask.maskBytes(ascii(vin)).count, 17)
    }
}
