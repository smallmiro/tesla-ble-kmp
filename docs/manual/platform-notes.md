# 플랫폼 노트 (Android / iOS)

이 문서는 [README.md](README.md) 기준 상대경로를 쓴다. M3 기준이며, 실기기 확인이 끝나지 않은 항목은 그렇게 표시한다.

## 1. 지원 범위

- Android 12 (API 31) 이상, iOS 16 이상. 앱이 **포그라운드**에 있을 때만 쓴다는 전제다(백그라운드 BLE는 범위 밖).
- JVM 타깃은 테스트·실험용이다. BLE는 btleplug 계열 경로이고, 키 보관소는 메모리라 **프로세스가 끝나면 사라진다**.

## 2. Android

- 권한: `BLUETOOTH_SCAN`(`neverForLocation`)과 `BLUETOOTH_CONNECT`를 매니페스트에 선언하고 **런타임에 요청**한다. 허용되지 않은 채 스캔·연결하면 `TransportError.PermissionDenied`가 돌아온다. `INTERNET` 권한은 필요 없다(서버 호출 없음).
- 연결 시 `requestMtu(517)`을 요청하고, 실패하면 블록 길이 20으로 계속한다.
- 키 보관소 별칭: Android Keystore 항목은 `teslable.key:<alias>`(키), `teslable.wrap:<alias>`(소프트웨어 대체 키를 감싸는 AES 키)로 **네임스페이스가 붙는다**. 앱의 다른 Keystore 별칭과 충돌하지 않는다.
- 자동 백업 주의: 소프트웨어 대체 키는 `filesDir/teslable/keys/<alias>.enc` 파일에 저장된다. Android 자동 백업(Auto Backup)이 이 파일만 복원하고 Keystore 래핑 키(`teslable.wrap:…`)는 복원하지 못하면, 복원된 키는 **손상된 키**로 읽힌다(4절). 앱은 `dataExtractionRules`(Android 12+) 또는 `fullBackupContent`에서 `files/teslable/keys/` 를 백업에서 **제외**해야 한다.
- 세션 캐시도 `filesDir/teslable/sessions` 아래에 있다. 캐시는 잃어도 다음 연결에서 핸드셰이크로 복구되지만, 같은 방식으로 제외하는 편이 깔끔하다.

## 3. iOS

- `Info.plist`에 `NSBluetoothAlwaysUsageDescription`이 필요하다. **첫 스캔이 권한 창을 띄운다.** 거부하면 `TransportError.PermissionDenied`.
- 응답 특성(0213) 구독 방식(notify/indicate)은 ADR-0003 스파이크로 실기기에서 확인하는 항목이다(결과는 해당 ADR에 기록된다). iOS에는 MTU 협상 API가 없어 시스템이 정한 쓰기 길이를 쓴다.
- 시뮬레이터에는 블루투스가 없다. 스캔·연결은 실기기(iPhone)에서만 확인할 수 있다.

## 4. 키 보관

`VehicleKey.protection`의 뜻:

| 값 | 의미 |
|---|---|
| `SECURE_ENCLAVE` | iOS Secure Enclave. 개인키가 칩 밖으로 나오지 않는다 |
| `STRONGBOX` | Android StrongBox 보안 칩 |
| `TEE` | Android 신뢰 실행 환경(Trusted Execution Environment) |
| `SOFTWARE` | 소프트웨어 키. 에뮬레이터·시뮬레이터, 또는 하드웨어 생성이 모두 실패했을 때 |

- 소프트웨어 키의 저장 형식: 개인키는 `04‖X‖Y‖d`(97바이트)로 직렬화한다. Android는 Keystore AES 키로 감싼 `TBK1` 파일, iOS는 Keychain generic password(`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`)다.
- **손상된 저장 키는 자동으로 교체하지 않는다.** `getOrCreate`/`get`이 `KeyStoreError.PlatformFailure`("stored key is corrupted")를 돌려주면, 앱이 사용자에게 알린 뒤 `keys.delete(alias)` → `keys.getOrCreate(alias)`로 다시 만든다. 새 키는 공개키가 다르므로 **차량에 다시 등록해야 한다**. 자동 교체를 하지 않는 이유는 조용히 키가 바뀌면 사용자가 원인을 모른 채 차량 제어를 잃기 때문이다.
- iOS에서 Secure Enclave 조회 자체가 실패한 경우(`Failed`)는 "키 없음"이 아니다. 같은 별칭으로 새 키를 만들어 덮어쓰지 않고 오류로 돌려준다.
- 개인키를 내보내는 API는 없다(ADR-0008).
- 시뮬레이터 Keychain: 시뮬레이터에서 도는 **순수 Kotlin/Native 테스트 바이너리**는 Keychain을 쓸 수 없다(`SecItemAdd`가 -25291로 실패). 그래서 실제 Keychain 왕복은 단위 테스트가 아니라 **샘플 앱**(앱 번들로 실행)에서 확인한다.

## 5. 세션 캐시

- 위치: Android `filesDir/teslable/sessions/`, iOS Keychain(`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`). 파일·항목 이름은 VIN의 해시라 VIN 원문은 저장되지 않는다.
- 캐시는 클라이언트 키에 종속된다. **키를 바꾸면 다음 연결에서 캐시를 자동 폐기**하고 새로 핸드셰이크한다. 차량 쪽 키 교체 등으로 오래된 캐시가 되면 이것도 자동으로 지운다.
- 캐시가 없거나 낡아도 오류가 아니다. 세션을 새로 맺을 뿐이다.

## 6. 시계

- 세션 시계는 단조 시계(`TimeSource.Monotonic`)로 흐른다. 기기가 **수면**에 들면 일부 플랫폼에서 단조 시계가 멈추므로, 캐시에서 복원한 세션의 "차량 시계 추정"이 실제보다 뒤처질 수 있다. 그러면 첫 명령이 `TIME_EXPIRED` 등으로 실패할 수 있고, 라이브러리가 응답에 동봉된 세션정보로 시계를 맞춘 뒤 재시도한다.
- 캐시의 `age`(저장된 뒤 지난 시간)는 벽시계 기준이다.

## 7. 연결 슬롯과 링크 끊김

- 차량은 BLE 연결을 **3개까지** 받는다. 쓰지 않을 때는 `disconnect()`를 불러 슬롯을 돌려준다. 가득 찬 상태에서는 `TransportError.MaxConnectionsExceeded`.
- BLE 링크가 끊겨 `Vehicle.connection`이 `Disconnected`가 되어도 **앱은 `Vehicle.disconnect()`를 불러야 한다.** 그래야 세션 키 삭제와 스코프 정리가 끝난다. `disconnect()`는 여러 번 불러도 안전하다.
- 끊긴 `Vehicle`은 다시 쓸 수 없다. `TeslaBle.connect`로 새로 연결한다.
