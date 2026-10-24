# 설계: `vehicle.events` 공개 API와 모니터링 샘플 앱

- 날짜: 2026-10-24
- 상태: 초안 (사용자 승인 대기)
- 관련: PRD FR-031, FR-110~112, NFR-010, NFR-014 / SDD §2.3, §8.4, §10 / `14-lib-reading-state.md` Part B, `17-lib-platform-notes.md`, `18-lib-troubleshooting.md` §Observation log
- 새 추적 ID: FR-035, FR-036, FR-037(PRD), SFR-010~017(샘플 스펙 `samples/documents/md/04-monitor-app.md`)

## 1. 의도와 이유

우리는 앱이 아니라 **라이브러리**를 만든다. 샘플 앱은 라이브러리를 검증하고, 실차에서 오래 켜 두고 모니터링하고,
오류를 잡고, **아직 모르는 프로토콜을 발견**하는 도구다.

지금은 차가 보내는 브로드캐스트(`DOMAIN_BROADCAST`)를 라이브러리가 버리고 `observe` 로그 한 줄로만 남긴다. 공개 API가
없으니 샘플이 그 상태를 보여 주려면 로그 글자를 파싱해야 하는데, 이는 AGENTS 절대 규칙 14가 금지하는 "예제에서 우회"다.
그래서 **M5 예정 항목인 `vehicle.events`를 앞당겨 라이브러리에 먼저 만들고**, 샘플은 그 공개 API만으로 만든다.

사용자가 원하는 장면: 차 안이나 차 밖에서 폰에 앱을 켜 둔다. 앱은 저장된 VIN으로 자동 연결하고, 끊기면 백그라운드에서도
계속 다시 붙는다. 실시간 탭에서 차 상태가 바로 바뀌는 것을 보고, 해석 못 한 메시지는 원본 바이트까지 폰에 쌓인다. 나중에
Mac에 케이블로 연결해 로그를 가져와 라이브러리 강건화(새 필드 해석, 오류 사례)에 쓴다.

## 2. 목표 / 비목표 / 반목표

**목표**
1. 라이브러리: `Vehicle.events` 공개 `Flow`. 평문 브로드캐스트를 해석한 이벤트 + 해석 못 한 부분은 **원본 바이트**까지 (FR-035).
2. 라이브러리: 공개 `VehicleStatus`에 `gear` 추가 (FR-036). 질의 응답과 브로드캐스트 모두에서 채운다.
3. 샘플(Android + iOS): 탭 구조. 실시간 상태, 주기 조회, 관찰 로그, 기존 제어/페어링 (SFR-010~017).
4. 샘플: VIN 저장, 앱 시작 시 자동 스캔·연결, 수동 연결 버튼, 끊기면 백그라운드에서도 자동 재연결, 포그라운드일 때 화면 꺼짐 방지 (SFR-010~017).
5. 샘플: 폰에 로그 파일 저장 + Mac에서 케이블로 가져오는 스크립트 (SFR-016~017).
6. 문서: 라이브러리 사용 문서(MD + HTML ko/en/ja), PRD, SDD를 같은 PR에서 갱신 (절대 규칙 11, 13).

**비목표 (이번 범위 밖)**
- `getState(category)`와 Infotainment 조회(M5 그대로). 조회 탭은 VCSEC 조회만.
- 클라우드 로그 전송(New Relic, Grafana 등). 로그 저장을 교체 가능한 구조(sink)로만 두고 붙이지 않는다. 나중에 붙이면 NFR-014·절대 규칙 6의 적용 범위를 밝히는 ADR이 필요하다.
- 라이브러리 자체의 자동 재연결 기능. 재연결 루프는 샘플(앱) 책임이다.
- 백그라운드 Phone-as-Key(자동 잠금 해제). PRD §3.2 v2 후보 그대로.
- 브로드캐스트 필드의 의미 해석(필드 48, 53, `vehicleStatus` 필드 8). 원본을 모으는 것까지만.

**반목표 (돌아가도 실패인 것)**
- 샘플이 `observe` 로그 글자를 파싱해서 상태를 만든다.
- 샘플이 `:application`·`:adapter-*` 내부 타입이나 `@InternalTeslableApi`를 쓴다.
- 이벤트 처리 때문에 응답 매칭이 느려지거나 막힌다(구독자가 느려도 디스패처는 기다리지 않는다).
- 라이브러리가 **복호화한** 평문이 이벤트나 로그로 나간다(FR-112 위반).
- 실제 VIN이 저장소(픽스처, 커밋된 로그)에 들어간다(절대 규칙 8).
- 반복 브로드캐스트를 "변화"로 표시한다(Part B: 변화 없이도 1~25초마다 반복).

