# 03. 프로토콜 사양

`pkg/protocol/protocol.md`를 에이전트용으로 재구성한 정밀 사양. 메시지 인코딩, 도메인, 핸드셰이크, 키 합의, 메타데이터 직렬화, HMAC/AES-GCM 명령 인증, 응답 복호화, 세션 복구, 응답 해석 규칙, Go 구현 매핑. 테스트 벡터는 원문 값을 그대로 보존한다. 충돌 시 `pkg/protocol/protocol.md`와 `*.proto`가 우선한다.

관련 파일: `pkg/protocol/protocol.md`, `pkg/protocol/protobuf/universal_message.proto`, `signatures.proto`, `keys.proto`, `vcsec.proto`, `car_server.proto`, `errors.proto`, `internal/authentication/{signer,peer,metadata,native,crypto,window}.go`, `internal/dispatcher/{dispatcher,session,receiver}.go`, `pkg/protocol/error.go`, `internal/authentication/protocol_doc_test.go`

---

## 1. 메시지 인코딩: `RoutableMessage`

`UniversalMessage.RoutableMessage` (`universal_message.proto`, Go 패키지 `universalmessage`). 모든 바이트 필드는 원시 바이너리이며 아래 hex는 표기용이다.

| 필드 | 번호 | 타입 | 요청(클라이언트→차량) | 응답(차량→클라이언트) |
|---|---|---|---|---|
| `to_destination` | 6 | `Destination` | `domain` = 대상 도메인 | `routing_address` = 요청의 `from_destination` |
| `from_destination` | 7 | `Destination` | `routing_address` = 16바이트 랜덤 (연결/요청 식별) | `domain` = 응답한 도메인 |
| `payload.protobuf_message_as_bytes` | 10 | `bytes` (oneof `payload`) | 애플리케이션 페이로드 (VCSEC: `VCSEC.UnsignedMessage`, Infotainment: `CarServer.Action`) | VCSEC: `VCSEC.FromVCSECMessage`, Infotainment: `CarServer.Response`. 암호화되어 있을 수 있음 |
| `payload.session_info_request` | 14 | `SessionInfoRequest` | 핸드셰이크 요청 | — |
| `payload.session_info` | 15 | `bytes` | — | 직렬화된 `Signatures.SessionInfo` (핸드셰이크 응답 또는 오류에 동반) |
| `signedMessageStatus` | 12 | `MessageStatus` | — | 프로토콜 계층 오류 (`operation_status`, `signed_message_fault`) |
| `sub_sigData.signature_data` | 13 | `Signatures.SignatureData` | 인증 정보 | 세션 정보 태그 또는 응답 암호화 정보 |
| `request_uuid` | 50 | `bytes` | — | 요청의 `uuid` 복사 (**VCSEC는 보통 비움**) |
| `uuid` | 51 | `bytes` | ≤16바이트, 예측 불가 랜덤 (재전송된 핸드셰이크 응답 방지) | — |
| `flags` | 52 | `uint32` | `Flags` 비트마스크. 항상 `FLAG_ENCRYPT_RESPONSE` 설정 권장 | 응답 플래그 (응답 메타데이터에 포함) |

예약: 1–5, 11, 16–40.

```proto
message Destination { oneof sub_destination { Domain domain = 1; bytes routing_address = 2; } }
enum Domain { DOMAIN_BROADCAST = 0; DOMAIN_VEHICLE_SECURITY = 2; DOMAIN_INFOTAINMENT = 3; }
enum OperationStatus_E { OPERATIONSTATUS_OK = 0; OPERATIONSTATUS_WAIT = 1; OPERATIONSTATUS_ERROR = 2; }
message MessageStatus { OperationStatus_E operation_status = 1; MessageFault_E signed_message_fault = 2; }
message SessionInfoRequest { bytes public_key = 1; bytes challenge = 2; }
enum Flags { FLAG_USER_COMMAND = 0; FLAG_ENCRYPT_RESPONSE = 1; }   // 비트 인덱스. ENCRYPT_RESPONSE → flags = 1<<1 = 2
```

`MessageFault_E` 전체 표는 [08-errors.md](08-errors.md). Go 클라이언트는 `vehicle.DefaultFlags = uint32(1 << universal.Flags_FLAG_ENCRYPT_RESPONSE)` (= 2)를 모든 요청에 넣는다. 차량은 flags를 인증하지만 모르는 비트는 무시한다. 2024.38+ 펌웨어만 응답을 암호화하고, 이전 펌웨어는 플래그를 무시하므로 펌웨어 확인 없이 항상 설정한다.

전형적인 요청 (원문 예시):

```proto
to_destination { domain: DOMAIN_VEHICLE_SECURITY }
from_destination { routing_address: 0a7962c10d38b61dd2a7722780a4f096 }
protobuf_message_as_bytes: 0a020805
uuid: 05514f57616bcc81a8ce0f9d7b483229
```

---

## 2. 서명 관련 메시지 (`signatures.proto`)

```proto
enum Tag {
    TAG_SIGNATURE_TYPE  = 0;  TAG_DOMAIN = 1;  TAG_PERSONALIZATION = 2;  TAG_EPOCH = 3;
    TAG_EXPIRES_AT = 4;       TAG_COUNTER = 5; TAG_CHALLENGE = 6;        TAG_FLAGS = 7;
    TAG_REQUEST_HASH = 8;     TAG_FAULT = 9;   TAG_END = 255;
}
enum SignatureType {
    SIGNATURE_TYPE_AES_GCM = 0;  SIGNATURE_TYPE_AES_GCM_PERSONALIZED = 5;  SIGNATURE_TYPE_HMAC = 6;
    reserved 7;                  SIGNATURE_TYPE_HMAC_PERSONALIZED = 8;     SIGNATURE_TYPE_AES_GCM_RESPONSE = 9;
}
message KeyIdentity { reserved 2; oneof identity_type { bytes public_key = 1; uint32 handle = 3; } }

message AES_GCM_Personalized_Signature_Data { bytes epoch = 1; bytes nonce = 2; uint32 counter = 3; fixed32 expires_at = 4; bytes tag = 5; }
message AES_GCM_Response_Signature_Data     { bytes nonce = 1; uint32 counter = 2; bytes tag = 3; }
message HMAC_Signature_Data                 { bytes tag = 1; }
message HMAC_Personalized_Signature_Data    { bytes epoch = 1; uint32 counter = 2; fixed32 expires_at = 3; bytes tag = 4; }

message SignatureData {
    reserved 7;
    KeyIdentity signer_identity = 1;
    oneof sig_type {
        AES_GCM_Personalized_Signature_Data AES_GCM_Personalized_data = 5;   // 요청 (AES-GCM)
        HMAC_Signature_Data                 session_info_tag          = 6;   // 핸드셰이크 응답
        HMAC_Personalized_Signature_Data    HMAC_Personalized_data    = 8;   // 요청 (HMAC)
        AES_GCM_Response_Signature_Data     AES_GCM_Response_data     = 9;   // 암호화된 응답
    }
}
enum Session_Info_Status { SESSION_INFO_STATUS_OK = 0; SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST = 1; }
message SessionInfo { uint32 counter = 1; bytes publicKey = 2; bytes epoch = 3; fixed32 clock_time = 4; Session_Info_Status status = 5; uint32 handle = 6; }
```

