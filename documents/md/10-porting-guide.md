# 10. 다른 언어/플랫폼 포팅 가이드 (BLE 클라이언트)

Python(bleak), Swift(CoreBluetooth), Kotlin/Android(BluetoothGatt), Rust(btleplug), Node(noble) 등으로 Tesla BLE 명령 클라이언트를 처음부터 구현하기 위한 단계별 체크리스트. 언어 중립 의사코드와 각 단계의 Go 원본 위치를 함께 제시한다. 프로토콜 세부는 [03-protocol.md](03-protocol.md), BLE 세부는 [02-ble-transport.md](02-ble-transport.md), 오류 코드는 [08-errors.md](08-errors.md).

관련 파일 (저장소 루트 기준)

- `pkg/protocol/protocol.md` — 사양 원문과 테스트 벡터 (**최종 권위**)
- `pkg/protocol/protobuf/*.proto` — 메시지 정의
- `pkg/connector/ble/ble.go` — 스캔/연결/프레이밍
- `internal/dispatcher/dispatcher.go`, `session.go`, `receiver.go` — 메시지 조립, 응답 매칭, 세션 갱신
- `internal/authentication/native.go`, `signer.go`, `peer.go`, `metadata.go`, `window.go`, `crypto.go` — 암호 연산
- `internal/authentication/protocol_doc_test.go` — 문서 벡터 고정 테스트
- `pkg/cache/cache.go` — 세션 캐시 파일 형식
- `pkg/vehicle/security.go` `SendAddKeyRequestWithRole` — 키 페어링 전송

표기: `||` 연결, `BE32(n)` 4바이트 빅엔디언, `ENCODE_PUBLIC(P)` = `0x04 || X(32) || Y(32)` (65바이트), `K` 128비트 AES 키.

---

## 0. 사전 준비

### 0.1 protobuf 바인딩

```bash
cd vehicle-command/pkg/protocol/protobuf
protoc --proto_path . --python_out=../../../../out/py   *.proto   # Python
protoc --proto_path . --swift_out=../../../../out/swift  *.proto   # Swift (protoc-gen-swift)
protoc --proto_path . --kotlin_out=... --java_out=...    *.proto   # Kotlin/Android
# Rust: prost-build / protobuf-codegen, Node: protobufjs 또는 @bufbuild/protobuf
```

필요한 메시지: `UniversalMessage.RoutableMessage`, `Destination`, `SessionInfoRequest`, `MessageStatus`; `Signatures.SessionInfo`, `SignatureData`, `KeyIdentity`, `AES_GCM_Personalized_Signature_Data`, `AES_GCM_Response_Signature_Data`, `HMAC_Personalized_Signature_Data`, `HMAC_Signature_Data`; `VCSEC.UnsignedMessage`, `FromVCSECMessage`, `ToVCSECMessage`, `SignedMessage`, `WhitelistOperation`, `PermissionChange`, `KeyMetadata`, `PublicKey`, `InformationRequest`, `ClosureMoveRequest`; `CarServer.Action`, `VehicleAction`, `Response`; `Keys.Role`; `Errors.NominalError`.

### 0.2 암호 프리미티브

| 용도 | 프리미티브 | 비고 |
|---|---|---|
| 키 쌍 | NIST P-256 (secp256r1) ECDSA/ECDH 키, PEM `EC PRIVATE KEY` 또는 PKCS8 | 공개 키는 uncompressed 65바이트로 전송 |
| 키 합의 | ECDH(c, V) → 공유점 X 좌표 32바이트 (`BE32`가 아니라 32바이트 고정 길이) | `native.go sharedSecret`: `sharedX.FillBytes(32)` |
| KDF | `K = SHA1(X)[:16]` | 충돌 저항 불필요 (레거시 호환) |
| 서브키 | `HMAC-SHA256(K, label)` — label `"session info"`, `"authenticated command"` | |
| 메타데이터 해시 | SHA256 | AES-GCM AAD |
| 명령 암호화 | AES-128-GCM, 12바이트 nonce, 16바이트 tag | |
| 명령 인증(HMAC 경로) | HMAC-SHA256 | Fleet API 전용. BLE에서는 AES-GCM 사용 |
| 난수 | CSPRNG: routing_address 16B, uuid 16B, nonce 12B | |
| 상수 시간 비교 | HMAC 태그 비교 | 필수 |