## 3. 제약

- 원본 Go는 브로드캐스트를 버린다(`route()`의 `TO_DOMAIN`). 이벤트 방출은 **원본에 없는 추가 동작**이므로 SDD §12 "원본과 다른 동작(의도)"에 적고, drop 판정 자체(응답 매칭에 안 들어감)는 바꾸지 않는다.
- 공개 API 변경: `explicitApi`, KDoc, `apiDump` 갱신, `ByteArray` 방어 복사(NFR-010). Swift는 SKIE로 `Flow` → `AsyncSequence`, sealed는 `onEnum(of:)`.
- `:domain`은 Wire 런타임·coroutines만 의존(NFR-016). 새 공개 모델은 `dev.mirolab.teslable.model`.
- 샘플은 `:sdk` 공개 API 중 문서에 "사용 가능"으로 적힌 것만 쓴다. 그래서 순서가 **라이브러리 + 문서 PR이 먼저, 샘플 PR이 다음**이다.
- 로컬 게이트 `tools/ci/verify.sh` 통과, Rebase merge, Tidy First.

## 4. 사용자가 정한 것

| # | 결정 |
|---|---|
| U1 | 실시간 상태 탭은 라이브러리 `vehicle.events` 공개 API를 먼저 만든 뒤 붙인다 |
| U2 | 로그는 폰에 저장한다. 클라우드 수집은 나중에 붙인다(샘플에만, 라이브러리에는 넣지 않음) |
| U3 | 앱 안 내보내기 대신 Mac에서 케이블로 직접 가져온다 |
| U4 | Android, iOS 샘플 둘 다 |
| U5 | 해석 못 한 메시지는 **원본 바이트까지** 공개 API로 넘긴다 |
| U6 | 조회 탭은 VCSEC 조회로 먼저, `getState`는 M5 |
| U7 | 링크가 끊기면 백그라운드에서도 계속 자동 재연결 |
| U8 | 앱이 포그라운드에 있으면 화면이 꺼지지 않는다 |
| U9 | VIN은 한 번 입력하면 저장하고, 앱을 켜면 자동으로 스캔·연결한다. 실패에 대비해 연결 버튼도 둔다 |
| U10 | 폰이 잠겨 있어도 **키가 있는 상태로** 모니터링한다(키 없이 연결하는 대안은 거절됨). → §5.6 |

## 5. 라이브러리 설계 (FR-035, FR-036)

### 5.1 공개 API (안)

```kotlin
public interface Vehicle {
    // 기존 멤버 …
    /**
     * 차량이 요청 없이 보낸 평문 VCSEC 브로드캐스트. 연결 하나에 하나의 hot 스트림.
     * 구독 전에 온 이벤트는 받지 못한다(replay 0). disconnect() 또는 링크 손실 뒤 완료된다.
     */
    public val events: Flow<VehicleEvent>
}

public sealed interface VehicleEvent {
    /** 수신한 RoutableMessage 프레임 원본(방어 복사본). VIN 마스킹 없음 — 저장·공유 전 앱이 가린다. */
    public val raw: ByteArray
    /** FromVCSECMessage 와 그 하위 메시지에서 스키마가 모르는 필드 번호(도착 순, 경로 표기 예: "53", "1.8"). */
    public val unknownFields: List<String>

    public class Status(val status: VehicleStatus, …) : VehicleEvent          // FromVCSECMessage.vehicleStatus (1)
    public class AuthenticationRequest(
        val requestedLevel: AuthenticationLevel,                              // NONE / UNLOCK / DRIVE / UNKNOWN
        val reasons: List<AuthenticationReason>,                              // 14 Part B3.2 표 + UNKNOWN
        …,
    ) : VehicleEvent                                                          // FromVCSECMessage.authenticationRequest (3)
    public class Unrecognized(…) : VehicleEvent                               // 위 둘이 없는 브로드캐스트(예: 48·53만 있는 프레임, 디코딩 실패)
}
```

