# 03. Protocol Specification

A precise specification restructuring `pkg/protocol/protocol.md` for agents. Covers message encoding, domains, handshake, key agreement, metadata serialization, HMAC/AES-GCM command authentication, response decryption, session recovery, response interpretation rules, and Go implementation mapping. Test vectors preserve the original values verbatim. On conflict, `pkg/protocol/protocol.md` and `*.proto` take precedence.

Related files: `pkg/protocol/protocol.md`, `pkg/protocol/protobuf/universal_message.proto`, `signatures.proto`, `keys.proto`, `vcsec.proto`, `car_server.proto`, `errors.proto`, `internal/authentication/{signer,peer,metadata,native,crypto,window}.go`, `internal/dispatcher/{dispatcher,session,receiver}.go`, `pkg/protocol/error.go`, `internal/authentication/protocol_doc_test.go`

---

## 1. Message encoding: `RoutableMessage`

`UniversalMessage.RoutableMessage` (`universal_message.proto`, Go package `universalmessage`). All bytes fields are raw binary; the hex below is for display only.

| Field | Number | Type | Request (client→vehicle) | Response (vehicle→client) |
|---|---|---|---|---|
| `to_destination` | 6 | `Destination` | `domain` = target domain | `routing_address` = the request's `from_destination` |
| `from_destination` | 7 | `Destination` | `routing_address` = 16 random bytes (identifies connection/request) | `domain` = responding domain |
| `payload.protobuf_message_as_bytes` | 10 | `bytes` (oneof `payload`) | Application payload (VCSEC: `VCSEC.UnsignedMessage`, Infotainment: `CarServer.Action`) | VCSEC: `VCSEC.FromVCSECMessage`, Infotainment: `CarServer.Response`. May be encrypted |
| `payload.session_info_request` | 14 | `SessionInfoRequest` | Handshake request | — |
| `payload.session_info` | 15 | `bytes` | — | Serialized `Signatures.SessionInfo` (handshake response, or attached to an error) |
| `signedMessageStatus` | 12 | `MessageStatus` | — | Protocol-layer error (`operation_status`, `signed_message_fault`) |
| `sub_sigData.signature_data` | 13 | `Signatures.SignatureData` | Authentication data | Session info tag or response encryption data |
| `request_uuid` | 50 | `bytes` | — | Copy of the request's `uuid` (**VCSEC usually leaves it empty**) |
| `uuid` | 51 | `bytes` | ≤16 bytes, unpredictable random (prevents replayed handshake responses) | — |
| `flags` | 52 | `uint32` | `Flags` bitmask. Always setting `FLAG_ENCRYPT_RESPONSE` is recommended | Response flags (included in response metadata) |

Reserved: 1–5, 11, 16–40.

```proto
message Destination { oneof sub_destination { Domain domain = 1; bytes routing_address = 2; } }
enum Domain { DOMAIN_BROADCAST = 0; DOMAIN_VEHICLE_SECURITY = 2; DOMAIN_INFOTAINMENT = 3; }
enum OperationStatus_E { OPERATIONSTATUS_OK = 0; OPERATIONSTATUS_WAIT = 1; OPERATIONSTATUS_ERROR = 2; }
message MessageStatus { OperationStatus_E operation_status = 1; MessageFault_E signed_message_fault = 2; }
message SessionInfoRequest { bytes public_key = 1; bytes challenge = 2; }
enum Flags { FLAG_USER_COMMAND = 0; FLAG_ENCRYPT_RESPONSE = 1; }   // bit index. ENCRYPT_RESPONSE → flags = 1<<1 = 2
```

The full `MessageFault_E` table is in [08-errors.md](08-errors.md). The Go client puts `vehicle.DefaultFlags = uint32(1 << universal.Flags_FLAG_ENCRYPT_RESPONSE)` (= 2) in every request. The vehicle authenticates flags but ignores unknown bits. Only 2024.38+ firmware encrypts responses; older firmware ignores the flag, so always set it without checking firmware.

Typical request (original example):

```proto
to_destination { domain: DOMAIN_VEHICLE_SECURITY }
from_destination { routing_address: 0a7962c10d38b61dd2a7722780a4f096 }
protobuf_message_as_bytes: 0a020805
uuid: 05514f57616bcc81a8ce0f9d7b483229
```

---