### 0.3 먼저 단위 테스트를 작성한다

`pkg/protocol/protocol.md`의 테스트 키/벡터로 다음을 고정한다 (자세한 값은 [03-protocol.md](03-protocol.md)):

1. `K(client.key, vehicle.pem) = 1b2fce19967b79db696f909cff89ea9a`
2. `SESSION_INFO_KEY = HMAC-SHA256(K, "session info") = fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300`
3. 메타데이터 `TLV({SIG_TYPE: 0x06, VIN: "5YJ30123456789ABC", CHALLENGE: 1588d5a3...})` = `000106021135594a333031323334353637383941424306101588d5a30eabc6f8fc9a951b11f6fd11ff`
4. 세션 정보 태그 = `996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002`
5. AES-GCM 메타데이터 `000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007070400000002ff`, 평문 `120452020801`, nonce `dbf79447fa156674dae1caed` → 암호문 `38038e8c0f2e`, 태그 `c228e0ff64991481db3a7bbc133696c5` (Go: `internal/authentication/protocol_doc_test.go`)
6. `VehicleLocalName("5YJS0000000000000") = "S1a87a5a75f3df858C"`

**테스트 키를 실제 차량에 등록하지 말 것** (개인 키가 공개되어 있음).

---

## 1. 광고 스캔

```
local_name = "S" + hex_lower(SHA1(VIN_ascii)[0:8]) + "C"    # 18자
scan(active=True)
for adv in advertisements:
    if adv.local_name == local_name:
        if not adv.connectable:  -> 오류 ErrMaxConnectionsExceeded (VCSEC는 최대 3개 연결)
        return adv.address
timeout -> 재시도 (Go는 ctx 만료까지 무한)
```

- Go: `ble.VehicleLocalName`, `scanVehicleBeacon` (`pkg/connector/ble/ble.go`). Linux 스캔 파라미터: active, interval/window 0x10 (10ms), filter policy 2.
- 광고 주기는 모델/상태에 따라 20ms~150ms. 깊은 수면에서는 더 드물 수 있으므로 스캔 타임아웃 20s 이상.
- 서비스 UUID 필터 대신 Local Name으로 식별한다 (광고에 서비스 UUID가 포함되지 않을 수 있음).

## 2. GATT 연결

```
connect(address)
svc  = discover_service("00000211-b2d1-43f0-9b88-960cebf8b91e")
tx   = discover_characteristic(svc, "00000212-b2d1-43f0-9b88-960cebf8b91e")   # 차량으로 write (with response)
rx   = discover_characteristic(svc, "00000213-b2d1-43f0-9b88-960cebf8b91e")   # 차량에서 indication
discover_descriptors(rx)                                                     # CCCD
subscribe(rx, mode=INDICATION, handler=on_rx)                                # Go: client.Subscribe(rxChar, ind=true, ...)
mtu = exchange_mtu(request=517)                                              # go-ble ble.MaxMTU. 실패 시 23
block_length = min(mtu, 1024) - 3                                            # ATT 헤더 3바이트. 실패 시 23-3 = 20
```

- Go: `tryToConnect`. 서비스/특성/구독/MTU 중 하나라도 실패하면 연결을 끊고 재시도(`retry=true`).
- 특성 213은 **indication**으로 구독한다 (Go의 `Subscribe(..., true, ...)`). 플랫폼이 notification만 지원하면 실제 차량에서 동작 여부를 확인할 것.
- 212는 "write with response" (Go `WriteCharacteristic(..., noRsp=false)`).

## 3. 프레이밍 (2바이트 길이 프리픽스)

송신 (Go `Connection.Send`):

```
frame = [len(msg) >> 8, len(msg) & 0xff] + msg      # 빅엔디언 uint16
for chunk in split(frame, block_length):
    write_with_response(tx, chunk)                   # 순서 보장, 직렬화(뮤텍스)
```

수신 (Go `Connection.rx` + `flush`):