- 한 프레임 = 이벤트 하나. `vehicleStatus`와 `authenticationRequest`가 한 프레임에 같이 오면 `Status`를 내고 `AuthenticationRequest`는 따로 하나 더 낸다(둘 다 같은 `raw`). 필드 53·48처럼 모르는 필드는 해당 이벤트의 `unknownFields`에 남는다. (빌더 재량: 구체 표현은 바꿔도 되지만 "모르는 부분이 있으면 원본으로 찾을 수 있다"는 성질은 지킨다.)
- `AuthenticationRequest`의 토큰(20 B)은 필드로 내보내지 않는다. 필요하면 `raw`에 있다.
- `Status` 이벤트의 `closures` 규칙은 `vehicleStatus()`와 같다(없으면 모두 `CLOSED`). KDoc과 14번 문서에 "인테리어 버튼으로 연 트렁크는 브로드캐스트가 오지 않으므로 트렁크는 `vehicleStatus()`로 읽는다"를 적는다.
- **중복**: 라이브러리는 중복을 걸러내지 않는다(원본 그대로가 발견 목적에 맞음). 문서에 "반복은 변화가 아니다, 비교는 앱이 한다"를 적는다.

### 5.2 어떤 프레임이 이벤트가 되나 (게이트)

18번 문서의 관찰 로그 v2 게이트를 그대로 쓴다. 모두 만족할 때만 이벤트로 낸다:
`from=domain(DOMAIN_VEHICLE_SECURITY)`, `to=domain(DOMAIN_BROADCAST)`, `request_uuid` 비어 있음, `signature_data` 없음,
`session_info` 없음, 페이로드가 평문 바이트.
- 페이로드가 `FromVCSECMessage`로 디코딩되면 `Status` / `AuthenticationRequest` / `Unrecognized`.
- 디코딩이 실패해도 게이트를 통과했으면 `Unrecognized`(원본 보존).
- 그 밖의 drop(`no-handler` 다른 클라이언트 응답, `unparseable`, `missing-source` 등)은 **이벤트로 내지 않는다**. 지금처럼 `observe` 로그로만 남고, 샘플은 그 줄도 저장한다.
- 복호화가 일어나는 경로와는 만나지 않으므로 FR-112(복호화 평문 비노출)를 지킨다. 원본 바이트 공개는 "차가 처음부터 평문으로 보낸 것"에 한한 좁은 예외로 PRD FR-035에 명시한다.

### 5.3 디스패처와 흐름 제어

- 디스패처의 `DropReason.TO_DOMAIN` 분기에서 관찰 로그를 쓰는 것에 더해, 게이트를 통과하면 이벤트 싱크에 **non-suspending**으로 넘긴다(`tryEmit`). 응답 매칭 경로를 절대 기다리게 하지 않는다.
- 버퍼: `MutableSharedFlow(replay = 0, extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST)` 수준. 넘쳐서 버린 수는 연결마다 세고 `WARN`으로 남긴다(BLE inbox 오버플로 로그와 같은 모양). 값은 빌더 재량.
- 완료(설계자 판단): 링크 손실(전송의 수신 흐름이 닫힘) **또는** `disconnect()` 중 먼저 오는 쪽에서 흐름이 **정상 완료**된다(예외 없음). 링크 손실 뒤에도 자원 해제는 지금처럼 앱의 `disconnect()` 몫이다(SDD §2.3). 끊긴 이유는 `connection`의 `Disconnected(reason)`으로 본다. 재연결 트리거는 `connection`을 보라고 문서에 적는다(흐름 완료로 판단하지 않는다). 재연결은 새 `Vehicle`이고 새 `events`다.
- 매핑(Wire → 공개 모델)은 기존 `VcsecInfoMapping`을 재사용해 질의와 브로드캐스트가 같은 규칙을 따르게 한다. 스키마 밖 enum 값은 `UNKNOWN`.

### 5.4 `VehicleStatus.gear` (FR-036)

- 공개 `Gear` enum: `UNKNOWN, PARK, DRIVE, REVERSE, NEUTRAL` (+ 스키마 밖 값도 `UNKNOWN`). `VehicleStatus`에 `gear` 추가.
- proto3 enum이라 "없음"과 `GEAR_UNKNOWN`(0)을 구별하지 못한다. KDoc에 적는다.
- `data class`에 필드를 더하는 것은 바이너리 비호환(생성자·`copy`·`componentN`)이다. 아직 배포 전(M6 전)이므로 `apiDump`로 받아들이고 PR 본문에 적는다.
- `uiDesire`(6)는 의미가 확인되지 않아 이번에도 노출하지 않는다.

