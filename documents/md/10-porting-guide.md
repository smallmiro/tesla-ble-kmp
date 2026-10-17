# 10. Porting Guide for Other Languages/Platforms (BLE Client)

A step-by-step checklist for implementing a Tesla BLE command client from scratch in Python (bleak), Swift (CoreBluetooth), Kotlin/Android (BluetoothGatt), Rust (btleplug), Node (noble), etc. Each step comes with language-neutral pseudocode and the location of the Go original. Protocol details: [03-protocol.md](03-protocol.md); BLE details: [02-ble-transport.md](02-ble-transport.md); error codes: [08-errors.md](08-errors.md).

Related files (relative to repository root)

- `pkg/protocol/protocol.md` — original specification and test vectors (**final authority**)
- `pkg/protocol/protobuf/*.proto` — message definitions
- `pkg/connector/ble/ble.go` — scan/connect/framing
- `internal/dispatcher/dispatcher.go`, `session.go`, `receiver.go` — message assembly, response matching, session updates
- `internal/authentication/native.go`, `signer.go`, `peer.go`, `metadata.go`, `window.go`, `crypto.go` — cryptographic operations
- `internal/authentication/protocol_doc_test.go` — tests pinning the documented vectors
- `pkg/cache/cache.go` — session cache file format
- `pkg/vehicle/security.go` `SendAddKeyRequestWithRole` — key pairing send

Notation: `||` concatenation, `BE32(n)` 4-byte big-endian, `ENCODE_PUBLIC(P)` = `0x04 || X(32) || Y(32)` (65 bytes), `K` 128-bit AES key.

---

## 0. Prerequisites

### 0.1 protobuf bindings

```bash
cd vehicle-command/pkg/protocol/protobuf
protoc --proto_path . --python_out=../../../../out/py   *.proto   # Python
protoc --proto_path . --swift_out=../../../../out/swift  *.proto   # Swift (protoc-gen-swift)
protoc --proto_path . --kotlin_out=... --java_out=...    *.proto   # Kotlin/Android
# Rust: prost-build / protobuf-codegen, Node: protobufjs or @bufbuild/protobuf
```

Required messages: `UniversalMessage.RoutableMessage`, `Destination`, `SessionInfoRequest`, `MessageStatus`; `Signatures.SessionInfo`, `SignatureData`, `KeyIdentity`, `AES_GCM_Personalized_Signature_Data`, `AES_GCM_Response_Signature_Data`, `HMAC_Personalized_Signature_Data`, `HMAC_Signature_Data`; `VCSEC.UnsignedMessage`, `FromVCSECMessage`, `ToVCSECMessage`, `SignedMessage`, `WhitelistOperation`, `PermissionChange`, `KeyMetadata`, `PublicKey`, `InformationRequest`, `ClosureMoveRequest`; `CarServer.Action`, `VehicleAction`, `Response`; `Keys.Role`; `Errors.NominalError`.

### 0.2 Cryptographic primitives

| Purpose | Primitive | Notes |
|---|---|---|
| Key pair | NIST P-256 (secp256r1) ECDSA/ECDH key, PEM `EC PRIVATE KEY` or PKCS8 | Public key is sent as uncompressed 65 bytes |
| Key agreement | ECDH(c, V) → shared point X coordinate, 32 bytes (fixed 32-byte length, not `BE32`) | `native.go sharedSecret`: `sharedX.FillBytes(32)` |
| KDF | `K = SHA1(X)[:16]` | Collision resistance not required (legacy compatibility) |
| Subkeys | `HMAC-SHA256(K, label)` — labels `"session info"`, `"authenticated command"` | |
| Metadata hash | SHA256 | AES-GCM AAD |
| Command encryption | AES-128-GCM, 12-byte nonce, 16-byte tag | |
| Command authentication (HMAC path) | HMAC-SHA256 | Fleet API only. BLE uses AES-GCM |
| Randomness | CSPRNG: routing_address 16B, uuid 16B, nonce 12B | |
| Constant-time comparison | HMAC tag comparison | Mandatory |

### 0.3 Write unit tests first

Pin the following using the test keys/vectors in `pkg/protocol/protocol.md` (detailed values in [03-protocol.md](03-protocol.md)):