```
on_rx(chunk):
    if now - last_rx > 1s: buffer = b""              # 조각 간 타임아웃 → 버퍼 폐기
    last_rx = now
    buffer += chunk
    loop:
        if len(buffer) < 2: break
        n = buffer[0]*256 + buffer[1]
        if n > 1024: buffer = b""; break             # maxBLEMessageSize 초과 → 폐기
        if len(buffer) < 2+n: break                  # 더 기다림
        msg = buffer[2:2+n]; buffer = buffer[2+n:]
        enqueue(msg)                                  # 큐(5) 가득 차면 드롭 (Go 동작). 포팅 시 더 큰 큐 권장
```

- 하나의 indication에 여러 메시지가 붙어 오거나, 하나의 메시지가 여러 indication으로 나뉘어 올 수 있다. 위 루프가 둘 다 처리한다.
- 메시지 최대 1024바이트 (`maxBLEMessageSize`). 응답이 이보다 크면 차량이 `RESPONSE_MTU_EXCEEDED`.

## 4. RoutableMessage 조립

Go `dispatcher.Send`:

```
msg = RoutableMessage()
msg.to_destination.domain = DOMAIN_VEHICLE_SECURITY(2) | DOMAIN_INFOTAINMENT(3)
msg.uuid = random(16)                                # 응답의 request_uuid로 돌아옴; 핸드셰이크 challenge로도 사용
if domain == VCSEC:
    routing_address = random(16)                     # 요청마다 새 주소 (VCSEC는 request_uuid를 안 채움)
else:
    routing_address = connection_address             # 연결당 고정 16바이트 (Go: Dispatcher.address)
msg.from_destination.routing_address = routing_address
msg.flags = 1 << FLAG_ENCRYPT_RESPONSE   # = 2. 항상 설정 (구 펌웨어는 무시)
msg.payload = protobuf_message_as_bytes | session_info_request
register_handler(key=(routing_address, uuid if domain!=VCSEC else 0, domain))
```

- `to_destination.domain == DOMAIN_BROADCAST(0)` 은 오류.
- 응답은 `to_destination.routing_address == 우리 주소`, `from_destination.domain == 요청 도메인`으로 온다.

## 5. 핸드셰이크

```
req = RoutableMessage(to=domain, session_info_request={public_key: ENCODE_PUBLIC(C)}, uuid=challenge)
send(req)
rsp = wait_response()                                 # RetryInterval(1s) 안에 없으면 재전송
if rsp.signedMessageStatus.signed_message_fault != NONE: 오류 (UNKNOWN_KEY_ID → 키 미등록)
info = SessionInfo.parse(rsp.session_info)
if info.status == KEY_NOT_ON_WHITELIST: 키 미등록
tag  = rsp.signature_data.session_info_tag.tag

# 키 합의
S_x = ECDH(c, info.publicKey)                          # 32바이트 X 좌표
K   = SHA1(S_x)[0:16]
SESSION_INFO_KEY = HMAC_SHA256(K, b"session info")

# 응답 무결성 검증 (필수)
M = TLV(TAG_SIGNATURE_TYPE=0x00, [0x06])              # SIGNATURE_TYPE_HMAC
  + TLV(TAG_PERSONALIZATION=0x02, VIN_ascii)
  + TLV(TAG_CHALLENGE=0x06, challenge)                # 요청의 uuid
  + 0xFF
expected = HMAC_SHA256(SESSION_INFO_KEY, M + rsp.session_info_bytes)   # 파싱 전 원본 bytes
if not constant_time_eq(expected, tag): 폐기

# 세션 상태 저장 (도메인별)
session[domain] = {
    K, vehicle_public: info.publicKey, epoch: info.epoch (16B),
    counter: info.counter,
    time_zero: now - info.clock_time seconds,          # 도메인 시계 원점(로컬 시각)
    set_time: info.clock_time,
}
```

- TLV: `tag(1) || len(1) || value`, 값 길이 ≤ 255, 태그 오름차순, 마지막 `0xFF`. 정수는 `BE32`.
- Go: `session.processHello` → `authentication.NewAuthenticatedSigner` → `NativeSession.SessionInfoHMAC` (HMAC 컨텍스트에 TLV를 직접 써 넣는 구현이지만 결과는 위 식과 같다).
- 두 도메인(VCSEC, Infotainment)에 각각 수행한다. Go는 병렬로 보낸다. Infotainment가 잠들어 있으면 그 핸드셰이크는 응답이 없다 → VCSEC로 `RKE_ACTION_WAKE_VEHICLE` 후 재시도.
- 요청 후 `AllowedLatency`(BLE 4s) 이상 지나서 온 session_info는 시계 동기화용으로 신뢰하지 않는다 (Go `receiver.expired`).

