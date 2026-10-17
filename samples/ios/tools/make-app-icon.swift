// samples/ios/tools/make-app-icon.swift — 샘플 앱 아이콘(1024×1024 PNG)을 그린다. 일반 도형(열쇠, 신호 물결)만 쓴다 — 블루투스·테슬라 로고 등 상표 금지.
// 사용: swift samples/ios/tools/make-app-icon.swift samples/ios/Sources/Assets.xcassets/AppIcon.appiconset/AppIcon.png
import AppKit

let size = 1024.0
let output = CommandLine.arguments.dropFirst().first ?? "AppIcon.png"
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: Int(size), height: Int(size), bitsPerComponent: 8, bytesPerRow: 0,
                    space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!

// 배경: 남색 → 청록 대각선 그라디언트 (iOS가 모서리를 둥글게 자르므로 사각형을 꽉 채운다)
let gradient = CGGradient(colorsSpace: space, colors: [
    CGColor(srgbRed: 0.07, green: 0.10, blue: 0.24, alpha: 1),
    CGColor(srgbRed: 0.05, green: 0.45, blue: 0.60, alpha: 1),
] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: size), end: CGPoint(x: size, y: 0), options: [])

// 열쇠(머리 링 + 몸통 + 이 두 개)와 오른쪽 위 신호 물결 세 줄 — 일반 도형만 쓴다(상표 없음)
ctx.setStrokeColor(.white)
ctx.setFillColor(.white)
ctx.setLineCap(.round)
ctx.setLineJoin(.round)
// 열쇠를 45도 기울여 그린다
ctx.saveGState()
ctx.translateBy(x: 470, y: 470)
ctx.rotate(by: .pi / 4)
ctx.setLineWidth(56)
ctx.strokeEllipse(in: CGRect(x: -330, y: -110, width: 220, height: 220))   // 머리
ctx.fill(CGRect(x: -112, y: -28, width: 420, height: 56))                  // 몸통
ctx.fill(CGRect(x: 200, y: -128, width: 52, height: 110))                  // 이 1
ctx.fill(CGRect(x: 110, y: -108, width: 52, height: 90))                   // 이 2
ctx.restoreGState()
// 신호 물결 두 줄(오른쪽 위, 열쇠 끝과 겹치지 않게)
ctx.setLineWidth(40)
for (index, radius) in [230.0, 320.0].enumerated() {
    ctx.setStrokeColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 0.85 - Double(index) * 0.3))
    ctx.addArc(center: CGPoint(x: 600, y: 600), radius: radius, startAngle: .pi / 12, endAngle: .pi * 5 / 12, clockwise: false)
    ctx.strokePath()
}

let image = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: image)
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: output))
print("wrote \(output)")
