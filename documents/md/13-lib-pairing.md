# Keys and Pairing

Scope: what exists on `main` (M4) is the local key store, the read-only key list, enrolling a key on the vehicle
(`addKeyRequest`, `sessionInfo`, `addKey`, `removeKey`). Real-vehicle check of pairing is pending. Go original behaviour: `09-recipes.md` (key pairing), `10-porting-guide.md`
§10 and §12. Errors: `16-lib-errors.md` §Transport and key store errors. Storage details: `17-lib-platform-notes.md`.

## Key store API (`TeslaBle.keys: VehicleKeyStore`)

| Call | Returns | Notes |
|---|---|---|
| `getOrCreate(alias = DEFAULT_ALIAS, policy = KeyPolicy.PREFER_HARDWARE)` | `VehicleResult<VehicleKey>` | Returns the stored key or creates a NIST P-256 key pair under `alias` |
| `get(alias = DEFAULT_ALIAS)` | `VehicleResult<VehicleKey?>` | `Success(null)` when no key exists |
| `delete(alias = DEFAULT_ALIAS)` | `VehicleResult<Unit>` | Succeeds when the key does not exist |
| `VehicleKeyStore.DEFAULT_ALIAS` | `"teslable-default"` | Swift: `VehicleKeyStoreCompanion.shared.DEFAULT_ALIAS` |
| `VehicleKeyStore.requireValidAlias(alias)` | `Unit` | Alias must match `[A-Za-z0-9._-]{1,64}` and not be `.` or `..`; throws `IllegalArgumentException` otherwise |

`VehicleKey` (handle, never the private key):

| Member | Type | Meaning |
|---|---|---|
| `alias` | `String` | Key store alias |
| `protection` | `KeyProtection` | Where the private key actually lives |
| `publicKey` | `PublicKeyBytes` | Uncompressed `0x04 ‖ X ‖ Y`, 65 bytes. `toByteArray()`, `x`, `y` return copies |

There is no API to export a private key (NFR-004, ADR-0008). ECDH runs inside the platform key store.

## Policies and protection levels

| `KeyPolicy` | Behaviour |
|---|---|
| `PREFER_HARDWARE` (default) | Try hardware first, fall back to a software key |
| `REQUIRE_HARDWARE` | Hardware only. Fails with `KeyStoreError.HardwareUnavailable` |
| `SOFTWARE_ONLY` | Software only (tests, emulators) |

| `KeyProtection` | `isHardware` | Where |
|---|---|---|
| `SECURE_ENCLAVE` | true | iOS Secure Enclave |
| `STRONGBOX` | true | Android StrongBox |
| `TEE` | true | Android Trusted Execution Environment |
| `SOFTWARE` | false | Software key wrapped by the platform key store (Android Keystore AES key, iOS Keychain) or in memory (JVM) |

Show `protection` to the user when it is `SOFTWARE` on a real device: the key is weaker than a hardware key.

## Checking whether a key is enrolled

`vehicle.listKeys()` works without a key and without waking Infotainment. Compare the vehicle's entries with the local
public key:

```kotlin
val mine = key.publicKey.toByteArray()
val enrolled = when (val r = vehicle.listKeys()) {
    is VehicleResult.Success -> r.value.firstOrNull { it.publicKey?.toByteArray()?.contentEquals(mine) == true }
    else -> null                                   // Uncertain or Failure: unknown, ask again later
}
// enrolled?.role is the KeyRole, enrolled?.slot the whitelist slot
```

A connection made with an unenrolled key still works for unauthenticated queries. `startSession()` with an unenrolled
key fails with `VehicleError.KeyNotPaired`.

## Deleting or replacing a key locally