## 2. Signature-related messages (`signatures.proto`)

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
        AES_GCM_Personalized_Signature_Data AES_GCM_Personalized_data = 5;   // request (AES-GCM)
        HMAC_Signature_Data                 session_info_tag          = 6;   // handshake response
        HMAC_Personalized_Signature_Data    HMAC_Personalized_data    = 8;   // request (HMAC)
        AES_GCM_Response_Signature_Data     AES_GCM_Response_data     = 9;   // encrypted response
    }
}
enum Session_Info_Status { SESSION_INFO_STATUS_OK = 0; SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST = 1; }
message SessionInfo { uint32 counter = 1; bytes publicKey = 2; bytes epoch = 3; fixed32 clock_time = 4; Session_Info_Status status = 5; uint32 handle = 6; }
```

---

## 3. Domains, time, roles

| Domain | Value | Responsibility | Notes |
|---|---|---|---|
| `DOMAIN_VEHICLE_SECURITY` (VCSEC) | 2 | Lock/unlock, remote start, trunk/frunk/tonneau, keychain (whitelist), body state, wake | Responds over BLE even while Infotainment is asleep. Memory-constrained |
| `DOMAIN_INFOTAINMENT` | 3 | Everything else (climate, charging, media, state, PIN, Sentry, schedules, etc.) | Separate key pair/session |
| `DOMAIN_BROADCAST` | 0 | Unused (`Dispatcher.Send` rejects it) | |

- **Time**: each domain has an `(epoch_id 16 random bytes, timestamp seconds)` pair. The epoch is generated at boot. The client tracks the offset as `timeZero = local receive time − clock_time` (`epochStartTime`).
- **Roles** (`keys.proto` `Keys.Role`): `ROLE_NONE=0, ROLE_SERVICE=1, ROLE_OWNER=2, ROLE_DRIVER=3, ROLE_FM=4, ROLE_VEHICLE_MONITOR=5, ROLE_CHARGING_MANAGER=6, ROLE_GUEST=8`.

| Role | Permission summary (protocol.md "Roles") |
|---|---|
| Owner | All commands; add/remove other users' keys |
| Driver | Most commands. Cannot manage keys/access control (e.g. change PIN) |
| Fleet Manager (FM) | Cloud Owner key. On 2023.38+ cannot add/remove other users' keys, **no BLE commands**. Access management happens at the Fleet API account level |
| Vehicle Monitor | Data reads only (location, etc.). No state-changing commands |
| Charging Manager | Data reads + charging-related commands |
| Guest | Temporary Driver (for rentals, automatic lifecycle) |
| Service | Key bootstrap, service technician commands. Remote (un)lock, remote removal of Driver/Guest/FM keys (not addition). Other commands over the internet are blocked by default |

---

## 4. Metadata serialization (TLV)

- Entry = `TAG(1 byte) || LEN(1 byte) || VALUE`. VALUE ≤ 255 bytes.
- Integers (uint32) are **big-endian, 4 bytes**.
- Set serialization: sort by **ascending tag** → serialize each entry → concatenate → append `0xFF` (`TAG_END`) at the end.
- The Go implementation (`metadata.go`) returns `errOutOfOrderMetadata` if a tag decreases, skips `nil` values, and returns `ErrMetadataFieldTooLong` above 255. `Checksum(msg)` feeds the remaining `0xFF || msg` and returns the hash.

```
TLV(VIN: "abc")     = 0x02 || 0x03 || 0x61 0x62 0x63          = 0203616263
TLV(COUNTER: 100)   = 0x05 || 0x04 || 0x00000064              = 050400000064
SERIALIZE({COUNTER: 100, VIN: "abc"}) = 0203616263 || 050400000064 || FF = 0203616263050400000064FF
```

---

## 5. Notation

| Symbol | Meaning |
|---|---|
| `c` / `C = (Cx, Cy)` | Client private key / public key (NIST P-256 point) |
| `v` / `V = (Vx, Vy)` | Vehicle (domain) private key / public key |
| `s[:n]` | First n bytes |
| `BIG_ENDIAN(m, n)` | m as n-byte big-endian, 0x00-padded |
| `K` | 128-bit AES-GCM shared key |
| `ENCODE_PUBLIC(P)` | `0x04 || BIG_ENDIAN(x,32) || BIG_ENDIAN(y,32)` (uncompressed SEC1, 65 bytes) |

---

## 6. Test keys

> **Warning**: Use the keys below only to verify test vectors. The private keys are public, so enrolling these public keys on a real vehicle allows unauthorized access.

### 6.1 Vehicle key

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

### 6.2 Client key

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

## 7. Handshake

Precondition: the client public key `C` is enrolled on the vehicle (for the enrollment procedure see `add-key-request` in [05-command-catalog.md](05-command-catalog.md)).

### 7.1 Request

Send a `RoutableMessage` to the target domain with `session_info_request.public_key = ENCODE_PUBLIC(C)`. (The Go implementation does not populate the `challenge` field; `uuid` serves as the challenge.)

```proto
to_destination { domain: DOMAIN_INFOTAINMENT }
from_destination { routing_address: 2c907bd76c640d360b3027dc7404efde }
session_info_request {
  public_key: 04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a714330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e
}
uuid: 1588d5a30eabc6f8fc9a951b11f6fd11
```

Go: `dispatcher.SessionInfoRequest(domain, publicBytes)` → `Dispatcher.RequestSessionInfo` (`ErrRequiresKey` if there is no private key).

### 7.2 Response

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

`session_info` decoded:

```
counter: 6
publicKey: 04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97
epoch: 4c463f9cc0d3d26906e982ed224adde6
clock_time: 2650
```

If `status == SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST`, the result is `protocol.ErrKeyNotPaired` (`protocol.GetError`).

### 7.3 Key agreement

```
S = (Sx, Sy) = ECDH(c, V) = ECDH(v, C)
K = SHA1(BIG_ENDIAN(Sx, 32))[:16]
```

OpenSSL verification:

```bash
export K=$(openssl pkeyutl -derive -inkey client.key -peerkey vehicle.pem \
    | openssl dgst -sha1 -binary \
    | head -c 16 \
    | xxd -p)
