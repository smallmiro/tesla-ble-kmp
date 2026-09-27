# Handoff — M2 디스패처·세션 계층(Dispatcher, HandshakeFlow, FakeVehicle) 완료 (2026-09-27)

## 목표
`{{PLANS_DIR}}2026-09-27-m2-dispatcher.md`, FR-010~012, FR-014, FR-017~018, FR-048~049, FR-100~102, NFR-003, NFR-007, NFR-012.

## 완료
- 병합된 PR(모두 rebase merge로 `main`에 반영, 각각 필수 검사 6/6 Green):
  - #28 Task 1 — `TestVerifier` 정리(oneof `replace`, `exhaustCounter`, `assignHandle`, `shiftTimeZero`), `timeZero` 문구 교정
  - #29 Task 2 — `Transport` 포트, `AuthMethod`, `FakeTransport`(Go `dummyConnector`)
  - #30 Task 3 — 응답 AAD가 모르는 `signed_message_fault` 원시값을 씀; Wire 디코딩 오류를 값으로(`decodeOrNull`)
  - #31 Task 4 — `FakeVehicle`(도메인별 `TestVerifier` + 대본), `verifier_test.go` `TestGCM*` 포팅
  - #32 Task 5 — `PendingRequest`(`receiver.go`), `SessionState`(`session.go`)
  - #33 Task 6 — `Dispatcher`(`dispatcher.go` 송수신 경로), `TeslaLogger` 포트
  - #34 Task 7 — `HandshakeFlow`(`StartSession` 재전송 루프, 병렬 `StartSessions`)
  - #35 Task 8 — 세션 캐시(`SessionCache` 포트, `KeyId`, v1 코덱 `SessionCacheCodec`, `InMemorySessionCache`, `SessionCacheSync`)
  - #36 Task 9 — `SendWithRetry`, `CommandTimeouts`(D29), `VehicleSession`
  - #38 Task 10 — VCSEC `interpret`/`readUntil`/직렬화 `execute`, Infotainment 응답 해석, `UnknownKeychainCode`
  - #39 Task 11 — `FakeVehicle` 시나리오 7종(NFR-003, 종단)
  - Task 12(이 커밋) — 문서 전용: `{{SDD_FILE}}`, `{{PRD_FILE}}` 용어, `errors.md`, `architecture.md`(신규), `README.md`, 이 인계 노트
  - #37은 무관한 CI PR(자체 호스팅 러너 전환)이며 아직 열려 있다 — M2 범위 밖이라 이 목록에서 제외.