---

## 3. 도메인, 시간, 역할

| 도메인 | 값 | 담당 | 비고 |
|---|---|---|---|
| `DOMAIN_VEHICLE_SECURITY` (VCSEC) | 2 | 잠금/해제, 원격 시동, 트렁크/프렁크/토노, 키체인(화이트리스트), 차체 상태, 웨이크 | Infotainment가 잠들어 있어도 BLE로 응답. 메모리 제약 |
| `DOMAIN_INFOTAINMENT` | 3 | 나머지(공조, 충전, 미디어, 상태, PIN, 센트리, 스케줄 등) | 별도 키 쌍/세션 |
| `DOMAIN_BROADCAST` | 0 | 사용 안 함 (`Dispatcher.Send`가 거부) | |

- **시간**: 도메인마다 `(epoch_id 16바이트 랜덤, timestamp 초)` 쌍. epoch는 부팅 시 생성. 클라이언트는 `timeZero = 로컬 수신 시각 − clock_time`으로 오프셋을 추적한다 (`epochStartTime`).
- **역할** (`keys.proto` `Keys.Role`): `ROLE_NONE=0, ROLE_SERVICE=1, ROLE_OWNER=2, ROLE_DRIVER=3, ROLE_FM=4, ROLE_VEHICLE_MONITOR=5, ROLE_CHARGING_MANAGER=6, ROLE_GUEST=8`.

| 역할 | 권한 요약 (protocol.md "Roles") |
|---|---|
| Owner | 모든 명령, 다른 사용자 키 추가/삭제 |
| Driver | 대부분 명령. 키 관리/접근 제어(PIN 변경 등) 불가 |
| Fleet Manager (FM) | 클라우드 Owner 키. 2023.38+에서 타 사용자 키 추가/삭제 불가, **BLE 명령 불가**. 접근 관리는 Fleet API 계정 수준으로 |
| Vehicle Monitor | 데이터 읽기(위치 등)만. 상태 변경 명령 불가 |
| Charging Manager | 데이터 읽기 + 충전 관련 명령 |
| Guest | 임시 Driver (렌탈용, 자동 수명 주기) |
| Service | 키 부트스트랩, 서비스 기술자 명령. 원격 (un)lock, Driver/Guest/FM 키 원격 삭제(추가는 불가). 기본 상태에서 인터넷 경유 다른 명령은 차단 |

---

## 4. 메타데이터 직렬화 (TLV)

- 항목 = `TAG(1바이트) || LEN(1바이트) || VALUE`. VALUE ≤ 255바이트.
- 정수(uint32)는 **big-endian 4바이트**.
- 집합 직렬화: **태그 오름차순** 정렬 → 각 항목 직렬화 → 연결 → 마지막에 `0xFF` (`TAG_END`).
- Go 구현(`metadata.go`)은 태그가 감소하면 `errOutOfOrderMetadata`, `nil` 값은 건너뜀, 255 초과면 `ErrMetadataFieldTooLong`. `Checksum(msg)`는 `0xFF || msg`를 마저 넣고 해시를 반환한다.

```
TLV(VIN: "abc")     = 0x02 || 0x03 || 0x61 0x62 0x63          = 0203616263
TLV(COUNTER: 100)   = 0x05 || 0x04 || 0x00000064              = 050400000064
SERIALIZE({COUNTER: 100, VIN: "abc"}) = 0203616263 || 050400000064 || FF = 0203616263050400000064FF
```

---

## 5. 표기법

| 기호 | 의미 |
|---|---|
| `c` / `C = (Cx, Cy)` | 클라이언트 개인 키 / 공개 키 (NIST P-256 점) |
| `v` / `V = (Vx, Vy)` | 차량(도메인) 개인 키 / 공개 키 |
| `s[:n]` | 앞 n바이트 |
| `BIG_ENDIAN(m, n)` | m의 n바이트 big-endian, 0x00 패딩 |
| `K` | 128비트 AES-GCM 공유 키 |
| `ENCODE_PUBLIC(P)` | `0x04 || BIG_ENDIAN(x,32) || BIG_ENDIAN(y,32)` (비압축 SEC1, 65바이트) |

---

## 6. 테스트 키

> **경고**: 아래 키는 테스트 벡터 검증에만 사용한다. 개인 키가 공개되어 있으므로 공개 키를 실제 차량에 등록하면 무단 접근이 가능하다.

### 6.1 차량 키

`vehicle.key`:

```
-----BEGIN EC PRIVATE KEY-----
MHcCAQEEIDRO5bRmp88e6xK29QMx2y5exYNO9fS+/P2MvlXCUo1woAoGCCqGSM49
AwEHoUQDQgAEx6H0cThIaqRymXFJSHjTOxok45Vx90im4WxZVbPYd9OmqqDpVRZk
dK9dMsQQ9DmiI0E3rRuwhf1OiBPJWPEdlw==
-----END EC PRIVATE KEY-----
```

`vehicle.pem`:

```
-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEx6H0cThIaqRymXFJSHjTOxok45Vx
90im4WxZVbPYd9OmqqDpVRZkdK9dMsQQ9DmiI0E3rRuwhf1OiBPJWPEdlw==
-----END PUBLIC KEY-----
```