### 5.5 플랫폼: 백그라운드 (라이브러리 관점)

라이브러리는 백그라운드 전용 코드를 넣지 않는 것이 기본이다. 다만 두 가지는 실기기로 확인해야 하고, 결과에 따라 라이브러리 작업이 생길 수 있다.

- **iOS 백그라운드 스캔**: 지금 스캔 필터는 Local Name 정확 일치뿐이다(`KableGattConnector`). iOS는 백그라운드 스캔에 서비스 UUID 필터가 필요하고 Local Name이 오지 않을 수 있다(SDD §8.4에도 경고). 그래서 계획의 첫 작업을 **스파이크**로 둔다: 실차에서 앱을 백그라운드에 둔 채 (a) 기존 스캔, (b) 서비스 UUID 필터 스캔, (c) 직전 기기 식별자로 바로 연결이 되는지 본다. 결과에 따라 라이브러리 변경(예: 스캔 필터에 서비스 UUID 추가, 이름은 코드에서 확인)을 별도 작업으로 계획에 넣는다. **스파이크 결과는 사용자에게 보고하고 진행 여부를 묻는다.**
- **Android**: 포그라운드 서비스가 프로세스를 살려 두면 BLE는 그대로 동작할 것으로 본다(추측, 실기기 확인 필요).
- 17번 문서의 "포그라운드 전용, 백그라운드 BLE 범위 밖"은 "앱이 아래 설정을 하면 백그라운드에서 동작한다, 실기기 확인 범위는 이만큼"으로 고친다. SDD §8.4 표도 같이 고친다.

### 5.6 잠긴 폰에서 키 사용 (FR-037, U10)

현재 상태(코드 확인):
- iOS 개인키: Secure Enclave 키와 소프트웨어 대체 키 모두 `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`(`adapter-crypto/src/iosMain/.../SecureEnclaveKeys.kt:123`, `KeychainItems.kt:65`). **잠긴 폰에서는 ECDH(핸드셰이크)가 실패**한다.
- iOS 세션 캐시: 이미 `AfterFirstUnlockThisDeviceOnly`(`KeychainSessionCache.kt:27`). 문제없음.
- Android: Keystore 키에 `setUnlockedDeviceRequired`/사용자 인증 요구가 없어 잠긴 상태에서도 쓸 수 있다(추측, 실기기 확인 대상).

설계:
- iOS 키 저장소에 키 **접근성 선택**을 공개 옵션으로 추가한다. 값은 `WHEN_UNLOCKED`(기본, 지금과 같음)와 `AFTER_FIRST_UNLOCK`(재부팅 후 한 번 잠금 해제한 뒤로는 잠긴 상태에서도 사용) 두 가지. 둘 다 `ThisDeviceOnly`(백업·기기 이전 불가)는 유지한다.
- **기본값은 바꾸지 않는다(설계자 판단).** 라이브러리를 쓰는 다른 앱의 보안 수준이 몰래 낮아지지 않게, 필요한 앱만 명시적으로 고른다. 샘플 모니터 앱은 `AFTER_FIRST_UNLOCK`을 고른다.
- 접근성은 **키를 만들 때 정해지고 바꿀 수 없다**(Secure Enclave 키는 내보낼 수 없으므로 다시 감쌀 수도 없다). 그래서 기존 키를 옮기는 마이그레이션은 없다. 접근성이 다른 키가 필요하면 새 별칭으로 새 키를 만들고 차에 **다시 등록**한다. `VehicleKey`에서 그 키의 실제 접근성을 읽을 수 있게 해, 샘플이 "이 키는 잠긴 상태에서 못 씀"을 표시하고 재생성을 안내하게 한다.
- 공개 API 모양(옵션을 `KeyPolicy` 옆 인자로 둘지, `getOrCreate`의 새 인자로 둘지, iOS 전용 팩토리로 둘지)은 빌더 재량. 단 Swift에서 모든 인자를 넘기는 오버로드가 있어야 한다(17번 문서 규칙). Android·JVM에서는 의미 없는 값이므로 무시하고 그 사실을 KDoc에 적는다.
- 잠긴 상태에서 키 접근이 실패하면 지금 어떤 `KeyStoreError`가 나오는지 확인하고, 앱이 구별할 수 있는 오류(예: `KeyStoreError.Locked` 류)로 내보낸다. 정확한 이름은 빌더 재량.
- **ADR 필요**: 키 보안 수준 선택을 여는 결정이므로 새 ADR(ADR-0008 키 저장 결정의 보완)을 쓴다. 17번 문서 §Key storage, 13번 문서 정책 표, SDD §8.2를 고친다.
- 스파이크(§5.5)에서 함께 확인: 잠긴 iPhone + `AFTER_FIRST_UNLOCK` 키로 백그라운드 재연결 → `startSession` → 이벤트·조회가 되는지. Secure Enclave 키에 이 접근성이 실제로 적용되는지도 본다.

