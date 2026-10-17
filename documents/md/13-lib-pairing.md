# Keys and Pairing

Scope: what exists on `main` (M3) is the local key store and the read-only key list. **Enrolling a key on the vehicle
(add-key-request) is M4 and not implemented.** Go original behaviour: `09-recipes.md` (key pairing), `10-porting-guide.md`
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
  on the vehicle (planned `removeKey`, M4, or the car's touchscreen).
- The session cache belongs to the client key (key ID = SHA-1 of the public key). After a key is deleted or replaced,
  the next `connect` discards the old key's cache entries and handshakes again.
- A corrupted stored key (`KeyStoreError.PlatformFailure`, message `stored key is corrupted`) is **never replaced
  automatically**. The app tells the user, calls `delete(alias)` then `getOrCreate(alias)`, and the new public key has
  to be enrolled again. See `17-lib-platform-notes.md` §Key storage.

## Planned (M4, not implemented)

Names follow `docs/prd/PRD.md` §5 and may change when M4 lands.

| FR | Planned API | Behaviour |
|---|---|---|
| FR-022 | `addKeyRequest(role = OWNER, formFactor = platform default)` | Sends `addKeyToWhitelistAndAddPermissions` as an unsigned VCSEC message over BLE. The user approves by tapping an NFC key card on the center console |
| FR-023 | progress `Flow` of the request | Reads the vehicle's `WAIT` and final responses (the Go original does not read them) |
| FR-024 | `sessionInfo(publicKey, domain)` | Enrollment check per domain, used to wait for Infotainment to sync after pairing |
| FR-026 | `removeKey(publicKey)` | Removes a key from the whitelist (authenticated) |
| FR-027 | `addKeyWithRole(publicKey, role, formFactor)` | An enrolled owner key adds another key without the NFC step |

Roles (`KeyRole`): `OWNER`, `DRIVER`, `FLEET_MANAGER`, `VEHICLE_MONITOR`, `CHARGING_MANAGER`, `GUEST`, `SERVICE`, `NONE`,
`UNKNOWN` (newer firmware). Form factors (`KeyFormFactor`): `NFC_CARD`, `IOS_DEVICE`, `ANDROID_DEVICE`, `CLOUD_KEY`,
`UNKNOWN`. Which commands each role may send: `05-command-catalog.md`.