```
v  = 0x344EE5B466A7CF1EEB12B6F50331DB2E5EC5834EF5F4BEFCFD8CBE55C2528D70
Vx = 0xC7A1F47138486AA4729971494878D33B1A24E39571F748A6E16C5955B3D877D3
Vy = 0xA6AAA0E955166474AF5D32C410F439A2234137AD1BB085FD4E8813C958F11D97
HEX(ENCODE_PUBLIC(V)) = 04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97
```

### 6.2 클라이언트 키

`client.key`:

```
-----BEGIN EC PRIVATE KEY-----
MHcCAQEEICU4zcKal8GcHpmmN9bPT4yXDBGLVu3h5jI+bRYsSzDboAoGCCqGSM49
AwEHoUQDQgAEsra8aMLaBmXOZWgVWUmWxiOU7di+qQX+eBp1T+aoRacUMwkC8iXp
Jp1GbgWzSZgf2p2FzCPG+0RKpztikQXcbg==
-----END EC PRIVATE KEY-----
```

`client.pem`:

```
-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsra8aMLaBmXOZWgVWUmWxiOU7di+
qQX+eBp1T+aoRacUMwkC8iXpJp1GbgWzSZgf2p2FzCPG+0RKpztikQXcbg==
-----END PUBLIC KEY-----
```

```
c  = 0x2538CDC29A97C19C1E99A637D6CF4F8C970C118B56EDE1E6323E6D162C4B30DB
Cx = 0xB2B6BC68C2DA0665CE656815594996C62394EDD8BEA905FE781A754FE6A845A7
Cy = 0x14330902F225E9269D466E05B349981FDA9D85CC23C6FB444AA73B629105DC6E
HEX(ENCODE_PUBLIC(C)) = 04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a714330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e
```

---

## 7. 핸드셰이크

전제: 클라이언트 공개 키 `C`가 차량에 등록되어 있다 (등록 절차는 [05-command-catalog.md](05-command-catalog.md)의 `add-key-request`).

### 7.1 요청

대상 도메인으로 `RoutableMessage`를 보내되 `session_info_request.public_key = ENCODE_PUBLIC(C)`. (`challenge` 필드는 Go 구현이 채우지 않으며 `uuid`가 challenge 역할을 한다.)

```proto
to_destination { domain: DOMAIN_INFOTAINMENT }
from_destination { routing_address: 2c907bd76c640d360b3027dc7404efde }
session_info_request {
  public_key: 04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a714330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e
}
uuid: 1588d5a30eabc6f8fc9a951b11f6fd11
```

Go: `dispatcher.SessionInfoRequest(domain, publicBytes)` → `Dispatcher.RequestSessionInfo` (개인 키 없으면 `ErrRequiresKey`).

### 7.2 응답

```proto
to_destination { routing_address: 2c907bd76c640d360b3027dc7404efde }
from_destination { domain: DOMAIN_INFOTAINMENT }
signature_data {
  session_info_tag {
    tag: 996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002
  }
}
session_info: 0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d971a104c463f9cc0d3d26906e982ed224adde6255a0a0000
request_uuid: 1588d5a30eabc6f8fc9a951b11f6fd11
```

`session_info` 디코딩:

```
counter: 6
publicKey: 04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97
epoch: 4c463f9cc0d3d26906e982ed224adde6
clock_time: 2650
```

`status == SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST`면 `protocol.ErrKeyNotPaired` (`protocol.GetError`).

### 7.3 키 합의

```
S = (Sx, Sy) = ECDH(c, V) = ECDH(v, C)
K = SHA1(BIG_ENDIAN(Sx, 32))[:16]
```

OpenSSL 검증:

```bash
export K=$(openssl pkeyutl -derive -inkey client.key -peerkey vehicle.pem \
    | openssl dgst -sha1 -binary \
    | head -c 16 \
    | xxd -p)
echo $K
1b2fce19967b79db696f909cff89ea9a
```

Go: `NativeECDHKey.Exchange` — `elliptic.P256().ScalarMult(x, y, D)` → `sharedX.FillBytes(32바이트)` → `sha1.Sum` → `[:16]` → `aes.NewCipher` + `cipher.NewGCM`. 성숙한 암호 라이브러리를 쓰고 직접 구현하지 않는다.

### 7.4 응답 인증 (세션 정보 태그 검증)

MITM이 만료 시간을 늘리는 것을 막기 위해 클라이언트는 세션 정보를 **반드시** 인증한다.

```
SESSION_INFO_KEY = HMAC-SHA256(K, "session info")          // ASCII 리터럴
```

```bash
export SESSION_INFO_KEY=$(\
    echo -n "session info" \
    | openssl dgst -sha256 -mac hmac -macopt hexkey:"$K")
echo $SESSION_INFO_KEY
fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300
```

메타데이터 (4절 규칙으로 직렬화):

| 태그 | 값 |
|---|---|
| `TAG_SIGNATURE_TYPE` (0) | `SIGNATURE_TYPE_HMAC` = 6 |
| `TAG_PERSONALIZATION` (2) | VIN |
| `TAG_CHALLENGE` (6) | 핸드셰이크 요청의 `uuid` |

`VIN = 5YJ30123456789ABC` 예시:

```
METADATA = TLV(TAG_SIGNATURE_TYPE, Signatures.SIGNATURE_TYPE_HMAC) ||
           TLV(TAG_PERSONALIZATION, "5YJ30123456789ABC") ||
           TLV(TAG_CHALLENGE, 1588d5a30eabc6f8fc9a951b11f6fd11) ||
           ff
         = 00 01 06 ||
           02 11 35594a3330313233343536373839414243 ||
           06 10 1588d5a30eabc6f8fc9a951b11f6fd11 ||
           ff
         = 000106021135594a333031323334353637383941424306101588d5a30eabc6f8fc9a951b11f6fd11ff
```

예상 태그 = `HMAC-SHA256(SESSION_INFO_KEY, METADATA || session_info)`:

```bash
export SESSION_INFO=0806124104c... (위 응답의 session_info 전체)
echo "$METADATA$SESSION_INFO" | xxd -r -p | openssl dgst -sha256 -mac hmac -macopt hexkey:"$SESSION_INFO_KEY"
996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002
```