1. `K(client.key, vehicle.pem) = 1b2fce19967b79db696f909cff89ea9a`
2. `SESSION_INFO_KEY = HMAC-SHA256(K, "session info") = fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300`
3. Metadata `TLV({SIG_TYPE: 0x06, VIN: "5YJ30123456789ABC", CHALLENGE: 1588d5a3...})` = `000106021135594a333031323334353637383941424306101588d5a30eabc6f8fc9a951b11f6fd11ff`
4. Session info tag = `996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002`
5. AES-GCM metadata `000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007070400000002ff`, plaintext `120452020801`, nonce `dbf79447fa156674dae1caed` → ciphertext `38038e8c0f2e`, tag `c228e0ff64991481db3a7bbc133696c5` (Go: `internal/authentication/protocol_doc_test.go`)
6. `VehicleLocalName("5YJS0000000000000") = "S1a87a5a75f3df858C"`

**Do not register the test keys on a real vehicle** (the private keys are public).

---

## 1. Advertisement scan

```
local_name = "S" + hex_lower(SHA1(VIN_ascii)[0:8]) + "C"    # 18 chars
scan(active=True)
for adv in advertisements:
    if adv.local_name == local_name:
        if not adv.connectable:  -> error ErrMaxConnectionsExceeded (VCSEC allows at most 3 connections)
        return adv.address
timeout -> retry (Go retries indefinitely until ctx expires)
```

- Go: `ble.VehicleLocalName`, `scanVehicleBeacon` (`pkg/connector/ble/ble.go`). Linux scan parameters: active, interval/window 0x10 (10ms), filter policy 2.
- Advertising period is 20ms–150ms depending on model/state. It may be rarer in deep sleep, so use a scan timeout of 20s or more.
- Identify by Local Name rather than a service UUID filter (the advertisement may not include the service UUID).

## 2. GATT connection

```
connect(address)
svc  = discover_service("00000211-b2d1-43f0-9b88-960cebf8b91e")
tx   = discover_characteristic(svc, "00000212-b2d1-43f0-9b88-960cebf8b91e")   # write to vehicle (with response)
rx   = discover_characteristic(svc, "00000213-b2d1-43f0-9b88-960cebf8b91e")   # indication from vehicle
discover_descriptors(rx)                                                     # CCCD
subscribe(rx, mode=INDICATION, handler=on_rx)                                # Go: client.Subscribe(rxChar, ind=true, ...)
mtu = exchange_mtu(request=517)                                              # go-ble ble.MaxMTU. 23 on failure
block_length = min(mtu, 1024) - 3                                            # 3-byte ATT header. On failure 23-3 = 20
```