## 6. 명령 인가 (AES-GCM)

```
P = serialize(CarServer.Action | VCSEC.UnsignedMessage)
s = session[domain]
if s.counter == 0xFFFFFFFF: 오류 (롤오버; 재핸드셰이크)
s.counter += 1
expires_at = int((now + lifetime - s.time_zero).seconds)     # lifetime: ctx 남은 시간, 기본 5s. 너무 길면 TIME_TO_LIVE_TOO_LONG
flags = 2

M = TLV(0x00, [0x05])                 # SIGNATURE_TYPE_AES_GCM_PERSONALIZED
  + TLV(0x01, [domain])               # 2 또는 3
  + TLV(0x02, VIN_ascii)              # 17바이트
  + TLV(0x03, s.epoch)                # 16바이트
  + TLV(0x04, BE32(expires_at))
  + TLV(0x05, BE32(s.counter))
  + TLV(0x07, BE32(flags))            # flags != 0 일 때만 포함
  + 0xFF
AAD   = SHA256(M)
nonce = random(12)
ct, tag = AES_128_GCM_encrypt(key=K, nonce, plaintext=P, aad=AAD)

msg.payload.protobuf_message_as_bytes = ct
msg.signature_data.signer_identity.public_key = ENCODE_PUBLIC(C)
msg.signature_data.AES_GCM_Personalized_data = {epoch: s.epoch, nonce, counter: s.counter, expires_at, tag}
msg.flags = flags
request_hash = [0x05] + tag           # 응답 복호화용으로 저장 (§7)
```

- Go: `Signer.Encrypt` → `encryptWithCounter`, `Peer.extractMetadata`, `metadata.Add/AddUint32/Checksum`.
- HMAC 경로(Fleet API 전용)는 `[0x08]` 서명 타입, `K' = HMAC_SHA256(K, "authenticated command")`, `tag = HMAC_SHA256(K', M || P)`, 페이로드 평문, `HMAC_Personalized_data{epoch, counter, expires_at, tag}`. BLE에서는 사용하지 않는다.
- VCSEC는 카운터 순서를 엄격히 요구한다 → VCSEC 명령은 동시에 보내지 말고 직렬화한다. Infotainment는 슬라이딩 윈도우 허용.

## 7. 응답 처리