이 값을 `signature_data.session_info_tag.tag`와 **상수 시간 비교**(`hmac.Equal`)한다. 불일치면 폐기. Go: `NativeSession.SessionInfoHMAC(id, challenge, encodedInfo)` — `newMetadataHash(NewHMAC("session info"))`에 위 세 태그를 넣고 `Checksum(encodedInfo)`.

핸드셰이크 완료 후 클라이언트가 가진 것: 도메인 시간 `(epoch, timestamp)`, 안티리플레이 카운터, 공유 키 `K`.

---

## 8. 명령 인증

명령 protobuf `P` (VCSEC: `VCSEC.UnsignedMessage`, Infotainment: `CarServer.Action`)를 만든 뒤 아래 메타데이터 `M`을 직렬화한다.

| 값 | 태그 | 설명 |
|---|---|---|
| 서명 타입 | `TAG_SIGNATURE_TYPE` (0) | `SIGNATURE_TYPE_HMAC_PERSONALIZED` (8) 또는 `SIGNATURE_TYPE_AES_GCM_PERSONALIZED` (5) |
| 도메인 | `TAG_DOMAIN` (1) | `to_destination.domain` 1바이트 (2 또는 3). 0–255 범위 밖이면 오류 |
| VIN | `TAG_PERSONALIZATION` (2) | 17자 VIN |
| epoch | `TAG_EPOCH` (3) | `session_info.epoch` 16바이트 |
| 만료 | `TAG_EXPIRES_AT` (4) | 도메인 시계 기준 초 (uint32). Go: `uint32(now.Add(expiresIn).Sub(timeZero)/time.Second)`. 상한 `2^30` |
| 카운터 | `TAG_COUNTER` (5) | 단조 증가. 초기값 `session_info.counter`, 매 명령 `+1` |
| 플래그 | `TAG_FLAGS` (7) | `RoutableMessage.flags`. **0이 아닐 때만** 포함 (하위 호환) |

- 카운터는 같은 epoch 안에서 증가해야 한다. Infotainment는 슬라이딩 윈도우로 순서 뒤바뀜을 허용, **VCSEC는 순서대로 도착해야 한다**.
- 두 인증 방식: HMAC-SHA256(평문, Fleet API가 OAuth scope 검사와 레거시 VCSEC 메시지 필터링 가능), AES-GCM(암호화, Fleet API는 차단). Go SDK는 **BLE → AES-GCM, Fleet API → HMAC**.

### 8.1 HMAC-SHA256

1. `K' = HMAC-SHA256(K, "authenticated command")`.
2. `RoutableMessage.payload.protobuf_message_as_bytes = P`.
3. `signature_data.signer_identity.public_key = ENCODE_PUBLIC(C)`.
4. `tag = HMAC-SHA256(K', M || P)` (`M`은 `0xFF` 포함).
5. `signature_data.HMAC_Personalized_data = {epoch, counter, expires_at, tag}`.

Go: `Signer.AuthorizeHMAC` → `Peer.hmacTag` → `newMetadataHash(session.NewHMAC("authenticated command"))` + `extractMetadata` + `Checksum(P)`.

### 8.2 AES-GCM

1. 키 = `K` (128비트).
2. nonce = 12바이트 랜덤 (IV).
3. AAD = `SHA256(M)`.
4. `x, tag = AES-GCM-Encrypt(K, nonce, P, AAD)`.
5. `payload.protobuf_message_as_bytes = x`.
6. `signature_data.signer_identity.public_key = ENCODE_PUBLIC(C)`.
7. `signature_data.AES_GCM_Personalized_data = {epoch, nonce, counter, expires_at, tag}`. epoch는 생략 가능하지만 포함하면 차량이 더 구체적인 오류를 준다.

Go: `Signer.Encrypt` → `encryptWithCounter` → `newMetadata()`(SHA-256) + `extractMetadata` + `meta.Checksum(nil)`을 AAD로 `session.Encrypt`.

#### 8.2.1 원문 예시 ("Turn HVAC on")

`VIN = 5YJ30123456789ABC`, `K = 1b2fce19967b79db696f909cff89ea9a`, flags = `1 << FLAG_ENCRYPT_RESPONSE = 2`.

명령 protobuf:

```bash
echo 'vehicleAction {
  hvacAutoAction {
    power_on: true
  }
}' | protoc --encode=CarServer.Action -I protobuf protobuf/*.proto | xxd -p
```

출력: `120452020801`.

메타데이터:

| 태그 | 값 | 인코딩 (hex) |
|---|---|---|
| `TAG_SIGNATURE_TYPE` | `SIGNATURE_TYPE_AES_GCM_PERSONALIZED = 0x05` | `00 01 05` |
| `TAG_DOMAIN` | `DOMAIN_INFOTAINMENT = 0x03` | `01 01 03` |
| `TAG_PERSONALIZATION` | `5YJ30123456789ABC` | `02 11 35594a3330313233343536373839414243` |
| `TAG_EPOCH` | `session_info.epoch` | `03 10 4c463f9cc0d3d26906e982ed224adde6` |
| `TAG_EXPIRES_AT` | `t=2655` | `04 04 00000a5f` |
| `TAG_COUNTER` | 7 | `05 04 00000007` |
| `TAG_FLAGS` | `0x02` | `07 04 00000002` |

연결 + `0xff`:

```
000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007070400000002ff
```

```python
import os
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import hashes

plaintext = bytes.fromhex("120452020801")

metadata = bytes.fromhex("000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007070400000002ff")
aad = hashes.Hash(hashes.SHA256())

aad.update(metadata)
key = bytes.fromhex("1b2fce19967b79db696f909cff89ea9a")
aesgcm = AESGCM(key)

nonce = os.urandom(12)
ct = aesgcm.encrypt(nonce, plaintext, aad.finalize())
print(f"Nonce: {nonce.hex()}, Ciphertext: {ct[:-16].hex()}, Tag: {ct[-16:].hex()}")
```

출력 (nonce에 따라 매번 다름):

```
Nonce: dbf79447fa156674dae1caed, Ciphertext: 38038e8c0f2e, Tag: c228e0ff64991481db3a7bbc133696c5
```