echo $K
1b2fce19967b79db696f909cff89ea9a
```

Go: `NativeECDHKey.Exchange` — `elliptic.P256().ScalarMult(x, y, D)` → `sharedX.FillBytes(32 bytes)` → `sha1.Sum` → `[:16]` → `aes.NewCipher` + `cipher.NewGCM`. Use a mature crypto library; do not implement it yourself.

### 7.4 Response authentication (session info tag verification)

To prevent a MITM from extending expiration times, the client **must** authenticate the session info.

```
SESSION_INFO_KEY = HMAC-SHA256(K, "session info")          // ASCII literal
```

```bash
export SESSION_INFO_KEY=$(\
    echo -n "session info" \
    | openssl dgst -sha256 -mac hmac -macopt hexkey:"$K")
echo $SESSION_INFO_KEY
fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300
```

Metadata (serialized per the rules in section 4):

| Tag | Value |
|---|---|
| `TAG_SIGNATURE_TYPE` (0) | `SIGNATURE_TYPE_HMAC` = 6 |
| `TAG_PERSONALIZATION` (2) | VIN |
| `TAG_CHALLENGE` (6) | The handshake request's `uuid` |

Example with `VIN = 5YJ30123456789ABC`:

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

Expected tag = `HMAC-SHA256(SESSION_INFO_KEY, METADATA || session_info)`:

```bash
export SESSION_INFO=0806124104c... (the full session_info from the response above)
echo "$METADATA$SESSION_INFO" | xxd -r -p | openssl dgst -sha256 -mac hmac -macopt hexkey:"$SESSION_INFO_KEY"
996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002
```

Compare this value against `signature_data.session_info_tag.tag` in **constant time** (`hmac.Equal`). Discard on mismatch. Go: `NativeSession.SessionInfoHMAC(id, challenge, encodedInfo)` — adds the three tags above to `newMetadataHash(NewHMAC("session info"))` and calls `Checksum(encodedInfo)`.

After the handshake, the client holds: the domain time `(epoch, timestamp)`, the anti-replay counter, and the shared key `K`.

---

## 8. Command authentication

Build the command protobuf `P` (VCSEC: `VCSEC.UnsignedMessage`, Infotainment: `CarServer.Action`), then serialize the metadata `M` below.

| Value | Tag | Description |
|---|---|---|
| Signature type | `TAG_SIGNATURE_TYPE` (0) | `SIGNATURE_TYPE_HMAC_PERSONALIZED` (8) or `SIGNATURE_TYPE_AES_GCM_PERSONALIZED` (5) |
| Domain | `TAG_DOMAIN` (1) | `to_destination.domain` as 1 byte (2 or 3). Error if outside 0–255 |
| VIN | `TAG_PERSONALIZATION` (2) | 17-character VIN |
| epoch | `TAG_EPOCH` (3) | `session_info.epoch`, 16 bytes |
| Expiration | `TAG_EXPIRES_AT` (4) | Seconds on the domain clock (uint32). Go: `uint32(now.Add(expiresIn).Sub(timeZero)/time.Second)`. Upper bound `2^30` |
| Counter | `TAG_COUNTER` (5) | Monotonically increasing. Initial value `session_info.counter`, `+1` per command |
| Flags | `TAG_FLAGS` (7) | `RoutableMessage.flags`. Included **only when non-zero** (backward compatibility) |

- The counter must increase within the same epoch. Infotainment tolerates reordering via a sliding window; **VCSEC requires in-order arrival**.
- Two authentication modes: HMAC-SHA256 (plaintext; lets the Fleet API check OAuth scopes and filter legacy VCSEC messages) and AES-GCM (encrypted; blocked by the Fleet API). The Go SDK uses **BLE → AES-GCM, Fleet API → HMAC**.

### 8.1 HMAC-SHA256

1. `K' = HMAC-SHA256(K, "authenticated command")`.
2. `RoutableMessage.payload.protobuf_message_as_bytes = P`.
3. `signature_data.signer_identity.public_key = ENCODE_PUBLIC(C)`.
4. `tag = HMAC-SHA256(K', M || P)` (`M` includes `0xFF`).
5. `signature_data.HMAC_Personalized_data = {epoch, counter, expires_at, tag}`.