```
on_message(rsp):
    if rsp.from_destination is None or rsp.to_destination.routing_address is None: 드롭
    key = (rsp.to_destination.routing_address,
           rsp.request_uuid if rsp.from_destination.domain != VCSEC else 0,
           rsp.from_destination.domain)
    handler = handlers.get(key) or 드롭

    # (a) 동봉 session_info 로 세션 갱신 (오류 응답에 흔히 동봉됨)
    if rsp.session_info and rsp.signature_data.session_info_tag:
        if not handler.expired(AllowedLatency): verify_and_update(domain, challenge=rsp.request_uuid, ...)   # §8 규칙

    # (b) 암호화 응답 복호화
    if rsp.signature_data.AES_GCM_Response_data:
        g = rsp.signature_data.AES_GCM_Response_data
        M = TLV(0x00, [0x09])                            # SIGNATURE_TYPE_AES_GCM_RESPONSE
          + TLV(0x01, [rsp.from_destination.domain])
          + TLV(0x02, VIN_ascii)
          + TLV(0x05, BE32(g.counter))
          + TLV(0x07, BE32(rsp.flags))                  # 응답 flags, 0이어도 항상 포함
          + TLV(0x08, handler.request_hash)             # §6에서 저장. VCSEC + HMAC이면 태그 16바이트로 절단
          + TLV(0x09, BE32(rsp.signedMessageStatus.signed_message_fault))
          + 0xFF
        plaintext = AES_128_GCM_decrypt(K, g.nonce, ct=rsp.protobuf_message_as_bytes, aad=SHA256(M), tag=g.tag)
        if fail: 드롭 (로그)
        if not handler.window.accept(g.counter): 드롭   # 요청별 슬라이딩 윈도우(32). 리플레이 방지
        rsp.protobuf_message_as_bytes = plaintext

    # (c) 프로토콜 오류
    fault = rsp.signedMessageStatus.signed_message_fault
    if fault != NONE: handler.deliver(error(fault)); return        # 08-errors §3.1
    if rsp.signedMessageStatus.operation_status == WAIT: handler.deliver(BUSY)

    # (d) 도메인별 페이로드
    if domain == INFOTAINMENT:
        r = CarServer.Response.parse(payload)
        if r.actionStatus.result == OPERATIONSTATUS_ERROR: 애플리케이션 오류 r.actionStatus.result_reason.plain_text
        else: 성공 (r.vehicleData 등)
        handler.done()                                   # 응답 1개
    else:  # VCSEC — 최대 3개 응답. 종료 규칙:
        f = VCSEC.FromVCSECMessage.parse(payload)        # payload 없음 → 빈 메시지 = 성공
        if f.nominalError: 오류 GenericError_E; done
        if f.commandStatus:
            if f.commandStatus.operationStatus == WAIT: 재시도(BUSY)
            if f.commandStatus.operationStatus == ERROR and f.commandStatus.whitelistOperationStatus.whitelistOperationInformation != NONE: KeychainError; done
            if whitelist 작업이고 whitelistOperationStatus 존재: done (NONE=성공)
            else: 계속 대기 (중간 상태)
        else:
            RKE/Closure: commandStatus 없는 메시지 = 최종 성공; 정보 요청: 첫 메시지가 결과
```

- Go: `dispatcher.process`, `checkForSessionUpdate`, `decrypt`, `Signer.Decrypt`, `Peer.responseMetadata`, `authentication.RequestID`, `SlidingWindow.Update`; 페이로드 해석은 `pkg/vehicle/vcsec.go unmarshalVCSECResponse/readUntil`, `infotainment.go getCarServerResponse`.
- `request_hash`: `[서명 타입 바이트] || 요청 태그`. AES-GCM 요청이면 `0x05 || tag(16)`. HMAC 요청이면 `0x08 || tag(32)`, 단 VCSEC 대상이면 `tag[:16]`.
- 응답을 받지 못한 채 타임아웃되면 "실행됐을 수 있음"으로 취급하고 자동 재전송하지 않는다.

## 8. 재시도와 세션 복구

재시도 가능한 fault (Go `retriableErrors`): `BUSY(1)`, `TIMEOUT(2)`, `INVALID_SIGNATURE(5)`, `INVALID_TOKEN_OR_COUNTER(6)`, `INTERNAL(11)`, `INCORRECT_EPOCH(15)`, `TIME_EXPIRED(17)`, `TIME_TO_LIVE_TOO_LONG(20)`, 그리고 `operation_status == WAIT`. 재시도 시 §6을 처음부터 다시 수행한다 (새 카운터, 새 nonce, 새 expires_at). 간격 1s. `RESPONSE_MTU_EXCEEDED(25)`는 "실행됨"이므로 재시도 금지.

세션 갱신 규칙 (`protocol.md` "Recovering from synchronization errors" + Go `Signer.UpdateSessionInfo`):

```
verify_and_update(domain, challenge, info_bytes, tag):
    if challenge 가 최근 몇 초 내 우리가 보낸 uuid 가 아님: 폐기
    if HMAC(SESSION_INFO_KEY, TLV(sig=0x06, VIN, challenge) + 0xFF + info_bytes) != tag: 폐기
    info = parse(info_bytes)
    if info.publicKey != s.vehicle_public: 폐기
    if info.epoch != s.epoch or s.set_time <= info.clock_time:
        s.counter = max(s.counter, info.counter)       # 카운터는 내리지 않는다 (Go 구현). 사양은 "epoch 변경 시 제외" 허용
        s.epoch = info.epoch; s.set_time = info.clock_time; s.time_zero = now - info.clock_time
    else: 무시 (더 오래된 시각의 정보)
```

## 9. 세션 캐시 형식 (Go 호환)