결과 `RoutableMessage`:

```proto
to_destination { domain: DOMAIN_INFOTAINMENT }
from_destination { routing_address: 2c907bd76c640d360b3027dc7404efde }
protobuf_message_as_bytes: 38038e8c0f2e
signature_data {
  signer_identity {
    public_key: 04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a714330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e
  }
  AES_GCM_Personalized_data {
    epoch: 4c463f9cc0d3d26906e982ed224adde6
    nonce: dbf79447fa156674dae1caed
    counter: 7
    expires_at: 2655
    tag: c228e0ff64991481db3a7bbc133696c5
  }
}
flags: 2
uuid: 58406580528b6a5301391800b4fe9b99
```

이 예시는 `internal/authentication/protocol_doc_test.go` (`TestProtocolDocAESGCMExample`)가 구현과 대조한다.

---

## 9. 응답 처리

명령을 보내고 응답을 못 받아도 차량이 실행했을 수 있다 (`protocol.MayHaveSucceeded`). 특히 불안정한 네트워크에서 그렇다.

### 9.1 프로토콜 계층 오류

응답의 `signedMessageStatus.signed_message_fault != MESSAGEFAULT_ERROR_NONE`이면 프로토콜 오류. 코드별 의미/재시도는 [08-errors.md](08-errors.md). Go: `protocol.GetError` → `UNKNOWN_KEY_ID`는 `ErrKeyNotPaired`, 나머지는 `RoutableMessageError{Code}`; `session_info`가 동반되면 status 검사; `operation_status`가 `WAIT`면 `ErrBusy`, 알 수 없는 값이면 `ErrUnknown`.

### 9.2 응답 복호화

`FLAG_ENCRYPT_RESPONSE`를 설정했고 차량이 2024.38+면 `signature_data.AES_GCM_Response_data`가 있고 `protobuf_message_as_bytes`가 암호문이다. 없으면 평문.

#### 요청 해시 (request hash)

각 요청마다 1바이트 서명 타입 + 요청의 인증 태그:

- AES-GCM 요청: `[0x05] || AES_GCM_Personalized_data.tag` (17바이트)
- HMAC 요청: `[0x08] || HMAC_Personalized_data.tag` (33바이트). **VCSEC 대상이면 태그를 16바이트로 절단** → 17바이트

Go: `authentication.RequestID(message)`; dispatcher는 `createHandler(key, RequestID(message))`로 receiver에 보관한다.

#### 카운터 검증

응답의 `counter`가 같은 요청의 이전 응답에서 쓰인 적이 없는지 확인한다. 한 요청에 여러 응답이 순서 없이 올 수 있다. 검사를 빠뜨리면 리플레이에 취약하다. Go: `receiver.antireplay` (`SlidingWindow`, 윈도우 32); 중복이면 `ErrReplayedResponse`로 드롭.

#### 응답 메타데이터 (AAD)

`K`, 응답의 `nonce`/`tag`, 아래 메타데이터의 직렬화(4절)를 AAD로 AES-GCM 복호화. **주의: 요청과 달리 AAD는 SHA-256 해시가 아니라 직렬화 바이트 자체**를 쓴다 (Go `responseMetadata`는 `newMetadata()`(SHA-256 컨텍스트)로 만든 뒤 `Checksum(nil)`, 즉 `SHA256(TLV || 0xFF)`를 AAD로 넘긴다. 원문 사양 문장은 "serializing the following metadata"라고만 적혀 있으나 구현은 SHA-256 다이제스트를 AAD로 사용한다. 포팅 시 Go 구현을 따른다.)

| 값 | 태그 | 설명 |
|---|---|---|
| 서명 타입 | `TAG_SIGNATURE_TYPE` (0) | `SIGNATURE_TYPE_AES_GCM_RESPONSE` (9) |
| 도메인 | `TAG_DOMAIN` (1) | 응답의 `from_destination.domain` |
| VIN | `TAG_PERSONALIZATION` (2) | |
| 카운터 | `TAG_COUNTER` (5) | 응답의 `AES_GCM_Response_data.counter` |
| 플래그 | `TAG_FLAGS` (7) | **응답**의 `flags`. **항상 포함** (0이어도) |
| 요청 해시 | `TAG_REQUEST_HASH` (8) | 위 request hash |
| 결함 | `TAG_FAULT` (9) | 응답의 `signed_message_fault`를 uint32 big-endian 4바이트 |

Go: `Signer.Decrypt(message, id)` → `responseMetadata` → `session.Decrypt(nonce, ct, aad, tag)` → 평문으로 `payload` 교체, `SubSigData = nil`, 카운터 반환.

### 9.3 세션 상태 캐싱

연속 실행되지 않는 클라이언트는 세션 상태와 시계 오프셋을 디스크에 저장한다. 캐시가 유효하면 핸드셰이크 왕복이 없어지고, 무효하면 첫 명령이 실패하면서 차량이 최신 세션 정보를 주므로 비용이 핸드셰이크와 같다. Go: `Signer.ExportSessionInfo`/`ImportSessionInfo`, `dispatcher.CacheEntry`, `pkg/cache`.

### 9.4 동기화 오류 복구 (MUST 규칙)

차량은 동기화 문제로 보이는 인증 오류에 최신 `session_info`(+ `session_info_tag`)를 동봉할 수 있다 (예: Infotainment 재부팅으로 epoch 변경).

클라이언트는 다음 중 하나라도 참이면 세션 정보를 **폐기해야 한다(MUST)**:

1. 요청 UUID(challenge)를 최근 수 초 안에 사용한 적이 없다. (Go: `receiver.expired(maxLatency)`, BLE 4s / inet 10s)
2. 세션 정보 HMAC 태그가 틀리다. (Go: `UpdateSignedSessionInfo`의 `hmac.Equal`)
3. 같은 epoch에서 이전에 인증된 세션 정보보다 `clock_time`이 이르다. (Go: `UpdateSessionInfo`의 `s.setTime <= info.ClockTime` 조건)

