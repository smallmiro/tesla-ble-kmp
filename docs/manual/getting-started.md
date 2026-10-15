# 시작하기

이 문서는 [README.md](README.md) 기준 상대경로를 쓴다. M3 범위(스캔, 연결, 키 목록, 차량 상태 조회)를 다룬다. **키 등록(페어링)은 M4**에서 추가되므로, 이 단계에서는 차량에 이미 등록된 키가 없는 상태로 인증 없는 조회만 할 수 있다.

설치는 [README.md](README.md)의 "설치"를 따른다. 플랫폼별 권한과 제약은 [platform-notes.md](platform-notes.md), 오류 처리는 [errors.md](errors.md)를 본다.

## 흐름

`TeslaBle.create` → `keys.getOrCreate()` → `scan(vin)` 첫 광고 → `connect(vin, key)` → `vehicleStatus()` / `listKeys()` → `disconnect()`

모든 호출은 `VehicleResult`(성공 / 불확실 / 실패)를 돌려준다. `TeslaBle`은 앱 수명 동안 하나만 만든다.

## Kotlin (Android)

```kotlin
import io.github.smallmiro.teslable.*
import io.github.smallmiro.teslable.model.*
import io.github.smallmiro.teslable.port.*
import kotlinx.coroutines.flow.first

suspend fun demo(context: android.content.Context, vinText: String) {
    val vin = Vin.parseOrNull(vinText) ?: return              // 17자 형식 검사
    val ble = TeslaBle.create(
        PlatformContext(context),
        TeslaBleConfig.default(logger = DebugLogger()),         // 개발용 로거. 릴리스에서는 쓰지 않는다
    )
    try {
        val key = when (val r = ble.keys.getOrCreate()) {       // 기본 별칭, 하드웨어 우선
            is VehicleResult.Success -> r.value
            is VehicleResult.Uncertain -> return
            is VehicleResult.Failure -> return                  // r.error: KeyStoreError
        }

        // 첫 광고를 찾는다. 시간 제한은 호출자가 건다(예: withTimeoutOrNull).
        val event = ble.scan(vin).first()
        val target = (event as? ScanEvent.Found)?.advertisement ?: return   // Failed면 event.error

        val vehicle = when (val r = ble.connect(vin, key, target)) {
            is VehicleResult.Success -> r.value
            is VehicleResult.Uncertain -> return
            is VehicleResult.Failure -> return                  // r.error: TransportError 등
        }
        try {
            println(vehicle.vehicleStatus())                    // 앱에서는 println 대신 화면에 표시
            println(vehicle.listKeys())
        } finally {
            vehicle.disconnect()                                // 슬롯 반환. 링크가 끊긴 뒤에도 불러야 한다
        }
    } finally {
        ble.close()
    }
}
```

권한(`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`)은 호출 전에 앱이 요청한다 — [platform-notes.md](platform-notes.md) 2절.

## Swift (iOS)

iOS는 SKIE가 `suspend`를 `async throws`로, `Flow`를 `AsyncSequence`로 바꿔 준다. `VehicleResult`는 `onEnum(of:)`로 세 경우를 모두 다룬다. 아래는 샘플 앱의 `SampleModel.swift`와 같은 모양이다.

```swift
import Teslable

let ble = TeslaBle.companion.create(
    platform: PlatformContext(),
    config: TeslaBleConfig.companion.default(logger: DebugLogger())
)

func run(vinText: String) async {
    guard let vin = Vin.companion.parseOrNull(value: vinText) else { return }   // 형식 오류

    // 1) 키
    var key: VehicleKey?
    do {
        let result = try await ble.keys.getOrCreate(alias: VehicleKeyStoreCompanion.shared.DEFAULT_ALIAS, policy: .preferHardware)
        switch onEnum(of: result) {
        case .success(let s): key = s.value as? VehicleKey
        case .uncertain(let u): print("불확실: \(u.error.message)")
        case .failure(let f): print("실패: \(f.error.message)")
        }
    } catch { print("오류: \(error)") }

    // 2) 스캔 (첫 광고에서 멈춘다. 시간 제한은 Task 취소로 건다)
    for await event in ble.scan(vin: vin) {
        switch onEnum(of: event) {
        case .found(let f): print("\(f.advertisement.localName.value) rssi=\(f.advertisement.rssi)")
        case .failed(let e): print("스캔 실패: \(e.error.message)")
        }
        break
    }

    // 3) 연결 후 조회
    do {
        let connected = try await ble.connect(vin: vin, key: key, target: nil, options: ConnectOptions(timeout: nil))
        switch onEnum(of: connected) {
        case .success(let s):
            guard let vehicle = s.value as? Vehicle else { return }
            let status = try await vehicle.vehicleStatus(timeout: nil)
            switch onEnum(of: status) {
            case .success(let v): print("상태: \(String(describing: v.value))")
            case .uncertain(let u): print("불확실: \(u.error.message)")   // 재전송하지 말고 재조회
            case .failure(let f): print("실패: \(f.error.message)")
            }
            try await vehicle.disconnect()
        case .uncertain(let u): print("불확실: \(u.error.message)")
        case .failure(let f): print("실패: \(f.error.message)")
        }
    } catch { print("오류: \(error)") }
}
```

`target: nil`이면 `connect`가 직접 스캔한다. `Info.plist`의 `NSBluetoothAlwaysUsageDescription`이 필요하다 — [platform-notes.md](platform-notes.md) 3절.

## 다음 단계

- 키 등록(페어링): M4. 그 전까지 `connect(vin, key = null)`로도 `vehicleStatus()`와 `listKeys()`는 쓸 수 있다.
- 연결 상태는 `vehicle.connection`(`StateFlow`)으로 관찰한다. `Disconnected`가 와도 `disconnect()`는 직접 부른다.
