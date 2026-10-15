# 아키텍처 (기여자용 요약)

이 문서는 `docs/manual/README.md` 기준 상대경로를 쓴다. 이 라이브러리에 기여하는 사람을 위한 설계 요약이며, 에이전트용 정본은
이 저장소의 `docs/sdd/SDD.md`다(이 문서는 그 내용을 옮겨 적은 요약이라 SDD의 절로 링크하지 않는다). M2 초안에 M3의 `:adapter-ble`·`:sdk` 절을 더했다. 제어·조회 API(M4~M5)가 들어오면 다시 채운다.

## 모듈 구조

```
앱 (Android Compose / iOS SwiftUI)
        │
        ▼
     :sdk            공개 파사드(TeslaBle, Vehicle). M3에서 스캔·연결·조회, M4~M6에서 확장
        │
        ▼
 :application         Dispatcher · SessionState · VcsecCommands · InfotainmentCommands · SessionCacheSync · VehicleSession
        │
        ▼
    :domain            프로토콜 모델(Signer, Metadata, SlidingWindow…), 포트(Transport, SessionCache, CryptoPrimitives…), 순수 Kotlin
        ▲        ▲          ▲
 :adapter-ble  :adapter-crypto  :adapter-storage    각 포트의 플랫폼 구현(Kable / Keystore·Security.framework / 파일·Keychain)
```

의존 방향은 항상 `:domain`을 향하고 Gradle 컨벤션 플러그인이 이를 강제한다. `:testing`은 `FakeVehicle`·`FakeTransport` 등
테스트 픽스처를 담고 다른 모듈의 테스트 코드에서만 참조된다.

## 명령 하나의 경로 (예: `lock()`)

**보내는 방향**

```
VehicleSession.vcsec.execute(payload, auth, done)
  → serial.withLock { ... }              // 연결당 VCSEC 명령 하나씩(직렬화 락, VcsecCommands 인스턴스 소유)
    → retryWhileRetriable(retryInterval) { ... }   // shouldRetry() 오류마다 새 시도
      → Dispatcher.send(message, auth, lifetime)
          → SessionState.authorize        // Signer.encrypt: counter++, AAD 계산, nonce
          → transport.send(bytes)         // Transport 포트(:adapter-ble가 구현)
      → PendingRequest 대기(readUntil)     // 종료 판정까지 응답을 기다림
```

Infotainment 명령은 직렬화 락 없이 `InfotainmentCommands.execute` → `SendWithRetry.send`로 같은 아래 두 단계(재시도 → `Dispatcher.send`)를 탄다.

**받는 방향**

```
Transport.incoming (Flow<ByteArray>)
  → Dispatcher의 수신 코루틴 1개가 collect
      → RoutableMessage로 파싱
      → 보낸 요청(PendingRequest)과 매칭
      → 동봉된 세션정보가 있으면 검증·반영(SessionState.processHello)
      → 암호화 응답이면 복호화 + 재전송 검사(SlidingWindow)
      → 매칭된 PendingRequest에 전달
```

## 동시성 요약

- **수신 코루틴은 차량 연결마다 정확히 1개**다. 이 코루틴은 다른 코루틴의 진행을 기다리는 suspend(채널 receive, `Deferred.await`,
  `delay`)를 쓰지 않는다 — 채널로 넘길 때도 대기하지 않는 `trySend`만 쓴다. 예외는 딱 하나, 코루틴을 새로 띄울 때 아직 끝나지
  않은 이전 코루틴의 종료를 기다리는 것뿐이다(두 수신 코루틴이 동시에 구독하지 않도록).
- **VCSEC 명령은 연결당 한 번에 하나만** 보낸다(직렬화 락). 앞 명령이 끝날 때까지 기다린 시간도 그 명령의 전체 시간 제한에
  포함된다. **Infotainment 명령은 병렬로 보낼 수 있다.**
- 도메인(VCSEC/INFOTAINMENT)마다 세션 상태가 하나씩 있고, 내부 락이 보호하는 임계 구역은 암호화·복호화·세션정보 갱신 호출뿐이라
  락 대기가 짧게 유계돼 있다.
- 핸드셰이크(두 도메인 세션 맺기)는 도메인마다 병렬로 진행되고, 하나가 실패하면 나머지는 취소된다.
- 명령·핸드셰이크·연결에는 각각 시간 제한이 있다. 시간 제한과 재시도 규칙은 [errors.md](errors.md)를 본다.

## BLE 어댑터 (`:adapter-ble`)

Kable 바인딩과 테스트 가능한 코어로 나뉜다. `KableGattConnector`(스캔·열기)와 `KableGattLink`(쓰기·구독·끊김 감지)만 Kable을 알고, `BleTransportFactory`·`BleTransport`는 Kable 없이 프레이밍·재조립·재시도·오류 변환을 맡아 JVM 단위 테스트로 검증된다. 링크가 끊기면 수신 흐름을 닫고 전송 상태를 `Disconnected`로 바꾼다. 스캔 필터는 로컬 이름(`S<VIN 해시>C`) 정확 일치다.

## 파사드 조립 (`:sdk`)

`TeslaBle.create(platform, config)`가 플랫폼별 어댑터(BLE 전송, 키 보관소, 세션 캐시, 암호 원시연산)를 조립한다. `connect`는 전송을 연 뒤 연결 전용 코루틴 스코프를 만들고 `Dispatcher`·`VehicleSession`을 붙여 캐시를 복원하며, 중간에 실패하거나 취소되면 전송과 스코프를 반드시 풀어 준다. `Vehicle`은 연결 하나에 객체 하나이고, `disconnect()` 뒤에는 명령이 `NotConnected`로 실패한다.

## 세션 캐시

세션(핸드셰이크로 맺은 암호화 키 상태)은 자체 바이너리 형식(v1)으로 캐시에 저장돼, 다음 연결에서 핸드셰이크를 다시 하지 않고
바로 명령을 보낼 수 있게 한다. 캐시는 클라이언트 키(공개키의 SHA-1)에 종속되며, 다른 키의 캐시는 무시하고 지운다. 저장 시점은
핸드셰이크가 끝났을 때, 세션정보가 갱신될 때, 연결을 끊을 때다(M3에서 파사드가 연결했다). 개인키·세션
암호화 키·VIN 원문은 저장하지 않는다. 손상된 캐시 항목이나 알아보지 못하는 값이 든 항목은 건너뛰고 나머지만 복원한다.