`pkg/cache/cache.go` + `internal/dispatcher/session.go CacheEntry`:

```json
{
  "MaxEntries": 0,
  "vehicles": {
    "5YJ30123456789ABC": [
      {"created_at": "2026-09-26T08:00:00.123456789+09:00", "domain": 2, "data": "<base64 std, Signatures.SessionInfo>"},
      {"created_at": "2026-09-26T08:00:00.123456789+09:00", "domain": 3, "data": "..."}
    ]
  }
}
```

- `data`는 `SessionInfo{counter, publicKey(차량), epoch, clock_time}`의 protobuf 바이트. `clock_time`은 **`created_at` 시점의 도메인 시각**이어야 한다 (Go `ExportSessionInfo`는 `timestamp()` = `now - time_zero`를 넣고, `ImportSessionInfo`는 `time_zero = created_at - clock_time`으로 복원).
- `created_at`은 RFC3339Nano (Go `time.Time` JSON). `domain`은 정수(2/3). `MaxEntries`는 태그 없는 필드라 대문자.
- 다른 언어에서 자체 형식을 써도 되지만, 캐시는 개인 키에 묶이며 카운터/epoch를 담으므로 파일 권한을 0600으로 제한한다.
- 캐시로 복원한 세션은 즉시 사용 가능(ready)으로 취급하고, 첫 명령 실패 시 §8로 복구한다.

## 10. 키 페어링 (개인 키 없이)

Go `SendAddKeyRequestWithRole`은 **RoutableMessage로 감싸지 않고** 다음 바이트를 `conn.Send`로 직접 보낸다:

```
inner = VCSEC.UnsignedMessage{
    WhitelistOperation{
        addKeyToWhitelistAndAddPermissions: PermissionChange{ key: PublicKey{PublicKeyRaw: ENCODE_PUBLIC(C)}, keyRole: ROLE_OWNER(2)|ROLE_DRIVER(3)|... },
        metadataForKey: KeyMetadata{ keyFormFactor: KEY_FORM_FACTOR_CLOUD_KEY(9)|IOS_DEVICE(6)|ANDROID_DEVICE(7)|NFC_CARD(1) }
    }
}
envelope = VCSEC.ToVCSECMessage{ signedMessage: SignedMessage{ protobufMessageAsBytes: serialize(inner), signatureType: SIGNATURE_TYPE_PRESENT_KEY(2) } }
ble_send(serialize(envelope))          # §3 프레이밍 적용
```

- 사용자가 NFC 카드를 센터 콘솔에 대고 화면에서 확인해야 한다. 차량은 `FromVCSECMessage.commandStatus.operationStatus = WAIT`를 보내며 탭을 기다린다 (Go는 이 응답을 읽지 않는다).
- 등록 확인: 해당 공개 키로 §5 핸드셰이크(`SessionInfoRequest`)를 Infotainment 도메인에 보내 `status == OK`이면 완료. VCSEC 등록 후 Infotainment 동기화까지 수십 초 걸릴 수 있다.
- 인터넷 경로에서는 불가능 (Go: `ErrRequiresBLE`). 실패 코드는 `WhitelistOperation_information_E` ([08-errors.md](08-errors.md) §3.7).

## 11. 검증 절차