Go: `Signer.AuthorizeHMAC` → `Peer.hmacTag` → `newMetadataHash(session.NewHMAC("authenticated command"))` + `extractMetadata` + `Checksum(P)`.

### 8.2 AES-GCM

1. Key = `K` (128 bits).
2. nonce = 12 random bytes (IV).
3. AAD = `SHA256(M)`.
4. `x, tag = AES-GCM-Encrypt(K, nonce, P, AAD)`.
5. `payload.protobuf_message_as_bytes = x`.
6. `signature_data.signer_identity.public_key = ENCODE_PUBLIC(C)`.
7. `signature_data.AES_GCM_Personalized_data = {epoch, nonce, counter, expires_at, tag}`. epoch may be omitted, but including it makes the vehicle return more specific errors.

Go: `Signer.Encrypt` → `encryptWithCounter` → `newMetadata()` (SHA-256) + `extractMetadata` + `session.Encrypt` with `meta.Checksum(nil)` as AAD.

#### 8.2.1 Original example ("Turn HVAC on")

`VIN = 5YJ30123456789ABC`, `K = 1b2fce19967b79db696f909cff89ea9a`, flags = `1 << FLAG_ENCRYPT_RESPONSE = 2`.

Command protobuf:

```bash
echo 'vehicleAction {
  hvacAutoAction {
    power_on: true
  }
}' | protoc --encode=CarServer.Action -I protobuf protobuf/*.proto | xxd -p
```

Output: `120452020801`.

Metadata:

| Tag | Value | Encoding (hex) |
|---|---|---|
| `TAG_SIGNATURE_TYPE` | `SIGNATURE_TYPE_AES_GCM_PERSONALIZED = 0x05` | `00 01 05` |
| `TAG_DOMAIN` | `DOMAIN_INFOTAINMENT = 0x03` | `01 01 03` |
| `TAG_PERSONALIZATION` | `5YJ30123456789ABC` | `02 11 35594a3330313233343536373839414243` |
| `TAG_EPOCH` | `session_info.epoch` | `03 10 4c463f9cc0d3d26906e982ed224adde6` |
| `TAG_EXPIRES_AT` | `t=2655` | `04 04 00000a5f` |
| `TAG_COUNTER` | 7 | `05 04 00000007` |
| `TAG_FLAGS` | `0x02` | `07 04 00000002` |

Concatenated + `0xff`:

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

Output (differs each run depending on the nonce):

```
Nonce: dbf79447fa156674dae1caed, Ciphertext: 38038e8c0f2e, Tag: c228e0ff64991481db3a7bbc133696c5
```

Resulting `RoutableMessage`:

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

This example is checked against the implementation by `internal/authentication/protocol_doc_test.go` (`TestProtocolDocAESGCMExample`).

---

## 9. Response handling

Even if no response arrives after sending a command, the vehicle may have executed it (`protocol.MayHaveSucceeded`). This is especially true on unreliable networks.

### 9.1 Protocol-layer errors

If the response's `signedMessageStatus.signed_message_fault != MESSAGEFAULT_ERROR_NONE`, it is a protocol error. Per-code meaning/retry behavior is in [08-errors.md](08-errors.md). Go: `protocol.GetError` → `UNKNOWN_KEY_ID` maps to `ErrKeyNotPaired`, everything else to `RoutableMessageError{Code}`; if `session_info` is attached, its status is checked; if `operation_status` is `WAIT` the result is `ErrBusy`, and an unknown value yields `ErrUnknown`.

### 9.2 Response decryption

If `FLAG_ENCRYPT_RESPONSE` was set and the vehicle is on 2024.38+, `signature_data.AES_GCM_Response_data` is present and `protobuf_message_as_bytes` is ciphertext. Otherwise it is plaintext.

#### Request hash

For each request: 1-byte signature type + the request's authentication tag:

- AES-GCM request: `[0x05] || AES_GCM_Personalized_data.tag` (17 bytes)
- HMAC request: `[0x08] || HMAC_Personalized_data.tag` (33 bytes). **If the target is VCSEC, truncate the tag to 16 bytes** → 17 bytes

Go: `authentication.RequestID(message)`; the dispatcher stores it in the receiver via `createHandler(key, RequestID(message))`.

#### Counter verification

Verify that the response's `counter` has not been used by a previous response to the same request. A single request may receive multiple responses out of order. Omitting this check leaves you vulnerable to replay. Go: `receiver.antireplay` (`SlidingWindow`, window 32); duplicates are dropped with `ErrReplayedResponse`.

#### Response metadata (AAD)