위 조건에 해당하지 않으면 **갱신해야 한다(MUST)**. 갱신 시 epoch가 바뀌지 않는 한 **안티리플레이 카운터를 롤백하지 않는다(MUST NOT)** (Go: `if s.counter < info.Counter { s.counter = info.Counter }`). 이는 보안 요구라기보다(리플레이 거부는 차량 책임) 불안정한 연결/순서 뒤바뀜을 견디기 위한 규칙이다. 갱신 후 dispatcher는 오류 응답을 그대로 핸들러에 전달하고, `Vehicle.Send`/`getVCSECResult`가 `ShouldRetry`면 새 세션으로 재전송한다.

---

## 10. Infotainment 응답

`protobuf_message_as_bytes`를 `CarServer.Response`로 파싱. `Response.actionStatus.result`가 `OPERATIONSTATUS_ERROR`면 `result_reason.plain_text`가 사유. Go: `getCarServerResponse` → `NominalError{NewError("car could not execute command: <text>", false, false)}` (text 없으면 `unspecified error`). `Response`의 oneof: `vehicleData`(GetState), `getNearbyChargingSites`, `ping`.

---

## 11. VCSEC 응답 종료 규칙

`protobuf_message_as_bytes`를 `VCSEC.FromVCSECMessage`로 파싱. VCSEC는 요청 하나에 **최대 3개** 응답을 보낸다. Fleet API 클라이언트는 최종 응답만 받지만 BLE 클라이언트는 직접 종료를 판정해야 한다.

| 상황 | 규칙 |
|---|---|
| NFC 카드 페어링 요청 | `commandStatus.operationStatus = OPERATIONSTATUS_WAIT` = 카드 탭 대기 중 |
| 그 외 요청의 `OPERATIONSTATUS_WAIT` | VCSEC 바쁨. 짧은 지연 후 재시도 (Go: `ErrBusy`, Temporary) |
| `OPERATIONSTATUS_ERROR` | 레거시 클라이언트용. 새 클라이언트는 버리고 다음 메시지의 구체적 오류를 기다린다 (Go: whitelist 코드가 있으면 `KeychainError`, `signedMessageStatus`도 없으면 `ErrUnknown`) |
| 그 외 `operationStatus` 값 | 무시 가능 |
| 화이트리스트 작업 (키 추가/삭제) | `commandStatus.whitelistOperationStatus`가 채워지면 **종료**. 빈 메시지는 버린다. `whitelistOperationInformation != NONE`이면 `KeychainError{Code}` |
| 비-화이트리스트 작업 (RKE, closure) | **빈 메시지 = 성공**, `nominalError` = 오류 (`NominalError{NominalVCSECError}`). Go의 `done`: `commandStatus == nil`이면 종료 |
| 정보 요청 (`InformationRequest`) | 첫 응답으로 종료 (`vehicleStatus` / `whitelistInfo` / `whitelistEntryInfo`) |

`VCSEC.FromVCSECMessage` oneof: `vehicleStatus=1`, `commandStatus=4`, `whitelistInfo=16`, `whitelistEntryInfo=17`, `nominalError=46` (6–10 예약). `Errors.GenericError_E`: `NONE=0, UNKNOWN=1, CLOSURES_OPEN=2, ALREADY_ON=3, DISABLED_FOR_USER_COMMAND=4, VEHICLE_NOT_IN_PARK=5, UNAUTHORIZED=6, NOT_ALLOWED_OVER_TRANSPORT=7`.

---

## 12. VCSEC 페이로드 (`vcsec.proto`)

```proto
message UnsignedMessage {                       // 요청 (protobuf_message_as_bytes)
    reserved 6,7,10,12,13;
    oneof sub_message {
        InformationRequest InformationRequest = 1;   // GET_STATUS=0, GET_WHITELIST_INFO=5, GET_WHITELIST_ENTRY_INFO=6; key oneof keyId=2 | publicKey=3 | slot=4
        RKEAction_E        RKEAction = 2;            // UNLOCK=0, LOCK=1, REMOTE_DRIVE=20, AUTO_SECURE_VEHICLE=29, WAKE_VEHICLE=30
        ClosureMoveRequest closureMoveRequest = 4;   // 필드: frontDriverDoor..tonneau (1..8), 값 ClosureMoveType_E NONE=0 MOVE=1 STOP=2 OPEN=3 CLOSE=4
        WhitelistOperation WhitelistOperation = 16;  // sub_message: addPublicKeyToWhitelist=1, removePublicKeyFromWhitelist=2, addPermissionsToPublicKey=3,
                                                     //   removePermissionsFromPublicKey=4, addKeyToWhitelistAndAddPermissions=5, updateKeyAndPermissions=7,
                                                     //   addImpermanentKey=8, addImpermanentKeyAndRemoveExisting=9, removeAllImpermanentKeys=16, replaceKey=17;
                                                     //   metadataForKey = 6 (KeyMetadata{keyFormFactor})
    }
}
message PermissionChange { PublicKey key = 1; uint32 secondsToBeActive = 3; Keys.Role keyRole = 4; }
message PublicKey { bytes PublicKeyRaw = 1; }        // ENCODE_PUBLIC 65바이트
enum KeyFormFactor { UNKNOWN=0; NFC_CARD=1; IOS_DEVICE=6; ANDROID_DEVICE=7; CLOUD_KEY=9; }

// NFC 승인 부트스트랩 봉투 (세션/서명 없이 BLE로 직접 전송)
message ToVCSECMessage { SignedMessage signedMessage = 1; }
message SignedMessage { bytes protobufMessageAsBytes = 2; SignatureType signatureType = 3; }   // SIGNATURE_TYPE_PRESENT_KEY = 2
```

Go 매핑: `vehicle.addKeyPayload` (`AddKeyToWhitelistAndAddPermissions` + `KeyMetadata`), `RemoveKey` (`RemovePublicKeyFromWhitelist`), `SendAddKeyRequestWithRole` (`ToVCSECMessage` + `PRESENT_KEY`, `v.conn.Send` 직접), `executeRKEAction`, `executeClosureAction` (trunk→`rearTrunk`, frunk→`frontTrunk`, tonneau→`tonneau`), `getVCSECInfo` (`slot != 0xFFFFFFFF`면 `InformationRequest_Slot`).