1. **벡터 테스트** (§0.3) 통과.
2. **바이트 비교**: `tesla-control -ble -vin $VIN -debug list-keys` (비인증) 의 `TX:` hex와 내 구현의 프레이밍 전 바이트를 비교. 랜덤 필드(`uuid`, `routing_address`)를 제외하면 동일해야 한다. `protoc --decode=UniversalMessage.RoutableMessage -I pkg/protocol/protobuf pkg/protocol/protobuf/*.proto` 로 양쪽을 디코딩해 필드 단위로 대조.
3. **비인증 명령 먼저**: `InformationRequest{GET_STATUS}` (VCSEC, `body-controller-state`), `GET_WHITELIST_INFO` (`list-keys`), `SessionInfoRequest` (`session-info`). 이 세 가지가 되면 §1~§4, §7(평문)이 맞다.
4. **핸드셰이크 검증**: `session-info public_key.pem vcsec` 출력의 `epoch/counter/clock_time`과 내 구현이 받은 값 비교. HMAC 검증이 통과해야 한다.
5. **인증 명령**: `RKE_ACTION_LOCK`/`UNLOCK` (VCSEC) → `HvacAutoAction` (Infotainment). 첫 명령이 `INVALID_SIGNATURE`면 메타데이터 TLV 순서/길이/`flags` 포함 규칙을, `TIME_EXPIRED`면 `expires_at` 계산(도메인 시계 기준)을, `INVALID_TOKEN_OR_COUNTER`면 카운터 증가 시점을 확인.
6. **응답 복호화**: `AES_GCM_Response_data`가 있는 응답이 복호화되면 §7(b)가 맞다. 실패하면 `request_hash`(요청 태그 저장)와 응답 메타데이터의 `flags` 항상 포함/`fault` 포함 규칙을 확인.
7. **복구**: 캐시를 일부러 낡게 만든 뒤(카운터 감소) 첫 명령 실패 → 동봉 session_info로 갱신 → 재전송 성공을 확인.

## 12. 플랫폼별 주의와 보안 체크리스트

| 플랫폼 | 주의 |
|---|---|
| iOS/macOS (CoreBluetooth) | Local Name은 `CBAdvertisementDataLocalNameKey`; 백그라운드에서는 Local Name이 제한될 수 있어 서비스 UUID 스캔이 필요할 수 있음. MTU는 `maximumWriteValueLength(for: .withResponse)`. 어댑터 ID 선택 불가. 앱 Bluetooth 권한 필요. |
| Android (BluetoothGatt) | `requestMtu(517)` 후 `onMtuChanged`를 기다린 뒤 block_length 계산. 213은 `setCharacteristicNotification` + CCCD에 `ENABLE_INDICATION_VALUE` 기록. 쓰기는 `WRITE_TYPE_DEFAULT`(응답 있음)이며 `onCharacteristicWrite` 후 다음 청크. 스캔 필터는 device name. Android 12+ `BLUETOOTH_SCAN/CONNECT` 권한. |
| Linux (BlueZ, bleak/btleplug) | 원시 HCI 접근이면 `CAP_NET_ADMIN`; BlueZ D-Bus면 불필요. 어댑터 선택 `hciN`. 여러 어댑터 초기화 반복 시 실패(Go 주석) → 어댑터 객체 재사용. |
| Windows | Go 구현은 미지원. WinRT BLE로 구현 가능하나 검증 사례 없음. |
| 공통 | 연결은 VCSEC 최대 3개. 명령을 보내지 않을 때는 연결을 끊어 키포브/폰키 슬롯을 비운다. |

보안 체크리스트

- [ ] 개인 키는 OS 키체인/Keystore/Secure Enclave 등 보호 저장소에. Go는 `99designs/keyring` (macOS Keychain 등).
- [ ] 세션 캐시 파일 0600, 다른 사용자가 읽지 못하게.
- [ ] 문서의 테스트 키(`client.key`, `vehicle.key`)를 실제 차량에 등록하지 않는다.
- [ ] HMAC 태그 비교는 상수 시간 함수.
- [ ] nonce는 명령마다 새 CSPRNG 12바이트. 재사용 금지.
- [ ] `session_info_tag` 검증 없이 세션을 갱신하지 않는다 (MITM이 만료 시각을 늘리는 공격 방지).
- [ ] 응답 카운터 슬라이딩 윈도우 구현 (리플레이 방지).
- [ ] `expires_at`은 짧게 (5~10s). 긴 TTL은 차량이 거부한다.
- [ ] 로그에 K, 개인 키, 복호화 전 페이로드를 남기지 않는다 (Go `-debug`는 TX/RX 암호문 hex만 남긴다).

## 부록: 단계 ↔ Go 구현 대응표

