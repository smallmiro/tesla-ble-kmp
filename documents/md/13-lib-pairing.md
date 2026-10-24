# Keys and Pairing

Scope: what exists on `main` (M4) is the local key store, the read-only key list, enrolling a key on the vehicle
(`addKeyRequest`, `sessionInfo`, `addKey`, `removeKey`), and the key accessibility choice for a locked iPhone (FR-037,
issue #91). Real-vehicle check of pairing is pending. Go original behaviour: `09-recipes.md` (key pairing), `10-porting-guide.md`
§10 and §12. Errors: `16-lib-errors.md` §Transport and key store errors. Storage details: `17-lib-platform-notes.md`.

## Key store API (`TeslaBle.keys: VehicleKeyStore`)

| Call | Returns | Notes |
|---|---|---|
| `getOrCreate(alias = DEFAULT_ALIAS, policy = KeyPolicy.PREFER_HARDWARE)` | `VehicleResult<VehicleKey>` | Returns the stored key or creates a NIST P-256 key pair under `alias` with `KeyAccessibility.WHEN_UNLOCKED`. Same as the three-argument form with `WHEN_UNLOCKED`. Swift: `getOrCreate(alias:policy:)` |
| `getOrCreate(alias, policy, accessibility)` | `VehicleResult<VehicleKey>` | FR-037. Creates a missing key with `accessibility` (§Key accessibility). An existing key is returned unchanged whatever `accessibility` says. No defaults; Swift: `getOrCreate(alias:policy:accessibility:)` |
| `get(alias = DEFAULT_ALIAS)` | `VehicleResult<VehicleKey?>` | `Success(null)` when no key exists |
| `delete(alias = DEFAULT_ALIAS)` | `VehicleResult<Unit>` | Succeeds when the key does not exist |
| `VehicleKeyStore.DEFAULT_ALIAS` | `"teslable-default"` | Swift: `VehicleKeyStoreCompanion.shared.DEFAULT_ALIAS` |
| `VehicleKeyStore.requireValidAlias(alias)` | `Unit` | Alias must match `[A-Za-z0-9._-]{1,64}` and not be `.` or `..`; throws `IllegalArgumentException` otherwise |

`VehicleKey` (handle, never the private key):

| Member | Type | Meaning |
|---|---|---|
| `alias` | `String` | Key store alias |
| `protection` | `KeyProtection` | Where the private key actually lives |
| `accessibility` | `KeyAccessibility` | When the key can be used. iOS reads it back from the stored Keychain item; Android and JVM always report `AFTER_FIRST_UNLOCK` |
| `publicKey` | `PublicKeyBytes` | Uncompressed `0x04 ‖ X ‖ Y`, 65 bytes. `toByteArray()`, `x`, `y` return copies |

There is no API to export a private key (NFR-004, ADR-0008). ECDH runs inside the platform key store.

`VehicleKey` also has `suspend fun sharedX(peer: PublicKeyBytes): ByteArray` (from the `EcdhPrivateKey` port), the
handshake's ECDH. Apps do not call it. It throws `KeyLockedException` (package `dev.mirolab.teslable.port`) when the key
exists but the phone lock blocks its use; the library turns that into `KeyStoreError.Locked` (§Key accessibility).

Handle lifetime (issue #97):

- `VehicleKey` has no `close()` and needs none. Keep the key while you use it, then drop the reference.
- On iOS and Android, `get` and `getOrCreate` return a new handle to the same stored key on every call
  (`inMemoryVehicleKeyStore()` returns the same instance). Reading the key again, for example right before each
  connection, does not accumulate handles.
- A `Vehicle` keeps a reference to the key it was connected with, so the app may drop its own reference after
  `connect`.
- iOS: each key object owns one platform reference (`SecKeyRef`) and releases it exactly once after the object is
  garbage collected (a Kotlin/Native `Cleaner`, some time after the last reference is gone, on another thread). It is
  never released while the app or a `Vehicle` still references the key. In Swift, dropping the last reference makes the
  object collectable. Android and JVM keys are ordinary garbage-collected objects.

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

## Key accessibility (locked phone, FR-037)

Whether the private key can be used while the phone is locked is chosen when the key is created (ADR-0014). The choice
only matters on iOS.

| `KeyAccessibility` | iOS Keychain class | Usable while the phone is locked | Use |
|---|---|---|---|
| `WHEN_UNLOCKED` (default) | `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` | No | Same as before FR-037. While the phone is locked, reading the key and a handshake with its Secure Enclave key return `KeyStoreError.Locked` |
| `AFTER_FIRST_UNLOCK` | `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` | Yes, once the phone was unlocked after the last restart | Opt in only when the app needs it, for example background monitoring |

Both classes are `ThisDeviceOnly`: the key never moves to another device through backup or device migration. The Secure
Enclave key gets the class through its access control; the software fallback key through its Keychain item.

Invariants:

- **Fixed at creation, no migration.** `getOrCreate(alias, policy, accessibility)` on an existing alias returns the
  existing key unchanged. Read `key.accessibility` for the real value.
- iOS reads the value back from the stored item's protection class (`kSecAttrAccessible`). If the Keychain does not
  report it (not expected), the key reports `WHEN_UNLOCKED` and the key store logs a `WARN` (tag `keystore`):
  `Keychain did not report the protection class of key <alias>; reporting WHEN_UNLOCKED`.
- Android and JVM ignore the argument and report `AFTER_FIRST_UNLOCK`: Android Keystore keys are generated without an
  unlocked-device or user-authentication requirement (per API; not yet confirmed on a device), and in-memory JVM keys
  have no lock.
- **Locked.** On iOS, `getOrCreate`, `get` and `delete` return `Failure(KeyStoreError.Locked)` while the phone is locked
  and the key is `WHEN_UNLOCKED`, and for every key before the first unlock after a restart. **Never delete or re-create
  the key on `Locked`**; call again after the phone is unlocked (iOS:
  `UIApplication.protectedDataDidBecomeAvailableNotification`). A key that cannot be created because the phone is locked
  is not replaced by a software key.
- A `WHEN_UNLOCKED` key object read while the phone was unlocked and used after the phone locked (issue #96):
  - Secure Enclave key (`protection == SECURE_ENCLAVE`): its handshake ECDH fails. `startSession()` returns
    `Failure(KeyStoreError.Locked)` right after the vehicle's first reply, for VCSEC and Infotainment alike, and the
    library does not retry it. An error the vehicle puts in that reply (for example `KeyNotPaired`) comes first.
    Restoring a cached session at `connect` is skipped for the same reason (`sessions` stays `None`; `WARN` tag
    `Dispatcher`: `Cached session for <domain> not restored: the private key is locked (device locked)`), and the
    cache is **kept**: nothing is written to the session cache until that domain has a session again.
    `sessionInfo(key.publicKey, domain)` still returns the summary, but no session is made. After the phone is
    unlocked, call `startSession()` again with the same key object.
  - Software fallback key (`protection == SOFTWARE`): the object holds the private key in memory, so it keeps working
    after the phone locks (per API, not confirmed on a device). Only reading it from the key store returns `Locked`.
  - Not yet confirmed on a device: that iOS reports the locked Secure Enclave ECDH as `errSecInteractionNotAllowed`
    (-25308). If iOS reports another error, `startSession()` still ends with `Failure(Timeout(afterSend = false))`.
  - Keep using one key object: reading it again from the key store while the phone is locked returns `Locked`.
- `WHEN_UNLOCKED` does not stop an established session when the screen locks: commands in a live session need no
  private-key operation. Only a new handshake and restoring a cached session use the private key.

Risk of `AFTER_FIRST_UNLOCK`: after the first unlock, anyone holding the locked phone, or code running with the app's
authority, can use the key over BLE for whatever its role allows (for example unlocking the car). The Secure Enclave
prevents export of the key, not its use, and asks for no confirmation per command. Tell the user, show
`key.accessibility`, and consider a restricted role for a monitoring key. A software fallback key
(`protection == SOFTWARE`) is more exposed; use `KeyPolicy.REQUIRE_HARDWARE` when the app needs a hardware key.

Changing the accessibility of a key (re-create and re-enroll):

1. Choose a new alias, for example `teslable-monitor`. Keep the old key until the new one works.
2. `keys.getOrCreate(newAlias, KeyPolicy.PREFER_HARDWARE, KeyAccessibility.AFTER_FIRST_UNLOCK)`. Check
   `key.accessibility == AFTER_FIRST_UNLOCK` and `key.protection`.
3. Enroll the new public key (§Enrolling a key): `addKeyRequest` with the NFC key card, or `addKey` from a connection
   whose enrolled key may add keys.
4. Confirm with `sessionInfo(key.publicKey, VehicleDomain.INFOTAINMENT)` or `listKeys()`.
5. Optionally remove the old public key on the vehicle (`removeKey`) and on the phone (`keys.delete(oldAlias)`).

Kotlin:

```kotlin
val key = when (val r = ble.keys.getOrCreate("teslable-monitor", KeyPolicy.PREFER_HARDWARE, KeyAccessibility.AFTER_FIRST_UNLOCK)) {
    is VehicleResult.Success -> r.value
    is VehicleResult.Failure ->
        if (r.error == VehicleError.KeyStoreError.Locked) return waitForUnlock()   // never delete or re-create here
        else return show("Key store: ${r.error.message}")
    is VehicleResult.Uncertain -> return                                          // the key store does not return it
}
if (key.accessibility != KeyAccessibility.AFTER_FIRST_UNLOCK) {
    show("This key works only while the phone is unlocked. Create a key under a new alias and enroll it again.")
}
```

Swift (pass every argument):

```swift
let r = try await ble.keys.getOrCreate(alias: "teslable-monitor", policy: .preferHardware, accessibility: .afterFirstUnlock)
switch onEnum(of: r) {
case .success(let s):
    guard let key = s.value as? VehicleKey else { return }
    if key.accessibility != .afterFirstUnlock { print("unlocked-only key: create a new alias and enroll it again") }
case .failure(let f):
    if f.error is VehicleErrorKeyStoreErrorLocked { print("phone locked: try again after unlock") }   // never delete
    else { print("key store: \(f.error.message)") }
case .uncertain: break                                                                               // not returned by the key store
}
```

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
- `KeyStoreError.Locked` is not damage: the key is fine but the phone is locked. Do not delete it; retry after unlock.
  The same holds for `Locked` from `startSession()`.

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