AES-GCM decrypt using `K`, the response's `nonce`/`tag`, and the serialization (section 4) of the metadata below as AAD. **Note: unlike requests, the AAD is the serialized bytes themselves rather than a SHA-256 hash** (Go's `responseMetadata` builds it with `newMetadata()` (SHA-256 context) and then passes `Checksum(nil)`, i.e. `SHA256(TLV || 0xFF)`, as AAD. The original spec text only says "serializing the following metadata", but the implementation uses the SHA-256 digest as AAD. When porting, follow the Go implementation.)

| Value | Tag | Description |
|---|---|---|
| Signature type | `TAG_SIGNATURE_TYPE` (0) | `SIGNATURE_TYPE_AES_GCM_RESPONSE` (9) |
| Domain | `TAG_DOMAIN` (1) | The response's `from_destination.domain` |
| VIN | `TAG_PERSONALIZATION` (2) | |
| Counter | `TAG_COUNTER` (5) | The response's `AES_GCM_Response_data.counter` |
| Flags | `TAG_FLAGS` (7) | The **response's** `flags`. **Always included** (even if 0) |
| Request hash | `TAG_REQUEST_HASH` (8) | The request hash above |
| Fault | `TAG_FAULT` (9) | The response's `signed_message_fault` as uint32 big-endian, 4 bytes |

Go: `Signer.Decrypt(message, id)` → `responseMetadata` → `session.Decrypt(nonce, ct, aad, tag)` → replaces `payload` with the plaintext, sets `SubSigData = nil`, returns the counter.

### 9.3 Session state caching

Clients that do not run continuously store session state and clock offset on disk. A valid cache eliminates the handshake round trip; an invalid one costs the same as a handshake, because the first command fails and the vehicle supplies the latest session info. Go: `Signer.ExportSessionInfo`/`ImportSessionInfo`, `dispatcher.CacheEntry`, `pkg/cache`.

### 9.4 Synchronization error recovery (MUST rules)

The vehicle may attach the latest `session_info` (+ `session_info_tag`) to authentication errors that look like synchronization problems (e.g. epoch changed due to an Infotainment reboot).

The client **MUST discard** the session info if any of the following is true:

1. The request UUID (challenge) has not been used within the last few seconds. (Go: `receiver.expired(maxLatency)`, BLE 4s / inet 10s)
2. The session info HMAC tag is wrong. (Go: `hmac.Equal` in `UpdateSignedSessionInfo`)
3. Within the same epoch, `clock_time` is earlier than previously authenticated session info. (Go: the `s.setTime <= info.ClockTime` condition in `UpdateSessionInfo`)