## 6. 샘플 앱 설계 (FR-113, FR-114)

> **2026-10-24 갱신:** 샘플 부분의 정본은 `samples/documents/md/04-monitor-app.md`(SFR-010~017, MD + HTML ko/en/ja)로 옮겼다. 샘플 요구사항은 PRD의 FR이 아니라 SFR로 관리하므로 FR-113·FR-114는 쓰지 않는다. 아래는 승인 당시 기록으로 남긴다.

### 6.1 탭

| 탭 | 내용 |
|---|---|
| **실시간** | `vehicle.events`의 최신 `Status`로 그리는 상태 카드: 잠금, 기어, 탑승, 수면, 문 4개·프렁크·트렁크·충전구. 각 항목 옆에 마지막으로 **바뀐** 시각. 최근 이벤트 목록(인증 요청 포함). 연결 상태 배너(연결됨 / 재연결 중 n회째 / 끊김 이유). "트렁크는 브로드캐스트가 없을 수 있음" 안내 |
| **조회** | 주기적으로 `vehicleStatus()`, `keySummary()`, `listKeys()`를 부르고 `sessions` 상태를 표시. 기본 30초, 화면에서 바꿀 수 있음(샘플이 정한 값). 각 결과는 `Success`/`Uncertain`/`Failure`와 시각을 함께 표시. 수동 "지금 조회" 버튼 |
| **로그** | 이번 세션에 저장된 줄 수, 해석 못 한 이벤트 수, 마지막 줄 몇 개, 파일 위치, 새 파일 시작 버튼 |
| **제어** | 지금 한 화면에 있는 키 생성, VIN 입력, 스캔, 연결/끊기, 페어링, 세션, 잠금·해제·트렁크·깨우기, 키 목록·삭제를 그대로 옮김 |

### 6.2 연결 수명

- VIN: 처음 입력하면 저장(Android `SharedPreferences`/DataStore, iOS `UserDefaults`). VIN은 화면 외 로그에 쓸 때 마스킹. 저장값을 지우는 버튼을 둔다.
- 앱 시작: VIN과 키가 있으면 자동으로 스캔 → `connect`. 키가 없으면 `connect(vin, key = null)`로 키 없이도 되는 조회와 이벤트만.
- 재연결: `connection`이 `Disconnected`가 되면 `Vehicle.disconnect()`로 자원을 풀고(SDD §2.3 링크 손실 규칙) 백오프(예: 2 s → 최대 30 s)로 다시 스캔·연결한다. 수동 "끊기"를 누르면 자동 재연결을 멈춘다. 수동 "연결" 버튼은 언제나 즉시 시도.
- 화면 꺼짐 방지: 앱이 보이는 동안만. Android `FLAG_KEEP_SCREEN_ON`(Activity), iOS `UIApplication.shared.isIdleTimerDisabled`(scenePhase가 active일 때 true, 아니면 false).

### 6.3 백그라운드 동작