- 테스트(0 failures, JVM `./gradlew jvmTest --console=plain` + iOS 시뮬레이터 `./gradlew iosSimulatorArm64Test --console=plain`, 둘 다 이 세션에서 실행; 최종 리뷰 수정 웨이브(PR #41) 반영 후 재계산): `:domain` 126개, `:application` 121개, `:adapter-storage` 4개, `:testing` 41개, `:adapter-crypto` 11개. `:application`의 M2 신규분이 대부분이다(`DispatcherTest` 26, `HandshakeFlowTest` 10, `SessionStateTest` 8, `PendingRequestTest` 4, `ResultsTest` 8, `DispatcherLifecycleTest` 11, `SessionCacheSyncTest` 6, `VcsecCommandsTest` 9, `VcsecResponsesTest` 10, `SendWithRetryTest` 8, `VehicleSessionTest` 9, `InfotainmentResponsesTest` 5, `FakeVehicleScenarioTest` 7). `:testing`은 `FakeVehicleTest` 23개를 포함해 41개, `:domain`은 M2에서 `SessionCacheCodecTest`(9)·`WireDecodingTest`(2)가 늘어 126개다.
- CI: 각 PR에서 `main`에 필수 상태 검사 6개(`lint`, `jvm-test`, `android`, `ios`, `license`, `secrets`) 모두 Green.
- 최종 리뷰: 완료(브랜치 전체, 범위 c151b00..a64c449) — 0 Critical / 0 Important, Minor만("With fixes"). 수정은 별도 PR(브랜치 `m2/final-review-fixes`)에서 한 번에 반영했다: 리뷰 과정 용어 정리(코드·테스트 KDoc/주석), `BadResponse.mayHaveSucceeded`가 Infotainment 파싱 실패에도 적용된다는 문서 정정, `SessionStateTest` 어설션 보강, `VehicleSession.disconnect()`가 완료되지 않은 `connect()` 위에서 캐시를 비우지 않도록 하는 동작 수정(TDD, RED→GREEN), `FakeVehicle.corruptNextSessionInfoTag`의 입력 검증. 판정 전체는 `{{HANDOFF_DIR}}2026-09-27-m2-rulings.md`.

## 진행 중
없음. M2 계획 Task 1~12(Task 12 문서화 포함, PR #40)와 최종 리뷰 수정 웨이브(PR #41, 브랜치 `m2/final-review-fixes`)가 모두 `main`에 병합됨.

## 결정
- 설계 구체화 1~12(M2 계획)를 코드에 채택했다. 코드로 확인한 주요 결과는 `{{SDD_FILE}}` §5·§7.1·§12에 반영: 수신 코루틴 락의 유계성(4: `SessionState`/`Dispatcher`의 임계 구역은 `Signer` 호출·맵 조작뿐), `SessionState.authorize`가 오류를 값으로 돌려주고 `SendWithRetry`/`VcsecCommands`가 간격을 두고 재시도(5, Go는 오류를 지우고 즉시 재시도), `Dispatcher.loadSessions`의 부분 복원(6, Go `LoadCache`는 손상 항목 하나로 전체 실패), 캐시 `age`를 클램프하지 않고 `Signer.importSessionInfo`가 0으로 봄(7), 캐시된 **차량** 공개키가 다르면 세션 안에서는 복구하지 않음(8, 아래 사용자 답 a), 모르는 `from_destination.domain`이 `DOMAIN_BROADCAST`로 떨어져 Go와 같이 드롭됨(10), 모르는 VCSEC `operationStatus`·Infotainment `actionStatus.result`는 Go처럼 통과(11). 나머지 항목(1~3, 9, 12)을 포함한 전체 rulings는 컨트롤러가 `{{HANDOFF_DIR}}2026-09-27-m2-rulings.md`에 별도 기록으로 남긴다(이 노트에서 중복하지 않음).
- 사용자 승인 답: **(a)** 캐시된 **차량** 공개키가 현재 차량과 다르면(차량 키 교체) M3 `connect()`가 그 도메인의 캐시 항목을 삭제한다(Go는 아무것도 하지 않는다) — 감지 방법(`UNKNOWN_KEY_ID` 또는 캐시 복원 세션의 세션정보 태그 실패)과 `{{SDD_FILE}}` §12 기록은 M3 계획에서 정한다. **(b)** 핸드셰이크 시간 초과는 전용 오류 타입 없이 `Failure(Timeout(afterSend = false))`로 남긴다(세션이 만들어지지 않았고 부작용이 없으므로).
- `TeslaLogger` 도입: `LogLevel`(DEBUG/INFO/WARN/ERROR) + `fun interface TeslaLogger`, 기본 `NoOp`(`:domain` `port/TeslaLogger.kt`).
- `ByteString.unknownVarint(tag)`(`:domain`)와 `ProtoAdapter<M>.decodeOrNull(bytes)`(`:domain`)가 `@InternalTeslableApi`로 공개되어 `:application`(`Dispatcher`, `VcsecResponses`, `InfotainmentResponses`)이 Wire `unknownFields`의 미등록 값을 되찾거나 손상 입력을 값으로 받는 데 쓴다.
- `PendingRequest.close()`는 non-suspend(플래그 + 채널 닫기만) — 취소 중 `finally`에서도 안전하고, 디스패처가 다음 `register`/`lookup` 때 맵에서 지연 제거한다.
- `VcsecCommands`의 생성자는 `internal`이다(연결당 직렬화 락 하나, `VehicleSession`이 정확히 하나만 만든다).
- 이제부터 머지 게이트는 GitHub Actions가 아니라 `tools/ci/verify.sh`다(ADR-0012, 2026-09-27 — 이 저장소의 GitHub Actions CI를 로컬 검증 게이트로 대체).

## 막힌 점 (M3 이후로 미룬 것)
- `TransportFactory`·`VehicleAdvertisement`·`ConnectOptions`(M3에서 설계).
- 캐시된 **차량** 키 불일치 감지: 위 사용자 답 (a)의 삭제 동작은 정했지만 감지 방법(`UNKNOWN_KEY_ID` 응답, 또는 캐시로 복원한 세션의 세션정보 태그 실패)은 M3 계획에서 정한다. 캐시 저장은 반드시 `Dispatcher.close()` **이전**에 끝나야 한다(`close()`가 세션 키를 지운 뒤 저장하면 빈 목록이 이전 저장을 덮어써 지운다 — `storingAfterCloseWipesThePreviouslySavedCacheHazard`로 고정됨). 감지·삭제를 실제로 걸려면 `Dispatcher`에 "세션 갱신됨" 신호가 필요하다(아래 세션정보 갱신 시 저장 트리거와 같은 신호를 재사용할 수 있다). `SessionCache` 포트에는 도메인 하나만 지우는 API가 없으므로, 그 도메인만 뺀 나머지 항목을 다시 저장하는 방식이 된다.
- `VehicleSession`의 "`disconnect()`에서 저장했음" 플래그(`storedOnDisconnect`)는 한번 세팅되면 리셋되지 않는다 — M3 파사드는 연결이 끊긴 `VehicleSession`의 재사용(재연결)을 금지하거나 `connect()`에서 이 플래그를 리셋해야 한다. 이 항목과 아래 `SessionState`의 영구 "닫힘" 플래그 항목은 M3에서 하나의 결정으로 묶인다: `Dispatcher`/`VehicleSession`을 연결당 1회만 쓰도록(single-use) 강제하고, 그 위에 `SessionState`의 영구 닫힘 플래그와 `disconnect()` 뒤 `connect()` 금지를 얹는다.
- 세션정보 갱신 때마다 캐시에 저장하는 것(`{{SDD_FILE}}` §3.4/§7.1이 원래 의도했던 트리거)은 M2에 없다 — 수신 코루틴이 캐시 I/O로 suspend해서는 안 되기 때문이다(§5). M3 파사드가 이 트리거를 연결해야 한다.
- `SessionCacheSync`는 `keyId`를 생성자에서 별도로 받는다 — M3에서 `Dispatcher`/`VehicleSession`을 조립할 때 실제로 쓰는 키의 `KeyId`를 정확히 넘겨야 한다(다른 키의 `KeyId`를 넘기면 캐시가 항상 빈 목록으로 보인다). 직접 넘기지 말고 파사드 안에서 실제 개인키의 공개키로부터 유도해서 넘기는 편이 더 안전하다(불일치를 구조적으로 없앤다).
- `SessionState`에는 영구적인 "닫힘" 플래그가 없다 — 취소된 수신 코루틴이 검사 시점을 지나 `close()` 이후에도 좁은 창에서 키를 다시 파생시킬 가능성이 이론상 있다. 재연결 의미(reconnect semantics)와 함께 M3에서 결정한다(위 `storedOnDisconnect` 항목과 함께).
- `Transport.incoming`은 수신 코루틴이 실제로 구독하기 전에 도착한 메시지를 버리지 않고 모아 뒀다가 넘겨야 한다(Channel 기반 버퍼링). 이 계약을 포트 KDoc과 `{{SDD_FILE}}` §2.1에 적어 두고, M3 `KableTransport`가 이를 지키도록 구현해야 한다.
- M3 파사드가 `Dispatcher`에 넘길 `CoroutineScope`는 `SupervisorJob` + 예외 핸들러로 감싸야 한다 — 사용자가 준 `TeslaLogger`가 던지면 수신 코루틴이 죽는다. `process()` 안에서 취소가 아닌 예외를 메시지 단위로 잡는 것도 검토 대상이다.
- `VehicleSession.send`/`SendWithRetry.send`는 `DOMAIN_VEHICLE_SECURITY`도 받아 `serial` 락을 건너뛴다 — VCSEC 직렬화(FR-049)는 `VehicleSession.vcsec`로만 보장되므로, M3/M4는 VCSEC에 이 경로들을 쓰지 않아야 한다(또는 여기서 거부해야 한다). `connect()`도 `Dispatcher.isListening`을 확인해야 한다.
- ADR-0010의 전제(라이브러리가 타임아웃을 갖는다)를 다시 볼 필요가 있다 — `expires_at`을 `min(commandLifetime, 남은 시간)`으로 잡을 수 있다. `KableTransport.send`가 쓰기 중간에 취소되면 어떻게 되는지도 M3에서 정의해야 한다.
- 테스트·정리 항목(M3으로 미룸): `FakeVehicle`이 두 도메인에 같은 차량 키를 쓴다(Go `dispatcher_test.go:126`은 도메인별로 다른 키를 쓴다) — 도메인 간 세션정보 혼동이 HMAC을 통과할 수 있다. `disconnect()`의 바깥쪽 `NonCancellable`을 직접 겨냥해 고정하는 테스트가 없다. `MessageStatus`의 raw fault 우선순위 판정(`TAG` 상수 + 판정 순서)이 `Signer.kt`와 `ResponseClassifier.kt`에 중복돼 있다 — 공유 헬퍼로 뽑을 수 있다(로그 위치 정리는 바로 다음 항목에서 다룬다).
- 로그 위치 차이(누락이 아니라 시점이 다름 — Task 7 리뷰 마이너 항목, 컨트롤러 정정): "Session for %s loaded from cache"는 Kotlin에서 `Dispatcher.loadSessions`가 캐시를 불러오는 시점에 남는다(`Dispatcher.kt:293`). Go는 이 문구를 `StartSession`이 호출됐을 때 세션이 이미(캐시로) 준비돼 있으면 그 시점에 남긴다(`dispatcher.go:82`) — 로그는 둘 다 있지만 나는 시점이 다르다. `HandshakeFlow.startSession`은 `dispatcher.session(domain)`이 null이면(`HandshakeFlow.kt:38`) `Dispatcher.requestSessionInfo`를 부르지 않고 곧바로 `RequiresKey`를 돌려준다 — 그래서 Go가 `RequestSessionInfo`에서 먼저 남기는 "Requesting session info from %s" 로그를 거치지 않는다(Kotlin의 `Dispatcher.requestSessionInfo` 자체는 이 로그를 `Dispatcher.kt:269`에서 남기지만, 이 경로에서는 그 함수를 부르지 않는다). `dispatcher.session(domain)`은 `Dispatcher.ALL_DOMAINS` 밖의 도메인뿐 아니라, 개인키가 전혀 없을 때(그러면 `sessions` 맵 자체가 비어 있다)도 null이다 — 즉 이 로그 생략은 Go `TestHandshakeWithoutKey`가 다루는 흔한 "키 없음" 경로에도 그대로 적용된다(도메인 밖 경로가 아니라 이게 주된 경로다). M3 로깅 정리 때 맞출지는 선택 사항.
- S7(Task 11 리뷰): `{{SDD_FILE}}` §9.4 — 연결 시점 거부(`connect()`의 `MaxConnectionsExceeded`)는 M3 `TransportFactory`가 들어와야 종단으로 검증된다. M2는 `Dispatcher`/`SendWithRetry`를 통한 전송 오류로서의 무재시도 분류만 다룬다.
- `VcsecRejected`의 문구와 모르는 VCSEC `genericError` 코드 보존(M4, L58) — Wire가 모르는 값을 기본값(`NONE`)으로 디코딩해 원시 코드를 잃는다. VCSEC 응답 해석을 다시 만질 때(M4) 재검토.
- `errors.md`의 "SKIE가 enum으로 노출" 문장은 `onEnum(of:)` 기반으로 M3에서 교정.
- `TestValidPIN`(`SetPINToDrive` 인자 검증, `InvalidArgument`)은 M5로 이동 — M2 범위 아님.
- 기기 수면 중 단조 시계 정지 문제는 `platform-notes.md`(M3)에서, 실차 골든 RX 수집은 M3에서(M1 권고 8, 9).

## 다음 한 걸음
M3 상세 계획 작성(`{{PLANS_DIR}}<날짜>-m3-transport-keystore.md`) 후 🛑 사용자 승인. 첫 작업: Kable 스파이크(ADR-0003).
검증: `./gradlew check --console=plain`(M2 기준선이 아직 Green인지 먼저 확인).

## 읽을 파일
`{{PATHS_FILE}}`, `{{AGENTS_FILE}}`, `{{SDD_FILE}}` §2.3~§2.5, §3.1, §8, `{{MANUAL_DIR}}02-ble-transport.md`, `{{REF_REPO_DIR}}pkg/connector/ble/ble.go`, 이 인계 노트.