- `keys.delete(alias)` removes the key pair from the device. The vehicle still lists the public key until it is removed
  on the vehicle (`removeKey`, or the car's touchscreen).
- The session cache belongs to the client key (key ID = SHA-1 of the public key). After a key is deleted or replaced,
  the next `connect` discards the old key's cache entries and handshakes again.
- A corrupted stored key (`KeyStoreError.PlatformFailure`, message `stored key is corrupted`) is **never replaced
  automatically**. The app tells the user, calls `delete(alias)` then `getOrCreate(alias)`, and the new public key has
  to be enrolled again. See `17-lib-platform-notes.md` §Key storage.

## Enrolling a key (M4)

Go original: `SendAddKeyRequestWithRole` sends the request and **does not read the reply**. Reading the reply as
`PairingEvent` values is new behaviour of this library, not checked on a real vehicle yet (ADR-0009).

| Call | Returns | Notes |
|---|---|---|
| `addKeyRequest(publicKey, role = KeyRole.OWNER, formFactor = platformKeyFormFactor(), timeout = null)` | `Flow<PairingEvent>` | FR-022, FR-023. Cold: every collection sends one request. Unsigned VCSEC message, no session needed. `timeout = null` uses `TeslaBleConfig.pairingTimeout` (60 s). Requests are serialized: a second collector waits for the first to reach a terminal event |
| `sessionInfo(publicKey, domain, timeout = null)` | `VehicleResult<SessionInfoSummary>` | FR-024. Per-domain enrollment check, no key needed on the connection. `Failure(KeyNotPaired)` when the vehicle does not list the key. Asking with the connection's own key also creates that domain's session, so `sessions` shows `Ready(fromCache = false)` |
| `addKey(publicKey, role = KeyRole.OWNER, formFactor = platformKeyFormFactor(), timeout = null)` | `VehicleResult<Unit>` | FR-027. Authenticated VCSEC (GCM): an enrolled key with enough privileges adds another key, no NFC step. Needs a VCSEC session |
| `removeKey(publicKey, timeout = null)` | `VehicleResult<Unit>` | FR-026. Authenticated VCSEC (GCM). Needs a VCSEC session |

`platformKeyFormFactor()` is `ANDROID_DEVICE` on Android, `IOS_DEVICE` on iOS and `CLOUD_KEY` on the JVM (tests). In
Swift, Kotlin default arguments are not available: pass every argument (`role:`, `formFactor:`, `timeout:`), for
example `formFactor: .iosDevice`.

`addKey` / `removeKey` failures: `InsufficientPrivileges` (the connection's key may not manage keys, for example a
Driver key), `KeychainRejected(code)` (for example `WHITELIST_FULL`, `NO_PERMISSION_TO_ADD`), `NoSession`,
`InvalidArgument` (`role = UNKNOWN`). `Uncertain` means the vehicle may have changed its key list: check with
`listKeys()`, do not resend.

### `PairingEvent`

| Event | Meaning | Terminal |
|---|---|---|
| `Sent` | The request was written. The user must tap an NFC key card on the center console and confirm on the screen | no |
| `WaitingForTap` | The vehicle reports `commandStatus.operationStatus == WAIT` | no |
| `Accepted` | `whitelistOperationStatus` NONE. Infotainment can take tens of seconds more to learn the key | yes |
| `Rejected(error)` | `KeychainRejected(code)`, `UnknownKeychainCode`, `VcsecRejected`, `UnknownVcsecError` | yes |
| `Unknown(raw)` | A frame that could not be decoded as `FromVCSECMessage`. `raw` is a copy of the bytes | no |
| `Failed(error)` | Not sent (`NotConnected`, write failure, `InvalidArgument`), or the link dropped while waiting | yes |
| `TimedOut` | No terminal reply within `timeout`. The key may still have been enrolled: check with `sessionInfo` | yes |

`isTerminal` is true for the four terminal events; the `Flow` completes right after one.

Ignored frames (no event): a frame without `commandStatus` and without `nominalError`; an `OK` intermediate status;
the legacy `ERROR` status. Frames that arrive before the request is sent are dropped.

Procedure:

1. `keys.getOrCreate()`, then `connect(vin, key)`.
2. Collect `vehicle.addKeyRequest(key.publicKey)`. Tell the user to tap the key card on the center console, then confirm on the screen.
3. After `Accepted` (or `TimedOut`), poll `vehicle.sessionInfo(key.publicKey, VehicleDomain.INFOTAINMENT)` every 3 s for
   up to 2 min. `Failure(KeyNotPaired)` means "not yet": keep polling. `Success` means enrolled and synced.
4. `startSession(setOf(VehicleDomain.VCSEC))`, then commands (`15-lib-commands.md`).

Kotlin:

```kotlin
val key = (ble.keys.getOrCreate() as VehicleResult.Success).value
val vehicle = (ble.connect(vin, key) as VehicleResult.Success).value

vehicle.addKeyRequest(key.publicKey).collect { event ->
    when (event) {
        PairingEvent.Sent -> show("Tap the key card on the center console, then confirm on the screen")
        PairingEvent.WaitingForTap -> show("Waiting for the card")
        PairingEvent.Accepted -> show("Accepted, waiting for the car to sync")
        is PairingEvent.Rejected -> show("Rejected: ${event.error.message}")
        is PairingEvent.Unknown -> report(event.raw.joinToString("") { it.toUByte().toString(16).padStart(2, '0') })      // see the warning below
        is PairingEvent.Failed -> show("Failed: ${event.error.message}")
        PairingEvent.TimedOut -> show("No answer, checking enrollment")
    }
}

suspend fun waitUntilEnrolled(vehicle: Vehicle, key: VehicleKey): Boolean {
    val deadline = TimeSource.Monotonic.markNow() + 2.minutes
    while (deadline.hasNotPassedNow()) {
        when (val r = vehicle.sessionInfo(key.publicKey, VehicleDomain.INFOTAINMENT)) {
            is VehicleResult.Success -> return true
            is VehicleResult.Failure -> if (r.error != VehicleError.KeyNotPaired) return false
            is VehicleResult.Uncertain -> Unit                  // reply lost: ask again
        }
        delay(3.seconds)
    }
    return false
}
```

Swift:

```swift
for await event in vehicle.addKeyRequest(publicKey: key.publicKey, role: .owner, formFactor: .iosDevice, timeout: nil) {
    switch onEnum(of: event) {
    case .sent: print("Tap the key card on the center console, then confirm on the screen")
    case .waitingForTap: print("Waiting for the card")
    case .accepted: print("Accepted, waiting for the car to sync")
    case .rejected(let r): print("Rejected: \(r.error.message)")
    case .unknown(let u): print("Unknown frame, \(u.raw.size) bytes")
    case .failed(let f): print("Failed: \(f.error.message)")
    case .timedOut: print("No answer, checking enrollment")
    }
}

// then, every 3 s for up to 2 minutes:
let r = try await vehicle.sessionInfo(publicKey: key.publicKey, domain: .infotainment, timeout: nil)
switch onEnum(of: r) {
case .success: print("enrolled")
case .failure(let f): print("not yet or failed: \(f.error.message)")   // KeyNotPaired: keep polling
case .uncertain: print("reply lost, ask again")
}
```

> **Warning (ADR-0009).** The reply format of add-key-request was not observed on a real vehicle yet. If you receive
> `Unknown(raw)`, please report `raw` as hex (hex only, no VIN) together with what the car did.

Roles (`KeyRole`): `OWNER`, `DRIVER`, `FLEET_MANAGER`, `VEHICLE_MONITOR`, `CHARGING_MANAGER`, `GUEST`, `SERVICE`, `NONE`,
`UNKNOWN` (newer firmware). Form factors (`KeyFormFactor`): `NFC_CARD`, `IOS_DEVICE`, `ANDROID_DEVICE`, `CLOUD_KEY`,
`UNKNOWN`. Values that exist only in the Teslemetry proto schema (for example `ROLE_RIDER`, `KEY_FORM_FACTOR_APPLE_WATCH`,
`KEY_FORM_FACTOR_NFC_CARD_P71`) read as `UNKNOWN` (`19-lib-architecture.md`). Which commands each role may send:
`05-command-catalog.md`.