- **Android**: `TeslaBle` 인스턴스, 연결·재연결 루프, 이벤트 구독, 로그 쓰기는 Activity가 아니라 **포그라운드 서비스**(`foregroundServiceType="connectedDevice"`)가 소유한다. 알림에 연결 상태와 "중지" 동작. 매니페스트: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`(런타임 요청). `INTERNET`은 여전히 없음(NFR-014). UI는 서비스의 상태 흐름을 구독한다.
- **iOS**: `Info.plist`에 `UIBackgroundModes = [bluetooth-central]`. 모니터 객체는 앱 수명 동안 하나. 백그라운드 재스캔·재연결 방법은 §5.5 스파이크 결과를 따른다. 스파이크 전까지는 "연결이 살아 있는 동안 백그라운드에서도 이벤트 수신"을 1차 목표로, "끊긴 뒤 백그라운드 재연결"을 2차 목표로 둔다.

### 6.4 로그 저장

- 형식: JSON Lines, 세션(앱 시작 또는 "새 파일")마다 파일 하나. 파일명 `monitor-<yyyyMMdd-HHmmss>.jsonl`.
- 줄 종류:
  - `event`: `VehicleEvent` 종류, 해석 결과 요약, `unknownFields`, `raw` 헥사. **모든 이벤트**를 저장하되 `unknownFields`가 있거나 `Unrecognized`면 `"unrecognized": true` 표시(사용자 요청 "정해 놓지 않은 정보는 따로" = 필터 가능한 표시. 별도 파일로 나누는 것은 빌더 재량).
  - `observe`: 라이브러리 `observe` 태그 줄(이미 VIN 마스킹됨). `TeslaLogger`를 구현해 받는다. BLE `TX:`/`RX:` 헥사는 저장하지 않는다(18번 문서 규칙).
  - `query`: 조회 탭 결과(종류, 결과 분류, 요약).
  - `link`: 연결·끊김·재연결 시도와 이유.
- 공통 필드: `ts`(ISO-8601, 기기 시각), `platform`, `appVersion`.
- **VIN 마스킹**: `raw`를 헥사로 쓰기 전에 원본 바이트에서 `[A-HJ-NPR-Z0-9]{17}` ASCII 구간을 같은 길이로 가린다(앞 3, 뒤 4 유지 — 18번 문서와 같은 규칙). 사용자 결정 U5의 "원본"은 라이브러리 API 기준이고, 저장 파일은 이 마스킹만 거친다(샘플이 정한 것).
- 크기: 파일당 상한(예: 20 MB)에서 새 파일로 넘김. 전체 상한(예: 200 MB)을 넘으면 가장 오래된 파일부터 지움. 값은 빌더 재량.
- 쓰기는 별도 큐/코루틴에서 하고, 이벤트 수신을 막지 않는다.
- 저장 위치:
  - Android: `getExternalFilesDir("logs")` = `Android/data/dev.mirolab.teslable.sample/files/logs/` (권한 없이 `adb pull` 가능)
  - iOS: 앱 `Documents/logs/`, `UIFileSharingEnabled`·`LSSupportsOpeningDocumentsInPlace` 켬(Finder/파일 앱에서도 보임)
- 로그 저장 부분은 `LogSink` 같은 작은 인터페이스 뒤에 둔다. 지금은 파일 구현 하나. 나중의 클라우드 전송 자리.

### 6.5 Mac에서 가져오기 (FR-114)

- `tools/samples/pull-monitor-logs.sh [android|ios]`: 연결된 기기에서 로그 폴더를 저장소의 git-ignored 폴더(`samples/logs/<platform>/<날짜>/`)로 복사.
  - Android: `adb pull /sdcard/Android/data/dev.mirolab.teslable.sample/files/logs/ …`
  - iOS: `xcrun devicectl device copy from --device <id> --domain-type appDataContainer --domain-identifier <bundle id> --source Documents/logs --destination …`
- 경로 키: `SAMPLE_LOGS_DIR`(PR #92에서 추가됨, git-ignored).
- 가져온 로그는 커밋하지 않는다. 픽스처로 쓰려면 기존 규칙대로 VIN 마스킹본을 Kotlin 상수로 옮긴다(ADR-0011).

## 7. 문서 갱신 (같은 PR, 절대 규칙 11·13)

| 문서 | 바뀌는 내용 |
|---|---|
| PRD | FR-035(`events`, 평문 브로드캐스트 원본 노출 예외), FR-036(`gear`), FR-037(iOS 키 접근성 선택), ~~FR-113, FR-114~~(샘플 요구사항은 SFR-010~017로 `samples/documents`에서 관리). §3.2 "백그라운드" 문구 정리(Phone-as-Key는 계속 v2), M5 표에서 `events` 이동 |
| SDD | §2.3 `Vehicle.events`, `VehicleEvent`, `Gear`. §3.3 디스패처의 브로드캐스트 분기. §8.4 백그라운드. §12 원본과 다른 동작(브로드캐스트 방출) |
| ADR | **필수 1건**: iOS 키 접근성 선택(§5.6). `events`는 모듈 의존성을 바꾸지 않으므로 SDD §12 기록으로 충분하다(설계자 판단) |
| `13-lib-pairing` | 키 접근성 옵션, `VehicleKey`의 접근성, 잠긴 폰 오류, 재생성·재등록 절차 |
| `11-lib-overview` | 상태 표: `vehicle.events`, `gear` 사용 가능. 마일스톤 표기 |
| `12-lib-getting-started` | 이벤트 구독 예제(Kotlin, Swift) |
| `14-lib-reading-state` | Part B2 "드롭만 함" → "events로 나옴", 게이트, 이벤트 종류, 중복 규칙, 트렁크 주의, `gear` 노출 |
| `16-lib-errors` | 이벤트 버퍼 오버플로(오류 아님, WARN) |
| `17-lib-platform-notes` | 백그라운드 설정(Android 포그라운드 서비스, iOS `bluetooth-central`)과 실기기 확인 범위, 스파이크 결과 |
| `18-lib-troubleshooting` | 관찰 로그와 `events`의 관계, 샘플 로그 가져오기 |
| HTML ko/en/ja | 위 MD 변경과 같은 내용. 영어판 먼저, ko/ja는 같은 마크업으로 번역 |
| `samples/*/README` | 탭, 백그라운드 권한, 로그 가져오기 |

## 8. 작업 순서 (계획에서 구체화)

1. **스파이크(버림)**: iOS 백그라운드 스캔·재연결 방식 확인 (§5.5). 결과 보고 → 사용자 판단.
2. 라이브러리 PR: `gear` (struct → feat), `events` (FakeVehicle 브로드캐스트 시나리오로 TDD), 문서 MD + HTML 3언어, PRD/SDD.
2-1. 라이브러리 PR: iOS 키 접근성 선택(FR-037, §5.6) + ADR. 2번과 독립이라 병행 가능.
3. (스파이크 결과에 따라) 라이브러리 PR: 스캔 필터 변경.
4. 샘플 PR: Android (탭, 서비스, 저장, 자동 연결), iOS (탭, 백그라운드 모드, 저장, 자동 연결), 가져오기 스크립트, `PATHS.md` 키.
- 순서(설계자 판단): 2번 라이브러리 PR(`gear`·`events`)은 스캔과 무관하므로 스파이크를 기다리지 않고 병행한다. 스파이크가 라이브러리 변경을 요구하면 3번을 별도 PR(문서 갱신 포함)로 계획에 추가하고, 그 전에 사용자 승인을 받는다. 4번 샘플 PR은 2번 머지 뒤에 시작한다.
5. 실차 확인 체크리스트(차 안·밖, 걸어 나갔다 돌아오기, 백그라운드 30분 이상) 결과를 17번 문서와 인계 노트에 반영.

## 9. 빌더 재량으로 남긴 것

- `VehicleEvent` 하위 타입의 정확한 필드 구성과 `unknownFields` 표기(경로 문자열 vs 구조체).
- 이벤트 버퍼 크기, 재연결 백오프 값, 로그 파일 크기 상한.
- 샘플 내부 구조(Android ViewModel/Service 분리 방식, iOS ObservableObject 분할), 탭 디자인 세부.
- 해석 못 한 줄을 같은 파일에 표시만 할지, 별도 파일로도 쓸지.

## 10. 위험

| 위험 | 대응 |
|---|---|
| iOS 백그라운드에서 이름 필터 스캔이 안 됨 | 첫 작업 스파이크, 결과 따라 라이브러리 작업 추가 |
| 백그라운드 재연결이 차의 연결 슬롯(최대 3)을 계속 차지 | 수동 "끊기"로 자동 재연결 중지. 17번 문서 연결 슬롯 절에 주의 추가 |
| 브로드캐스트 빈도가 높아 배터리·저장 공간 소모 | 로그 상한, 이벤트 수신은 이미 열린 구독이라 추가 BLE 트래픽 없음. 조회 주기 조절 가능 |
| `data class` 필드 추가로 API 비호환 | 배포 전이라 허용, PR에 명시 |
| `AFTER_FIRST_UNLOCK` 키는 잠긴 폰을 손에 넣은 사람이 BLE 명령에 쓸 수 있음 | 옵트인, 기본값 유지, ADR과 13·17번 문서에 위험을 적음. 샘플 화면에 현재 키 접근성 표시 |
| 원본 바이트에 예상 못 한 민감 정보 | 평문 브로드캐스트만, 저장 시 VIN 마스킹, 로그는 커밋 금지 |
