# Architecture (Contributor Summary)

A design summary for people contributing to tesla-ble-kmp. The authoritative design is `docs/sdd/SDD.md` in this
repository; this page summarizes it and does not link to SDD sections. Covers M2 plus the M3 `:adapter-ble` and `:sdk`
parts. It is filled in again when the control and query APIs (M4–M5) land.

## Modules

```
App (Android Compose / iOS SwiftUI)
        │
        ▼
     :sdk            Public facade (TeslaBle, Vehicle). Scan, connect, queries in M3; extended in M4–M6
        │
        ▼
 :application        Dispatcher · SessionState · VcsecCommands · InfotainmentCommands · SessionCacheSync · VehicleSession
        │
        ▼
    :domain          Protocol model (Signer, Metadata, SlidingWindow…), ports (Transport, SessionCache, CryptoPrimitives…), pure Kotlin
        ▲        ▲          ▲
 :adapter-ble  :adapter-crypto  :adapter-storage    Platform implementations of the ports (Kable / Keystore·Security.framework / files·Keychain)
```

Dependencies always point towards `:domain`, and the Gradle convention plugins enforce it. `:testing` holds test
fixtures such as `FakeVehicle` and `FakeTransport` and is referenced only from other modules' test code.

## The path of one command (example: `lock()`, M4)

**Sending**

```
VehicleSession.vcsec.execute(payload, auth, done)
  → serial.withLock { ... }              // one VCSEC command per connection (serialization lock owned by VcsecCommands)
    → retryWhileRetriable(retryInterval) { ... }   // a new attempt for every shouldRetry() error
      → Dispatcher.send(message, auth, lifetime)
          → SessionState.authorize        // Signer.encrypt: counter++, AAD, nonce
          → transport.send(bytes)         // Transport port (implemented by :adapter-ble)
      → wait on PendingRequest (readUntil) // until the response is judged final
```

Infotainment commands take the same last two steps (retry → `Dispatcher.send`) through
`InfotainmentCommands.execute` → `SendWithRetry.send`, without the serialization lock.

**Receiving**

```
Transport.incoming (Flow<ByteArray>)
  → collected by the Dispatcher's single receive coroutine
      → parse as RoutableMessage
      → match against the sent request (PendingRequest)
      → verify and apply attached session info (SessionState.processHello)
      → decrypt encrypted responses and check for replays (SlidingWindow)
      → hand to the matched PendingRequest
```

## Concurrency

- **Exactly one receive coroutine per vehicle connection.** It never suspends waiting for another coroutine (channel
  receive, `Deferred.await`, `delay`); when it hands data to a channel it uses non-suspending `trySend`. The one
  exception: when a new receive coroutine starts, it waits for the previous one to finish, so two never collect at once.
- **One VCSEC command at a time per connection** (serialization lock). Time spent waiting for the previous command
  counts against that command's timeout. **Infotainment commands may run in parallel.**
- Each domain (VCSEC / INFOTAINMENT) has one session state. Its internal lock guards only encrypt, decrypt and session
  info updates, so lock waits are short and bounded.
- The handshake (sessions for both domains) runs per domain in parallel; if one fails the other is cancelled.
- Commands, handshakes and connections each have a timeout. Timeouts and retry rules: `16-lib-errors.md`.

## BLE adapter (`:adapter-ble`)

Split into Kable bindings and a testable core. Only `KableGattConnector` (scan, open) and `KableGattLink` (write,
subscribe, drop detection) know Kable. `BleTransportFactory` and `BleTransport` handle framing, reassembly, retries and
error mapping without Kable and are verified by JVM unit tests. When the link drops, the incoming flow closes and the
transport state becomes `Disconnected`. The scan filter is an exact match on the local name (`S<VIN hash>C`).

## Facade assembly (`:sdk`)

`TeslaBle.create(platform, config)` assembles the platform adapters (BLE transport, key store, session cache, crypto
primitives). `connect` opens the transport, creates a coroutine scope for that connection, attaches a `Dispatcher` and a
`VehicleSession`, and restores the cache. If anything fails or is cancelled midway, it always releases the transport
and the scope. `Vehicle` is one object per connection; after `disconnect()`, commands fail with `NotConnected`.

## Session cache

Sessions (encryption key state agreed in the handshake) are stored in a custom binary format (v1) so the next
connection can send commands without a new handshake. The cache belongs to the client key (SHA-1 of the public key);
caches of other keys are ignored and deleted. It is stored when a handshake completes, when session info is updated,
and on disconnect (wired up by the facade in M3). Private keys, session encryption keys and raw VINs are never stored.
Corrupted entries or entries with unrecognized values are skipped and the rest restored.

## Protobuf schema

- `:domain` generates Kotlin from `domain/src/commonMain/proto/` with Wire 7.0.4. Generated packages follow each file's
  `java_package` (`com.tesla.generated.*`; `managed_charging.proto` has none and becomes `ManagedCharging`).
- Since 2026-10-18 (ADR-0002 revision) the 9 files (`car_server`, `common`, `errors`, `keys`, `managed_charging`,
  `signatures`, `universal_message`, `vcsec`, `vehicle`) are a byte-for-byte copy of
  Teslemetry/tesla-protocol@`e6f7afb1b633d7215e34d0cdac66dab983c6ae78` `proto/command/`: the official vehicle-command
  protos plus additions marked `TESLEMETRY-EXT` (some also `UNCONFIRMED` or `name unverified`). `session.proto` and the
  telemetry, energy and dashcam families are not used. Provenance: `PROTO_SOURCE.md` next to the files, and `NOTICE`.
- The ground truth for meaning, cryptography, routing and error classification stays the official Go code
  (vehicle-command@`a4b43c1`). When the two disagree, the Go code wins.
- Extension fields and values exist as generated types only. No command, event or public model value is built on them
  until a real-vehicle capture or the official Go code confirms them. Public model enums (`KeyRole`, `KeyFormFactor`)
  map extension values to `UNKNOWN`. The observation log (`18-lib-troubleshooting.md`) prints their schema names.
- To update: check out tesla-protocol at the pinned commit, run `tools/proto/sync-protos.sh <checkout>` (no network; it
  refuses any other commit), then `./gradlew :domain:generateCommonMainProtos`, `./gradlew :domain:apiDump` and the
  tests. The pinned commit is in the script and in `docs/PATHS.md` (`PROTO_SCHEMA_COMMIT`); change both in one PR.