`VehicleStatus` (GET_STATUS 응답): `closureStatuses`(각 문/트렁크/충전포트/토노 `ClosureState_E`: CLOSED=0 OPEN=1 AJAR=2 UNKNOWN=3 FAILED_UNLATCH=4 OPENING=5 CLOSING=6), `vehicleLockState`(UNLOCKED=0 LOCKED=1 INTERNAL_LOCKED=2 SELECTIVE_UNLOCKED=3), `vehicleSleepStatus`(UNKNOWN=0 AWAKE=1 ASLEEP=2), `userPresence`(UNKNOWN=0 NOT_PRESENT=1 PRESENT=2), `detailedClosureStatus.tonneauPercentOpen`.

`WhitelistInfo`: `numberOfEntries`, `whitelistEntries[]`(KeyIdentifier{publicKeySHA1}), `slotMask`. `WhitelistEntryInfo`: `keyId`, `publicKey`, `metadataForKey`, `slot`, `keyRole`. `list-keys`는 `slotMask` 비트마다 `GET_WHITELIST_ENTRY_INFO(slot)`를 호출한다.

---

## 13. Infotainment 페이로드 (`car_server.proto`)

`CarServer.Action{ oneof action_msg { VehicleAction vehicleAction = 2; } }`, `VehicleAction{ oneof vehicle_action_msg {...} }`. 사용 중인 하위 메시지와 필드 번호(전체 목록은 proto 파일):

`getVehicleData=1, chargingSetLimitAction=5, chargingStartStopAction=6, drivingClearSpeedLimitPinAction=7, drivingSetSpeedLimitAction=8, drivingSpeedLimitAction=9, hvacAutoAction=10, hvacSetPreconditioningMaxAction=12, hvacSteeringWheelHeaterAction=13, hvacTemperatureAdjustmentAction=14, mediaPlayAction=15, mediaUpdateVolume=16, mediaNextFavorite=17, mediaPreviousFavorite=18, mediaNextTrack=19, mediaPreviousTrack=20, getNearbyChargingSites=23, vehicleControlCancelSoftwareUpdateAction=25, vehicleControlFlashLightsAction=26, vehicleControlHonkHornAction=27, vehicleControlResetValetPinAction=28, vehicleControlScheduleSoftwareUpdateAction=29, vehicleControlSetSentryModeAction=30, vehicleControlSetValetModeAction=31, vehicleControlSunroofOpenCloseAction=32, vehicleControlTriggerHomelinkAction=33, vehicleControlWindowAction=34, hvacBioweaponModeAction=35, hvacSeatHeaterActions=36, scheduledChargingAction=41, scheduledDepartureAction=42, setChargingAmpsAction=43, hvacClimateKeeperAction=44, ping=46, autoSeatClimateAction=48, hvacSeatCoolerActions=49, setCabinOverheatProtectionAction=50, setVehicleNameAction=54, chargePortDoorClose=61, chargePortDoorOpen=62, setCopTempAction=66, eraseUserDataAction=72, vehicleControlSetPinToDriveAction=77, vehicleControlResetPinToDriveAction=78, drivingClearSpeedLimitPinAdminAction=79, vehicleControlResetPinToDriveAdminAction=89, addChargeScheduleAction=97, removeChargeScheduleAction=98, addPreconditionScheduleAction=99, removePreconditionScheduleAction=100, batchRemovePreconditionSchedulesAction=107, batchRemoveChargeSchedulesAction=108, parentalControlsClearPinAction=109, parentalControlsClearPinAdminAction=110, parentalControlsAction=111, parentalControlsEnableSettingsAction=112, parentalControlsSetSpeedLimitAction=113, setLowPowerModeAction=130, setKeepAccessoryPowerModeAction=138`

`GetVehicleData` 하위: `getChargeState=2, getClimateState=3, getDriveState=4, getLocationState=7, getClosuresState=8, getChargeScheduleState=10, getPreconditioningScheduleState=11, getTirePressureState=14, getMediaState=15, getMediaDetailState=16, getSoftwareUpdateState=17, getParentalControlsState=19` (`vehicle.StateCategory` ↔ 매핑은 `pkg/vehicle/state.go`).

명령 ↔ 메시지 매핑 표는 [05-command-catalog.md](05-command-catalog.md).

---

## 14. Go 구현 매핑

| 사양 개념 | 구현 위치 |
|---|---|
| RoutableMessage 조립 (to/from/uuid/flags) | `pkg/vehicle/vehicle.go` `getReceiver`; `internal/dispatcher/dispatcher.go` `Send` |
| 핸드셰이크 요청 | `internal/dispatcher/dispatcher.go` `SessionInfoRequest`, `RequestSessionInfo`, `StartSession`/`tryStartSession` |
| 세션 정보 태그 검증, 세션 생성 | `internal/dispatcher/session.go` `processHello` → `internal/authentication/signer.go` `NewAuthenticatedSigner` / `UpdateSignedSessionInfo` |
| 세션 정보 지연/폐기 규칙 | `internal/dispatcher/dispatcher.go` `checkForSessionUpdate`, `internal/dispatcher/receiver.go` `expired` |
| ECDH + KDF (K) | `internal/authentication/native.go` `NativeECDHKey.Exchange`, `sharedSecret` |
| 서브키 (`"session info"`, `"authenticated command"`) | `internal/authentication/native.go` `subkey`, `NewHMAC`; 라벨 상수 `crypto.go` |
| 메타데이터 TLV | `internal/authentication/metadata.go` (`Add`, `AddUint32`, `Checksum`) |
| 요청 메타데이터 구성 | `internal/authentication/peer.go` `extractMetadata` |
| HMAC 인증 | `internal/authentication/signer.go` `AuthorizeHMAC`, `peer.go` `hmacTag` |
| AES-GCM 암호화 | `internal/authentication/signer.go` `Encrypt`/`encryptWithCounter`, `native.go` `NativeSession.Encrypt` |
| 카운터 증가/롤오버 | `signer.go` `Encrypt` (`0xFFFFFFFF` 검사), `AuthorizeHMAC` |
| 만료 시간 계산, 시계 오프셋 | `peer.go` `timestamp`, `crypto.go` `epochStartTime`, `signer.go` `ImportSessionInfo` (`timeZero = generatedAt − ClockTime`) |
| 세션 갱신 MUST 규칙 | `signer.go` `UpdateSessionInfo` (공개 키 일치, epoch/시계 조건, 카운터 비롤백) |
| 요청 해시 | `peer.go` `RequestID` |
| 응답 메타데이터 / 복호화 | `peer.go` `responseMetadata`, `signer.go` `Decrypt`, `session.go` `decrypt` |
| 응답 안티리플레이 | `internal/authentication/window.go` `SlidingWindow.Update`, `receiver.go` `antireplay` |
| 프로토콜 오류 → Go 오류 | `pkg/protocol/error.go` `GetError`, `RoutableMessageError` |
| VCSEC 응답 종료/오류 | `pkg/vehicle/vcsec.go` `unmarshalVCSECResponse`, `readUntil`, `isWhitelistOperationComplete` |
| Infotainment 응답 | `pkg/vehicle/infotainment.go` `getCarServerResponse` |
| 세션 캐시 export/import | `signer.go` `ExportSessionInfo`/`ImportSessionInfo`, `dispatcher.go` `Cache`/`LoadCache`, `pkg/cache` |
| 차량 측 참조 구현 (테스트) | `internal/authentication/verifier.go` |
| 사양 예시 검증 테스트 | `internal/authentication/protocol_doc_test.go` |