- Go: `tryToConnect`. If any of service/characteristic/subscribe/MTU fails, disconnect and retry (`retry=true`).
- Subscribe to characteristic 213 with **indication** (Go's `Subscribe(..., true, ...)`). If the platform supports only notification, verify that it works on a real vehicle.
- 212 is "write with response" (Go `WriteCharacteristic(..., noRsp=false)`).

## 3. Framing (2-byte length prefix)

Send (Go `Connection.Send`):

```
frame = [len(msg) >> 8, len(msg) & 0xff] + msg      # big-endian uint16
for chunk in split(frame, block_length):
    write_with_response(tx, chunk)                   # ordered, serialized (mutex)
```

Receive (Go `Connection.rx` + `flush`):

```
on_rx(chunk):
    if now - last_rx > 1s: buffer = b""              # inter-fragment timeout → discard buffer
    last_rx = now
    buffer += chunk
    loop:
        if len(buffer) < 2: break
        n = buffer[0]*256 + buffer[1]
        if n > 1024: buffer = b""; break             # exceeds maxBLEMessageSize → discard
        if len(buffer) < 2+n: break                  # wait for more
        msg = buffer[2:2+n]; buffer = buffer[2+n:]
        enqueue(msg)                                  # drop if queue(5) is full (Go behavior). A larger queue is recommended when porting
```

- Multiple messages may arrive concatenated in one indication, or one message may be split across multiple indications. The loop above handles both.
- Maximum message size is 1024 bytes (`maxBLEMessageSize`). If a response would be larger, the vehicle returns `RESPONSE_MTU_EXCEEDED`.

## 4. RoutableMessage assembly

Go `dispatcher.Send`:

```
msg = RoutableMessage()
msg.to_destination.domain = DOMAIN_VEHICLE_SECURITY(2) | DOMAIN_INFOTAINMENT(3)
msg.uuid = random(16)                                # comes back as the response's request_uuid; also used as the handshake challenge
if domain == VCSEC:
    routing_address = random(16)                     # new address per request (VCSEC does not populate request_uuid)
else:
    routing_address = connection_address             # fixed 16 bytes per connection (Go: Dispatcher.address)
msg.from_destination.routing_address = routing_address
msg.flags = 1 << FLAG_ENCRYPT_RESPONSE   # = 2. Always set (old firmware ignores it)
msg.payload = protobuf_message_as_bytes | session_info_request
register_handler(key=(routing_address, uuid if domain!=VCSEC else 0, domain))
```

- `to_destination.domain == DOMAIN_BROADCAST(0)` is an error.
- The response arrives with `to_destination.routing_address == our address` and `from_destination.domain == request domain`.

## 5. Handshake

```
req = RoutableMessage(to=domain, session_info_request={public_key: ENCODE_PUBLIC(C)}, uuid=challenge)
send(req)
rsp = wait_response()                                 # resend if none within RetryInterval(1s)
if rsp.signedMessageStatus.signed_message_fault != NONE: error (UNKNOWN_KEY_ID → key not registered)
info = SessionInfo.parse(rsp.session_info)
if info.status == KEY_NOT_ON_WHITELIST: key not registered
tag  = rsp.signature_data.session_info_tag.tag

# Key agreement
S_x = ECDH(c, info.publicKey)                          # 32-byte X coordinate
K   = SHA1(S_x)[0:16]
SESSION_INFO_KEY = HMAC_SHA256(K, b"session info")

# Verify response integrity (mandatory)
M = TLV(TAG_SIGNATURE_TYPE=0x00, [0x06])              # SIGNATURE_TYPE_HMAC
  + TLV(TAG_PERSONALIZATION=0x02, VIN_ascii)
  + TLV(TAG_CHALLENGE=0x06, challenge)                # the request's uuid
  + 0xFF
expected = HMAC_SHA256(SESSION_INFO_KEY, M + rsp.session_info_bytes)   # raw bytes before parsing
if not constant_time_eq(expected, tag): discard

# Store session state (per domain)
session[domain] = {
    K, vehicle_public: info.publicKey, epoch: info.epoch (16B),
    counter: info.counter,
    time_zero: now - info.clock_time seconds,          # origin of domain clock (local time)
    set_time: info.clock_time,
}
```

- TLV: `tag(1) || len(1) || value`, value length ≤ 255, tags in ascending order, trailing `0xFF`. Integers are `BE32`.
- Go: `session.processHello` → `authentication.NewAuthenticatedSigner` → `NativeSession.SessionInfoHMAC` (the implementation writes the TLV directly into the HMAC context, but the result equals the formula above).
- Perform for each of the two domains (VCSEC, Infotainment). Go sends them in parallel. If Infotainment is asleep, its handshake gets no response → retry after `RKE_ACTION_WAKE_VEHICLE` via VCSEC.
- session_info arriving more than `AllowedLatency` (BLE 4s) after the request is not trusted for clock synchronization (Go `receiver.expired`).

## 6. Command authorization (AES-GCM)

```
P = serialize(CarServer.Action | VCSEC.UnsignedMessage)
s = session[domain]
if s.counter == 0xFFFFFFFF: error (rollover; re-handshake)
s.counter += 1
expires_at = int((now + lifetime - s.time_zero).seconds)     # lifetime: remaining ctx time, default 5s. Too long → TIME_TO_LIVE_TOO_LONG
flags = 2

M = TLV(0x00, [0x05])                 # SIGNATURE_TYPE_AES_GCM_PERSONALIZED
  + TLV(0x01, [domain])               # 2 or 3
  + TLV(0x02, VIN_ascii)              # 17 bytes
  + TLV(0x03, s.epoch)                # 16 bytes
  + TLV(0x04, BE32(expires_at))
  + TLV(0x05, BE32(s.counter))
  + TLV(0x07, BE32(flags))            # included only when flags != 0
  + 0xFF
AAD   = SHA256(M)
nonce = random(12)
ct, tag = AES_128_GCM_encrypt(key=K, nonce, plaintext=P, aad=AAD)

msg.payload.protobuf_message_as_bytes = ct
msg.signature_data.signer_identity.public_key = ENCODE_PUBLIC(C)
msg.signature_data.AES_GCM_Personalized_data = {epoch: s.epoch, nonce, counter: s.counter, expires_at, tag}
msg.flags = flags
request_hash = [0x05] + tag           # stored for response decryption (§7)
```

- Go: `Signer.Encrypt` → `encryptWithCounter`, `Peer.extractMetadata`, `metadata.Add/AddUint32/Checksum`.
- The HMAC path (Fleet API only) uses signature type `[0x08]`, `K' = HMAC_SHA256(K, "authenticated command")`, `tag = HMAC_SHA256(K', M || P)`, plaintext payload, `HMAC_Personalized_data{epoch, counter, expires_at, tag}`. Not used over BLE.
- VCSEC strictly requires counter order → do not send VCSEC commands concurrently; serialize them. Infotainment allows a sliding window.

## 7. Response handling

```
on_message(rsp):
    if rsp.from_destination is None or rsp.to_destination.routing_address is None: drop
    key = (rsp.to_destination.routing_address,
           rsp.request_uuid if rsp.from_destination.domain != VCSEC else 0,
           rsp.from_destination.domain)
    handler = handlers.get(key) or drop

    # (a) Update session from enclosed session_info (commonly enclosed in error responses)
    if rsp.session_info and rsp.signature_data.session_info_tag:
        if not handler.expired(AllowedLatency): verify_and_update(domain, challenge=rsp.request_uuid, ...)   # §8 rules

    # (b) Decrypt encrypted response
    if rsp.signature_data.AES_GCM_Response_data:
        g = rsp.signature_data.AES_GCM_Response_data
        M = TLV(0x00, [0x09])                            # SIGNATURE_TYPE_AES_GCM_RESPONSE
          + TLV(0x01, [rsp.from_destination.domain])
          + TLV(0x02, VIN_ascii)
          + TLV(0x05, BE32(g.counter))
          + TLV(0x07, BE32(rsp.flags))                  # response flags, always included even if 0
          + TLV(0x08, handler.request_hash)             # stored in §6. For VCSEC + HMAC, truncate tag to 16 bytes
          + TLV(0x09, BE32(rsp.signedMessageStatus.signed_message_fault))
          + 0xFF
        plaintext = AES_128_GCM_decrypt(K, g.nonce, ct=rsp.protobuf_message_as_bytes, aad=SHA256(M), tag=g.tag)
        if fail: drop (log)
        if not handler.window.accept(g.counter): drop   # per-request sliding window (32). Replay protection
        rsp.protobuf_message_as_bytes = plaintext

    # (c) Protocol error
    fault = rsp.signedMessageStatus.signed_message_fault
    if fault != NONE: handler.deliver(error(fault)); return        # 08-errors §3.1
    if rsp.signedMessageStatus.operation_status == WAIT: handler.deliver(BUSY)

    # (d) Per-domain payload
    if domain == INFOTAINMENT:
        r = CarServer.Response.parse(payload)
        if r.actionStatus.result == OPERATIONSTATUS_ERROR: application error r.actionStatus.result_reason.plain_text
        else: success (r.vehicleData, etc.)
        handler.done()                                   # 1 response
    else:  # VCSEC — up to 3 responses. Termination rules:
        f = VCSEC.FromVCSECMessage.parse(payload)        # no payload → empty message = success
        if f.nominalError: error GenericError_E; done
        if f.commandStatus:
            if f.commandStatus.operationStatus == WAIT: retry (BUSY)
            if f.commandStatus.operationStatus == ERROR and f.commandStatus.whitelistOperationStatus.whitelistOperationInformation != NONE: KeychainError; done
            if whitelist operation and whitelistOperationStatus present: done (NONE=success)
            else: keep waiting (intermediate state)
        else:
            RKE/Closure: message without commandStatus = final success; information request: first message is the result
```

- Go: `dispatcher.process`, `checkForSessionUpdate`, `decrypt`, `Signer.Decrypt`, `Peer.responseMetadata`, `authentication.RequestID`, `SlidingWindow.Update`; payload interpretation in `pkg/vehicle/vcsec.go unmarshalVCSECResponse/readUntil`, `infotainment.go getCarServerResponse`.
- `request_hash`: `[signature type byte] || request tag`. For an AES-GCM request, `0x05 || tag(16)`. For an HMAC request, `0x08 || tag(32)`, but `tag[:16]` when targeting VCSEC.
- If it times out without receiving a response, treat it as "may have been executed" and do not resend automatically.

## 8. Retry and session recovery

Retriable faults (Go `retriableErrors`): `BUSY(1)`, `TIMEOUT(2)`, `INVALID_SIGNATURE(5)`, `INVALID_TOKEN_OR_COUNTER(6)`, `INTERNAL(11)`, `INCORRECT_EPOCH(15)`, `TIME_EXPIRED(17)`, `TIME_TO_LIVE_TOO_LONG(20)`, plus `operation_status == WAIT`. On retry, redo §6 from the start (new counter, new nonce, new expires_at). Interval 1s. `RESPONSE_MTU_EXCEEDED(25)` means "executed", so do not retry.

Session update rules (`protocol.md` "Recovering from synchronization errors" + Go `Signer.UpdateSessionInfo`):

```
verify_and_update(domain, challenge, info_bytes, tag):
    if challenge is not a uuid we sent within the last few seconds: discard
    if HMAC(SESSION_INFO_KEY, TLV(sig=0x06, VIN, challenge) + 0xFF + info_bytes) != tag: discard
    info = parse(info_bytes)
    if info.publicKey != s.vehicle_public: discard
    if info.epoch != s.epoch or s.set_time <= info.clock_time:
        s.counter = max(s.counter, info.counter)       # never lower the counter (Go implementation). The spec allows an "except when epoch changes" exception
        s.epoch = info.epoch; s.set_time = info.clock_time; s.time_zero = now - info.clock_time
    else: ignore (information with an older timestamp)
```

## 9. Session cache format (Go-compatible)

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

- `data` is the protobuf bytes of `SessionInfo{counter, publicKey (vehicle), epoch, clock_time}`. `clock_time` must be **the domain time at `created_at`** (Go `ExportSessionInfo` stores `timestamp()` = `now - time_zero`, and `ImportSessionInfo` restores `time_zero = created_at - clock_time`).
- `created_at` is RFC3339Nano (Go `time.Time` JSON). `domain` is an integer (2/3). `MaxEntries` is capitalized because it is an untagged field.
- Other languages may use their own format, but since the cache is bound to the private key and holds counter/epoch, restrict file permissions to 0600.
- Treat sessions restored from the cache as immediately usable (ready), and recover via §8 if the first command fails.

## 10. Key pairing (without a private key)

Go `SendAddKeyRequestWithRole` **does not wrap it in a RoutableMessage**; it sends the following bytes directly via `conn.Send`:

```
inner = VCSEC.UnsignedMessage{
    WhitelistOperation{
        addKeyToWhitelistAndAddPermissions: PermissionChange{ key: PublicKey{PublicKeyRaw: ENCODE_PUBLIC(C)}, keyRole: ROLE_OWNER(2)|ROLE_DRIVER(3)|... },
        metadataForKey: KeyMetadata{ keyFormFactor: KEY_FORM_FACTOR_CLOUD_KEY(9)|IOS_DEVICE(6)|ANDROID_DEVICE(7)|NFC_CARD(1) }
    }
}
envelope = VCSEC.ToVCSECMessage{ signedMessage: SignedMessage{ protobufMessageAsBytes: serialize(inner), signatureType: SIGNATURE_TYPE_PRESENT_KEY(2) } }
ble_send(serialize(envelope))          # apply §3 framing
```

- The user must place an NFC card on the center console and confirm on screen. The vehicle sends `FromVCSECMessage.commandStatus.operationStatus = WAIT` and waits for the tap (Go does not read this response).
- Confirming registration: send a §5 handshake (`SessionInfoRequest`) with that public key to the Infotainment domain; it is complete when `status == OK`. Syncing to Infotainment after VCSEC registration can take tens of seconds.
- Not possible over the internet path (Go: `ErrRequiresBLE`). Failure codes are `WhitelistOperation_information_E` ([08-errors.md](08-errors.md) §3.7).

## 11. Verification procedure

1. **Vector tests** (§0.3) pass.
2. **Byte comparison**: compare the `TX:` hex of `tesla-control -ble -vin $VIN -debug list-keys` (unauthenticated) with your implementation's pre-framing bytes. Excluding random fields (`uuid`, `routing_address`), they must be identical. Decode both sides with `protoc --decode=UniversalMessage.RoutableMessage -I pkg/protocol/protobuf pkg/protocol/protobuf/*.proto` and compare field by field.
3. **Unauthenticated commands first**: `InformationRequest{GET_STATUS}` (VCSEC, `body-controller-state`), `GET_WHITELIST_INFO` (`list-keys`), `SessionInfoRequest` (`session-info`). If these three work, §1–§4 and §7 (plaintext) are correct.
4. **Handshake verification**: compare the `epoch/counter/clock_time` in the `session-info public_key.pem vcsec` output with the values your implementation received. HMAC verification must pass.
5. **Authenticated commands**: `RKE_ACTION_LOCK`/`UNLOCK` (VCSEC) → `HvacAutoAction` (Infotainment). If the first command returns `INVALID_SIGNATURE`, check metadata TLV order/length/`flags` inclusion rules; if `TIME_EXPIRED`, check the `expires_at` calculation (based on the domain clock); if `INVALID_TOKEN_OR_COUNTER`, check when the counter is incremented.
6. **Response decryption**: if a response with `AES_GCM_Response_data` decrypts, §7(b) is correct. If it fails, check `request_hash` (storing the request tag) and the response metadata rules for always including `flags` and including `fault`.
7. **Recovery**: deliberately make the cache stale (decrease the counter), then confirm first command fails → session updated from enclosed session_info → resend succeeds.

## 12. Platform-specific notes and security checklist

| Platform | Notes |
|---|---|
| iOS/macOS (CoreBluetooth) | Local Name is `CBAdvertisementDataLocalNameKey`; in the background the Local Name may be restricted, so a service UUID scan may be needed. MTU is `maximumWriteValueLength(for: .withResponse)`. Adapter ID selection not possible. App Bluetooth permission required. |
| Android (BluetoothGatt) | After `requestMtu(517)`, wait for `onMtuChanged` before computing block_length. For 213, `setCharacteristicNotification` + write `ENABLE_INDICATION_VALUE` to the CCCD. Writes use `WRITE_TYPE_DEFAULT` (with response); send the next chunk after `onCharacteristicWrite`. Scan filter by device name. Android 12+ requires `BLUETOOTH_SCAN/CONNECT` permissions. |
| Linux (BlueZ, bleak/btleplug) | `CAP_NET_ADMIN` for raw HCI access; not needed with BlueZ D-Bus. Adapter selection `hciN`. Repeated initialization of multiple adapters fails (Go comment) → reuse the adapter object. |
| Windows | Not supported by the Go implementation. Implementable with WinRT BLE, but no verified cases. |
| Common | VCSEC allows at most 3 connections. Disconnect when not sending commands to free key fob/phone key slots. |

Security checklist

- [ ] Store the private key in protected storage such as the OS keychain/Keystore/Secure Enclave. Go uses `99designs/keyring` (macOS Keychain, etc.).
- [ ] Session cache file 0600, unreadable by other users.
- [ ] Do not register the documented test keys (`client.key`, `vehicle.key`) on a real vehicle.
- [ ] HMAC tag comparison uses a constant-time function.
- [ ] Nonce is a fresh 12-byte CSPRNG value per command. Never reuse.
- [ ] Never update the session without verifying `session_info_tag` (prevents MITM attacks that extend the expiration time).
- [ ] Implement a sliding window for response counters (replay protection).
- [ ] Keep `expires_at` short (5–10s). The vehicle rejects long TTLs.
- [ ] Do not log K, private keys, or pre-decryption payloads (Go `-debug` logs only TX/RX ciphertext hex).

## Appendix: Step ↔ Go implementation mapping

| Step | File:function |
|---|---|
| 1 Scan | `pkg/connector/ble/ble.go`: `VehicleLocalName`, `ScanVehicleBeacon`, `scanVehicleBeacon`; `device_linux.go`: `scanParams` |
| 2 GATT | `ble.go`: `tryToConnect` (Dial → DiscoverServices → DiscoverCharacteristics → DiscoverDescriptors → Subscribe → ExchangeMTU) |
| 3 Framing | `ble.go`: `Connection.Send`, `Connection.rx`, `Connection.flush`; constants `maxBLEMessageSize=1024`, `rxTimeout=1s` |
| 4 RoutableMessage | `internal/dispatcher/dispatcher.go`: `Dispatcher.Send`, `SessionInfoRequest`; `receiver.go`: `receiverKey` |
| 5 Handshake | `dispatcher.go`: `StartSession`, `tryStartSession`, `StartSessions`, `checkForSessionUpdate`; `session.go`: `processHello`; `internal/authentication/signer.go`: `NewAuthenticatedSigner`, `ImportSessionInfo`; `native.go`: `NativeECDHKey.Exchange`, `NativeSession.SessionInfoHMAC` |
| 6 Authorization | `session.go`: `authorize`; `signer.go`: `Encrypt`, `encryptWithCounter`, `AuthorizeHMAC`; `peer.go`: `extractMetadata`, `hmacTag`; `metadata.go`; `crypto.go`: label constants |
| 7 Response | `dispatcher.go`: `process`, `decrypt`; `session.go`: `decrypt`; `signer.go`: `Decrypt`; `peer.go`: `RequestID`, `responseMetadata`; `window.go`: `SlidingWindow`; `pkg/vehicle/vcsec.go`: `unmarshalVCSECResponse`, `readUntil`; `infotainment.go`: `getCarServerResponse`; `pkg/protocol/error.go`: `GetError` |
| 8 Retry/recovery | `pkg/vehicle/vehicle.go`: `Send`, `StartSession`; `pkg/protocol/error.go`: `ShouldRetry`, `retriableErrors`; `signer.go`: `UpdateSignedSessionInfo`, `UpdateSessionInfo` |
| 9 Cache | `pkg/cache/cache.go`; `session.go`: `CacheEntry`, `export`; `dispatcher.go`: `Cache`, `LoadCache`; `signer.go`: `ExportSessionInfo` |
| 10 Pairing | `pkg/vehicle/security.go`: `SendAddKeyRequestWithRole`; `vcsec.go`: `addKeyPayload`; `pkg/vehicle/vehicle.go`: `SessionInfo` |
| 11 Verification | `cmd/tesla-control` (`-debug`, `list-keys`, `session-info`, `body-controller-state`); `internal/authentication/protocol_doc_test.go`; `pkg/protocol/protocol.md` |

---

## Verification checklist

- [ ] The 3 UUIDs, Local Name rule, length prefix (2-byte big-endian), `maxBLEMessageSize=1024`, `rxTimeout=1s`, `blockLength = min(mtu,1024)-3` match `pkg/connector/ble/ble.go`.
- [ ] TLV tag values (0 SIGNATURE_TYPE, 1 DOMAIN, 2 PERSONALIZATION, 3 EPOCH, 4 EXPIRES_AT, 5 COUNTER, 6 CHALLENGE, 7 FLAGS, 8 REQUEST_HASH, 9 FAULT, 255 END) and signature type values (5 AES_GCM_PERSONALIZED, 6 HMAC, 8 HMAC_PERSONALIZED, 9 AES_GCM_RESPONSE) match `signatures.proto`.
- [ ] Request metadata includes FLAGS only when `flags != 0`; response metadata always includes it — matches `peer.go extractMetadata` / `responseMetadata`.
- [ ] The `request_hash` truncation rule (VCSEC + HMAC → 16 bytes) matches `peer.go RequestID`.
- [ ] Reflects the Go behavior of never lowering the counter on session update (`signer.go UpdateSessionInfo`).
- [ ] Cache JSON keys (`vehicles`, `created_at`, `domain`, `data`, `MaxEntries`) match `cache.go`/`session.go`.
- [ ] Re-confirm in `security.go` that the pairing message is a `ToVCSECMessage`, not a RoutableMessage.
- [ ] §0.3 vectors match the values in `pkg/protocol/protocol.md` byte for byte.