| 단계 | 파일:함수 |
|---|---|
| 1 스캔 | `pkg/connector/ble/ble.go`: `VehicleLocalName`, `ScanVehicleBeacon`, `scanVehicleBeacon`; `device_linux.go`: `scanParams` |
| 2 GATT | `ble.go`: `tryToConnect` (Dial → DiscoverServices → DiscoverCharacteristics → DiscoverDescriptors → Subscribe → ExchangeMTU) |
| 3 프레이밍 | `ble.go`: `Connection.Send`, `Connection.rx`, `Connection.flush`; 상수 `maxBLEMessageSize=1024`, `rxTimeout=1s` |
| 4 RoutableMessage | `internal/dispatcher/dispatcher.go`: `Dispatcher.Send`, `SessionInfoRequest`; `receiver.go`: `receiverKey` |
| 5 핸드셰이크 | `dispatcher.go`: `StartSession`, `tryStartSession`, `StartSessions`, `checkForSessionUpdate`; `session.go`: `processHello`; `internal/authentication/signer.go`: `NewAuthenticatedSigner`, `ImportSessionInfo`; `native.go`: `NativeECDHKey.Exchange`, `NativeSession.SessionInfoHMAC` |
| 6 인가 | `session.go`: `authorize`; `signer.go`: `Encrypt`, `encryptWithCounter`, `AuthorizeHMAC`; `peer.go`: `extractMetadata`, `hmacTag`; `metadata.go`; `crypto.go`: 라벨 상수 |
| 7 응답 | `dispatcher.go`: `process`, `decrypt`; `session.go`: `decrypt`; `signer.go`: `Decrypt`; `peer.go`: `RequestID`, `responseMetadata`; `window.go`: `SlidingWindow`; `pkg/vehicle/vcsec.go`: `unmarshalVCSECResponse`, `readUntil`; `infotainment.go`: `getCarServerResponse`; `pkg/protocol/error.go`: `GetError` |
| 8 재시도/복구 | `pkg/vehicle/vehicle.go`: `Send`, `StartSession`; `pkg/protocol/error.go`: `ShouldRetry`, `retriableErrors`; `signer.go`: `UpdateSignedSessionInfo`, `UpdateSessionInfo` |
| 9 캐시 | `pkg/cache/cache.go`; `session.go`: `CacheEntry`, `export`; `dispatcher.go`: `Cache`, `LoadCache`; `signer.go`: `ExportSessionInfo` |
| 10 페어링 | `pkg/vehicle/security.go`: `SendAddKeyRequestWithRole`; `vcsec.go`: `addKeyPayload`; `pkg/vehicle/vehicle.go`: `SessionInfo` |
| 11 검증 | `cmd/tesla-control` (`-debug`, `list-keys`, `session-info`, `body-controller-state`); `internal/authentication/protocol_doc_test.go`; `pkg/protocol/protocol.md` |

---

## 검증 체크리스트

- [ ] UUID 3개, Local Name 규칙, 길이 프리픽스(빅엔디언 2바이트), `maxBLEMessageSize=1024`, `rxTimeout=1s`, `blockLength = min(mtu,1024)-3` 이 `pkg/connector/ble/ble.go`와 일치.
- [ ] TLV 태그 값(0 SIGNATURE_TYPE, 1 DOMAIN, 2 PERSONALIZATION, 3 EPOCH, 4 EXPIRES_AT, 5 COUNTER, 6 CHALLENGE, 7 FLAGS, 8 REQUEST_HASH, 9 FAULT, 255 END)과 서명 타입 값(5 AES_GCM_PERSONALIZED, 6 HMAC, 8 HMAC_PERSONALIZED, 9 AES_GCM_RESPONSE)이 `signatures.proto`와 일치.
- [ ] 요청 메타데이터는 `flags != 0`일 때만 FLAGS 포함, 응답 메타데이터는 항상 포함 — `peer.go extractMetadata` / `responseMetadata`와 일치.
- [ ] `request_hash` 절단 규칙(VCSEC + HMAC → 16바이트)이 `peer.go RequestID`와 일치.
- [ ] 세션 갱신 시 카운터를 내리지 않는 Go 동작(`signer.go UpdateSessionInfo`)을 반영.
- [ ] 캐시 JSON 키(`vehicles`, `created_at`, `domain`, `data`, `MaxEntries`)가 `cache.go`/`session.go`와 일치.
- [ ] 페어링 메시지가 RoutableMessage가 아닌 `ToVCSECMessage`임을 `security.go`에서 재확인.
- [ ] §0.3 벡터가 `pkg/protocol/protocol.md`의 값과 바이트 단위로 일치.