---

## 15. protoc로 디코딩

`tesla-control -vin YOUR_VIN -ble -debug list-keys`의 디버그 라인:

```
2023-12-13T14:41:13-08:00 [debug] TX: 320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805
```

`pkg/protocol/` 디렉터리에서:

```bash
echo 320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805 \
    | xxd -r -p \
    | protoc --decode=UniversalMessage.RoutableMessage -I protobuf protobuf/*.proto
```

```proto
to_destination {
  domain: DOMAIN_VEHICLE_SECURITY
}
from_destination {
  routing_address: "\nyb\301\r8\266\035\322\247r\'\200\244\360\226"
}
protobuf_message_as_bytes: "\n\002\010\005"
uuid: "\005QOWak\314\201\250\316\017\235{H2)"
```

VCSEC 페이로드 디코딩:

```bash
printf "\n\002\010\005" | protoc --decode=VCSEC.UnsignedMessage -I protobuf protobuf/*.proto
```

```proto
InformationRequest {
  informationRequestType: INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO
}
```

Infotainment 페이로드는 `--decode=CarServer.Action` / `CarServer.Response`, VCSEC 응답은 `VCSEC.FromVCSECMessage`, `session_info`는 `Signatures.SessionInfo`. 암호화된 응답(`AES_GCM_Response_data` 존재)은 K 없이는 페이로드를 볼 수 없다. Go 코드로 디코딩하려면 `pkg/protocol/protobuf/universalmessage` 등 생성 패키지를 `proto.Unmarshal`에 쓴다.

---

## 16. 전송별 차이 요약

| 항목 | BLE | Fleet API (HTTPS) |
|---|---|---|
| 엔드포인트 | GATT 0212/0213, 2바이트 길이 프레이밍 ([02-ble-transport.md](02-ble-transport.md)) | `POST api/1/vehicles/<VIN>/signed_command` body `{"routable_message": base64}` → 200 `{"response": base64}` |
| 인증 방식 | AES-GCM | HMAC-SHA256 (AES-GCM은 서버가 차단) |
| 응답 수 | VCSEC 최대 3개, 직접 종료 판정 | 서버가 최종 응답만 전달 |
| 순서/유실 | GATT 신뢰성 | 서버↔차량 구간은 TCP 보장 없음: 유실/순서 뒤바뀜 가능 |
| HTTP 200 의미 | — | 차량이 응답했다는 뜻일 뿐 성공 아님. RoutableMessage 안의 오류 확인 필요. 422 = 프로토콜 미지원 차량(`ErrProtocolNotSupported`), 503 = 차량 오프라인(`ErrVehicleNotAwake`), 408 + "vehicle is offline" = 동일, 421 = 리전 재지정(`use base URL: https://...`) |
| 세션 정보 허용 지연 | 4s | 10s |
| 키 부트스트랩 (NFC) | 가능 (`PRESENT_KEY` 봉투) | 불가 (`ErrRequiresBLE`) |
| FM 역할 | 명령 불가 | 가능 |

---

## 검증 체크리스트

`internal/authentication`, `internal/dispatcher`, `pkg/protocol`, `*.proto`를 수정한 뒤:

- [ ] `TestProtocolDocAESGCMExample`이 통과하는가? (사양 예시 = 구현)
- [ ] `signer_test.go`의 세션 갱신 거부 케이스(잘못된 태그/challenge/카운터/epoch/공개 키)와 `TestSignerCounterRollover`, `TestExportImport`, `TestImportWrongTime`이 통과하는가?
- [ ] `verifier_test.go`의 `TestGCMWindow`, `TestGCMOutOfOrderMessage`, `TestGCMFlags`, `TestHMACTampered`가 통과하는가?
- [ ] `peer_test.go` `TestRequestID` (VCSEC HMAC 17바이트 절단)가 통과하는가?
- [ ] 메타데이터 태그 순서/`0xFF` 종료/uint32 4바이트/`TAG_FLAGS` 조건부(요청) vs 무조건(응답) 규칙을 바꾸지 않았는가?
- [ ] `.proto` 필드 번호를 재사용하거나 예약 번호(RoutableMessage 1–5, 11, 16–40; SignatureData 7; SignatureType 7; VCSEC UnsignedMessage 6,7,10,12,13; FromVCSECMessage 6–10)를 쓰지 않았는가? `make proto-gen`을 돌렸는가?
- [ ] 이 문서의 테스트 벡터를 바꾸지 않았는가? (원문 `pkg/protocol/protocol.md`와 hex 값이 동일해야 한다. 아래 명령으로 대조 가능)

```bash
cd documents/md && grep -oE '[0-9a-f]{24,}' 03-protocol.md | sort -u > /tmp/a
cd ../../vehicle-command && grep -oE '[0-9a-f]{24,}' pkg/protocol/protocol.md | sort -u > /tmp/b
comm -13 /tmp/b /tmp/a     # 출력이 없어야 함 (문서에만 있는 hex가 없어야 함)
```