If none of the above apply, the client **MUST update**. When updating, it **MUST NOT roll back the anti-replay counter** unless the epoch changed (Go: `if s.counter < info.Counter { s.counter = info.Counter }`). This rule exists less as a security requirement (rejecting replays is the vehicle's responsibility) than to tolerate unreliable connections/reordering. After the update, the dispatcher passes the error response to the handler unchanged, and `Vehicle.Send`/`getVCSECResult` resends with the new session if `ShouldRetry`.

---

## 10. Infotainment responses

Parse `protobuf_message_as_bytes` as `CarServer.Response`. If `Response.actionStatus.result` is `OPERATIONSTATUS_ERROR`, `result_reason.plain_text` is the reason. Go: `getCarServerResponse` → `NominalError{NewError("car could not execute command: <text>", false, false)}` (`unspecified error` if there is no text). `Response` oneof: `vehicleData` (GetState), `getNearbyChargingSites`, `ping`.

---

## 11. VCSEC response termination rules

Parse `protobuf_message_as_bytes` as `VCSEC.FromVCSECMessage`. VCSEC sends **up to 3** responses per request. Fleet API clients receive only the final response, but BLE clients must determine termination themselves.

| Situation | Rule |
|---|---|
| NFC card pairing request | `commandStatus.operationStatus = OPERATIONSTATUS_WAIT` = waiting for card tap |
| `OPERATIONSTATUS_WAIT` for any other request | VCSEC busy. Retry after a short delay (Go: `ErrBusy`, Temporary) |
| `OPERATIONSTATUS_ERROR` | For legacy clients. New clients discard it and wait for the specific error in the next message (Go: `KeychainError` if a whitelist code is present; `ErrUnknown` if `signedMessageStatus` is also absent) |
| Other `operationStatus` values | May be ignored |
| Whitelist operations (add/remove key) | **Terminate** once `commandStatus.whitelistOperationStatus` is populated. Discard empty messages. If `whitelistOperationInformation != NONE`, `KeychainError{Code}` |
| Non-whitelist operations (RKE, closure) | **Empty message = success**, `nominalError` = error (`NominalError{NominalVCSECError}`). Go's `done`: terminate when `commandStatus == nil` |
| Information request (`InformationRequest`) | Terminate on the first response (`vehicleStatus` / `whitelistInfo` / `whitelistEntryInfo`) |

`VCSEC.FromVCSECMessage` oneof: `vehicleStatus=1`, `commandStatus=4`, `whitelistInfo=16`, `whitelistEntryInfo=17`, `nominalError=46` (6–10 reserved). `Errors.GenericError_E`: `NONE=0, UNKNOWN=1, CLOSURES_OPEN=2, ALREADY_ON=3, DISABLED_FOR_USER_COMMAND=4, VEHICLE_NOT_IN_PARK=5, UNAUTHORIZED=6, NOT_ALLOWED_OVER_TRANSPORT=7`.

---

## 12. VCSEC payloads (`vcsec.proto`)

```proto
message UnsignedMessage {                       // request (protobuf_message_as_bytes)
    reserved 6,7,10,12,13;
    oneof sub_message {
        InformationRequest InformationRequest = 1;   // GET_STATUS=0, GET_WHITELIST_INFO=5, GET_WHITELIST_ENTRY_INFO=6; key oneof keyId=2 | publicKey=3 | slot=4
        RKEAction_E        RKEAction = 2;            // UNLOCK=0, LOCK=1, REMOTE_DRIVE=20, AUTO_SECURE_VEHICLE=29, WAKE_VEHICLE=30
        ClosureMoveRequest closureMoveRequest = 4;   // fields: frontDriverDoor..tonneau (1..8), values ClosureMoveType_E NONE=0 MOVE=1 STOP=2 OPEN=3 CLOSE=4
        WhitelistOperation WhitelistOperation = 16;  // sub_message: addPublicKeyToWhitelist=1, removePublicKeyFromWhitelist=2, addPermissionsToPublicKey=3,
                                                     //   removePermissionsFromPublicKey=4, addKeyToWhitelistAndAddPermissions=5, updateKeyAndPermissions=7,
                                                     //   addImpermanentKey=8, addImpermanentKeyAndRemoveExisting=9, removeAllImpermanentKeys=16, replaceKey=17;
                                                     //   metadataForKey = 6 (KeyMetadata{keyFormFactor})
    }
}
message PermissionChange { PublicKey key = 1; uint32 secondsToBeActive = 3; Keys.Role keyRole = 4; }
message PublicKey { bytes PublicKeyRaw = 1; }        // ENCODE_PUBLIC, 65 bytes
enum KeyFormFactor { UNKNOWN=0; NFC_CARD=1; IOS_DEVICE=6; ANDROID_DEVICE=7; CLOUD_KEY=9; }

// NFC-approval bootstrap envelope (sent directly over BLE without session/signature)
message ToVCSECMessage { SignedMessage signedMessage = 1; }
message SignedMessage { bytes protobufMessageAsBytes = 2; SignatureType signatureType = 3; }   // SIGNATURE_TYPE_PRESENT_KEY = 2
```

Go mapping: `vehicle.addKeyPayload` (`AddKeyToWhitelistAndAddPermissions` + `KeyMetadata`), `RemoveKey` (`RemovePublicKeyFromWhitelist`), `SendAddKeyRequestWithRole` (`ToVCSECMessage` + `PRESENT_KEY`, directly via `v.conn.Send`), `executeRKEAction`, `executeClosureAction` (trunk→`rearTrunk`, frunk→`frontTrunk`, tonneau→`tonneau`), `getVCSECInfo` (`InformationRequest_Slot` if `slot != 0xFFFFFFFF`).

`VehicleStatus` (GET_STATUS response): `closureStatuses` (each door/trunk/charge port/tonneau `ClosureState_E`: CLOSED=0 OPEN=1 AJAR=2 UNKNOWN=3 FAILED_UNLATCH=4 OPENING=5 CLOSING=6), `vehicleLockState` (UNLOCKED=0 LOCKED=1 INTERNAL_LOCKED=2 SELECTIVE_UNLOCKED=3), `vehicleSleepStatus` (UNKNOWN=0 AWAKE=1 ASLEEP=2), `userPresence` (UNKNOWN=0 NOT_PRESENT=1 PRESENT=2), `detailedClosureStatus.tonneauPercentOpen`.

`WhitelistInfo`: `numberOfEntries`, `whitelistEntries[]` (KeyIdentifier{publicKeySHA1}), `slotMask`. `WhitelistEntryInfo`: `keyId`, `publicKey`, `metadataForKey`, `slot`, `keyRole`. `list-keys` calls `GET_WHITELIST_ENTRY_INFO(slot)` for each bit in `slotMask`.

---

## 13. Infotainment payloads (`car_server.proto`)

`CarServer.Action{ oneof action_msg { VehicleAction vehicleAction = 2; } }`, `VehicleAction{ oneof vehicle_action_msg {...} }`. Sub-messages in use and their field numbers (full list in the proto file):

`getVehicleData=1, chargingSetLimitAction=5, chargingStartStopAction=6, drivingClearSpeedLimitPinAction=7, drivingSetSpeedLimitAction=8, drivingSpeedLimitAction=9, hvacAutoAction=10, hvacSetPreconditioningMaxAction=12, hvacSteeringWheelHeaterAction=13, hvacTemperatureAdjustmentAction=14, mediaPlayAction=15, mediaUpdateVolume=16, mediaNextFavorite=17, mediaPreviousFavorite=18, mediaNextTrack=19, mediaPreviousTrack=20, getNearbyChargingSites=23, vehicleControlCancelSoftwareUpdateAction=25, vehicleControlFlashLightsAction=26, vehicleControlHonkHornAction=27, vehicleControlResetValetPinAction=28, vehicleControlScheduleSoftwareUpdateAction=29, vehicleControlSetSentryModeAction=30, vehicleControlSetValetModeAction=31, vehicleControlSunroofOpenCloseAction=32, vehicleControlTriggerHomelinkAction=33, vehicleControlWindowAction=34, hvacBioweaponModeAction=35, hvacSeatHeaterActions=36, scheduledChargingAction=41, scheduledDepartureAction=42, setChargingAmpsAction=43, hvacClimateKeeperAction=44, ping=46, autoSeatClimateAction=48, hvacSeatCoolerActions=49, setCabinOverheatProtectionAction=50, setVehicleNameAction=54, chargePortDoorClose=61, chargePortDoorOpen=62, setCopTempAction=66, eraseUserDataAction=72, vehicleControlSetPinToDriveAction=77, vehicleControlResetPinToDriveAction=78, drivingClearSpeedLimitPinAdminAction=79, vehicleControlResetPinToDriveAdminAction=89, addChargeScheduleAction=97, removeChargeScheduleAction=98, addPreconditionScheduleAction=99, removePreconditionScheduleAction=100, batchRemovePreconditionSchedulesAction=107, batchRemoveChargeSchedulesAction=108, parentalControlsClearPinAction=109, parentalControlsClearPinAdminAction=110, parentalControlsAction=111, parentalControlsEnableSettingsAction=112, parentalControlsSetSpeedLimitAction=113, setLowPowerModeAction=130, setKeepAccessoryPowerModeAction=138`

`GetVehicleData` sub-fields: `getChargeState=2, getClimateState=3, getDriveState=4, getLocationState=7, getClosuresState=8, getChargeScheduleState=10, getPreconditioningScheduleState=11, getTirePressureState=14, getMediaState=15, getMediaDetailState=16, getSoftwareUpdateState=17, getParentalControlsState=19` (`vehicle.StateCategory` ↔ mapping is in `pkg/vehicle/state.go`).

The command ↔ message mapping table is in [05-command-catalog.md](05-command-catalog.md).

---

## 14. Go implementation mapping

| Spec concept | Implementation location |
|---|---|
| RoutableMessage assembly (to/from/uuid/flags) | `pkg/vehicle/vehicle.go` `getReceiver`; `internal/dispatcher/dispatcher.go` `Send` |
| Handshake request | `internal/dispatcher/dispatcher.go` `SessionInfoRequest`, `RequestSessionInfo`, `StartSession`/`tryStartSession` |
| Session info tag verification, session creation | `internal/dispatcher/session.go` `processHello` → `internal/authentication/signer.go` `NewAuthenticatedSigner` / `UpdateSignedSessionInfo` |
| Session info latency/discard rules | `internal/dispatcher/dispatcher.go` `checkForSessionUpdate`, `internal/dispatcher/receiver.go` `expired` |
| ECDH + KDF (K) | `internal/authentication/native.go` `NativeECDHKey.Exchange`, `sharedSecret` |
| Subkeys (`"session info"`, `"authenticated command"`) | `internal/authentication/native.go` `subkey`, `NewHMAC`; label constants in `crypto.go` |
| Metadata TLV | `internal/authentication/metadata.go` (`Add`, `AddUint32`, `Checksum`) |
| Request metadata construction | `internal/authentication/peer.go` `extractMetadata` |
| HMAC authentication | `internal/authentication/signer.go` `AuthorizeHMAC`, `peer.go` `hmacTag` |
| AES-GCM encryption | `internal/authentication/signer.go` `Encrypt`/`encryptWithCounter`, `native.go` `NativeSession.Encrypt` |
| Counter increment/rollover | `signer.go` `Encrypt` (`0xFFFFFFFF` check), `AuthorizeHMAC` |
| Expiration computation, clock offset | `peer.go` `timestamp`, `crypto.go` `epochStartTime`, `signer.go` `ImportSessionInfo` (`timeZero = generatedAt − ClockTime`) |
| Session update MUST rules | `signer.go` `UpdateSessionInfo` (public key match, epoch/clock conditions, no counter rollback) |
| Request hash | `peer.go` `RequestID` |
| Response metadata / decryption | `peer.go` `responseMetadata`, `signer.go` `Decrypt`, `session.go` `decrypt` |
| Response anti-replay | `internal/authentication/window.go` `SlidingWindow.Update`, `receiver.go` `antireplay` |
| Protocol error → Go error | `pkg/protocol/error.go` `GetError`, `RoutableMessageError` |
| VCSEC response termination/errors | `pkg/vehicle/vcsec.go` `unmarshalVCSECResponse`, `readUntil`, `isWhitelistOperationComplete` |
| Infotainment responses | `pkg/vehicle/infotainment.go` `getCarServerResponse` |
| Session cache export/import | `signer.go` `ExportSessionInfo`/`ImportSessionInfo`, `dispatcher.go` `Cache`/`LoadCache`, `pkg/cache` |
| Vehicle-side reference implementation (tests) | `internal/authentication/verifier.go` |
| Spec example verification test | `internal/authentication/protocol_doc_test.go` |

---

## 15. Decoding with protoc

Debug line from `tesla-control -vin YOUR_VIN -ble -debug list-keys`:

```
2023-12-13T14:41:13-08:00 [debug] TX: 320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805
```

From the `pkg/protocol/` directory:

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

Decoding the VCSEC payload:

```bash
printf "\n\002\010\005" | protoc --decode=VCSEC.UnsignedMessage -I protobuf protobuf/*.proto
```

```proto
InformationRequest {
  informationRequestType: INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO
}
```

Infotainment payloads use `--decode=CarServer.Action` / `CarServer.Response`, VCSEC responses use `VCSEC.FromVCSECMessage`, and `session_info` uses `Signatures.SessionInfo`. For encrypted responses (`AES_GCM_Response_data` present), the payload cannot be viewed without K. To decode in Go code, pass generated packages such as `pkg/protocol/protobuf/universalmessage` to `proto.Unmarshal`.

---

## 16. Per-transport differences summary

| Item | BLE | Fleet API (HTTPS) |
|---|---|---|
| Endpoint | GATT 0212/0213, 2-byte length framing ([02-ble-transport.md](02-ble-transport.md)) | `POST api/1/vehicles/<VIN>/signed_command` body `{"routable_message": base64}` → 200 `{"response": base64}` |
| Authentication mode | AES-GCM | HMAC-SHA256 (server blocks AES-GCM) |
| Number of responses | VCSEC up to 3, client determines termination | Server delivers only the final response |
| Ordering/loss | GATT reliability | No TCP guarantee on the server↔vehicle leg: loss/reordering possible |
| Meaning of HTTP 200 | — | Only means the vehicle responded, not success. Must check for errors inside the RoutableMessage. 422 = vehicle does not support the protocol (`ErrProtocolNotSupported`), 503 = vehicle offline (`ErrVehicleNotAwake`), 408 + "vehicle is offline" = same, 421 = region redirect (`use base URL: https://...`) |
| Allowed session info latency | 4s | 10s |
| Key bootstrap (NFC) | Supported (`PRESENT_KEY` envelope) | Not supported (`ErrRequiresBLE`) |
| FM role | No commands | Allowed |

---

## Verification checklist

After modifying `internal/authentication`, `internal/dispatcher`, `pkg/protocol`, or `*.proto`:

- [ ] Does `TestProtocolDocAESGCMExample` pass? (spec example = implementation)
- [ ] Do the session-update rejection cases in `signer_test.go` (bad tag/challenge/counter/epoch/public key) and `TestSignerCounterRollover`, `TestExportImport`, `TestImportWrongTime` pass?
- [ ] Do `TestGCMWindow`, `TestGCMOutOfOrderMessage`, `TestGCMFlags`, `TestHMACTampered` in `verifier_test.go` pass?
- [ ] Does `peer_test.go` `TestRequestID` (VCSEC HMAC 17-byte truncation) pass?
- [ ] Did you leave unchanged the rules for metadata tag order / `0xFF` terminator / uint32 as 4 bytes / `TAG_FLAGS` conditional (request) vs unconditional (response)?
- [ ] Did you avoid reusing `.proto` field numbers or using reserved numbers (RoutableMessage 1–5, 11, 16–40; SignatureData 7; SignatureType 7; VCSEC UnsignedMessage 6,7,10,12,13; FromVCSECMessage 6–10)? Did you run `make proto-gen`?
- [ ] Did you leave this document's test vectors unchanged? (hex values must match the original `pkg/protocol/protocol.md`; verify with the commands below)

```bash
cd documents/md && grep -oE '[0-9a-f]{24,}' 03-protocol.md | sort -u > /tmp/a
cd ../../vehicle-command && grep -oE '[0-9a-f]{24,}' pkg/protocol/protocol.md | sort -u > /tmp/b
comm -13 /tmp/b /tmp/a     # must print nothing (no hex that exists only in this document)
```
