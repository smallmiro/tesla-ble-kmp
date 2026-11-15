# Tesla BLE KMP User Guide

An offline, single-page guide for the current app-facing library APIs.
Scope: :sdk facade, key management, configuration, input models and returned values. Internal protocol/adapter APIs are not app integration APIs.
Requirements and implementation semantics are unchanged. Sources: {{LIB_DOCS_INDEX}} and the maintained 12-19 library chapters.
Reference manual: {{MANUAL_DIR}}04-go-api-reference.md, 05-command-catalog.md, 08-errors.md and 09-recipes.md (read-only).

Human-readable editions: [Korean](../html/ko/11-lib-user-guide.html), [English](../html/en/11-lib-user-guide.html), [Japanese](../html/ja/11-lib-user-guide.html).

## Installation



## Installation and project setup


The iOS library is published as a Swift package from version 1.0.0. Android and JVM are not published yet (Maven Central comes later): build them from a source checkout and do not invent Maven coordinates. CocoaPods is not supported. Dependency downloads need Internet access during a build; vehicle communication uses BLE only.



### iOS: Swift Package Manager


In Xcode choose File > Add Package Dependencies, enter the URL below, choose the dependency rule Up to Next Major Version from 1.0.0, and add the Teslable product to the app target. In a Package.swift, add the package to dependencies and the product to the dependencies of your target.


```swift
.package(url: "https://github.com/smallmiro/tesla-ble-kmp", from: "1.0.0")
.product(name: "Teslable", package: "tesla-ble-kmp")
```


Requirements: iOS 16 or later and NSBluetoothAlwaysUsageDescription in Info.plist. The framework is static, with an arm64 device slice and an arm64 simulator slice only: use the simulator on an Apple Silicon Mac, and set EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64 for simulator builds of every architecture (for example Release). The simulator has no Bluetooth, so scanning and connecting need a real iPhone, and its Keychain needs a signed app (ad-hoc signing is enough). Details are in Platform notes and Troubleshooting.


Then import Teslable. Swift gets no Kotlin default-argument overloads, so pass every argument, including timeout: nil. Use withSeconds and Timeouts for time limits. Full Swift examples are in Getting started and Platform notes. The configuration below does not log.


```swift
import Teslable
let ble = TeslaBle.companion.create(
    platform: PlatformContext(),
    config: TeslaBleConfig.companion.default(logger: TeslaLoggerCompanion.shared.NoOp)
)
```


### Android and JVM: build from source


Development is complete, but the Android real-device check is not finished, so there is no Maven artifact yet. Use Git, a JDK 17 toolchain (provisioned by Gradle), Android SDK 37 and an Android API 31+ device, with the repository Gradle Wrapper and pinned dependency versions. The verified path is an app module depending on :sdk within the complete source Gradle build. Build the Android sample first, then add your app module to that same build and add this dependency. Copying only :sdk omits required internal modules and build plugins.


```bash
git clone https://github.com/smallmiro/tesla-ble-kmp
cd tesla-ble-kmp
./gradlew :sdk:assemble
./gradlew :samples:android:assembleDebug
```


```kotlin
dependencies {
    implementation(project(":sdk"))
}
```


Set app minSdk to at least 31. Declare and request BLUETOOTH_SCAN and BLUETOOTH_CONNECT before scanning. Exclude key files from backup. Exact XML and storage settings are in Platform notes. The default configuration below does not log.


```kotlin
val ble = TeslaBle.create(PlatformContext(context), TeslaBleConfig())
```


### iOS framework from source


To change the library or run the sample app, build the framework from source instead of using the package. Link the iosArm64 static framework for a device, and the iosSimulatorArm64 framework for an Apple Silicon simulator. Add the matching output directory to Framework Search Paths and link Teslable.framework with Do Not Embed (it is static). Rebuild the framework when Kotlin code changes. The existing XcodeGen sample shows both configurations.


```bash
./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64
./gradlew :sdk:linkDebugFrameworkIosArm64
```


```text
sdk/build/bin/iosArm64/debugFramework/Teslable.framework
sdk/build/bin/iosSimulatorArm64/debugFramework/Teslable.framework
```


## Complete method reference

These are individual call examples. ble, vehicle, key and vin come from the getting-started flow; inputs are illustrative. Each call is independent, not a script to execute in sequence. Run suspend calls in a coroutine. Imports: dev.mirolab.teslable.*, dev.mirolab.teslable.model.*, dev.mirolab.teslable.port.*, kotlin.time.Duration.Companion.seconds. Schedule types are in com.tesla.generated.carserver.common.*.

### Vehicle.startSession

```kotlin
public suspend fun startSession( domains: Set<VehicleDomain> = VehicleDomain.ALL, timeout: Duration? = null): VehicleResult<Unit>
```

- `domains: Set<VehicleDomain>` (VehicleDomain.ALL): Domains to establish. ALL contains VCSEC and INFOTAINMENT.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.startSession(domains = setOf(VehicleDomain.VCSEC, VehicleDomain.INFOTAINMENT), timeout = 10.seconds)
```

Success(Unit): requested domains are ready for authentication, including cache restores. Failure(error): RequiresKey/KeyNotPaired/Locked/Timeout, etc. Uses handshakeTimeout by default.

### Vehicle.disconnect

```kotlin
public suspend fun disconnect(): Unit
```


```kotlin
vehicle.disconnect()
```

Unit. Idempotent cleanup: save cache, wipe session keys, release links. close cleans up all connections; a closed TeslaBle cannot be reused.

### Vehicle.vehicleStatus

```kotlin
public suspend fun vehicleStatus(timeout: Duration? = null): VehicleResult<VehicleStatus>
```

No authenticated session required. Connect over BLE first.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.vehicleStatus(timeout = 10.seconds)
```

Success(value: VehicleStatus). lockState, closures, sleepStatus, userPresence, tonneauPercentOpen, gear. LOCKED/ASLEEP/PARK are illustrative, not a promised response. Uncertain(error) / Failure(error).

### Vehicle.getState

```kotlin
public suspend fun getState( category: StateCategory, timeout: Duration? = null): VehicleResult<VehicleData>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `category: StateCategory` (required): One category per request. All 12 values and fields are in Reading state.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.getState(category = StateCategory.CHARGE, timeout = 10.seconds)
```

Success(value: VehicleData). Nullable state messages for category; e.g. charge_state?.battery_level=80 (illustrative). Missing=null, not zero. Empty VehicleData can also succeed. Uncertain(error) / Failure(error).

### Vehicle.keySummary

```kotlin
public suspend fun keySummary(timeout: Duration? = null): VehicleResult<WhitelistSummary>
```

No authenticated session required. Connect over BLE first.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.keySummary(timeout = 10.seconds)
```

Success(value: WhitelistSummary). numberOfEntries, slotMask, occupiedSlots. Example: slotMask=5u gives occupiedSlots=[0,2]. Uncertain(error) / Failure(error).

### Vehicle.listKeys

```kotlin
public suspend fun listKeys(timeout: Duration? = null): VehicleResult<List<WhitelistEntry>>
```

No authenticated session required. Connect over BLE first.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.listKeys(timeout = 10.seconds)
```

Success(value: List<WhitelistEntry>). List of slot, publicKey?, keyIdSha1Hex?, role and formFactor per key; timeout covers the whole list. Uncertain(error) / Failure(error).

### Vehicle.sessionInfo

```kotlin
public suspend fun sessionInfo( publicKey: PublicKeyBytes, domain: VehicleDomain, timeout: Duration? = null): VehicleResult<SessionInfoSummary>
```

No authenticated session required. Connect over BLE first.

- `publicKey: PublicKeyBytes` (required): 65-byte uncompressed P-256 public key: 0x04 || X(32) || Y(32).
- `domain: VehicleDomain` (required): VCSEC or INFOTAINMENT. Verify enrollment using INFOTAINMENT.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.sessionInfo(publicKey = key.publicKey, domain = VehicleDomain.INFOTAINMENT, timeout = 10.seconds)
```

Success(value: SessionInfoSummary). counter(UInt), clockTime(UInt, seconds since epoch start), vehiclePublicKey?. INFOTAINMENT success verifies enrollment. Uncertain(error) / Failure(error).

### Vehicle.addKeyRequest

```kotlin
public fun addKeyRequest( publicKey: PublicKeyBytes, role: KeyRole = KeyRole.OWNER, formFactor: KeyFormFactor = platformKeyFormFactor(), timeout: Duration? = null): Flow<PairingEvent>
```

No authenticated session required. Connect over BLE first.

- `publicKey: PublicKeyBytes` (required): 65-byte uncompressed P-256 public key: 0x04 || X(32) || Y(32).
- `role: KeyRole` (KeyRole.OWNER): Role for the enrolled key; default OWNER. See models for every value.
- `formFactor: KeyFormFactor` (platformKeyFormFactor()): Key form factor. Platform default: Android=ANDROID_DEVICE, iOS=IOS_DEVICE, JVM=CLOUD_KEY.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
vehicle.addKeyRequest(publicKey = key.publicKey, role = KeyRole.OWNER, formFactor = platformKeyFormFactor(), timeout = 10.seconds).collect { event ->
    // Handle each event in the UI.
}
```

Cold Flow: Sent/WaitingForTap/Unknown, then terminal Accepted/Rejected/Failed/TimedOut. Every collection sends a request; avoid duplicate collectors. Verify with sessionInfo(INFOTAINMENT) after Accepted.

### Vehicle.addKey

```kotlin
public suspend fun addKey( publicKey: PublicKeyBytes, role: KeyRole = KeyRole.OWNER, formFactor: KeyFormFactor = platformKeyFormFactor(), timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `publicKey: PublicKeyBytes` (required): 65-byte uncompressed P-256 public key: 0x04 || X(32) || Y(32).
- `role: KeyRole` (KeyRole.OWNER): Role for the enrolled key; default OWNER. See models for every value.
- `formFactor: KeyFormFactor` (platformKeyFormFactor()): Key form factor. Platform default: Android=ANDROID_DEVICE, iOS=IOS_DEVICE, JVM=CLOUD_KEY.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.addKey(publicKey = key.publicKey, role = KeyRole.OWNER, formFactor = platformKeyFormFactor(), timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.removeKey

```kotlin
public suspend fun removeKey( publicKey: PublicKeyBytes, timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `publicKey: PublicKeyBytes` (required): 65-byte uncompressed P-256 public key: 0x04 || X(32) || Y(32).
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.removeKey(publicKey = key.publicKey, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.lock

```kotlin
public suspend fun lock(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.lock(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.unlock

```kotlin
public suspend fun unlock(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.unlock(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.wakeUp

```kotlin
public suspend fun wakeUp(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.wakeUp(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.remoteDrive

```kotlin
public suspend fun remoteDrive(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.remoteDrive(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.autoSecureVehicle

```kotlin
public suspend fun autoSecureVehicle(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.autoSecureVehicle(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.openTrunk

```kotlin
public suspend fun openTrunk(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.openTrunk(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.actuateTrunk

```kotlin
public suspend fun actuateTrunk(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.actuateTrunk(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.closeTrunk

```kotlin
public suspend fun closeTrunk(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.closeTrunk(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.openFrunk

```kotlin
public suspend fun openFrunk(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.openFrunk(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.openTonneau

```kotlin
public suspend fun openTonneau(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.openTonneau(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.closeTonneau

```kotlin
public suspend fun closeTonneau(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.closeTonneau(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.stopTonneau

```kotlin
public suspend fun stopTonneau(timeout: Duration? = null): VehicleResult<Unit>
```

Requires a VCSEC session: call after startSession(setOf(VehicleDomain.VCSEC)) succeeds. After an uncertain key change use listKeys(); for other controls use vehicleStatus().

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.stopTonneau(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.climateOn

```kotlin
public suspend fun climateOn(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.climateOn(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.climateOff

```kotlin
public suspend fun climateOff(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.climateOff(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setSeatHeater

```kotlin
public suspend fun setSeatHeater( levels: Map<SeatPosition, SeatClimateLevel>, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `levels: Map<SeatPosition, SeatClimateLevel>` (required): Heater levels per seat: OFF/LOW/MED/HIGH. Empty maps are sent; declaration order is used.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setSeatHeater(levels = mapOf(SeatPosition.FRONT_LEFT to SeatClimateLevel.HIGH), timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setClimateTemp

```kotlin
public suspend fun setClimateTemp( driverCelsius: Float, passengerCelsius: Float, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `driverCelsius: Float` (required): Driver temperature in Celsius. No local range check.
- `passengerCelsius: Float` (required): Passenger temperature in Celsius. No local range check.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setClimateTemp(driverCelsius = 22.0f, passengerCelsius = 21.5f, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setSeatCooler

```kotlin
public suspend fun setSeatCooler( level: SeatClimateLevel, seat: SeatPosition, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `level: SeatClimateLevel` (required): Seat cooler: OFF/LOW/MED/HIGH. The sunroof Int level is an absolute position with no local range check.
- `seat: SeatPosition` (required): Cooler accepts only FRONT_LEFT/FRONT_RIGHT; otherwise InvalidArgument.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setSeatCooler(level = SeatClimateLevel.LOW, seat = SeatPosition.FRONT_RIGHT, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setSteeringWheelHeater

```kotlin
public suspend fun setSteeringWheelHeater( enabled: Boolean, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `enabled: Boolean` (required): true enables; false disables.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setSteeringWheelHeater(enabled = true, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.autoSeatAndClimate

```kotlin
public suspend fun autoSeatAndClimate( seats: List<SeatPosition>, enabled: Boolean, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `seats: List<SeatPosition>` (required): Auto climate retains UNKNOWN/FRONT_LEFT/FRONT_RIGHT and silently drops others.
- `enabled: Boolean` (required): true enables; false disables.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.autoSeatAndClimate(seats = listOf(SeatPosition.FRONT_LEFT, SeatPosition.FRONT_RIGHT), enabled = true, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setPreconditioningMax

```kotlin
public suspend fun setPreconditioningMax( enabled: Boolean, manualOverride: Boolean, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `enabled: Boolean` (required): true enables; false disables.
- `manualOverride: Boolean` (required): Manual override flag sent to the vehicle.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setPreconditioningMax(enabled = true, manualOverride = true, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setClimateKeeperMode

```kotlin
public suspend fun setClimateKeeperMode( mode: ClimateKeeperMode, manualOverride: Boolean, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `mode: ClimateKeeperMode` (required): OFF/ON/DOG/CAMP.
- `manualOverride: Boolean` (required): Manual override flag sent to the vehicle.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setClimateKeeperMode(mode = ClimateKeeperMode.DOG, manualOverride = true, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setBioweaponDefenseMode

```kotlin
public suspend fun setBioweaponDefenseMode( enabled: Boolean, manualOverride: Boolean, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `enabled: Boolean` (required): true enables; false disables.
- `manualOverride: Boolean` (required): Manual override flag sent to the vehicle.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setBioweaponDefenseMode(enabled = true, manualOverride = true, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setCabinOverheatProtection

```kotlin
public suspend fun setCabinOverheatProtection( enabled: Boolean, fanOnly: Boolean, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `enabled: Boolean` (required): true enables; false disables.
- `fanOnly: Boolean` (required): Request fan-only cabin overheat protection.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setCabinOverheatProtection(enabled = true, fanOnly = true, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setCabinOverheatProtectionTemperature

```kotlin
public suspend fun setCabinOverheatProtectionTemperature( temperature: CabinOverheatProtectionTemperature, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `temperature: CabinOverheatProtectionTemperature` (required): UNSPECIFIED/LOW/MEDIUM/HIGH. A level, not Celsius; UNSPECIFIED does not mean off.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setCabinOverheatProtectionTemperature(temperature = CabinOverheatProtectionTemperature.HIGH, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.chargeStart

```kotlin
public suspend fun chargeStart(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.chargeStart(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.chargeStop

```kotlin
public suspend fun chargeStop(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.chargeStop(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setChargeLimit

```kotlin
public suspend fun setChargeLimit( percent: Int, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `percent: Int` (required): Charge limit, percent. Forwarded without a local range check.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setChargeLimit(percent = 80, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setChargingAmps

```kotlin
public suspend fun setChargingAmps( amps: Int, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `amps: Int` (required): Charge current in amperes. No local range check.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setChargingAmps(amps = 16, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.chargeStandardRange

```kotlin
public suspend fun chargeStandardRange(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.chargeStandardRange(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.chargeMaxRange

```kotlin
public suspend fun chargeMaxRange(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.chargeMaxRange(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.scheduleCharging

```kotlin
public suspend fun scheduleCharging( enabled: Boolean, minutesAfterMidnight: Int, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `enabled: Boolean` (required): true enables; false disables.
- `minutesAfterMidnight: Int` (required): Minutes after vehicle-local midnight; 120=02:00. No local range check.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.scheduleCharging(enabled = true, minutesAfterMidnight = 120, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.scheduleDeparture

```kotlin
public suspend fun scheduleDeparture( departAtMinutes: Int, offPeakEndMinutes: Int, preconditioning: ChargingPolicy, offPeak: ChargingPolicy, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `departAtMinutes: Int` (required): Minutes after vehicle-local midnight; 480=08:00. Range 0..1440 inclusive.
- `offPeakEndMinutes: Int` (required): Off-peak end, minutes after vehicle-local midnight; 360=06:00. No local check.
- `preconditioning: ChargingPolicy` (required): OFF/ALL_DAYS/WEEKDAYS. OFF omits that sub-setting.
- `offPeak: ChargingPolicy` (required): OFF/ALL_DAYS/WEEKDAYS. OFF omits that sub-setting.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.scheduleDeparture(departAtMinutes = 480, offPeakEndMinutes = 360, preconditioning = ChargingPolicy.ALL_DAYS, offPeak = ChargingPolicy.WEEKDAYS, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.clearScheduledDeparture

```kotlin
public suspend fun clearScheduledDeparture(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.clearScheduledDeparture(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setLowPowerMode

```kotlin
public suspend fun setLowPowerMode( enabled: Boolean, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `enabled: Boolean` (required): true enables; false disables.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setLowPowerMode(enabled = true, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setKeepAccessoryPowerMode

```kotlin
public suspend fun setKeepAccessoryPowerMode( enabled: Boolean, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `enabled: Boolean` (required): true enables; false disables.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setKeepAccessoryPowerMode(enabled = true, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.addChargeSchedule

```kotlin
public suspend fun addChargeSchedule( schedule: ChargeSchedule, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `schedule: ChargeSchedule` (required): Wire schedule object, sent as supplied. See Platform notes for every constructor field and Swift examples.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.addChargeSchedule(schedule = ChargeSchedule(id = 1_700_000_000L, name = "Home", days_of_week = 127, start_enabled = true, start_time = 120, enabled = true), timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.removeChargeSchedule

```kotlin
public suspend fun removeChargeSchedule( id: Long, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `id: Long` (required): Schedule id to remove (Long, wire uint64). Use the actual id from a query.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.removeChargeSchedule(id = 1_700_000_000L, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.batchRemoveChargeSchedules

```kotlin
public suspend fun batchRemoveChargeSchedules( home: Boolean, work: Boolean, other: Boolean, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `home: Boolean` (required): Whether to remove schedules classified as home.
- `work: Boolean` (required): Whether to remove schedules classified as work.
- `other: Boolean` (required): Whether to remove schedules classified as neither home nor work.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.batchRemoveChargeSchedules(home = true, work = false, other = false, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.addPreconditionSchedule

```kotlin
public suspend fun addPreconditionSchedule( schedule: PreconditionSchedule, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `schedule: PreconditionSchedule` (required): Wire schedule object, sent as supplied. See Platform notes for every constructor field and Swift examples.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.addPreconditionSchedule(schedule = PreconditionSchedule(id = 2L, name = "Work", days_of_week = 62, precondition_time = 450, enabled = true), timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.removePreconditionSchedule

```kotlin
public suspend fun removePreconditionSchedule( id: Long, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `id: Long` (required): Schedule id to remove (Long, wire uint64). Use the actual id from a query.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.removePreconditionSchedule(id = 1_700_000_000L, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.batchRemovePreconditionSchedules

```kotlin
public suspend fun batchRemovePreconditionSchedules( home: Boolean, work: Boolean, other: Boolean, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `home: Boolean` (required): Whether to remove schedules classified as home.
- `work: Boolean` (required): Whether to remove schedules classified as work.
- `other: Boolean` (required): Whether to remove schedules classified as neither home nor work.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.batchRemovePreconditionSchedules(home = true, work = false, other = false, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.openChargePort

```kotlin
public suspend fun openChargePort(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.openChargePort(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.closeChargePort

```kotlin
public suspend fun closeChargePort(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.closeChargePort(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.ping

```kotlin
public suspend fun ping(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.ping(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.ventWindows

```kotlin
public suspend fun ventWindows(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.ventWindows(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.closeWindows

```kotlin
public suspend fun closeWindows(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.closeWindows(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setSunroof

```kotlin
public suspend fun setSunroof( level: Int, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `level: Int` (required): Absolute sunroof position. Zero is sent too. No local range check.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setSunroof(level = 0, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.honkHorn

```kotlin
public suspend fun honkHorn(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.honkHorn(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.flashLights

```kotlin
public suspend fun flashLights(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.flashLights(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setSentryMode

```kotlin
public suspend fun setSentryMode( enabled: Boolean, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `enabled: Boolean` (required): true enables; false disables.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setSentryMode(enabled = true, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.enableValetMode

```kotlin
public suspend fun enableValetMode( pin: String, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `pin: String` (required): Illustrative PIN. Exactly four ASCII digits. Never keep a real PIN in code or logs.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.enableValetMode(pin = "1234", timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.disableValetMode

```kotlin
public suspend fun disableValetMode(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.disableValetMode(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.resetValetPin

```kotlin
public suspend fun resetValetPin(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.resetValetPin(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.volumeUp

```kotlin
public suspend fun volumeUp(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.volumeUp(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.volumeDown

```kotlin
public suspend fun volumeDown(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.volumeDown(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.setVolume

```kotlin
public suspend fun setVolume( volume: Float, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `volume: Float` (required): 0..10 inclusive. NaN and infinities are rejected.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.setVolume(volume = 5.5f, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.mediaNextTrack

```kotlin
public suspend fun mediaNextTrack(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.mediaNextTrack(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.mediaPreviousTrack

```kotlin
public suspend fun mediaPreviousTrack(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.mediaPreviousTrack(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.mediaNextFavorite

```kotlin
public suspend fun mediaNextFavorite(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.mediaNextFavorite(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.mediaPreviousFavorite

```kotlin
public suspend fun mediaPreviousFavorite(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.mediaPreviousFavorite(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.toggleMediaPlayback

```kotlin
public suspend fun toggleMediaPlayback(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.toggleMediaPlayback(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.scheduleSoftwareUpdate

```kotlin
public suspend fun scheduleSoftwareUpdate( delaySeconds: Int, timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `delaySeconds: Int` (required): Update delay in seconds; 7200=2 hours. No local check.
- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.scheduleSoftwareUpdate(delaySeconds = 7200, timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### Vehicle.cancelSoftwareUpdate

```kotlin
public suspend fun cancelSoftwareUpdate(timeout: Duration? = null): VehicleResult<Unit>
```

Requires an INFOTAINMENT session and an awake vehicle. Call after startSession(setOf(VehicleDomain.INFOTAINMENT)) succeeds. Does not start a session or wake the vehicle automatically.

- `timeout: Duration?` (null): Whole-call time limit. null uses the config default: commands 5 s, handshake 20 s, connect 30 s, pairing 60 s.

```kotlin
val result = vehicle.cancelSoftwareUpdate(timeout = 10.seconds)
```

Success(Unit): vehicle acknowledgement with no data. Re-query when the UI needs the resulting state. Uncertain(error): it may have executed; check state instead of resending. Failure(error): act on its type.

### TeslaBle.scan

```kotlin
public fun scan(vin: Vin): Flow<ScanEvent>
```

- `vin: Vin` (required): VIN object validated using Vin.parseOrNull(user input).

```kotlin
ble.scan(vin = vin).collect { event ->
    // Handle each event in the UI.
}
```

Found(advertisement) or Failed(error). Cancel collection to stop scanning; the caller bounds scan time.

### TeslaBle.connect

```kotlin
public suspend fun connect( vin: Vin, key: VehicleKey?, target: VehicleAdvertisement? = null, options: ConnectOptions = ConnectOptions()): VehicleResult<Vehicle>
```

- `vin: Vin` (required): VIN object validated using Vin.parseOrNull(user input).
- `key: VehicleKey?` (required): Key handle; null permits unauthenticated queries only.
- `target: VehicleAdvertisement?` (null): Advertisement obtained from scan. null makes connect scan.
- `options: ConnectOptions` (ConnectOptions()): Connection options. timeout covers scan, open and subscription.

```kotlin
val result = ble.connect(vin = vin, key = key, target = null, options = ConnectOptions(timeout = 30.seconds))
```

Success(value: Vehicle). A new connection object. Observe connection/sessions/events and disconnect in finally. Uncertain(error) / Failure(error).

### TeslaBle.close

```kotlin
public suspend fun close(): Unit
```


```kotlin
ble.close()
```

Unit. Idempotent cleanup: save cache, wipe session keys, release links. close cleans up all connections; a closed TeslaBle cannot be reused.

### TeslaBle.create

```kotlin
public fun create( platform: PlatformContext, config: TeslaBleConfig = TeslaBleConfig()): TeslaBle
```

- `platform: PlatformContext` (required): Android: PlatformContext(context). iOS/JVM: PlatformContext().
- `config: TeslaBleConfig` (TeslaBleConfig()): Logger, cache and time limits; defaults are in the configuration table.

```kotlin
val result = TeslaBle.create(platform = PlatformContext(context), config = TeslaBleConfig())
```

TeslaBle. See the linked instructions and model declarations for semantics and exceptions.

### TeslaBleConfig.default

```kotlin
public fun default(logger: TeslaLogger = TeslaLogger.NoOp): TeslaBleConfig
```

- `logger: TeslaLogger` (TeslaLogger.NoOp): No logging by default. DebugLogger() is for development.

```kotlin
val result = TeslaBleConfig.default(logger = TeslaLogger.NoOp)
```

TeslaBleConfig with the supplied logger and other defaults: commandTimeout=5 s, commandLifetime=5 s, handshakeTimeout=20 s, connectTimeout=30 s, pairingTimeout=60 s, sessionCache=null.

### TeslaBleConfig.withSeconds

```kotlin
public fun withSeconds( logger: TeslaLogger, commandTimeoutSeconds: Double, handshakeTimeoutSeconds: Double, connectTimeoutSeconds: Double, pairingTimeoutSeconds: Double): TeslaBleConfig
```

- `logger: TeslaLogger` (required): No logging by default. DebugLogger() is for development.
- `commandTimeoutSeconds: Double` (required): Positive finite seconds (Double). Nonpositive, NaN or infinity throws IllegalArgumentException.
- `handshakeTimeoutSeconds: Double` (required): Positive finite seconds (Double). Nonpositive, NaN or infinity throws IllegalArgumentException.
- `connectTimeoutSeconds: Double` (required): Positive finite seconds (Double). Nonpositive, NaN or infinity throws IllegalArgumentException.
- `pairingTimeoutSeconds: Double` (required): Positive finite seconds (Double). Nonpositive, NaN or infinity throws IllegalArgumentException.

```kotlin
val result = TeslaBleConfig.withSeconds(logger = TeslaLogger.NoOp, commandTimeoutSeconds = 5.0, handshakeTimeoutSeconds = 20.0, connectTimeoutSeconds = 30.0, pairingTimeoutSeconds = 60.0)
```

TeslaBleConfig. Seconds must be positive finite Doubles, otherwise IllegalArgumentException. commandLifetime stays 5 s; uses the platform cache.

### Timeouts.seconds

```kotlin
public fun seconds(seconds: Double): Duration?
```

- `seconds: Double` (required): Positive finite seconds (Double). Nonpositive, NaN or infinity throws IllegalArgumentException.

```kotlin
val result = Timeouts.seconds(seconds = 10.0)
```

Boxed Duration? for a Swift timeout argument; seconds(10.0) represents 10 seconds.

### Timeouts.millisecondsOf

```kotlin
public fun millisecondsOf(timeout: Duration?): Long
```

- `timeout: Duration?` (required): Boxed Duration? to convert. null returns -1.

```kotlin
val result = Timeouts.millisecondsOf(timeout = 10.seconds)
```

Long milliseconds; null input gives -1. Example: 10 seconds → 10000.

### platformKeyFormFactor.platformKeyFormFactor

```kotlin
public expect fun platformKeyFormFactor(): KeyFormFactor
```


```kotlin
val result = platformKeyFormFactor()
```

KeyFormFactor: Android=ANDROID_DEVICE, iOS=IOS_DEVICE, JVM=CLOUD_KEY.

### VehicleKeyStore.getOrCreate

```kotlin
public suspend fun getOrCreate( alias: String = DEFAULT_ALIAS, policy: KeyPolicy = KeyPolicy.PREFER_HARDWARE): VehicleResult<VehicleKey>
```

- `alias: String` (DEFAULT_ALIAS): [A-Za-z0-9._-]{1,64}; neither . nor ... Default teslable-default.
- `policy: KeyPolicy` (KeyPolicy.PREFER_HARDWARE): PREFER_HARDWARE/REQUIRE_HARDWARE/SOFTWARE_ONLY.

```kotlin
val result = ble.keys.getOrCreate(alias = "teslable-default", policy = KeyPolicy.PREFER_HARDWARE)
```

Success(value: VehicleKey). Key handle with alias, protection, accessibility and publicKey. No private-key bytes are returned. Uncertain(error) / Failure(error).

### VehicleKeyStore.getOrCreate

```kotlin
public suspend fun getOrCreate( alias: String, policy: KeyPolicy, accessibility: KeyAccessibility): VehicleResult<VehicleKey>
```

- `alias: String` (required): [A-Za-z0-9._-]{1,64}; neither . nor ... Default teslable-default.
- `policy: KeyPolicy` (required): PREFER_HARDWARE/REQUIRE_HARDWARE/SOFTWARE_ONLY.
- `accessibility: KeyAccessibility` (required): WHEN_UNLOCKED/AFTER_FIRST_UNLOCK. Existing keys are unchanged. Ignored on Android/JVM.

```kotlin
val result = ble.keys.getOrCreate(alias = "teslable-default", policy = KeyPolicy.PREFER_HARDWARE, accessibility = KeyAccessibility.WHEN_UNLOCKED)
```

Success(value: VehicleKey). Key handle with alias, protection, accessibility and publicKey. No private-key bytes are returned. Uncertain(error) / Failure(error).

### VehicleKeyStore.get

```kotlin
public suspend fun get(alias: String = DEFAULT_ALIAS): VehicleResult<VehicleKey?>
```

- `alias: String` (DEFAULT_ALIAS): [A-Za-z0-9._-]{1,64}; neither . nor ... Default teslable-default.

```kotlin
val result = ble.keys.get(alias = "teslable-default")
```

Success(value: VehicleKey?). Stored key handle; absent means Success(null), distinct from an error. Uncertain(error) / Failure(error).

### VehicleKeyStore.delete

```kotlin
public suspend fun delete(alias: String = DEFAULT_ALIAS): VehicleResult<Unit>
```

- `alias: String` (DEFAULT_ALIAS): [A-Za-z0-9._-]{1,64}; neither . nor ... Default teslable-default.

```kotlin
val result = ble.keys.delete(alias = "teslable-default")
```

Success(Unit): removed from the local key store; an absent key also succeeds. Remove vehicle enrollment separately with removeKey. Failure(error): key-store error.

### VehicleKeyStore.requireValidAlias

```kotlin
public fun requireValidAlias(alias: String): Unit
```

- `alias: String` (required): [A-Za-z0-9._-]{1,64}; neither . nor ... Default teslable-default.

```kotlin
VehicleKeyStore.requireValidAlias(alias = "teslable-default")
```

Unit when valid; an invalid format throws IllegalArgumentException (a programming error).

### Vin.toByteArray

```kotlin
public fun toByteArray(): ByteArray
```


```kotlin
val result = vin.toByteArray()
```

A ByteArray copy: Vin gives 17 unmasked VIN ASCII bytes (never log them); PublicKeyBytes gives 65 public-key bytes.

### Vin.parseOrNull

```kotlin
public fun parseOrNull(value: String): Vin?
```

- `value: String` (required): User-entered VIN string. Checks 17-character format, normalizes uppercase; invalid input returns null.

```kotlin
val result = Vin.parseOrNull(value = vinText)
```

Valid input gives uppercase Vin; malformed input gives null. Does not verify checksum or vehicle existence.

### PublicKeyBytes.toByteArray

```kotlin
public fun toByteArray(): ByteArray
```


```kotlin
val result = key.publicKey.toByteArray()
```

A ByteArray copy: Vin gives 17 unmasked VIN ASCII bytes (never log them); PublicKeyBytes gives 65 public-key bytes.

### VehicleError.VehicleError.shouldRetry

```kotlin
public fun VehicleError.shouldRetry(): Boolean
```


```kotlin
val result = error.shouldRetry()
```

!mayHaveSucceeded && temporary. A true value does not make the library retry key-store calls or a Locked handshake.

### VehicleError.VehicleError.toResult

```kotlin
public fun VehicleError.toResult(): VehicleResult<Nothing>
```


```kotlin
val result = error.toResult()
```

mayHaveSucceeded=true gives Uncertain(this); otherwise Failure(this).

### TeslaLogger.log

```kotlin
public fun log( level: LogLevel, tag: String, message: () -> String): Unit
```

- `level: LogLevel` (required): DEBUG/INFO/WARN/ERROR.
- `tag: String` (required): Log tag.
- `message: () -> String,` (required): Lazily evaluated message. Do not include secrets.

```kotlin
logger.log(level = LogLevel.INFO, tag = "my-app", message = { "Connection changed" })
```

Unit. See the linked instructions and model declarations for semantics and exceptions.

### SessionCache.load

```kotlin
public suspend fun load( vin: Vin, keyId: KeyId): List<CachedSession>
```

- `vin: Vin` (required): VIN object validated using Vin.parseOrNull(user input).
- `keyId: KeyId` (required): Client public-key identifier. Load only the cache for that key.

```kotlin
val result = cache.load(vin = vin, keyId = keyId)
```

List<CachedSession>. See the linked instructions and model declarations for semantics and exceptions.

### SessionCache.store

```kotlin
public suspend fun store( vin: Vin, keyId: KeyId, entries: List<SessionSnapshot>): Unit
```

- `vin: Vin` (required): VIN object validated using Vin.parseOrNull(user input).
- `keyId: KeyId` (required): Client public-key identifier. Load only the cache for that key.
- `entries: List<SessionSnapshot>` (required): Session snapshots to store; an empty list effectively deletes.

```kotlin
cache.store(vin = vin, keyId = keyId, entries = emptyList())
```

Unit. See the linked instructions and model declarations for semantics and exceptions.

### SessionCache.clear

```kotlin
public suspend fun clear(vin: Vin): Unit
```

- `vin: Vin` (required): VIN object validated using Vin.parseOrNull(user input).

```kotlin
cache.clear(vin = vin)
```

Unit. See the linked instructions and model declarations for semantics and exceptions.

### VehicleKey.sharedX

```kotlin
public suspend fun sharedX(peer: PublicKeyBytes): ByteArray
```

- `peer: PublicKeyBytes` (required): Peer ECDH public key. Used by the handshake; apps need not call this.

```kotlin
val result = key.sharedX(peer = peerPublicKey)
```

ECDH shared X bytes for the handshake. Never store or log them. A locked key throws KeyLockedException.



## Classes and returned data structures


Constructor field types, nullability and enum values are extracted from source. Function bodies are omitted: these are reference declarations, not compilable implementations. For inherited fields, semantics, defaults and error handling, also read Reading state, Keys and Errors. VehicleData fields are tabulated in Reading state.



```kotlin
sealed interface TransportState
data object Connected : TransportState
data class Disconnected(val reason: VehicleError.TransportError?) : TransportState
```



### CabinOverheatProtectionTemperature



```kotlin
public enum class CabinOverheatProtectionTemperature
    UNSPECIFIED, LOW, MEDIUM, HIGH
```



### ChargingPolicy



```kotlin
public enum class ChargingPolicy
    OFF, ALL_DAYS, WEEKDAYS
```



### ClimateKeeperMode



```kotlin
public enum class ClimateKeeperMode
    OFF, ON, DOG, CAMP
```



### KeyId



```kotlin
public class KeyId(
    bytes: ByteArray,
    )

// Properties (including derived properties)
SIZE: Int
```



### PairingEvent



```kotlin
public sealed interface PairingEvent
public data object Sent : PairingEvent
public data object WaitingForTap : PairingEvent
public data object Accepted : PairingEvent
public data class Rejected(
    val error: VehicleError,
    ) : PairingEvent
public class Unknown(
    raw: ByteArray,
    ) : PairingEvent
public data class Failed(
    val error: VehicleError,
    ) : PairingEvent
public data object TimedOut : PairingEvent

// Properties (including derived properties)
raw: ByteArray
PairingEvent.isTerminal: Boolean
```



### PublicKeyBytes



```kotlin
public class PublicKeyBytes(
    bytes: ByteArray,
    )

// Properties (including derived properties)
x: ByteArray
y: ByteArray
SIZE: Int
```



### SeatClimateLevel



```kotlin
public enum class SeatClimateLevel
    OFF, LOW, MED, HIGH
```



### SeatPosition



```kotlin
public enum class SeatPosition
    UNKNOWN, FRONT_LEFT, FRONT_RIGHT, SECOND_ROW_LEFT, SECOND_ROW_LEFT_BACK, SECOND_ROW_CENTER, SECOND_ROW_RIGHT, SECOND_ROW_RIGHT_BACK, THIRD_ROW_LEFT, THIRD_ROW_RIGHT
```



### SessionInfoSummary



```kotlin
public data class SessionInfoSummary(
    val counter: UInt,
    val clockTime: UInt,
    val vehiclePublicKey: PublicKeyBytes?,
    )
```



### StateCategory



```kotlin
public enum class StateCategory
    CHARGE, CLIMATE, DRIVE, LOCATION, CLOSURES, CHARGE_SCHEDULE, PRECONDITIONING_SCHEDULE, TIRE_PRESSURE, MEDIA, MEDIA_DETAIL, SOFTWARE_UPDATE, PARENTAL_CONTROLS
```



### VehicleDomain



```kotlin
public enum class VehicleDomain(
    public val wire: Domain,
    )
    VCSEC, INFOTAINMENT

// Properties (including derived properties)
wire: Domain
ALL: Set<VehicleDomain>
```



### VehicleError



```kotlin
public sealed interface VehicleError
public data class ProtocolFault(
    public val fault: MessageFault_E,
    ) : VehicleError
public data object KeyNotPaired : VehicleError
public data object InsufficientPrivileges : VehicleError
public data object Busy : VehicleError
public data class UnknownFault(
    public val rawCode: Int,
    ) : VehicleError
public data class UnknownKeychainCode(
    public val rawCode: Int,
    ) : VehicleError
public data object UnknownResponse : VehicleError
public data object NotConnected : VehicleError
public data object NoSession : VehicleError
public data object RequiresKey : VehicleError
public data class BadResponse(
    public val detail: String,
    override val mayHaveSucceeded: Boolean = false,
    ) : VehicleError
public data class KeychainRejected(
    public val code: WhitelistOperation_information_E,
    ) : VehicleError
public data class VcsecRejected(
    public val error: GenericError_E,
    ) : VehicleError
public data class UnknownVcsecError(
    public val rawCode: Int,
    ) : VehicleError
public data class InfotainmentRejected(
    public val reason: String,
    ) : VehicleError
public sealed interface TransportError : VehicleError
public data object ScanTimeout : TransportError
public data object MaxConnectionsExceeded : TransportError
public data class ConnectFailed(
    public val cause: String,
    ) : TransportError
public data object Disconnected : TransportError
public data object BluetoothOff : TransportError
public data object PermissionDenied : TransportError
public data class WriteFailed(
    public val cause: String,
    ) : TransportError
public sealed interface KeyStoreError : VehicleError
public data object HardwareUnavailable : KeyStoreError
public data object KeyNotFound : KeyStoreError
public data object Locked : KeyStoreError
public data class PlatformFailure(
    public val cause: String,
    ) : KeyStoreError
public data class Timeout(
    public val afterSend: Boolean,
    ) : VehicleError
public data class InvalidArgument(
    public val detail: String,
    ) : VehicleError

// Properties (including derived properties)
message: String
mayHaveSucceeded: Boolean
temporary: Boolean
fault: MessageFault_E
rawCode: Int
detail: String
code: WhitelistOperation_information_E
error: GenericError_E
reason: String
cause: String
afterSend: Boolean
RETRIABLE_FAULTS: Set<MessageFault_E>
```



### VehicleEvent



```kotlin
public sealed interface VehicleEvent
public class Status(
    public val status: VehicleStatus,
    raw: ByteArray,
    unknownFields: List<String>,
    ) : VehicleEvent
public class AuthenticationRequest(
    public val requestedLevel: AuthenticationLevel,
    reasons: List<AuthenticationReason>,
    raw: ByteArray,
    unknownFields: List<String>,
    ) : VehicleEvent
public class Unrecognized(
    raw: ByteArray,
    unknownFields: List<String>,
    ) : VehicleEvent
public enum class AuthenticationLevel
    NONE, UNLOCK, DRIVE, UNKNOWN
public enum class AuthenticationReason
    NOT_DOCUMENTED, IDENTIFICATION, POWER_ON_VEHICLE_REQUEST, GTW_REQUEST, UI_UNLOCK_PASSIVE_AUTH, PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL, PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL, PASSIVE_UNLOCK_AUTOPRESENT_DOOR, ENTERED_HIGHER_AUTH_ZONE, WALK_UP_UNLOCK, IMMOBILIZER, UNKNOWN

// Properties (including derived properties)
raw: ByteArray
unknownFields: List<String>
status: VehicleStatus
requestedLevel: AuthenticationLevel
reasons: List<AuthenticationReason>
```



### VehicleResult



```kotlin
public sealed interface VehicleResult<out T>
public data class Success<T>(
    public val value: T,
    ) : VehicleResult<T>
public data class Uncertain(
    public val error: VehicleError,
    ) : VehicleResult<Nothing>
public data class Failure(
    public val error: VehicleError,
    ) : VehicleResult<Nothing>

// Properties (including derived properties)
value: T
error: VehicleError
```



### VehicleStatus



```kotlin
public enum class LockState
    UNLOCKED, LOCKED, INTERNAL_LOCKED, SELECTIVE_UNLOCKED, UNKNOWN
public enum class ClosureState
    CLOSED, OPEN, AJAR, UNKNOWN, FAILED_UNLATCH, OPENING, CLOSING
public enum class SleepStatus
    UNKNOWN, AWAKE, ASLEEP
public enum class UserPresence
    UNKNOWN, NOT_PRESENT, PRESENT
public enum class Gear
    UNKNOWN, PARK, DRIVE, REVERSE, NEUTRAL
public data class Closures(
    val frontDriverDoor: ClosureState,
    val frontPassengerDoor: ClosureState,
    val rearDriverDoor: ClosureState,
    val rearPassengerDoor: ClosureState,
    val rearTrunk: ClosureState,
    val frontTrunk: ClosureState,
    val chargePort: ClosureState,
    val tonneau: ClosureState,
    )
public data class VehicleStatus(
    val lockState: LockState,
    val closures: Closures,
    val sleepStatus: SleepStatus,
    val userPresence: UserPresence,
    val tonneauPercentOpen: Int?,
    val gear: Gear,
    )
```



### Vin



```kotlin
public class Vin(
    value: String,
    )

// Properties (including derived properties)
value: String
```



### Whitelist



```kotlin
public enum class KeyRole
    NONE, SERVICE, OWNER, DRIVER, FLEET_MANAGER, VEHICLE_MONITOR, CHARGING_MANAGER, GUEST, UNKNOWN
public enum class KeyFormFactor
    UNKNOWN, NFC_CARD, IOS_DEVICE, ANDROID_DEVICE, CLOUD_KEY
public data class WhitelistSummary(
    val numberOfEntries: Int,
    val slotMask: UInt,
    )
public data class WhitelistEntry(
    val slot: Int,
    val publicKey: PublicKeyBytes?,
    val keyIdSha1Hex: String?,
    val role: KeyRole,
    val formFactor: KeyFormFactor,
    )
```



### SessionStatus



```kotlin
public sealed interface SessionStatus
public data object None : SessionStatus
public data object Handshaking : SessionStatus
public data class Ready(
    val fromCache: Boolean,
    ) : SessionStatus
```



### TeslaBleConfig



```kotlin
public data class TeslaBleConfig(
    val logger: TeslaLogger = TeslaLogger.NoOp,
    val sessionCache: SessionCache? = null,
    val commandTimeout: Duration = 5.seconds,
    val commandLifetime: Duration = 5.seconds,
    val handshakeTimeout: Duration = 20.seconds,
    val connectTimeout: Duration = 30.seconds,
    val pairingTimeout: Duration = 60.seconds,
    )
```



### Vehicle



```kotlin
public val vin: Vin
public val hasKey: Boolean
public val scanObservation: ConnectScanObservation? get() 
public val connection: StateFlow<TransportState>
public val sessions: StateFlow<Map<VehicleDomain, SessionStatus>>
public val events: Flow<VehicleEvent>
```



### VehicleKeyStore



```kotlin
public enum class KeyProtection(
    public val isHardware: Boolean,
    )
    SECURE_ENCLAVE, STRONGBOX, TEE, SOFTWARE
public enum class KeyPolicy
    PREFER_HARDWARE, REQUIRE_HARDWARE, SOFTWARE_ONLY
public enum class KeyAccessibility
    WHEN_UNLOCKED, AFTER_FIRST_UNLOCK
public interface VehicleKey : EcdhPrivateKey
public interface VehicleKeyStore

// Properties (including derived properties)
isHardware: Boolean
alias: String
protection: KeyProtection
accessibility: KeyAccessibility
DEFAULT_ALIAS: String
```



### TransportFactory



```kotlin
public data class VehicleAdvertisement(
    public val localName: LocalName,
    public val identifier: String,
    public val rssi: Int,
    public val isConnectable: Boolean?,
    )
public sealed interface ScanEvent
public data class Found(
    public val advertisement: VehicleAdvertisement,
    ) : ScanEvent
public data class Failed(
    public val error: VehicleError.TransportError,
    ) : ScanEvent
public data class ConnectScanObservation(
    public val hitAfterMillis: Long,
    public val rssi: Int?,
    public val openAttempt: Int,
    )
public data class ConnectOptions(
    public val timeout: Duration? = null,
    )
public interface TransportFactory

// Properties (including derived properties)
localName: LocalName
identifier: String
rssi: Int
isConnectable: Boolean?
advertisement: VehicleAdvertisement
error: VehicleError.TransportError
hitAfterMillis: Long
rssi: Int?
openAttempt: Int
timeout: Duration?
```



### TeslaLogger



```kotlin
public enum class LogLevel
    DEBUG, INFO, WARN, ERROR
public fun interface TeslaLogger

// Properties (including derived properties)
NoOp: TeslaLogger
```



### CachedSession



```kotlin
public class SessionSnapshot(
    public val domain: Domain,
    sessionInfo: ByteArray,
    )
public class CachedSession(
    public val domain: Domain,
    sessionInfo: ByteArray,
    public val age: Duration,
    )

// Properties (including derived properties)
domain: Domain
sessionInfo: ByteArray
age: Duration
```



### EcdhPrivateKey



```kotlin
public interface EcdhPrivateKey

// Properties (including derived properties)
publicKey: PublicKeyBytes
```



### ChargeSchedule



```protobuf
message ChargeSchedule {
uint64                      id                      = 1; // datetime in epoch time
    string                      name                    = 2;
    int32                       days_of_week            = 3;
    bool                        start_enabled           = 4;
    int32                       start_time              = 5; // 24h in minutes
    bool                        end_enabled             = 6;
    int32                       end_time                = 7; // 24h in minutes
    bool                        one_time                = 8;
    bool                        enabled                 = 9;
    float                       latitude                = 10;
    float                       longitude               = 11;
}
```



### PreconditionSchedule



```protobuf
message PreconditionSchedule {
uint64                      id                      = 1; // datetime in epoch time
    string                      name                    = 2;
    int32                       days_of_week            = 3;
    int32                       precondition_time       = 4; // 24h in minutes
    bool                        one_time                = 5;
    bool                        enabled                 = 6;
    float                       latitude                = 7;
    float                       longitude               = 8;
}
```





## Full getState field schemas


Complete field declarations for VehicleData and the 12 queried state messages: field names, wire types, numbers and oneof alternatives. Schema presence does not promise a vehicle will send a field. Fields marked TESLEMETRY-EXT are unsupported; do not depend on them. Units, null handling and Kotlin/Swift access examples are in Reading state.



### VehicleData



```protobuf
message VehicleData {
    GuiSettings gui_settings = 2;  // TESLEMETRY-EXT
    ChargeState charge_state = 3;
    ClimateState climate_state = 4;
    DriveState drive_state = 5;
    VehicleState legacy_vehicle_state = 6; // VehicleState carries the legacy vehicle-state surface  // TESLEMETRY-EXT
    VehicleConfig vehicle_config = 7;  // TESLEMETRY-EXT
    LocationState location_state = 8;
    ClosuresState closures_state = 9;
    oneof optional_proto_json_version { int32 proto_json_version = 10; }  // TESLEMETRY-EXT
    // Each entry is an encoded CarServer.EncryptedData (car_server.proto), declared
    // as bytes because vehicle.proto cannot import car_server.proto.
    repeated bytes encrypted_data = 11;  // TESLEMETRY-EXT UNCONFIRMED: not yet confirmed on a live vehicle
    oneof optional_upload_reason { string upload_reason = 12; }  // TESLEMETRY-EXT
    ParkedAccessoryState parked_accessory_state = 14;  // TESLEMETRY-EXT
    ChargeScheduleState charge_schedule_state = 15;
    PreconditioningScheduleState preconditioning_schedule_state = 16;
    SohState soh_state = 17;  // TESLEMETRY-EXT
    CurrentVehicleState vehicle_state = 18; // present on live vehicleDataSubscription pushes  // TESLEMETRY-EXT
    TirePressureState tire_pressure_state = 19;
    MediaState media_state = 20;
    MediaDetailState media_detail_state = 21;
    VehicleDetailState vehicle_detail_state = 22;  // TESLEMETRY-EXT
    SoftwareUpdateState software_update_state = 23;
    ParentalControlsState parental_controls_state = 24;
    AlertState alert_state = 25;  // TESLEMETRY-EXT
    LightShowState light_show_state = 26;  // TESLEMETRY-EXT
    VehicleImageState vehicle_image_state = 27;  // TESLEMETRY-EXT
    SuspensionState suspension_state = 29;  // TESLEMETRY-EXT
    ChildPresenceDetectionState child_presence_detection_state = 30;  // TESLEMETRY-EXT
    DisplayState display_state = 36;  // TESLEMETRY-EXT
    // Each entry is an encoded CarServer.PiiKeyResponse (car_server.proto), declared
    // as bytes because vehicle.proto cannot import car_server.proto.
    repeated bytes pii_key_responses = 900;  // TESLEMETRY-EXT UNCONFIRMED: not yet confirmed on a live vehicle; name not recovered
    // An encrypted envelope whose message type this repo does not publish.
    bytes wrapped_key = 901;  // TESLEMETRY-EXT UNCONFIRMED: not yet confirmed on a live vehicle
    oneof optional_supports_optional_fields { bool supports_optional_fields = 999; }  // TESLEMETRY-EXT
}
```



### ChargeState



```protobuf
message ChargeState {
    message CableType {
        oneof type {
            Void SNA = 1;
            Void IEC = 2;
            Void SAE = 3;
            Void GB_AC = 4;
            Void GB_DC = 5;
        }
    }

    message ChargerType {
        oneof type {
            Void SNA = 1;
            Void Supercharger = 2;
            Void Chademo = 3;
            Void Gb = 4;
            Void ACSingleWireCAN = 5;
            Void Combo = 6;
            Void MCSingleWireCAN = 7;
            Void Other = 8;
            Void Tesla = 9;
        }
    }

    message ChargingState {
        oneof type {
            Void Unknown = 1;
            Void Disconnected = 2;
            Void NoPower = 3;
            Void Starting = 4;
            Void Charging = 5;
            Void Complete = 6;
            Void Stopped = 7;
            Void Calibrating = 8;
        }
    }

    message ChargerBrand {
        oneof type {
            Void Tesla = 1;
            Void SNA = 2;
        }
    }

    enum ScheduledChargingMode {
        ScheduledChargingModeOff = 0;
        ScheduledChargingModeStartAt = 1;
        ScheduledChargingModeDepartBy = 2;
    }

    enum ChargePortColor_E {
        ChargePortColorOff = 0;
        ChargePortColorRed = 1;
        ChargePortColorGreen = 2;
        ChargePortColorBlue = 3;
        ChargePortColorWhite = 4;
        ChargePortColorFlashingGreen = 5;
        ChargePortColorFlashingAmber = 6;
        ChargePortColorAmber = 7;
        ChargePortColorRave = 8;
        ChargePortColorDebug = 9;
        ChargePortColorFlashingBlue = 10;
    }

    enum ChargeLimitReason {
        ChargeLimitReasonUnknown = 0;
        ChargeLimitReasonNone = 1;
        ChargeLimitReasonEvse = 2;
        ChargeLimitReasonBattTempLow = 3;
        ChargeLimitReasonHighSoc = 4;
        ChargeLimitReasonCabin = 5;
        ChargeLimitReasonEvseRelocationRecommended = 6;  // TESLEMETRY-EXT
    }

    enum OutletState {
        OutletStateOff = 0;
        OutletStateCabinAndBed = 1;
        OutletStateCabin = 2;
    }

    enum PowerFeedState {
        PowerFeedStateOff = 0;
        PowerFeedStateCabinAndBed = 1;
        PowerFeedStateCabin = 2;
    }

    enum PowershareStatus {
        PowershareStatusInactive = 0;                // disconnected or connected, but don't know if powershare capable
        PowershareStatusInit    = 1;                 // initializing
        PowershareStatusActive  = 2;                 // actively powersharing
        PowershareStatusStopped = 3;                 // powersharing has stopped - either standby or faulted or blocked; look at stopReason
        PowershareStatusHandshaking = 4;             // powersharing is handshaking
        PowershareStatusActiveReconnectingSoon = 5;  // powersharing is active, but reconnecting soon
    }

    enum PowershareType {
        PowershareTypeNone = 0;
        PowershareTypeLoad = 1;
        PowershareTypeHome = 2;
        PowershareTypeGrid = 3;  // TESLEMETRY-EXT
        PowershareTypePowerwall = 4;  // TESLEMETRY-EXT
    }

    enum PowershareStopReason {
        PowershareStopReasonNone = 0;
        PowershareStopReasonSOCTooLow = 1;
        PowershareStopReasonRetry = 2;
        PowershareStopReasonFault = 3;
        PowershareStopReasonUser = 4;
        PowershareStopReasonReconnecting = 5;
        PowershareStopReasonAuthentication = 6;
        PowershareStopReasonAdapterUpdating = 7;  // TESLEMETRY-EXT
        PowershareStopReasonAdapterInitializationFailed = 8;  // TESLEMETRY-EXT
    }

    ChargingState charging_state = 1;
    ChargerType   fast_charger_type = 2;
    ChargerBrand  fast_charger_brand = 3;
    oneof optional_charge_limit_soc { int32         charge_limit_soc = 104; }
    oneof optional_charge_limit_soc_std { int32         charge_limit_soc_std = 105; }
    oneof optional_charge_limit_soc_min { int32         charge_limit_soc_min = 106; }
    oneof optional_charge_limit_soc_max { int32         charge_limit_soc_max = 107; }
    reserved 8;
    oneof optional_max_range_charge_counter { int32         max_range_charge_counter = 109; }
    oneof optional_fast_charger_present { bool          fast_charger_present = 110; }

    // 2 decimals
    oneof optional_battery_range { float         battery_range = 111; }
    oneof optional_est_battery_range { float         est_battery_range = 112; }
    oneof optional_ideal_battery_range { float         ideal_battery_range = 113; }

    oneof optional_battery_level { int32         battery_level = 114; }
    oneof optional_usable_battery_level { int32         usable_battery_level = 115; }

    // 1 decimal
    oneof optional_charge_energy_added { float         charge_energy_added = 116; }
    oneof optional_charge_miles_added_rated { float         charge_miles_added_rated = 117; }
    oneof optional_charge_miles_added_ideal { float         charge_miles_added_ideal = 118; }

    oneof optional_charger_voltage { int32         charger_voltage = 119; }
    oneof optional_charger_pilot_current { int32         charger_pilot_current = 120; }
    oneof optional_charger_actual_current { int32         charger_actual_current = 121; }
    oneof optional_charger_power { int32         charger_power = 122; }

    oneof optional_minutes_to_full_charge { int32 minutes_to_full_charge = 123; }
    oneof optional_minutes_to_charge_limit { int32 minutes_to_charge_limit = 142; }
    oneof optional_trip_charging { bool          trip_charging = 125; }

    oneof optional_charge_rate_mph { int32         charge_rate_mph = 126; }
    oneof optional_charge_port_door_open { bool          charge_port_door_open = 127; }
    CableType     conn_charge_cable = 28;

    // seconds / datetime
    oneof optional_scheduled_charging_start_time { uint64        scheduled_charging_start_time = 129; }
    oneof optional_scheduled_charging_pending { bool          scheduled_charging_pending = 130; }
    google.protobuf.Timestamp scheduled_departure_time = 31;
    oneof optional_user_charge_enable_request { bool          user_charge_enable_request = 132; }
    oneof optional_charge_enable_request { bool          charge_enable_request = 133; }
    oneof optional_charger_phases { int32         charger_phases = 134; }
    ChargePortLatchState charge_port_latch = 35;
    oneof optional_charge_port_cold_weather_mode { bool          charge_port_cold_weather_mode = 136; }
    oneof optional_charge_current_request { int32         charge_current_request = 137; }
    oneof optional_charge_current_request_max { int32         charge_current_request_max = 138; }
    oneof optional_managed_charging_active { bool          managed_charging_active = 139; }
    oneof optional_managed_charging_user_canceled { bool          managed_charging_user_canceled = 140; }
    oneof optional_managed_charging_start_time { uint64        managed_charging_start_time = 141; }
    reserved 42, 43;

    google.protobuf.Timestamp timestamp = 44;

    PreconditioningTimes        preconditioning_times = 45;
    OffPeakChargingTimes        off_peak_charging_times = 46;

    oneof optional_off_peak_hours_end_time { uint32        off_peak_hours_end_time = 147; }

    oneof optional_scheduled_charging_mode { ScheduledChargingMode       scheduled_charging_mode = 148; }
    oneof optional_charging_amps { int32         charging_amps = 149; }

    oneof optional_scheduled_charging_start_time_minutes { uint32        scheduled_charging_start_time_minutes = 150; }
    oneof optional_scheduled_departure_time_minutes { uint32        scheduled_departure_time_minutes = 151; }
    oneof optional_preconditioning_enabled { bool          preconditioning_enabled = 152; }
    oneof optional_scheduled_charging_start_time_app { sint32        scheduled_charging_start_time_app = 153; }

    oneof optional_supercharger_session_trip_planner { bool          supercharger_session_trip_planner = 154; }

    oneof optional_charge_port_color { ChargePortColor_E           charge_port_color = 155; }

    oneof optional_charge_rate_mph_float { float         charge_rate_mph_float = 156; }

    oneof optional_charge_limit_reason { ChargeLimitReason charge_limit_reason = 157; }

    ManagedChargingState managed_charging_state = 158;

    oneof optional_charge_cable_unlatched { bool charge_cable_unlatched = 159; }

    oneof optional_outlet_state { OutletState outlet_state = 160; }
    oneof optional_power_feed_state { PowerFeedState power_feed_state = 161; }
    oneof option_outlet_soc_limit { int32 outlet_soc_limit = 162; }
    oneof option_power_feed_soc_limit { int32 power_feed_soc_limit = 163; }
    oneof option_outlet_time_remaining { int64 outlet_time_remaining = 164; }
    oneof option_power_feed_time_remaining { int64 power_feed_time_remaining = 165; }
    oneof optional_powershare_feature_allowed { bool powershare_feature_allowed = 166; }
    oneof optional_powershare_feature_enabled { bool powershare_feature_enabled = 167; }
    oneof optional_powershare_request { bool powershare_request = 168; }
    oneof optional_powershare_type { PowershareType powershare_type = 169; }
    oneof optional_powershare_status { PowershareStatus powershare_status = 170; }
    oneof optional_powershare_stop_reason { PowershareStopReason powershare_stop_reason = 171; }
    oneof optional_powershare_instantaneous_load_kw { float powershare_instantaneous_load_kw = 172; }
    oneof optional_powershare_vehicle_energy_left_hr { int32 powershare_vehicle_energy_left_hr = 173; }
    oneof optional_powershare_soc_limit { int32 powershare_soc_limit = 174; }
    oneof optional_one_time_soc_limit { int32 one_time_soc_limit = 175; }
    oneof optional_home_location { LatLong home_location = 176; }
    oneof optional_work_location { LatLong work_location = 177; }
    oneof optional_outlet_max_timer_minutes { int32 outlet_max_timer_minutes = 178; }
    oneof optional_batt_heat_min_to_start_charge { int32 batt_heat_min_to_start_charge = 179; }  // TESLEMETRY-EXT
    oneof optional_batt_heat_min_to_start_charge_calculating { bool batt_heat_min_to_start_charge_calculating = 180; }  // TESLEMETRY-EXT
    oneof optional_has_ac_outlets { bool has_ac_outlets = 181; }  // TESLEMETRY-EXT
    oneof optional_paid_session_fee { float paid_session_fee = 183; }  // TESLEMETRY-EXT
    oneof optional_paid_session_fee_currency { string paid_session_fee_currency = 184; }  // TESLEMETRY-EXT
    oneof optional_paid_session_kwh_rate { float paid_session_kwh_rate = 185; }  // TESLEMETRY-EXT
    oneof optional_discharge_limit_soe { int32 discharge_limit_soe = 186; }  // TESLEMETRY-EXT
    oneof optional_is_roaming { bool is_roaming = 187; }  // TESLEMETRY-EXT
    oneof optional_paid_session_location_guid { string paid_session_location_guid = 188; }  // TESLEMETRY-EXT
    oneof optional_paid_session_pricebook_guid { string paid_session_pricebook_guid = 189; }  // TESLEMETRY-EXT
    google.protobuf.Timestamp paid_session_start_time = 190;  // TESLEMETRY-EXT
    oneof optional_low_power_mode { bool low_power_mode = 191; }  // TESLEMETRY-EXT
    oneof optional_low_power_mode_forced_on { bool low_power_mode_forced_on = 192; }  // TESLEMETRY-EXT
    oneof optional_convenience_features { uint32 convenience_features = 193; }  // TESLEMETRY-EXT
    oneof optional_keep_accessory_power_mode { bool keep_accessory_power_mode = 194; }  // TESLEMETRY-EXT
    oneof optional_should_hide_range_info { bool should_hide_range_info = 198; }  // TESLEMETRY-EXT
}
```



### ClimateState



```protobuf
message ClimateState {
    message ClimateKeeperMode {
        oneof type {
            Void Unknown = 1;
            Void Off = 2;
            Void On = 3;
            Void Dog = 4;
            Void Party = 5;
        }
    }

    message DefrostMode {
        oneof type {
            Void Off = 1;
            Void Normal = 2;
            Void Max = 3;
        }
    }

    enum HvacAutoRequest {
        HvacAutoRequestOn = 0;
        HvacAutoRequestOverride = 1;
    }

    enum CabinOverheatProtection_E {
        CabinOverheatProtectionOff = 0;
        CabinOverheatProtectionOn = 1;
        CabinOverheatProtectionFanOnly = 2;
    }

    enum SeatHeaterLevel_E {
        SeatHeaterLevelOff = 0;
        SeatHeaterLevelLow = 1;
        SeatHeaterLevelMed = 2;
        SeatHeaterLevelHigh = 3;
    }

    enum SeatCoolingLevel_E {
        SeatCoolingLevelOff = 0;
        SeatCoolingLevelLow = 1;
        SeatCoolingLevelMed = 2;
        SeatCoolingLevelHigh = 3;
    }

    enum CopActivationTemp {
        CopActivationTempUnspecified = 0;
        CopActivationTempLow = 1;
        CopActivationTempMedium = 2;
        CopActivationTempHigh = 3;
    }

    enum COPNotRunningReason {
        COPNotRunningReasonNoReason = 0;
        COPNotRunningReasonUserInteraction = 1;
        COPNotRunningReasonEnergyConsumptionReached = 2;
        COPNotRunningReasonTimeout = 3;
        COPNotRunningReasonLowSolarLoad = 4;
        COPNotRunningReasonFault = 5;
        COPNotRunningReasonCabinBelowThreshold = 6;
    }

    enum DogModeState {  // TESLEMETRY-EXT
        DogModeStateUnavailableFault = 0;
        DogModeStateUnavailableTooHot = 1;
        DogModeStateAvailable = 2;
        DogModeStateRunningNominal = 3;
        DogModeStateRunningFault = 4;
        DogModeStateRunningTemperatureMonitorTrip = 5;
    }

    oneof optional_inside_temp_celsius { float inside_temp_celsius = 101; }
    oneof optional_outside_temp_celsius { float outside_temp_celsius = 102; }
    oneof optional_driver_temp_setting { float driver_temp_setting = 103; }
    oneof optional_passenger_temp_setting { float passenger_temp_setting = 104; }
    oneof optional_left_temp_direction { int32 left_temp_direction = 105; }
    oneof optional_right_temp_direction { int32 right_temp_direction = 106; }
    oneof optional_is_front_defroster_on { bool  is_front_defroster_on = 107; }
    oneof optional_is_rear_defroster_on { bool  is_rear_defroster_on = 108; }
    oneof optional_fan_status { int32 fan_status = 109; }
    oneof optional_is_climate_on { bool  is_climate_on = 110; }
    oneof optional_min_avail_temp_celsius { float min_avail_temp_celsius = 111; }
    oneof optional_max_avail_temp_celsius { float max_avail_temp_celsius = 112; }

    oneof optional_seat_heater_left { int32 seat_heater_left = 113; } // not set when no seat heaters installed. Values in 'SeatHeaterLevel_E'.
    oneof optional_seat_heater_right { int32 seat_heater_right = 114; } // not set when no seat heaters installed. Values in 'SeatHeaterLevel_E'.
    oneof optional_seat_heater_rear_left { int32 seat_heater_rear_left = 115; } // not set when no rear seat heaters installed. Values in 'SeatHeaterLevel_E'.
    oneof optional_seat_heater_rear_right { int32 seat_heater_rear_right = 116; } // not set when no rear seat heaters installed. Values in 'SeatHeaterLevel_E'.
    oneof optional_seat_heater_rear_center { int32 seat_heater_rear_center = 117; } // not set when no rear center seat heaters installed. Values in 'SeatHeaterLevel_E'.
    oneof optional_seat_heater_rear_right_back { int32 seat_heater_rear_right_back = 118; } // not set for non-executive rear seat heaters. Values in 'SeatHeaterLevel_E'.
    oneof optional_seat_heater_rear_left_back { int32 seat_heater_rear_left_back = 119; } // not set for non-executive rear seat heaters. Values in 'SeatHeaterLevel_E'.
    oneof optional_seat_heater_third_row_right { int32 seat_heater_third_row_right = 120; } // not set when no third row seat heaters installed. Values in 'SeatHeaterLevel_E'.
    oneof optional_seat_heater_third_row_left { int32 seat_heater_third_row_left = 121; } // not set when no third row seat heaters installed. Values in 'SeatHeaterLevel_E'.

    oneof optional_battery_heater { bool  battery_heater = 122; }
    oneof optional_battery_heater_no_power { bool  battery_heater_no_power = 123; }

    oneof optional_steering_wheel_heater { bool  steering_wheel_heater = 125; } // not set when no steering wheel heater installed.
    oneof optional_wiper_blade_heater { bool  wiper_blade_heater = 126; }
    oneof optional_side_mirror_heaters { bool  side_mirror_heaters = 127; }
    oneof optional_is_preconditioning { bool  is_preconditioning = 128; }

    oneof optional_remote_heater_control_enabled { bool  remote_heater_control_enabled = 129; }
    ClimateKeeperMode climate_keeper_mode = 30;

    google.protobuf.Timestamp timestamp = 33;

    oneof optional_bioweapon_mode_on { bool  bioweapon_mode_on = 134; } // only set in cars with HEPA filter
    DefrostMode defrost_mode = 35;
    oneof optional_is_auto_conditioning_on { bool is_auto_conditioning_on = 136; }
    oneof optional_auto_seat_climate_left { bool auto_seat_climate_left = 137; }
    oneof optional_auto_seat_climate_right { bool auto_seat_climate_right = 138; }
    oneof optional_seat_fan_front_left { int32 seat_fan_front_left = 139; } // Values in 'SeatCoolingLevel_E'.
    oneof optional_seat_fan_front_right { int32 seat_fan_front_right = 140; } // Values in 'SeatCoolingLevel_E'.
    oneof optional_allow_cabin_overheat_protection { bool allow_cabin_overheat_protection = 141; }
    oneof optional_supports_fan_only_cabin_overheat_protection { bool supports_fan_only_cabin_overheat_protection = 142; }
    oneof optional_cabin_overheat_protection { CabinOverheatProtection_E cabin_overheat_protection = 143; }
    oneof optional_cabin_overheat_protection_actively_cooling { bool cabin_overheat_protection_actively_cooling = 144; }
    reserved 145;
    oneof optional_cop_activation_temperature { CopActivationTemp cop_activation_temperature = 146; }

    oneof optional_auto_steering_wheel_heat { bool auto_steering_wheel_heat = 147; }
    oneof optional_steering_wheel_heat_level { StwHeatLevel steering_wheel_heat_level = 148; }

    oneof optional_hvac_auto_request { HvacAutoRequest hvac_auto_request = 150; }
    oneof optional_cop_not_running_reason { COPNotRunningReason cop_not_running_reason = 151; }
    oneof optional_seat_fan_second_row_left { int32 seat_fan_second_row_left = 152; } // Values in 'SeatCoolingLevel_E'.  // TESLEMETRY-EXT
    oneof optional_seat_fan_second_row_right { int32 seat_fan_second_row_right = 153; } // Values in 'SeatCoolingLevel_E'.  // TESLEMETRY-EXT
    oneof optional_dog_mode_state { DogModeState dog_mode_state = 154; }  // TESLEMETRY-EXT
}
```



### DriveState



```protobuf
message DriveState {
    ShiftState        shift_state = 1;
    oneof optional_speed { uint32  speed = 102; }
    oneof optional_power { int32   power = 103; }

    google.protobuf.Timestamp timestamp = 4;

    oneof optional_odometer_in_hundredths_of_a_mile { int32 odometer_in_hundredths_of_a_mile = 105; }

    oneof optional_speed_float { float         speed_float = 106; }

    oneof optional_active_route_destination { string active_route_destination = 7; }
    oneof optional_active_route_minutes_to_arrival { float active_route_minutes_to_arrival = 8; }
    oneof optional_active_route_miles_to_arrival { float active_route_miles_to_arrival = 9; }
    oneof optional_active_route_traffic_minutes_delay { float active_route_traffic_minutes_delay = 10; }
    oneof optional_active_route_energy_at_arrival { float active_route_energy_at_arrival = 11; }
    oneof optional_last_route_update { uint32 last_route_update = 14; }
    google.protobuf.Timestamp last_traffic_update = 15;

    LatLong active_route_coordinates = 12;

    message FsdMonthlyMileage {  // TESLEMETRY-EXT
        string year_month  = 1;
        float  fsd_miles   = 2;
        float  total_miles = 3;
    }

    message FsdLast7DaysUsage {  // TESLEMETRY-EXT
        bool          week_starts_on_sunday = 1;
        repeated bool day_usage             = 2; // one entry per day, most recent first
    }

    oneof optional_fsd_user_total_miles_travelled { float fsd_user_total_miles_travelled = 16; }  // TESLEMETRY-EXT
    oneof optional_fsd_user_total_miles { float fsd_user_total_miles = 17; }  // TESLEMETRY-EXT
    oneof optional_fsd_user_miles_hands_free_current { float fsd_user_miles_hands_free_current = 18; }  // TESLEMETRY-EXT
    oneof optional_fsd_user_miles_hands_free_max { float fsd_user_miles_hands_free_max = 19; }  // TESLEMETRY-EXT
    oneof optional_fsd_total_miles_this_month { float fsd_total_miles_this_month = 20; }  // TESLEMETRY-EXT
    repeated FsdMonthlyMileage fsd_monthly_history = 21;  // TESLEMETRY-EXT
    oneof optional_fsd_streak_days { int32 fsd_streak_days = 22; }  // TESLEMETRY-EXT
    FsdLast7DaysUsage fsd_last_7_days_usage = 23;  // TESLEMETRY-EXT
    oneof optional_rainbow_road_enabled { bool rainbow_road_enabled = 24; }  // TESLEMETRY-EXT
    oneof optional_fsd_active { bool fsd_active = 25; }  // TESLEMETRY-EXT
}
```



### LocationState



```protobuf
message LocationState {
    message GPSCoordinateType {
        oneof type {
            Void GCJ = 1;
            Void WGS = 2;
        }
    }

    reserved 12;

    oneof optional_latitude { float latitude = 101; }
    oneof optional_longitude { float longitude = 102; }
    oneof optional_heading { uint32 heading = 103; }
    oneof optional_gps_as_of { uint64 gps_as_of = 104; }
    oneof optional_native_location_supported { bool native_location_supported = 105; }
    oneof optional_native_latitude { float native_latitude = 106; }
    oneof optional_native_longitude { float native_longitude = 107; }
    GPSCoordinateType native_type = 8;
    oneof optional_corrected_latitude { float corrected_latitude = 109; } // only for China cars
    oneof optional_corrected_longitude { float corrected_longitude = 110; } // only for China cars

    google.protobuf.Timestamp timestamp = 11;

    oneof optional_homelink_nearby { bool homelink_nearby = 112; } // only set when homelink supported

    oneof optional_location_name { string location_name = 113; } // contains a non-precise location name

    oneof optional_geo_latitude  { float geo_latitude = 114; } // raw GPS coordinates
    oneof optional_geo_longitude { float geo_longitude = 115; }
    oneof optional_geo_heading   { float geo_heading = 116; }
    oneof optional_geo_elevation { float geo_elevation = 117; }
    oneof optional_geo_accuracy  { float geo_accuracy = 118; }
    oneof optional_estimated_gps_valid { bool estimated_gps_valid  = 119; }
    oneof optional_estimated_to_raw_distance  { float estimated_to_raw_distance  = 120; } // the distance between the estimated and raw coordinates in meters

    oneof optional_supercharger_trt_id { int32 supercharger_trt_id = 121; }  // TESLEMETRY-EXT

    // double-precision variants of the corresponding float coordinate fields
    oneof optional_native_latitude_d { double native_latitude_d = 122; }  // TESLEMETRY-EXT
    oneof optional_native_longitude_d { double native_longitude_d = 123; }  // TESLEMETRY-EXT
    oneof optional_geo_latitude_d { double geo_latitude_d = 124; }  // TESLEMETRY-EXT
    oneof optional_geo_longitude_d { double geo_longitude_d = 125; }  // TESLEMETRY-EXT
    oneof optional_geo_hw_raw_latitude_d { double geo_hw_raw_latitude_d = 126; }  // TESLEMETRY-EXT
    oneof optional_geo_hw_raw_longitude_d { double geo_hw_raw_longitude_d = 127; }  // TESLEMETRY-EXT
    oneof optional_geo_horizontal_accuracy { float geo_horizontal_accuracy = 128; }  // TESLEMETRY-EXT
}
```



### ClosuresState



```protobuf
message ClosuresState {
    message SunRoofState {
        oneof type {
            Void Unknown = 1;
            Void Calibrating = 2;
            Void Closed = 3;
            Void Open = 4;
            Void Moving = 5;
            Void Vent = 6;
        }
    }

    message DisplayState {
        oneof type {
            Void Off = 1;
            Void Dim = 2;
            Void Accessory = 3;
            Void On = 4;
            Void Driving = 5;
            Void Charging = 6;
            Void Lock = 7;
            Void Sentry = 8;
            Void Dog = 9;
            Void Entertainment = 10;
        }
    }

    message SentryModeState {
        oneof type {
            Void Off = 1;
            Void Idle = 2;
            Void Armed = 3;
            Void Aware = 4;
            Void Panic = 5;
            Void Quiet = 6;
        }
    }

    oneof optional_door_open_driver_front { bool door_open_driver_front = 101; }
    oneof optional_door_open_driver_rear { bool door_open_driver_rear = 102; }
    oneof optional_door_open_passenger_front { bool door_open_passenger_front = 103; }
    oneof optional_door_open_passenger_rear { bool door_open_passenger_rear = 104; }
    oneof optional_door_open_trunk_front { bool door_open_trunk_front = 105; }
    oneof optional_door_open_trunk_rear { bool door_open_trunk_rear = 106; }

    oneof optional_window_open_driver_front { bool window_open_driver_front = 107; }
    oneof optional_window_open_passenger_front { bool window_open_passenger_front = 108; }
    oneof optional_window_open_driver_rear { bool window_open_driver_rear = 109; }
    oneof optional_window_open_passenger_rear { bool window_open_passenger_rear = 110; }
    SunRoofState sun_roof_state = 11; // only set when sunroof installed

    oneof optional_sun_roof_percent_open { int32 sun_roof_percent_open = 112; } // only set when sunroof installed

    oneof optional_locked { bool locked = 113; }
    oneof optional_is_user_present { bool is_user_present = 114; }
    DisplayState center_display_state = 15;

    // `true` when remote start is active.
    oneof optional_remote_start { bool remote_start = 116; }
    oneof optional_valet_mode { bool valet_mode = 117; }
    oneof optional_valet_pin_needed { bool valet_pin_needed = 118; }
    SentryModeState sentry_mode_state = 19; // only set when sentry mode supported

    oneof optional_sentry_mode_available { bool sentry_mode_available = 120; } // only set when sentry mode supported

    SpeedLimitMode speed_limit_mode = 22; // only set when speed limit mode supported

    oneof optional_tonneau_state { VCSEC.ClosureState_E tonneau_state = 23; }
    oneof optional_tonneau_percent_open { uint32 tonneau_percent_open = 24; }
    oneof optional_tonneau_in_motion { bool tonneau_in_motion = 25; }
    oneof optional_has_automatic_tonneau { bool has_automatic_tonneau = 26; }  // TESLEMETRY-EXT
    oneof optional_has_side_storage_doors { bool has_side_storage_doors = 27; }  // TESLEMETRY-EXT
    oneof optional_door_open_side_storage_left { bool door_open_side_storage_left = 28; }  // TESLEMETRY-EXT
    oneof optional_door_open_side_storage_right { bool door_open_side_storage_right = 29; }  // TESLEMETRY-EXT
    double cruise_speed_limit_mph = 30;  // TESLEMETRY-EXT

    google.protobuf.Timestamp timestamp = 2000;
}
```



### ChargeScheduleState



```protobuf
message ChargeScheduleState {
    repeated ChargeSchedule       charge_schedules       = 1;
    oneof optional_charge_schedule_window { ChargeSchedule charge_schedule_window = 2; }
    oneof optional_charge_buffer { int32 charge_buffer = 3; }
    oneof optional_max_num_charge_schedules { uint32 max_num_charge_schedules = 4; }
    oneof optional_next_schedule { bool next_schedule = 5; }
    oneof optional_show_schedule_complete_state { bool show_schedule_complete_state = 6; }
    google.protobuf.Timestamp timestamp = 2000;
}
```



### PreconditioningScheduleState



```protobuf
message PreconditioningScheduleState {
    repeated PreconditionSchedule precondition_schedules = 1;
    oneof optional_preconditioning_schedule_window { PreconditionSchedule preconditioning_schedule_window = 2; }
    oneof optional_max_num_precondition_schedules { uint32 max_num_precondition_schedules = 3; }
    oneof optional_next_schedule { bool next_schedule = 4; }
    google.protobuf.Timestamp timestamp = 2000;
}
```



### TirePressureState



```protobuf
message TirePressureState {
    // tpms pressure values in bar
    google.protobuf.Timestamp timestamp = 1;
    oneof optional_tpms_pressure_fl { float tpms_pressure_fl = 2; }
    oneof optional_tpms_pressure_fr { float tpms_pressure_fr = 3; }
    oneof optional_tpms_pressure_rl { float tpms_pressure_rl = 4; }
    oneof optional_tpms_pressure_rr { float tpms_pressure_rr = 5; }
    google.protobuf.Timestamp tpms_last_seen_pressure_time_fl = 6;
    google.protobuf.Timestamp tpms_last_seen_pressure_time_fr = 7;
    google.protobuf.Timestamp tpms_last_seen_pressure_time_rl = 8;
    google.protobuf.Timestamp tpms_last_seen_pressure_time_rr = 9;
    oneof optional_tpms_hard_warning_fl { bool tpms_hard_warning_fl = 10; }
    oneof optional_tpms_hard_warning_fr { bool tpms_hard_warning_fr = 11; }
    oneof optional_tpms_hard_warning_rl { bool tpms_hard_warning_rl = 12; }
    oneof optional_tpms_hard_warning_rr { bool tpms_hard_warning_rr = 13; }
    oneof optional_tpms_soft_warning_fl { bool tpms_soft_warning_fl = 14; }
    oneof optional_tpms_soft_warning_fr { bool tpms_soft_warning_fr = 15; }
    oneof optional_tpms_soft_warning_rl { bool tpms_soft_warning_rl = 16; }
    oneof optional_tpms_soft_warning_rr { bool tpms_soft_warning_rr = 17; }
    // rcp values in bar
    oneof optional_tpms_rcp_front_value { float tpms_rcp_front_value = 18; }
    oneof optional_tpms_rcp_rear_value { float tpms_rcp_rear_value = 19; }

    // rear-axle (re1/re2) positions, only set on vehicles with dual rear axles
    oneof optional_tpms_pressure_re1_l0 { float tpms_pressure_re1_l0 = 20; }  // TESLEMETRY-EXT
    oneof optional_tpms_pressure_re1_l1 { float tpms_pressure_re1_l1 = 21; }  // TESLEMETRY-EXT
    oneof optional_tpms_pressure_re1_r0 { float tpms_pressure_re1_r0 = 22; }  // TESLEMETRY-EXT
    oneof optional_tpms_pressure_re1_r1 { float tpms_pressure_re1_r1 = 23; }  // TESLEMETRY-EXT
    oneof optional_tpms_pressure_re2_l0 { float tpms_pressure_re2_l0 = 24; }  // TESLEMETRY-EXT
    oneof optional_tpms_pressure_re2_l1 { float tpms_pressure_re2_l1 = 25; }  // TESLEMETRY-EXT
    oneof optional_tpms_pressure_re2_r0 { float tpms_pressure_re2_r0 = 26; }  // TESLEMETRY-EXT
    oneof optional_tpms_pressure_re2_r1 { float tpms_pressure_re2_r1 = 27; }  // TESLEMETRY-EXT
    google.protobuf.Timestamp tpms_last_seen_pressure_time_re1_l0 = 28;  // TESLEMETRY-EXT
    google.protobuf.Timestamp tpms_last_seen_pressure_time_re1_l1 = 29;  // TESLEMETRY-EXT
    google.protobuf.Timestamp tpms_last_seen_pressure_time_re1_r0 = 30;  // TESLEMETRY-EXT
    google.protobuf.Timestamp tpms_last_seen_pressure_time_re1_r1 = 31;  // TESLEMETRY-EXT
    google.protobuf.Timestamp tpms_last_seen_pressure_time_re2_l0 = 32;  // TESLEMETRY-EXT
    google.protobuf.Timestamp tpms_last_seen_pressure_time_re2_l1 = 33;  // TESLEMETRY-EXT
    google.protobuf.Timestamp tpms_last_seen_pressure_time_re2_r0 = 34;  // TESLEMETRY-EXT
    google.protobuf.Timestamp tpms_last_seen_pressure_time_re2_r1 = 35;  // TESLEMETRY-EXT
    oneof optional_tpms_hard_warning_re1_l0 { bool tpms_hard_warning_re1_l0 = 36; }  // TESLEMETRY-EXT
    oneof optional_tpms_hard_warning_re1_l1 { bool tpms_hard_warning_re1_l1 = 37; }  // TESLEMETRY-EXT
    oneof optional_tpms_hard_warning_re1_r0 { bool tpms_hard_warning_re1_r0 = 38; }  // TESLEMETRY-EXT
    oneof optional_tpms_hard_warning_re1_r1 { bool tpms_hard_warning_re1_r1 = 39; }  // TESLEMETRY-EXT
    oneof optional_tpms_hard_warning_re2_l0 { bool tpms_hard_warning_re2_l0 = 40; }  // TESLEMETRY-EXT
    oneof optional_tpms_hard_warning_re2_l1 { bool tpms_hard_warning_re2_l1 = 41; }  // TESLEMETRY-EXT
    oneof optional_tpms_hard_warning_re2_r0 { bool tpms_hard_warning_re2_r0 = 42; }  // TESLEMETRY-EXT
    oneof optional_tpms_hard_warning_re2_r1 { bool tpms_hard_warning_re2_r1 = 43; }  // TESLEMETRY-EXT
    oneof optional_tpms_soft_warning_re1_l0 { bool tpms_soft_warning_re1_l0 = 44; }  // TESLEMETRY-EXT
    oneof optional_tpms_soft_warning_re1_l1 { bool tpms_soft_warning_re1_l1 = 45; }  // TESLEMETRY-EXT
    oneof optional_tpms_soft_warning_re1_r0 { bool tpms_soft_warning_re1_r0 = 46; }  // TESLEMETRY-EXT
    oneof optional_tpms_soft_warning_re1_r1 { bool tpms_soft_warning_re1_r1 = 47; }  // TESLEMETRY-EXT
    oneof optional_tpms_soft_warning_re2_l0 { bool tpms_soft_warning_re2_l0 = 48; }  // TESLEMETRY-EXT
    oneof optional_tpms_soft_warning_re2_l1 { bool tpms_soft_warning_re2_l1 = 49; }  // TESLEMETRY-EXT
    oneof optional_tpms_soft_warning_re2_r0 { bool tpms_soft_warning_re2_r0 = 50; }  // TESLEMETRY-EXT
    oneof optional_tpms_soft_warning_re2_r1 { bool tpms_soft_warning_re2_r1 = 51; }  // TESLEMETRY-EXT
    // tpms temperature values in celsius
    oneof optional_tpms_temperature_fl { float tpms_temperature_fl = 52; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_fr { float tpms_temperature_fr = 53; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_rl { float tpms_temperature_rl = 54; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_rr { float tpms_temperature_rr = 55; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_re1_l0 { float tpms_temperature_re1_l0 = 56; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_re1_l1 { float tpms_temperature_re1_l1 = 57; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_re1_r0 { float tpms_temperature_re1_r0 = 58; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_re1_r1 { float tpms_temperature_re1_r1 = 59; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_re2_l0 { float tpms_temperature_re2_l0 = 60; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_re2_l1 { float tpms_temperature_re2_l1 = 61; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_re2_r0 { float tpms_temperature_re2_r0 = 62; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_re2_r1 { float tpms_temperature_re2_r1 = 63; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_fl { bool tpms_temperature_hard_warning_fl = 64; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_fr { bool tpms_temperature_hard_warning_fr = 65; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_rl { bool tpms_temperature_hard_warning_rl = 66; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_rr { bool tpms_temperature_hard_warning_rr = 67; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_re1_l0 { bool tpms_temperature_hard_warning_re1_l0 = 68; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_re1_l1 { bool tpms_temperature_hard_warning_re1_l1 = 69; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_re1_r0 { bool tpms_temperature_hard_warning_re1_r0 = 70; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_re1_r1 { bool tpms_temperature_hard_warning_re1_r1 = 71; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_re2_l0 { bool tpms_temperature_hard_warning_re2_l0 = 72; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_re2_l1 { bool tpms_temperature_hard_warning_re2_l1 = 73; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_re2_r0 { bool tpms_temperature_hard_warning_re2_r0 = 74; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_hard_warning_re2_r1 { bool tpms_temperature_hard_warning_re2_r1 = 75; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_fl { bool tpms_temperature_soft_warning_fl = 76; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_fr { bool tpms_temperature_soft_warning_fr = 77; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_rl { bool tpms_temperature_soft_warning_rl = 78; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_rr { bool tpms_temperature_soft_warning_rr = 79; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_re1_l0 { bool tpms_temperature_soft_warning_re1_l0 = 80; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_re1_l1 { bool tpms_temperature_soft_warning_re1_l1 = 81; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_re1_r0 { bool tpms_temperature_soft_warning_re1_r0 = 82; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_re1_r1 { bool tpms_temperature_soft_warning_re1_r1 = 83; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_re2_l0 { bool tpms_temperature_soft_warning_re2_l0 = 84; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_re2_l1 { bool tpms_temperature_soft_warning_re2_l1 = 85; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_re2_r0 { bool tpms_temperature_soft_warning_re2_r0 = 86; }  // TESLEMETRY-EXT
    oneof optional_tpms_temperature_soft_warning_re2_r1 { bool tpms_temperature_soft_warning_re2_r1 = 87; }  // TESLEMETRY-EXT
}
```



### MediaState



```protobuf
message MediaState {
    google.protobuf.Timestamp timestamp = 1;
    oneof optional_remote_control_enabled { bool remote_control_enabled = 2; }
    oneof optional_now_playing_artist { string now_playing_artist = 3; }
    oneof optional_now_playing_title { string now_playing_title = 4; }
    oneof optional_audio_volume { float audio_volume = 5; }
    oneof optional_audio_volume_increment { float audio_volume_increment = 6; }
    oneof optional_audio_volume_max { float audio_volume_max = 7; }
    oneof optional_now_playing_source { MediaSourceType now_playing_source = 8; }
    oneof optional_media_playback_status { MediaPlaybackStatus media_playback_status = 9; }
}
```



### MediaDetailState



```protobuf
message MediaDetailState {
    google.protobuf.Timestamp timestamp = 1;
    oneof optional_now_playing_duration { int32 now_playing_duration = 2; }
    oneof optional_now_playing_elapsed { int32 now_playing_elapsed = 3; }
    oneof optional_now_playing_source_string { string now_playing_source_string = 4; }
    oneof optional_now_playing_album { string now_playing_album = 5; }
    oneof optional_now_playing_station { string now_playing_station = 6; }
    oneof optional_a2dp_source_name { string a2dp_source_name = 7; }
    oneof optional_recent_theater_source { TheaterSource recent_theater_source = 8; }  // TESLEMETRY-EXT
    oneof optional_recent_theater_source_string { string recent_theater_source_string = 9; }  // TESLEMETRY-EXT
    oneof optional_theater_source_is_playing { bool theater_source_is_playing = 10; }  // TESLEMETRY-EXT
}
```



### SoftwareUpdateState



```protobuf
message SoftwareUpdateState {
    message SoftwareUpdateStatus {
        oneof type {
            Void Unknown = 1;
            Void Installing = 2;
            Void Scheduled = 3;
            Void Available = 4;
            Void DownloadingWifiWait = 5;
            Void Downloading = 6;
        }
    }

    SoftwareUpdateStatus status = 1;
    oneof optional_scheduled_time_ms { uint64 scheduled_time_ms = 102; }
    oneof optional_warning_time_remaining_ms { uint64 warning_time_remaining_ms = 103; }
    oneof optional_expected_duration_sec { uint32 expected_duration_sec = 104; }
    oneof optional_download_perc { uint32 download_perc = 105; }
    oneof optional_install_perc { uint32 install_perc = 106; }
    oneof optional_version { string version = 107; }

    google.protobuf.Timestamp timestamp = 108;

    oneof optional_auto_scheduled { bool auto_scheduled = 109; }  // TESLEMETRY-EXT
}
```



### ParentalControlsState



```protobuf
message ParentalControlsState {
    google.protobuf.Timestamp timestamp = 1;
    oneof optional_parental_controls_active { bool parental_controls_active = 2; }
    oneof optional_parental_controls_pin_set { bool parental_controls_pin_set = 3; }
    ParentalControlsSettings parental_controls_settings = 4;
}
```



### CarServer common



```protobuf
// UPSTREAM-SOURCE: teslamotors/vehicle-command
// UPSTREAM-PATH:   pkg/protocol/protobuf/common.proto
// UPSTREAM-COMMIT: see upstream.json
// This file = upstream base + Teslemetry extensions marked TESLEMETRY-EXT.
syntax = "proto3";

package CarServer;

option go_package = "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/carserver";
option java_package = "com.tesla.generated.carserver.common";

enum Invalid {
    INVALID = 0;
}

message Void {}

message LatLong {
    float latitude = 1;
    float longitude = 2;
}

message ChargePortLatchState {
    oneof type {
        Void SNA = 1;
        Void Disengaged = 2;
        Void Engaged = 3;
        Void Blocking = 4;
    }
}

message PreconditioningTimes {
    oneof times {
        Void all_week = 1;
        Void weekdays = 2;
    }
}

message OffPeakChargingTimes {
    oneof times {
        Void all_week = 1;
        Void weekdays = 2;
    }
}

enum MediaPlaybackStatus {
    Stopped = 0;
    Playing = 1;
    Paused = 2;
}

enum StwHeatLevel {
    StwHeatLevel_Unknown = 0;
    StwHeatLevel_Off = 1;
    StwHeatLevel_Low = 2;
    StwHeatLevel_High = 3;
}

message ChargeSchedule {
    uint64                      id                      = 1; // datetime in epoch time
    string                      name                    = 2;
    int32                       days_of_week            = 3;
    bool                        start_enabled           = 4;
    int32                       start_time              = 5; // 24h in minutes
    bool                        end_enabled             = 6;
    int32                       end_time                = 7; // 24h in minutes
    bool                        one_time                = 8;
    bool                        enabled                 = 9;
    float                       latitude                = 10;
    float                       longitude               = 11;
}

message PreconditionSchedule {
    uint64                      id                      = 1; // datetime in epoch time
    string                      name                    = 2;
    int32                       days_of_week            = 3;
    int32                       precondition_time       = 4; // 24h in minutes
    bool                        one_time                = 5;
    bool                        enabled                 = 6;
    float                       latitude                = 7;
    float                       longitude               = 8;
}


```



---

# tesla-ble-kmp Library Manual (Entry Point)



Files `11-lib-*.md` to `19-lib-*.md` document **this repository's library**, tesla-ble-kmp: a Kotlin Multiplatform
(Android + iOS) port of the BLE path of `vehicle-command`. Files `00`–`10` document the Go original and are the
reference for porting. The human-readable version of these pages is `../html/<lang>/11-lib-overview.html` to
`19-lib-architecture.html`.

These pages describe the code on `main`. They are maintained by agents: every PR that changes library behaviour,
public API or milestone status updates the affected `1x-lib-*.md` page **and** the matching HTML page in `ko/`, `en/`
and `ja/` in the same PR (AGENTS.md §3 rule 13).

## Status (M6 stage 1)

| Area | State | Page |
|---|---|---|
| Protocol core, dispatcher, sessions, retry, session cache (M0–M2) | Done | `16-lib-errors.md`, `19-lib-architecture.md` |
| BLE scan and connect (Kable), key store, session cache storage, facade `TeslaBle` / `Vehicle` (M3) | Done (real-vehicle check on iOS 2026-10-18; Android real device not checked) | `12-lib-getting-started.md`, `17-lib-platform-notes.md` |
| Read-only queries without a key: `vehicleStatus()`, `keySummary()`, `listKeys()` (M3) | Done | `14-lib-reading-state.md` |
| Key enrollment (`addKeyRequest`, `sessionInfo`, `addKey`, `removeKey`), VCSEC control (lock, unlock, trunk…) (M4) | Done (real-vehicle check pending) | `13-lib-pairing.md`, `15-lib-commands.md` |
| Broadcast events `vehicle.events` (`Flow<VehicleEvent>`) and `VehicleStatus.gear` (FR-035, FR-036; issue #91, ahead of M5) | Done (the broadcasts were observed on a real car; the `events` API itself is checked with FakeVehicle tests, real-vehicle check pending) | `14-lib-reading-state.md` B2, `12-lib-getting-started.md` |
| Key accessibility for a locked iPhone: `KeyAccessibility`, `VehicleKey.accessibility`, `getOrCreate(alias, policy, accessibility)`, `KeyStoreError.Locked` (FR-037, ADR-0014; issue #91). `startSession()` also returns `Locked`, without retrying, when the phone lock blocks a key read earlier, and that cache restore keeps the cache (issue #96) | Done (unit tests with fakes; the Keychain round trip, the Secure Enclave error on a locked iPhone and use on a locked iPhone are checked later on a device) | `13-lib-pairing.md` §Key accessibility, `16-lib-errors.md`, `17-lib-platform-notes.md` §Key storage |
| Infotainment state query `getState(category)`, 12 categories (FR-033, #115, #116; ahead of the rest of M5) | Done (checked with FakeVehicle tests; real-vehicle check pending, #118) | `14-lib-reading-state.md` A8, `16-lib-errors.md` §`getState` results |
| Connect scan observation `Vehicle.scanObservation` (`ConnectScanObservation`: time to the scan hit, RSSI, open attempt) and the `[connect:scan-hit]` observation log line (FR-038, #134, not part of M5) | Done (checked with fake connector tests; real-vehicle check pending) | `12-lib-getting-started.md` §Connect scan observation, `18-lib-troubleshooting.md` §Observation log (development) |
| Session cache counter reservation: the cached anti-replay counter is stored before it is used, so the first request after a process ended without `disconnect()` is no longer refused once (FR-017, ADR-0016, issue H17; no API change) | Done (checked with FakeVehicle tests; real-vehicle check pending, analysis scenario N10) | `17-lib-platform-notes.md` §Session cache, `19-lib-architecture.md` §Session cache, `16-lib-errors.md` §Retries and timeouts |
| Infotainment commands: 51 `Vehicle` methods for climate, charging and power, charge port, body, security, media, software update and `ping()` (FR-034, FR-050–053, FR-060–085, FR-090–092; M5) | Done (checked with FakeVehicle tests and a Swift typecheck; real-vehicle check pending, #118) | `15-lib-commands.md` §Implemented: Infotainment, `16-lib-errors.md` §Invalid arguments, `17-lib-platform-notes.md` §Swift: command arguments |
| Sample apps: climate and charging in the samples (M5, SFR-001, SFR-002, SFR-025) | Done (Android and iOS sample unit tests; real-device check pending, #118) | `samples/documents/md/05-key-control-tab.md` |
| Packaging, iOS: Swift Package Manager, a static `Teslable.xcframework` attached to a GitHub Release (M6 stage 1, ADR-0017) | Done (`1.0.0`, tag `v1.0.0`, released 2026-11-15; checked with an isolated consumer app against the published package: device build and simulator tests) | this page, "Installation"; `12-lib-getting-started.md` §Add the library; `17-lib-platform-notes.md` §iOS |
| Packaging, Android / JVM: Maven Central (M6 stage 2) | **Planned** (development is complete; the Android real-device check is not finished. Build from source until then) | this page, "Installation" |

Do not document or call an API that is listed as not implemented. Planned names come from `docs/prd/PRD.md` and may
change when the milestone lands.

## Platforms and packaging

- Android 12 (API 31) or later, iOS 16 or later. Foreground use only.
- JVM target exists for tests and experiments (in-memory key store and session cache).
- Package root `dev.mirolab.teslable`. Public types live in `dev.mirolab.teslable` (facade),
  `dev.mirolab.teslable.model` (values and results) and `dev.mirolab.teslable.port` (key store, scan, logger). Wire
  generated protocol types are in `com.tesla.generated.*`, for example `com.tesla.generated.carserver.vehicle.VehicleData`.
- iOS framework name `Teslable` (exported by `:sdk`, Swift interop through SKIE). Shipped as a Swift package: a
  static XCFramework with the slices `ios-arm64` (devices) and `ios-arm64-simulator` (Apple Silicon simulator only).
- Android and JVM: not published yet (Maven Central in M6 stage 2); build from source.

## Installation

### iOS: Swift Package Manager

The repository-root `Package.swift` points at a static `Teslable.xcframework` attached to each GitHub Release
(ADR-0017). The package URL is the repository URL.

- Xcode: **File > Add Package Dependencies…**, enter `https://github.com/smallmiro/tesla-ble-kmp`, choose the
  dependency rule **Up to Next Major Version** from `1.0.0`, and add the product `Teslable` to the app target. Then
  `import Teslable`.
- In a `Package.swift`:

```swift
.package(url: "https://github.com/smallmiro/tesla-ble-kmp", from: "1.0.0")
// in the target's dependencies:
.product(name: "Teslable", package: "tesla-ble-kmp")
```

- Requirements: iOS 16 or later; `NSBluetoothAlwaysUsageDescription` in `Info.plist`; the simulator slice is arm64
  only (Apple Silicon Mac), so a simulator build for every architecture needs
  `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64`; the framework is static. Details: `17-lib-platform-notes.md` §iOS.
- Versions follow SemVer: a release that breaks the public API is a new major version, so **Up to Next Major Version**
  takes only compatible releases.
- CocoaPods is not supported (the CocoaPods trunk is read-only from 2026-12-02).
- Step by step, with a complete package manifest: `12-lib-getting-started.md` §Add the library.

### Build from source (Android, JVM, contributors)

Android and JVM are not published yet: development is complete, but the Android real-device check is not finished.
Maven Central comes in M6 stage 2. Until then, build inside this repository's Gradle build
(`12-lib-getting-started.md` §Add the library):

| Target | Command |
|---|---|
| Android / JVM | `./gradlew :sdk:assemble` |
| iOS simulator framework (library work, sample app) | `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64` |
| Sample apps | `samples/android` (Gradle module), `samples/ios` (`xcodegen generate`, see `samples/ios/README.md`) |

### Real-vehicle check pending

Version `1.0.0` includes features that pass the FakeVehicle tests but are not confirmed on a real car yet, notably
the Infotainment commands and `getState` (checklist: issue #118). Android has not been checked on a real device yet.
The status table above gives the state of each area.

## Reference files by task

| Task | Files to read |
|---|---|
| Add the library to an app (iOS Swift package, Android source build) | this page, "Installation"; `12-lib-getting-started.md` §Add the library; `17-lib-platform-notes.md` §iOS |
| Write app code that scans, connects and reads state | `12-lib-getting-started.md`, `14-lib-reading-state.md`, `16-lib-errors.md` |
| Show live state from the vehicle's broadcasts (`vehicle.events`) | `12-lib-getting-started.md` §Broadcast events, `14-lib-reading-state.md` B2, `16-lib-errors.md` |
| Read Infotainment state with `getState` (battery, climate, speed, location…) | `14-lib-reading-state.md` A8, `16-lib-errors.md` §`getState` results, `17-lib-platform-notes.md` §Swift: generated types |
| Handle keys (create, look up, delete, check enrollment) | `13-lib-pairing.md`, `17-lib-platform-notes.md` §Key storage |
| Use the key while the phone is locked (iOS key accessibility, `KeyStoreError.Locked`) | `13-lib-pairing.md` §Key accessibility, `16-lib-errors.md`, `17-lib-platform-notes.md` §Key storage |
| Know which commands exist and which are planned | `15-lib-commands.md` |
| Send Infotainment commands (call order, re-query after `Uncertain`, Kotlin / Swift) | `15-lib-commands.md` §Implemented: Infotainment, `16-lib-errors.md` §Per-command re-query, `17-lib-platform-notes.md` §Swift: command arguments |
| Read a state field that is a wrapper message (`charging_state`, `climate_keeper_mode`, `sentry_mode_state` …) | `14-lib-reading-state.md` A8.6 |
| Map a `VehicleError` to app behaviour, understand retries | `16-lib-errors.md` |
| Android permissions, iOS `Info.plist`, backup rules, session cache location | `17-lib-platform-notes.md` |
| Diagnose scan, connect, pairing or session problems | `18-lib-troubleshooting.md` |
| Find out when `connect` found the car and how strong the signal was | `12-lib-getting-started.md` §Connect scan observation, `18-lib-troubleshooting.md` §Observation log (development) |
| Change library internals (modules, dispatcher, cache) | `19-lib-architecture.md`, then `docs/sdd/SDD.md` |

## File list

| File | Contents |
|---|---|
| `11-lib-overview.md` | This page: status, platforms, installation, reading order |
| `12-lib-getting-started.md` | Adding the library (iOS Swift package, Android source build), end-to-end flow and full Kotlin / Swift examples |
| `13-lib-pairing.md` | `VehicleKeyStore`, `KeyPolicy`, `KeyProtection`, `KeyAccessibility` (locked phone), public key bytes, enrollment check, `addKeyRequest` and `PairingEvent`, `sessionInfo`, `addKey`, `removeKey` |
| `14-lib-reading-state.md` | Part A, data you request: `vehicleStatus()`, `keySummary()`, `listKeys()`, `sessionInfo()` with protocol payloads and enum tables, and `getState()` (A8, wrapper fields in A8.6). Part B, data the vehicle broadcasts (confirmed on a real car) and how `vehicle.events` delivers it, result types, wake behaviour |
| `15-lib-commands.md` | Session API, the VCSEC control commands and the Infotainment commands (method ↔ FR ↔ payload, call order, local checks, re-query, Kotlin / Swift examples), and the items deferred to v1.1 |
| `16-lib-errors.md` | `VehicleResult`, `VehicleError` hierarchy, invalid arguments, transport and key store errors, retry and timeouts, per-command re-query, `getState` results, response size limit, reply authenticity |
| `17-lib-platform-notes.md` | Android / iOS requirements, the iOS Swift package binary (static, Apple Silicon simulator), key storage, session cache, clocks, connection slots, Swift timeouts, generated types and command arguments |
| `18-lib-troubleshooting.md` | Symptom → cause → action table (including the iOS simulator), debug logging |
| `19-lib-architecture.md` | Contributor summary: modules, command path, concurrency, adapters |


---

# Getting Started with tesla-ble-kmp

Scope: M3 (scan, connect, key list, vehicle status), M4 (pairing, VCSEC commands: see "Pair, then lock"), what `connect`
saw when it scanned (`Vehicle.scanObservation`, FR-038: see "Connect scan observation") and broadcast events
(`Vehicle.events`, FR-035: see "Broadcast events"). The main examples below use only the unauthenticated queries.
Adding the library: "Add the library" below (summary in `11-lib-overview.md` §Installation). Permissions:
`17-lib-platform-notes.md`. Errors: `16-lib-errors.md`.

## Add the library

### iOS: Swift Package Manager

1. In Xcode choose **File > Add Package Dependencies…** and enter `https://github.com/smallmiro/tesla-ble-kmp`.
2. Dependency rule **Up to Next Major Version**, from `1.0.0`. Add the library product `Teslable` to the app target.
3. App target: deployment target iOS 16.0 or later, and `NSBluetoothAlwaysUsageDescription` in `Info.plist`
   (`17-lib-platform-notes.md` §iOS).
4. Simulator builds work on an Apple Silicon Mac only. If a simulator build builds every architecture (for example a
   Release build), set `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64` on the app target (`17-lib-platform-notes.md`
   §iOS).
5. `import Teslable`, then follow "Swift (iOS)" below.

A Swift package that depends on the library:

```swift
// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "VehicleFeature",
    platforms: [.iOS(.v16)],
    products: [.library(name: "VehicleFeature", targets: ["VehicleFeature"])],
    dependencies: [
        .package(url: "https://github.com/smallmiro/tesla-ble-kmp", from: "1.0.0"),
    ],
    targets: [
        .target(name: "VehicleFeature", dependencies: [.product(name: "Teslable", package: "tesla-ble-kmp")]),
    ]
)
```

- The package has one product, `Teslable`, backed by a binary target: a static XCFramework with the slices
  `ios-arm64` (devices) and `ios-arm64-simulator` (simulator, arm64 only). `package:` is the package identity from the
  URL, `tesla-ble-kmp`, not the package name.
- **Up to Next Major Version** accepts `1.x` releases only; a release that breaks the public API is a new major version.
- The sample app in this repository does not use the package: it links the framework of a source build
  (`samples/ios/README.md`).
- CocoaPods is not supported.

### Android and JVM: build from source

Not published yet: development is complete, the Android real-device check is not finished, and Maven Central comes in
M6 stage 2. Until then the verified path is an app module inside this repository's Gradle build that depends on `:sdk`.
Copying the `:sdk` folder alone does not build: it needs the other library modules and the build plugins.

```bash
git clone https://github.com/smallmiro/tesla-ble-kmp
cd tesla-ble-kmp
./gradlew :sdk:assemble
./gradlew :samples:android:assembleDebug
```

```kotlin
// build.gradle.kts of an app module included in this repository's settings.gradle.kts
dependencies {
    implementation(project(":sdk"))
}
```

The app's `minSdk` is 31 or higher. Permissions and backup rules: `17-lib-platform-notes.md` §Android.

## Flow

```
TeslaBle.create → keys.getOrCreate() → scan(vin).first() → connect(vin, key, advertisement)
  → vehicleStatus() / keySummary() / listKeys() → vehicle.disconnect() → ble.close()
```

Invariants:

- Every call that talks to the vehicle or the key store returns `VehicleResult<T>` (`Success` / `Uncertain` / `Failure`).
  Failures are values, not exceptions. Only caller cancellation propagates (`CancellationException`).
- Create **one** `TeslaBle` per app process and call `close()` (suspend) when done. `close()` disconnects every vehicle
  still connected.
- One `Vehicle` per connection. Always call `vehicle.disconnect()`, even after the link dropped
  (`connection` is `Disconnected`). It saves the session cache, wipes session keys and frees the coroutine scope.
  It is idempotent. A disconnected `Vehicle` cannot be reused; connect again.
- `key = null` is allowed: the unauthenticated queries work, `startSession()` fails with `RequiresKey`.
- `TeslaBleConfig.default(logger = DebugLogger())` is for development builds only.

## Kotlin (Android)

```kotlin
import dev.mirolab.teslable.*
import dev.mirolab.teslable.model.*
import dev.mirolab.teslable.port.*
import kotlinx.coroutines.flow.first

suspend fun demo(context: android.content.Context, vinText: String) {
    val vin = Vin.parseOrNull(vinText) ?: return              // 17-character format check
    val ble = TeslaBle.create(
        PlatformContext(context),
        TeslaBleConfig.default(logger = DebugLogger()),         // development logger, not for release builds
    )
    try {
        val key = when (val r = ble.keys.getOrCreate()) {       // default alias, hardware first
            is VehicleResult.Success -> r.value
            is VehicleResult.Uncertain -> return
            is VehicleResult.Failure -> return                  // r.error: KeyStoreError
        }

        // Take the first advertisement. The caller sets the time limit (for example withTimeoutOrNull).
        val event = ble.scan(vin).first()
        val target = (event as? ScanEvent.Found)?.advertisement ?: return   // on Failed, see event.error

        val vehicle = when (val r = ble.connect(vin, key, target)) {
            is VehicleResult.Success -> r.value
            is VehicleResult.Uncertain -> return
            is VehicleResult.Failure -> return                  // r.error: TransportError and others
        }
        try {
            println(vehicle.vehicleStatus())                    // show it in the UI instead of println
            println(vehicle.listKeys())
        } finally {
            vehicle.disconnect()                                // frees the slot; call it even after the link dropped
        }
    } finally {
        ble.close()                                             // suspend; disconnects any vehicle still connected
    }
}
```

The app must request `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` at runtime before these calls
(`17-lib-platform-notes.md` §Android).

## Swift (iOS)

SKIE turns `suspend` into `async throws`, `Flow` into `AsyncSequence`, and sealed types into enums reachable with
`onEnum(of:)`. The shape below matches `samples/ios/Sources/SampleModel.swift`. The repository `README.md` quick start
repeats this example; keep the two identical.

```swift
import Teslable

let ble = TeslaBle.companion.create(
    platform: PlatformContext(),
    config: TeslaBleConfig.companion.default(logger: DebugLogger())
)

func run(vinText: String) async {
    guard let vin = Vin.companion.parseOrNull(value: vinText) else { return }   // bad format

    // 1) Key
    var key: VehicleKey?
    do {
        let result = try await ble.keys.getOrCreate(alias: VehicleKeyStoreCompanion.shared.DEFAULT_ALIAS, policy: .preferHardware)
        switch onEnum(of: result) {
        case .success(let s): key = s.value as? VehicleKey
        case .uncertain(let u): print("uncertain: \(u.error.message)")
        case .failure(let f): print("failure: \(f.error.message)")
        }
    } catch { print("error: \(error)") }

    // 2) Scan (stop at the first advertisement; set a time limit by cancelling the Task)
    for await event in ble.scan(vin: vin) {
        switch onEnum(of: event) {
        case .found(let f): print("\(f.advertisement.localName.value) rssi=\(f.advertisement.rssi)")
        case .failed(let e): print("scan failed: \(e.error.message)")
        }
        break
    }

    // 3) Connect and query
    do {
        let connected = try await ble.connect(vin: vin, key: key, target: nil, options: ConnectOptions(timeout: nil))
        switch onEnum(of: connected) {
        case .success(let s):
            guard let vehicle = s.value as? Vehicle else { return }
            let status = try await vehicle.vehicleStatus(timeout: nil)
            switch onEnum(of: status) {
            case .success(let v): print("status: \(String(describing: v.value))")
            case .uncertain(let u): print("uncertain: \(u.error.message)")   // query again, do not resend
            case .failure(let f): print("failure: \(f.error.message)")
            }
            try await vehicle.disconnect()
        case .uncertain(let u): print("uncertain: \(u.error.message)")
        case .failure(let f): print("failure: \(f.error.message)")
        }
    } catch { print("error: \(error)") }
}
```

With `target: nil`, `connect` scans by itself ("Connect scan observation" below tells when it found the car). `Info.plist`
needs `NSBluetoothAlwaysUsageDescription` (`17-lib-platform-notes.md` §iOS).

To pass a time limit from Swift, use the boxed helper and the seconds-based config factory (every argument is required;
Swift gets no default-argument overloads):

```swift
let status = try await vehicle.vehicleStatus(timeout: Timeouts.shared.seconds(seconds: 10))
let config = TeslaBleConfig.companion.withSeconds(logger: DebugLogger(), commandTimeoutSeconds: 10,
    handshakeTimeoutSeconds: 20, connectTimeoutSeconds: 30, pairingTimeoutSeconds: 90)
```

## Connect scan observation

When `connect` scans by itself, `vehicle.scanObservation` (`ConnectScanObservation?`, FR-038) tells what it saw of the
advertisement the link opened through. Use it to read slow connects and reconnects, instead of scanning first in the app,
which would change the connection you are measuring. It is a reading only: it changes nothing in how the link is opened.
The Go original has no such API.

| Field | Kotlin / Swift | Meaning |
|---|---|---|
| `hitAfterMillis` | `Long` / `Int64` | Milliseconds from the start of `connect` until the library received that advertisement. Includes earlier attempts and the waits between them |
| `rssi` | `Int?` / `KotlinInt?` | Signal strength of that advertisement in dBm. `null` when the platform gave no value (iOS reserves 127, the JVM stack gives `Int.MIN_VALUE`). `VehicleAdvertisement.rssi` keeps those raw numbers |
| `openAttempt` | `Int` / `Int32` | Which open attempt made this link, from 1. Above 1 means earlier opens failed, **not** that the car was scanned again |

Rules:

- `connect` with `target = null`: always has a value (the BLE transport; a test fake has none). Each retry scans again, and
  the value describes the last scan, the one the link opened from.
- `connect` with a `target` from `ble.scan(vin)`: no scan runs, so the value is `null`. The app already holds that
  advertisement.
- A `target` the library no longer remembers (it keeps the last 16 it saw, per `TeslaBle`): `connect` scans by itself, so
  there is a value, and retries reuse that hit. So `null` means "this call has no observation of the advertisement it
  used", not "no scan ran".
- It is fixed for the connection and still readable after `disconnect()`.
- It exists only on `Success`: a failed `connect` returns no `Vehicle`. Scan hits of failed attempts are in the observation
  log (`18-lib-troubleshooting.md` §Observation log (development)).
- `hitAfterMillis` is the time the library received the advertisement on the monotonic clock, not a radio timestamp. That
  clock can stop while the device sleeps (`17-lib-platform-notes.md` §Clocks), so the start time plus `hitAfterMillis` is
  not a reliable wall-clock time. For a wall-clock time, use the arrival time of the `[connect:scan-hit]` log line.
- There is no advertised name and no device identifier in it, and `toString()` prints only the three fields, so the value
  can be logged as it is. The advertised name comes from the VIN and a VIN mask does not catch it.

```kotlin
val vehicle = (ble.connect(vin, key) as VehicleResult.Success).value           // target = null: connect scans
vehicle.scanObservation?.let { scan ->
    log("scan hit after ${scan.hitAfterMillis} ms, rssi ${scan.rssi}, open attempt ${scan.openAttempt}")
}
```

```swift
// vehicle from ble.connect(vin: vin, key: key, target: nil, options: ConnectOptions(timeout: nil))
if let scan = vehicle.scanObservation {
    let rssi = scan.rssi.map { String($0.int32Value) } ?? "unavailable"
    print("scan hit after \(scan.hitAfterMillis) ms, rssi \(rssi), open attempt \(scan.openAttempt)")
}
```

`hitAfterMillis` is a plain number because a non-null `Duration` does not reach Swift as a usable value.

## Pair, then lock

First time with a new phone key: enroll it with the key card, wait until the car lists it, then start the VCSEC
session and send a command. Details and event handling: `13-lib-pairing.md`; commands: `15-lib-commands.md`.

```kotlin
// vehicle was connected with `key` (see above)
vehicle.addKeyRequest(key.publicKey).collect { event -> render(event) }      // user taps the key card, confirms on screen
if (waitUntilEnrolled(vehicle, key)) {                                       // polls sessionInfo(…, INFOTAINMENT) (13-lib-pairing.md)
    vehicle.startSession(setOf(VehicleDomain.VCSEC))
    val result = vehicle.lock()                                              // VehicleResult<Unit>; Uncertain: vehicleStatus(), do not resend
}
```

```swift
for await event in vehicle.addKeyRequest(publicKey: key.publicKey, role: .owner, formFactor: .iosDevice, timeout: nil) {
    render(event)                                                            // user taps the key card, confirms on screen
}
// poll vehicle.sessionInfo(publicKey:domain:timeout:) until success (13-lib-pairing.md), then:
_ = try await vehicle.startSession(domains: [.vcsec], timeout: nil)
let result = try await vehicle.lock(timeout: nil)
```

## Broadcast events

The vehicle pushes plaintext state broadcasts (lock, closures, gear, presence, sleep) and authentication requests
without being asked. `vehicle.events` (`Flow<VehicleEvent>`) delivers them; no key and no session are needed. Rules
(gate, event types, `unknownFields`, buffer): `14-lib-reading-state.md` B2.

- Collect in its own coroutine (Swift: its own `Task`). The flow **completes by itself** when the link drops or
  `disconnect()` is called; a collection started after that ends at once. Then look at `connection` and call
  `disconnect()`. A new connection gives a new `Vehicle` with new `events`.
- Only broadcasts that arrive while you collect are delivered (no replay). **A repeat is not a change**: the vehicle also
  resends an unchanged status, 60 s after the previous one while awake with a person present
  (Likely, `14-lib-reading-state.md` B3.1), so compare with the last value.
- The trunk opened or closed by hand from outside is broadcast, but with the interior button it is not; when the trunk
  state matters, read it with `vehicleStatus()`.
- `raw` is the whole received frame, **not VIN-masked**, and every access returns a new copy: read it once and mask the
  VIN before you store or share it.

```kotlin
// vehicle was connected as above (key = null is enough)
scope.launch {
    vehicle.events.collect { event ->
        when (event) {
            is VehicleEvent.Status -> render(event.status)                        // a repeat is not a change
            is VehicleEvent.AuthenticationRequest -> note(event.requestedLevel, event.reasons)
            is VehicleEvent.Unrecognized -> keep(event.raw, event.unknownFields)  // mask the VIN in raw before storing it
        }
    }
    // completed: the link dropped or disconnect() was called. Check vehicle.connection, then call vehicle.disconnect().
}
```

Swift: SKIE flows do not throw, so `for await` is enough. Write `AuthenticationLevel.none` with the type name: where the
value is optional (for example a saved last level), a bare `.none` means `nil`. In Swift the public `VehicleStatus` model
is named `VehicleStatus_` (the generated VCSEC message has the plain name), so `s.status` is a `VehicleStatus_`.

```swift
// in its own Task; the loop ends by itself when the link drops or disconnect() is called
for await event in vehicle.events {
    switch onEnum(of: event) {
    case .status(let s):
        render(s.status)                                         // a repeat is not a change
    case .authenticationRequest(let a):
        if a.requestedLevel != AuthenticationLevel.none {
            print("authentication request: \(a.requestedLevel), reasons \(a.reasons)")
        }
    case .unrecognized(let u):
        let raw = u.raw                                          // every access copies the bytes: read it once
        keep(raw, u.unknownFields)                               // mask the VIN in raw before storing it
    }
}
// the loop ended: check vehicle.connection, then call vehicle.disconnect()
```

## Next steps

- Key enrollment and the pairing events: `13-lib-pairing.md`. `connect(vin, key = null)` is still enough for
  `vehicleStatus()`, `keySummary()` and `listKeys()`.
- Observe the link with `vehicle.connection` (`StateFlow<TransportState>`) and sessions with `vehicle.sessions`
  (`StateFlow<Map<VehicleDomain, SessionStatus>>`). Call `disconnect()` yourself when `Disconnected` arrives.
- When `connect` found the car and how strong the signal was: `vehicle.scanObservation` ("Connect scan observation" above).
- Live state from broadcasts: `vehicle.events` ("Broadcast events" above, `14-lib-reading-state.md` B2).


---

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
    Exception: if another restored session in that connection turned out stale, the cache is rewritten without it
    (the blocked entry is lost too and that domain makes a new handshake next time).
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
`listKeys()`, do not resend. After a failed write that the library repeated, a protocol-layer refusal such as
`InsufficientPrivileges` or `KeyNotPaired` (removing your own key makes the repeated copy's key unknown) is reported as
`Uncertain(Timeout(afterSend = true))`, not `Failure` (`16-lib-errors.md` §Retries and timeouts).

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


---

# Reading Vehicle State

Scope: everything the library can read from the vehicle over BLE, in two parts.

- **Part A, on demand:** data you request. The four VCSEC queries on `Vehicle` (`vehicleStatus()`, `keySummary()`,
  `listKeys()`, `sessionInfo()`), with wire format, field tables and enum tables. None of the four needs a key, a session
  or a wake-up: VCSEC answers over BLE while Infotainment is asleep. A8 adds the Infotainment query `getState(category)`,
  which does need an enrolled key, an Infotainment session and an awake vehicle.
- **Part B, broadcast:** data the vehicle pushes without a request. What each message is, how often it came, and what is
  confirmed on a real car. The library delivers the plain VCSEC broadcasts through `Vehicle.events` (`Flow<VehicleEvent>`,
  FR-035, B2); they still never reach a pending request, and the observation log still shows them.

**`getState()` is available** (A8). It is checked with FakeVehicle tests, not yet on a real vehicle (A8.5, #118). Go
original: VCSEC queries in `05-command-catalog.md` §B and `03-protocol.md` §11–12; `getState` in `05-command-catalog.md`
§I and `03-protocol.md` §10, §13; transport in `02-ble-transport.md`. Control payloads (lock, trunk, wake, climate,
charging, media and the other Infotainment commands) are in `15-lib-commands.md`.

Status labels used in Part B: **Confirmed** = seen on a real vehicle (results note, see Provenance);
**Likely** = consistent and repeated in real-car logs, but not reproduced in a controlled scenario;
**Schema-only** = defined in the proto files, never seen by us; **Unknown** = seen on the wire, meaning not known.

## Calls

| Call | Returns | Go original | FR |
|---|---|---|---|
| `vehicleStatus(timeout = null)` | `VehicleResult<VehicleStatus>` | `BodyControllerState` (`InformationRequest{GET_STATUS}`) | FR-031 |
| `keySummary(timeout = null)` | `VehicleResult<WhitelistSummary>` | `KeySummary` (`GET_WHITELIST_INFO`) | FR-032 |
| `listKeys(timeout = null)` | `VehicleResult<List<WhitelistEntry>>` | `KeySummary` + `KeyInfoBySlot` per occupied slot | FR-025 |
| `sessionInfo(publicKey, domain, timeout = null)` | `VehicleResult<SessionInfoSummary>` | `SessionInfo` (`session_info_request`) | FR-024 |
| `getState(category, timeout = null)` | `VehicleResult<VehicleData>` | `GetState` (`getVehicleData`) | FR-033 |

- `timeout = null` uses `TeslaBleConfig.commandTimeout` (5 s). For `listKeys` the timeout covers all requests together.
- VCSEC requests are serialized per connection: a query waits behind any VCSEC request already in flight, and that
  wait counts against its timeout. `getState` goes to Infotainment and does not wait in that queue.
- These calls are queries. An `Uncertain` result means no data was confirmed, not that a command may have run, so it is
  safe to ask again. Do not do that in a tight loop (`16-lib-errors.md` §`getState` results).
- While another BLE client is active, a query can get no reply at all, `listKeys` most often (`16-lib-errors.md`
  §Queries while another BLE client is active).

# Part A: On-demand data (you request it)

## A1. How a request travels

| Item | Value |
|---|---|
| GATT service | `00000211-b2d1-43f0-9b88-960cebf8b91e` |
| Write characteristic (phone to vehicle) | `00000212-b2d1-43f0-9b88-960cebf8b91e` |
| Notify/indicate characteristic (vehicle to phone) | `00000213-b2d1-43f0-9b88-960cebf8b91e` |
| Framing | 2-byte big-endian length prefix, then the serialized `RoutableMessage`. Maximum message 1024 bytes (`Framer.MAX_MESSAGE_SIZE`); a larger one fails with `InvalidArgument` |
| Block length for writes | `min(ATT MTU, 1024) - 3`; 20 when the MTU exchange fails |

Every frame, both directions, is a `UniversalMessage.RoutableMessage` (`universal_message.proto`):

| Field | Number | Type | Meaning |
|---|---|---|---|
| `to_destination` | 6 | `Destination` | `oneof`: `domain` (1, enum `Domain`) or `routing_address` (2, bytes) |
| `from_destination` | 7 | `Destination` | Same shape. Requests carry our routing address; vehicle replies carry the vehicle domain |
| `protobuf_message_as_bytes` | 10 | bytes | `oneof payload`: serialized domain message (`UnsignedMessage` / `ToVCSECMessage` to the vehicle, `FromVCSECMessage` back) |
| `session_info_request` | 14 | `SessionInfoRequest` | `oneof payload`: `public_key` (1), `challenge` (2) |
| `session_info` | 15 | bytes | `oneof payload`: serialized `Signatures.SessionInfo` |
| `signature_data` | 13 | `Signatures.SignatureData` | Present on authenticated (AES-GCM) commands and replies |
| `signedMessageStatus` | 12 | `MessageStatus` | `operation_status` (1), `signed_message_fault` (2, `MessageFault_E`) |
| `request_uuid` | 50 | bytes | Echo of the request `uuid` (16 B). Used to match Infotainment replies |
| `uuid` | 51 | bytes | Request id, 16 random bytes |
| `flags` | 52 | uint32 | Bit mask of `Flags` (`FLAG_USER_COMMAND` 0, `FLAG_ENCRYPT_RESPONSE` 1; 2 to 4 are Teslemetry-only) |

`Domain` values: `DOMAIN_BROADCAST` 0, `DOMAIN_VEHICLE_SECURITY` 2 (VCSEC), `DOMAIN_INFOTAINMENT` 3. The schema also lists
4, 5, 7, 8 (Teslemetry-only); the library does not use them. Field 53 `message_frame` in the Teslemetry schema is
unconfirmed and unused.

Routing rules for VCSEC requests (`Dispatcher.kt`, `VcsecCommands.kt`):

- `to_destination.domain = DOMAIN_VEHICLE_SECURITY`, `from_destination.routing_address` = **a fresh random 16-byte address per
  request**. The reply comes back with `to_destination.routing_address` = that address, and the dispatcher matches
  it by address (plus the vehicle domain). `request_uuid` is **not** used to match VCSEC replies (the dispatcher uses an
  empty uuid in the match key for `DOMAIN_VEHICLE_SECURITY`). The `uuid` (51) is still filled with 16 random bytes.
- A reply addressed to any other routing address is not ours; it is dropped as `no-handler` (see B3).
- Queries are **unauthenticated**: `AuthMethod.NONE`, no `signature_data`, and `protobuf_message_as_bytes` is the plain
  `UnsignedMessage`. Commands (`15-lib-commands.md`) are authenticated: the same envelope with `signature_data` and an
  AES-GCM protected payload.
- For `sessionInfo` the payload is `session_info_request` (14) with `to_destination.domain` = the requested domain; the
  reply carries `session_info` (15).

## A2. `vehicleStatus()`

```kotlin
suspend fun vehicleStatus(timeout: Duration? = null): VehicleResult<VehicleStatus>
```

| Item | Value |
|---|---|
| Auth | None (works with an unenrolled key, no session) |
| Domain | `DOMAIN_VEHICLE_SECURITY` |
| Request payload | `UnsignedMessage{InformationRequest{GET_STATUS}}` = hex `0a00` (field 1, length 0; `informationRequestType` 0 is the proto3 default and is not encoded) |
| Terminal rule | First message of the reply (`VcsecResponses.FIRST_MESSAGE`) |
| Reply | `FromVCSECMessage.vehicleStatus` (field 1) |
| Missing field | `Failure(BadResponse("missing vehicleStatus"))` |
| Errors | `FromVCSECMessage.nominalError` (46) becomes `Failure(VcsecRejected(...))`; a lost reply is `Uncertain(Timeout(afterSend = true))` |

`VCSEC.VehicleStatus` (`vcsec.proto`):

| # | Field | Type | Meaning | In public `VehicleStatus` |
|---|---|---|---|---|
| 1 | `closureStatuses` | `ClosureStatuses` | Per-opening state; **only sent while something is open** (see below) | `closures`; absent becomes all `CLOSED` |
| 2 | `vehicleLockState` | `VehicleLockState_E` | Lock state | `lockState` |
| 3 | `vehicleSleepStatus` | `VehicleSleepStatus_E` | Infotainment sleep state | `sleepStatus` |
| 4 | `userPresence` | `UserPresence_E` | Person in or at the vehicle (also `PRESENT` while standing outside with a door open, B3.1) | `userPresence` |
| 5 | `detailedClosureStatus` | `DetailedClosureStatus` | `tonneauPercentOpen` (1, uint32), Cybertruck | `tonneauPercentOpen` (`null` if absent) |
| 6 | `uiDesire` | `UIDesire_E` (TESLEMETRY-EXT) | In schema only | **Not surfaced** (planned M5) |
| 7 | `gear` | `Gear_E` (TESLEMETRY-EXT; confirmed in broadcasts; Likely in query replies) | Current gear | `gear`; absent or outside the schema becomes `UNKNOWN` |
| 8 | (unknown, not in schema) | message | Seen on a real car: `{1: 1\|2, 2: 1\|2, 3: 1\|2}`; meaning unknown (B3.1) | Not surfaced |

`ClosureStatuses` has eight `ClosureState_E` fields, numbered 1 to 8: `frontDriverDoor`, `frontPassengerDoor`,
`rearDriverDoor`, `rearPassengerDoor`, `rearTrunk`, `frontTrunk`, `chargePort`, `tonneau`.

Mapping rules (`VcsecInfoMapping`): a value outside the schema (found in `unknownFields`) maps to `UNKNOWN` instead of the
zero value. proto3 cannot tell "missing" from the zero value for enums, so a missing `vehicleLockState` reads as
`UNLOCKED`; the library keeps that Go behaviour for the query. `gear` has no Go counterpart (the official Go `vcsec.proto`
ends at field 5); its zero value is `GEAR_UNKNOWN`, so a missing `gear` reads as `UNKNOWN`. Gear was confirmed in
broadcasts (B3.1). Query replies also fill it (Likely, B5): 144 of 144 carried `gear`; `NEUTRAL` not seen. The real-car
results show that the vehicle omits `closureStatuses` when every field is `CLOSED` (the zero value) and sends it while
any field is not (front and rear driver doors, rear trunk, charge port seen), so "absent = all `CLOSED`" matches what we
saw. The one exception is the trunk operated with the interior button, which sent no broadcast at all; this query
should still report it (not yet checked on a real car).

## A3. `keySummary()`

```kotlin
suspend fun keySummary(timeout: Duration? = null): VehicleResult<WhitelistSummary>
```

| Item | Value |
|---|---|
| Auth / domain | None / `DOMAIN_VEHICLE_SECURITY` |
| Request payload | `UnsignedMessage{InformationRequest{GET_WHITELIST_INFO}}` = hex `0a020805` (`informationRequestType` 5) |
| Terminal rule | First message |
| Reply | `FromVCSECMessage.whitelistInfo` (field 16); missing becomes `BadResponse("missing whitelistInfo")` |

`WhitelistInfo`:

| # | Field | Type | Meaning | Public model |
|---|---|---|---|---|
| 1 | `numberOfEntries` | uint32 | Enrolled keys | `WhitelistSummary.numberOfEntries` |
| 2 | `whitelistEntries` | repeated `KeyIdentifier{publicKeySHA1 = 1}` | Key ids | Not surfaced |
| 3 | `slotMask` | uint32 | Bit *i* set = slot *i* occupied | `slotMask`, `occupiedSlots` |

## A4. `listKeys()`

```kotlin
suspend fun listKeys(timeout: Duration? = null): VehicleResult<List<WhitelistEntry>>
```

| Item | Value |
|---|---|
| Auth / domain | None / `DOMAIN_VEHICLE_SECURITY` |
| Requests | One `keySummary` request, then one `GET_WHITELIST_ENTRY_INFO` per occupied slot, sequentially |
| Slot request payload | `UnsignedMessage{InformationRequest{GET_WHITELIST_ENTRY_INFO, slot = N}}` = hex `0a04080620NN`, for example slot 0 `0a0408062000`, slot 2 `0a0408062002` (`informationRequestType` 6, `slot` field 4). `NN` is a varint; for slots of 128 or more it takes two bytes and the `InformationRequest` length byte becomes 5 (`0a05080620` followed by the two varint bytes) |
| Terminal rule | First message of each reply |
| Reply | `FromVCSECMessage.whitelistEntryInfo` (field 17); missing becomes `BadResponse("missing whitelistEntryInfo")` |
| Budget | One `timeout` for all requests; the first `Failure` or `Uncertain` is returned; no time left gives `Timeout(afterSend = false)` |

`WhitelistEntryInfo`:

| # | Field | Type | Meaning | Public model |
|---|---|---|---|---|
| 1 | `keyId` | `KeyIdentifier{publicKeySHA1 = 1}` | SHA-1 of the public key | `keyIdSha1Hex` (`null` if empty) |
| 2 | `publicKey` | `PublicKey{PublicKeyRaw = 1}` | 65-byte key | `publicKey` (`null` if missing or malformed) |
| 4 | `metadataForKey` | `KeyMetadata{keyFormFactor = 1}` | Form factor | `formFactor` |
| 6 | `slot` | uint32 | Slot | `slot` is the **requested** slot, not this field (same as Go `list-keys`) |
| 7 | `keyRole` | `Keys.Role` | Role | `role` |

`keyInfoBySlot(slot)` exists on the internal query class but is not on `Vehicle`.

## A5. `sessionInfo(publicKey, domain)`

```kotlin
suspend fun sessionInfo(publicKey: PublicKeyBytes, domain: VehicleDomain, timeout: Duration? = null): VehicleResult<SessionInfoSummary>
```

| Item | Value |
|---|---|
| Auth | None. Any 65-byte public key can be asked about, including keys other than the connection's own |
| Domain | `domain` = `VCSEC` (`DOMAIN_VEHICLE_SECURITY`) or `INFOTAINMENT` (`DOMAIN_INFOTAINMENT`) |
| Request | `RoutableMessage{to_destination{domain}, from_destination{routing_address = 16 bytes}, session_info_request{public_key}, uuid}`; the address is fresh per request for `VCSEC` and the connection's own fixed address for `INFOTAINMENT`; the optional `challenge` (2) is not sent |
| Terminal rule | The first reply; sent once, never repeated |
| Reply | `RoutableMessage.session_info` (15) = serialized `Signatures.SessionInfo` |
| Errors | `signedMessageStatus` fault `UNKNOWN_KEY_ID`, or `SessionInfo.status = KEY_NOT_ON_WHITELIST`, becomes `Failure(KeyNotPaired)`; `status = INVALID_HANDLE` or an unknown `status` value becomes `Failure(UnknownResponse)`; missing `session_info` or an undecodable one becomes `Failure(BadResponse)`; no reply in time is `Uncertain(Timeout(afterSend = true))` |
| Side effect | Asking with the connection's own key also builds that domain's session, so `sessions` shows `Ready(fromCache = false)` |

`Signatures.SessionInfo` (`signatures.proto`):

| # | Field | Type | Public `SessionInfoSummary` |
|---|---|---|---|
| 1 | `counter` | uint32 | `counter` |
| 2 | `publicKey` | bytes | `vehiclePublicKey` (`null` if not a valid 65-byte key) |
| 3 | `epoch` | bytes | Not surfaced |
| 4 | `clock_time` | fixed32 | `clockTime` (seconds) |
| 5 | `status` | `Session_Info_Status` | Checked before the payload is used: `OK` 0 passes; `KEY_NOT_ON_WHITELIST` 1 gives `KeyNotPaired`; `INVALID_HANDLE` 2 (Teslemetry-only) or any unknown value gives `UnknownResponse` |
| 6 | `handle` | uint32 | Not surfaced (Teslemetry-only) |

Use it to confirm enrollment after pairing: `sessionInfo(publicKey, INFOTAINMENT)` (see `13-lib-pairing.md`).

## A6. Enum value tables

All values come from `vcsec.proto` and `universal_message.proto`. `Public` is the library model value; a value the
library does not know maps to `UNKNOWN`.

`VehicleLockState_E` (`vehicleLockState`, field 2):

| Value | Name | Public `LockState` |
|---|---|---|
| 0 | `VEHICLELOCKSTATE_UNLOCKED` | `UNLOCKED` |
| 1 | `VEHICLELOCKSTATE_LOCKED` | `LOCKED` |
| 2 | `VEHICLELOCKSTATE_INTERNAL_LOCKED` | `INTERNAL_LOCKED` |
| 3 | `VEHICLELOCKSTATE_SELECTIVE_UNLOCKED` | `SELECTIVE_UNLOCKED` |

`VehicleSleepStatus_E` (field 3): 0 `VEHICLE_SLEEP_STATUS_UNKNOWN` (`UNKNOWN`), 1 `..._AWAKE` (`AWAKE`), 2 `..._ASLEEP` (`ASLEEP`).

`UserPresence_E` (field 4): 0 `VEHICLE_USER_PRESENCE_UNKNOWN` (`UNKNOWN`), 1 `..._NOT_PRESENT` (`NOT_PRESENT`), 2 `..._PRESENT` (`PRESENT`).

`ClosureState_E` (each `ClosureStatuses` field):

| Value | Name | Public `ClosureState` |
|---|---|---|
| 0 | `CLOSURESTATE_CLOSED` | `CLOSED` |
| 1 | `CLOSURESTATE_OPEN` | `OPEN` |
| 2 | `CLOSURESTATE_AJAR` | `AJAR` |
| 3 | `CLOSURESTATE_UNKNOWN` | `UNKNOWN` |
| 4 | `CLOSURESTATE_FAILED_UNLATCH` | `FAILED_UNLATCH` |
| 5 | `CLOSURESTATE_OPENING` | `OPENING` |
| 6 | `CLOSURESTATE_CLOSING` | `CLOSING` |

`Gear_E` (`VehicleStatus.gear`, field 7, TESLEMETRY-EXT, **Confirmed on a real car in broadcasts**: each of P, R, N, D produced a
broadcast with the matching value; `GEAR_UNKNOWN` is the zero value and is never encoded, but frames with no gear field do
occur and read as `UNKNOWN`, B2.4):

| Value | Name | Public `Gear` |
|---|---|---|
| 0 | `GEAR_UNKNOWN` | `UNKNOWN` |
| 1 | `GEAR_PARK` | `PARK` |
| 2 | `GEAR_DRIVE` | `DRIVE` |
| 3 | `GEAR_REVERSE` | `REVERSE` |
| 4 | `GEAR_NEUTRAL` | `NEUTRAL` |

`UIDesire_E` (`VehicleStatus.uiDesire`, field 6, TESLEMETRY-EXT, **Schema-only**): 0 `UI_DESIRE_NONE`, 1 `UI_DESIRE_HAS_DATA`. Meaning on a real car is not established.

`Keys.Role` (`keyRole`) and the public `KeyRole`:

| Value | Wire name | Public `KeyRole` |
|---|---|---|
| 0 | `ROLE_NONE` | `NONE` |
| 1 | `ROLE_SERVICE` | `SERVICE` |
| 2 | `ROLE_OWNER` | `OWNER` |
| 3 | `ROLE_DRIVER` | `DRIVER` |
| 4 | `ROLE_FM` | `FLEET_MANAGER` |
| 5 | `ROLE_VEHICLE_MONITOR` | `VEHICLE_MONITOR` |
| 6 | `ROLE_CHARGING_MANAGER` | `CHARGING_MANAGER` |
| 7 | `ROLE_SERVICE_TECH` (Teslemetry, unconfirmed) | `UNKNOWN` |
| 8 | `ROLE_GUEST` | `GUEST` |
| 9, 10, 11 | `ROLE_RIDER`, `ROLE_PREDELIVERY`, `ROLE_INFOTAINMENT` (Teslemetry, unconfirmed) | `UNKNOWN` |

`KeyFormFactor` (`KeyMetadata.keyFormFactor`): the public model has `UNKNOWN`, `NFC_CARD`, `IOS_DEVICE`, `ANDROID_DEVICE`,
`CLOUD_KEY` only. Wire values 0 `UNKNOWN`, 1 `NFC_CARD`, 6 `IOS_DEVICE`, 7 `ANDROID_DEVICE`, 9 `CLOUD_KEY` map to those.
**The Teslemetry snapshot values map to `UNKNOWN`:** 2 `3_BUTTON_BLE_CAR_KEYFOB`, 3 `BLE_DEVICE`, 4 `NFC_DEVICE`,
5 `BLE_AND_NFC_DEVICE`, 8 `3_BUTTON_BLE_CAR_KEYFOB_P60`, 10 `3_BUTTON_GEN2_CAR_KEYFOB_P60`, 11 `5_BUTTON_GEN2_CAR_KEYFOB_P60`,
12 `..._P60_V2`, 13 `..._P60_V3`, 14 `NFC_CARD_P71`, 15 `NFC_CARD_METAL_CARD`, 16 `NFC_CARD_PLASTIC_CARD`, 17 `APPLE_WATCH`,
18 `NFC_CARD_WD_PLASTIC_CARD`, 19 `HARMONY_OS_NEXT_DEVICE` (all prefixed `KEY_FORM_FACTOR_`).

## A7. Other request types in the schema

`InformationRequestType` also defines `GET_TOKEN` 1, `GET_COUNTER` 2, `GET_EPHEMERAL_PUBLIC_KEY` 3, `GET_SESSION_DATA` 4,
`GET_PERSONALIZATION_INFO` 7, `GET_KEYSTATUS_INFO` 8, `GET_ACTIVE_KEY` 9, `GET_CAPABILITIES` 16 (Teslemetry-only). The
library sends none of them and has no API for them.

`getState(category)` is not an `InformationRequest`: it goes to Infotainment (next section).

## A8. `getState(category)` (Infotainment)

Available (FR-033, #115, #116). It is checked with FakeVehicle tests; **nothing in this section is confirmed on a real
vehicle yet** (A8.5).

```kotlin
suspend fun getState(category: StateCategory, timeout: Duration? = null): VehicleResult<VehicleData>
```

| Item | Value |
|---|---|
| Auth | AES-GCM over the Infotainment session, with `flags` = 2 (`FLAG_ENCRYPT_RESPONSE`). Needs an enrolled key |
| Domain | `DOMAIN_INFOTAINMENT`; the reply is matched by `request_uuid` |
| Request payload | `CarServer.Action{vehicleAction{getVehicleData{selector}}}` with exactly one selector (A8.1). `CHARGE` is hex `12040a021200` |
| Requests | One category per request. There is no combined query |
| Terminal rule | The first reply |
| Reply | `CarServer.Response.vehicleData`, returned unchanged as the Wire generated type `com.tesla.generated.carserver.vehicle.VehicleData` |
| Timeout | `timeout = null` uses `commandTimeout` (5 s) |
| Retries | `16-lib-errors.md` §Retries and timeouts: temporary errors from the vehicle are retried inside `timeout` with a new counter, nonce and `expires_at`, and a temporary write error writes the same bytes again; `Uncertain` is never resent. Unlike a command, a read keeps this retry after a failed write too: running it twice changes nothing |
| Results | `16-lib-errors.md` §`getState` results |

### A8.1 Categories

`StateCategory` (`dev.mirolab.teslable.model`) has the 12 Go constants in the Go order. All 12 reply messages have a
`timestamp` field. Full field lists: the official `pkg/protocol/protobuf/vehicle.proto` of `vehicle-command`.

| `StateCategory` | Swift | Selector (`GetVehicleData` field) | `VehicleData` field (number) | Reply message | Official fields, for example |
|---|---|---|---|---|---|
| `CHARGE` | `.charge` | `getChargeState` (2) | `charge_state` (3) | `ChargeState` | `battery_level`, `charging_state`, `charge_limit_soc`, `charger_power`, `minutes_to_full_charge`, `charge_port_door_open` |
| `CLIMATE` | `.climate` | `getClimateState` (3) | `climate_state` (4) | `ClimateState` | `inside_temp_celsius`, `outside_temp_celsius`, `is_climate_on`, `driver_temp_setting`, `seat_heater_left` |
| `DRIVE` | `.drive` | `getDriveState` (4) | `drive_state` (5) | `DriveState` | `shift_state`, `speed`, `speed_float`, `power`, `odometer_in_hundredths_of_a_mile`, `active_route_destination`, `active_route_minutes_to_arrival` |
| `LOCATION` | `.location` | `getLocationState` (7) | `location_state` (8) | `LocationState` | A8.4 |
| `CLOSURES` | `.closures` | `getClosuresState` (8) | `closures_state` (9) | `ClosuresState` | `door_open_driver_front`, `window_open_driver_front`, `locked`, `sun_roof_state`, `sentry_mode_state`, `is_user_present` |
| `CHARGE_SCHEDULE` | `.chargeSchedule` | `getChargeScheduleState` (10) | `charge_schedule_state` (15) | `ChargeScheduleState` | `charge_schedules`, `charge_schedule_window`, `next_schedule` |
| `PRECONDITIONING_SCHEDULE` | `.preconditioningSchedule` | `getPreconditioningScheduleState` (11) | `preconditioning_schedule_state` (16) | `PreconditioningScheduleState` | `precondition_schedules`, `preconditioning_schedule_window`, `next_schedule` |
| `TIRE_PRESSURE` | `.tirePressure` | `getTirePressureState` (14) | `tire_pressure_state` (19) | `TirePressureState` | `tpms_pressure_fl` (also `fr`, `rl`, `rr`), `tpms_hard_warning_fl`, `tpms_soft_warning_fl`, `tpms_last_seen_pressure_time_fl` |
| `MEDIA` | `.media` | `getMediaState` (15) | `media_state` (20) | `MediaState` | `now_playing_title`, `now_playing_artist`, `audio_volume`, `media_playback_status` |
| `MEDIA_DETAIL` | `.mediaDetail` | `getMediaDetailState` (16) | `media_detail_state` (21) | `MediaDetailState` | `now_playing_duration`, `now_playing_elapsed`, `now_playing_album` |
| `SOFTWARE_UPDATE` | `.softwareUpdate` | `getSoftwareUpdateState` (17) | `software_update_state` (23) | `SoftwareUpdateState` | `status`, `version`, `download_perc`, `install_perc`, `scheduled_time_ms` |
| `PARENTAL_CONTROLS` | `.parentalControls` | `getParentalControlsState` (19) | `parental_controls_state` (24) | `ParentalControlsState` | `parental_controls_active`, `parental_controls_pin_set`, `parental_controls_settings` |

**Only fields of the official proto are supported.** `VehicleData` is generated from the Teslemetry snapshot, so it also
has `TESLEMETRY-EXT` fields, for example `gui_settings` and `vehicle_config`, and `native_latitude_d` on `LocationState`.
The library never requests them, and a value that arrives in one has no guaranteed meaning. Do not build on them. In this
repository's `domain/src/commonMain/proto/vehicle.proto` they carry a `TESLEMETRY-EXT` comment (ADR-0002, amendment
2026-11-01).

### A8.2 Call order

`getState` **never starts a session and never wakes the vehicle**.

| Situation | What `getState` does | What the app does |
|---|---|---|
| No `INFOTAINMENT` session | Sends nothing, returns `Failure(NoSession)` | `startSession(setOf(VehicleDomain.INFOTAINMENT))` first |
| A session restored from the cache that the vehicle no longer accepts | Returns `Failure(NoSession)`; `sessions` then shows `None` for `INFOTAINMENT` | `startSession(setOf(VehicleDomain.INFOTAINMENT))`, then ask again |
| Infotainment asleep | Sends one request, gets no reply, returns `Uncertain(Timeout(afterSend = true))` | Wake it first (below) |

```
Awake:    connect → startSession(setOf(INFOTAINMENT)) → getState(category)
Asleep:   connect → vehicleStatus() shows ASLEEP → startSession(setOf(VCSEC)) → wakeUp()
            → vehicleStatus() shows AWAKE → startSession(setOf(INFOTAINMENT)) → getState(category)
```

- Decide with `vehicleStatus()`: it needs no session and never wakes the vehicle.
- On a sleeping vehicle, handshake with VCSEC alone. Go `tesla-control` does this for a BLE wake because Infotainment is
  presumably asleep (`cmd/tesla-control/commands.go:152-158`), and the Go recipes warn that a handshake with both domains
  can then take a long time (`09-recipes.md`).
- `wakeUp()` returning `Success` means VCSEC accepted the command, not that Infotainment is up. Wait for `AWAKE`, then
  start the Infotainment session (`09-recipes.md`). The vehicle can still answer busy right after waking
  (`08-errors.md` §3.1); the library retries that inside `timeout`, so a first query after a wake may need a longer
  `timeout`.
- **A cached session says nothing about sleep.** `startSession` succeeds without a request and `sessions` shows
  `Ready(fromCache = true)` even while Infotainment sleeps; the first query can then time out (`09-recipes.md`). Read
  `sleepStatus` instead.
- Waking is the app's decision. The library never does it for you.
- `ping()` (`15-lib-commands.md` §Ping) is a command that changes nothing and returns no data. `Success` shows that the
  key and the Infotainment session work. It needs the same session and awake vehicle as `getState`.

```kotlin
import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.*
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

// vehicle was connected with an enrolled key (12-lib-getting-started.md). True when getState can be called.
suspend fun prepareInfotainment(vehicle: Vehicle): Boolean {
    val status = vehicle.vehicleStatus()                                  // no session needed, never wakes the car
    if (status is VehicleResult.Success && status.value.sleepStatus == SleepStatus.ASLEEP) {
        if (vehicle.startSession(setOf(VehicleDomain.VCSEC)) !is VehicleResult.Success) return false
        if (vehicle.wakeUp() is VehicleResult.Failure) return false       // Uncertain: do not resend, the loop reads the state
        for (attempt in 1..30) {                                          // about 30 s; the real wake time is not confirmed (#118)
            delay(1.seconds)
            val now = vehicle.vehicleStatus()
            if (now is VehicleResult.Success && now.value.sleepStatus == SleepStatus.AWAKE) break
        }
    }
    return vehicle.startSession(setOf(VehicleDomain.INFOTAINMENT)) is VehicleResult.Success
}
```

```swift
func prepareInfotainment(vehicle: Vehicle) async throws -> Bool {
    let status = try await vehicle.vehicleStatus(timeout: nil)                 // no session needed, never wakes the car
    if case .success(let s) = onEnum(of: status), (s.value as? VehicleStatus_)?.sleepStatus == .asleep {
        let vcsec = try await vehicle.startSession(domains: [.vcsec], timeout: nil)
        guard case .success = onEnum(of: vcsec) else { return false }
        let woke = try await vehicle.wakeUp(timeout: nil)
        if case .failure = onEnum(of: woke) { return false }                   // uncertain: do not resend, the loop reads the state
        for _ in 1...30 {                                                      // about 30 s; the real wake time is not confirmed (#118)
            try await Task.sleep(nanoseconds: 1_000_000_000)
            let now = try await vehicle.vehicleStatus(timeout: nil)
            if case .success(let n) = onEnum(of: now), (n.value as? VehicleStatus_)?.sleepStatus == .awake { break }
        }
    }
    let session = try await vehicle.startSession(domains: [.infotainment], timeout: nil)
    if case .success = onEnum(of: session) { return true }
    return false
}
```

The 30 s comes from the Go BLE example (`examples/ble/main.go:95`) and is not a measurement. Instead of polling, the app
can watch `sleepStatus` in `vehicle.events` (`VehicleEvent.Status`, B2).

### A8.3 Reading the reply

- **Absent is not 0.** Every scalar and message field directly on the 12 reply messages is nullable: a field the vehicle
  did not send is `null`, and `speed = 0` stays 0. Never replace `null` with 0. Repeated fields (`charge_schedules`,
  `precondition_schedules`) are empty lists when absent. Inside nested messages such as `ChargeSchedule`, fields are
  plain proto3 values: absent reads as 0, empty or false.
- **Check every field you use.** A reply with no `vehicleData` is `Success(VehicleData())`, the same as an empty
  `vehicleData`, and the two cannot be told apart. Go returns `(nil, nil)` here; the library keeps a non-null result
  because the app checks each field anyway. Another kind of reply, such as `Ping`, gives the same empty result.
- **The reply is not checked against `category`** (same as Go). Read the field for the category you asked for; a different
  field that arrives is passed through.
- **`Success` is not proof of a new measurement.** Compare `timestamp` with the previous reply: if it did not move, do not
  present the values as fresh. If it is `null`, you cannot tell. `timestamp` is a Wire `Instant`: `getEpochSecond()`
  works in common Kotlin and in Swift.
- Speed, power and odometer exist only in `DRIVE`, and that reply can exceed the vehicle's size limit
  (`16-lib-errors.md` §Response size limit). Real-time speed is not guaranteed, and there is no other source for it.
- Wire keeps proto names, so fields are `drive_state`, `speed_float`, `charge_state`, unlike the camelCase hand-written
  models. Generated types are in `com.tesla.generated.*`.
- **Some states are wrapper messages, not enums**: `charging_state`, `climate_keeper_mode`, `sentry_mode_state` and
  others carry one empty member that names the value (A8.6).
- **Do not log a result**, `toString()` included. A `VehicleData` can hold the location and the destination. The library
  does not log it, and neither should the app (FR-112).

```kotlin
import com.tesla.generated.universalmessage.MessageFault_E
import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.*
import kotlin.time.Duration.Companion.seconds

suspend fun readSpeed(vehicle: Vehicle): Int? {
    when (val result = vehicle.getState(StateCategory.DRIVE)) {
        is VehicleResult.Success ->
            return result.value.drive_state?.speed               // null = the vehicle did not send it; 0 = it sent 0
        is VehicleResult.Uncertain -> {
            val e = result.error
            if (e is VehicleError.ProtocolFault && e.fault == MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED) {
                return null                                      // reply too large
            }
            if (e is VehicleError.Timeout) return null           // no reply
            return null
        }
        is VehicleResult.Failure -> {
            if (result.error == VehicleError.NoSession) return null
            return null
        }
    }
}

// With an explicit timeout, and reading the reply's timestamp:
suspend fun readBattery(vehicle: Vehicle, lastSeconds: Long?) {
    val result = vehicle.getState(StateCategory.CHARGE, timeout = 8.seconds)
    if (result !is VehicleResult.Success) return
    val charge = result.value.charge_state ?: return             // no charge data in this reply
    val level: Int? = charge.battery_level
    val seconds: Long? = charge.timestamp?.getEpochSecond()
    val fresh = seconds != null && seconds != lastSeconds
    render(level, fresh)
}
```

Swift passes every argument. `s.value` needs a cast and numbers bridge as `KotlinInt?`: `17-lib-platform-notes.md`
§Swift: generated types. This form passes a Swift typecheck against the framework; it has not run in a Swift program.

```swift
func readSpeed(vehicle: Vehicle) async throws -> Int32? {
    let result = try await vehicle.getState(category: .drive, timeout: nil)
    switch onEnum(of: result) {
    case .success(let s):
        // VehicleResult's generic is erased in Swift: s.value is AnyObject?, so cast it.
        guard let data = s.value as? VehicleData else { return nil }
        // Nullable numerics bridge as KotlinInt? / KotlinFloat?, not Int32? / Float?.
        let speed: KotlinInt? = data.drive_state?.speed
        let speedFloat: KotlinFloat? = data.drive_state?.speed_float
        _ = speedFloat?.floatValue
        return speed?.int32Value // nil = the vehicle did not send it; 0 = it sent 0
    case .uncertain(let u):
        switch onEnum(of: u.error) {
        case .protocolFault(let p) where p.fault == .messagefaultErrorResponseMtuExceeded:
            return nil // reply too large
        case .timeout(let t):
            _ = t.afterSend
            return nil // no reply
        default:
            return nil
        }
    case .failure(let f):
        if case .noSession = onEnum(of: f.error) { return nil }
        return nil
    }
}

// With an explicit timeout, and reading the reply's timestamp:
func readBattery(vehicle: Vehicle, lastSeconds: Int64?) async throws {
    let result = try await vehicle.getState(category: .charge, timeout: Timeouts.shared.seconds(seconds: 8))
    guard case .success(let s) = onEnum(of: result), let data = s.value as? VehicleData else { return }
    guard let charge = data.charge_state else { return }   // no charge data in this reply
    let level: KotlinInt? = charge.battery_level
    let seconds: Int64? = charge.timestamp?.getEpochSecond()
    let fresh = seconds != nil && seconds != lastSeconds
    render(level?.int32Value, fresh)
}
```

### A8.4 Location: which coordinates to use

`LOCATION` can return several coordinate pairs. The rule is the `LocationState` comment in the official `vehicle.proto`,
which Go `GetState` points to (`state.go:66-67`):

| Use | Fields | When |
|---|---|---|
| First choice | `native_latitude`, `native_longitude` | Normally. They use WGS (international) or GCJ (China) depending on the vehicle's region; `native_type` says which |
| Fallback, WGS client | `latitude`, `longitude` | The native fields are not provided |
| Fallback, GCJ client | `corrected_latitude`, `corrected_longitude` | The native fields are not provided. The proto marks these "only for China cars" |
| Exception | `geo_latitude`, `geo_longitude` | Raw WGS GPS, usually less accurate. Use them when `estimated_to_raw_distance` (meters between the estimated and raw coordinates) shows a large gap, which mostly happens after the vehicle was transported or towed. The proto gives no threshold |

`native_latitude_d`, `geo_latitude_d` and the other double-precision variants are `TESLEMETRY-EXT`: do not use them.

### A8.5 Not confirmed on a real vehicle (#118)

- The whole of `getState`: so far it has run only against FakeVehicle.
- Which fields each category really carries, and how often they update.
- The unit of `speed` and `speed_float`. Do not assume km/h or mph.
- What `timestamp` marks: the measurement or the reply. Until that is known, the check in A8.3 is the best available.
- How long a wake takes, and what a sleeping Infotainment does with a request. The tests model no reply. If a real
  vehicle answers busy instead, the library retries until `timeout`, and the result can be
  `Failure(Timeout(afterSend = false))` (`16-lib-errors.md` §`getState` results).
- How the size limit behaves per firmware, and how the fault arrives on our own `getState`. In replies to another client it
  came in plaintext `signedMessageStatus` (Likely, `16-lib-errors.md` §Response size limit).

### A8.6 Wrapper fields: state, mode and status

Some fields that look like enums are small Wire messages with a `oneof type` of empty (`Void`) members. Wire makes every
member a nullable property, and the one that is not `null` is the value. If the wrapper is `null`, or none of its members
is set, the vehicle did not send the value (or sent a member this library's schema does not know). Never compare a
wrapper with a string or an enum value.

| Field (category) | Wire type | Members (property names in Kotlin and Swift) | Notes |
|---|---|---|---|
| `charge_state.charging_state` (`CHARGE`) | `ChargeState.ChargingState` | `Unknown`, `Disconnected`, `NoPower`, `Starting`, `Charging`, `Complete`, `Stopped`, `Calibrating` | Read after `chargeStart`, `chargeStop` (`15-lib-commands.md` §Re-query) |
| `charge_state.preconditioning_times`, `charge_state.off_peak_charging_times` (`CHARGE`) | `PreconditioningTimes`, `OffPeakChargingTimes` | `all_week`, `weekdays` | The days of `scheduleDeparture`; how a policy that is off is reported is not confirmed |
| `climate_state.climate_keeper_mode` (`CLIMATE`) | `ClimateState.ClimateKeeperMode` | `Unknown`, `Off`, `On`, `Dog`, `Party` | The command enum says `CAMP`; the state names that member `Party`. That they mean the same mode is not confirmed |
| `climate_state.defrost_mode` (`CLIMATE`) | `ClimateState.DefrostMode` | `Off`, `Normal`, `Max` | |
| `closures_state.sun_roof_state` (`CLOSURES`) | `ClosuresState.SunRoofState` | `Unknown`, `Calibrating`, `Closed`, `Open`, `Moving`, `Vent` | Only set when a sunroof is installed |
| `closures_state.sentry_mode_state` (`CLOSURES`) | `ClosuresState.SentryModeState` | `Off`, `Idle`, `Armed`, `Aware`, `Panic`, `Quiet` | Only set where sentry mode is supported |
| `software_update_state.status` (`SOFTWARE_UPDATE`) | `SoftwareUpdateState.SoftwareUpdateStatus` | `Unknown`, `Installing`, `Scheduled`, `Available`, `DownloadingWifiWait`, `Downloading` | |
| `drive_state.shift_state` (`DRIVE`) | `ShiftState` | `CarServer_Invalid` (proto `Invalid`), `P`, `R`, `N`, `D`, `SNA` | `17-lib-platform-notes.md` §Swift: generated types |

Other value-like fields are plain nullable values read directly: for example `charge_port_door_open` (`Boolean?`),
`scheduled_charging_mode` (enum `ChargeState.ScheduledChargingMode?`), `media_playback_status` (enum
`MediaPlaybackStatus?`), and the `seat_heater_*` and `seat_fan_front_*` integers (`Int?`, 0 off, 1 low, 2 medium,
3 high, as the proto's `SeatHeaterLevel_E` and `SeatCoolingLevel_E` list them).

```kotlin
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleResult

// The member that is not null is the value. null (wrapper or every member) means the vehicle did not send it.
fun chargingStateName(state: ChargeState.ChargingState?): String? =
    when {
        state == null -> null
        state.Charging != null -> "charging"
        state.Starting != null -> "starting"
        state.Complete != null -> "complete"
        state.Stopped != null -> "stopped"
        state.Disconnected != null -> "disconnected"
        state.NoPower != null -> "no power"
        state.Calibrating != null -> "calibrating"
        state.Unknown != null -> "unknown"
        else -> null // no member set, or one this library's schema does not know
    }

suspend fun isCharging(vehicle: Vehicle): Boolean? {
    val result = vehicle.getState(StateCategory.CHARGE)
    if (result !is VehicleResult.Success) return null // keep the last value (16-lib-errors.md §getState results)
    val charging = result.value.charge_state?.charging_state ?: return null // not sent
    return charging.Charging != null
}

fun climateKeeperOn(mode: ClimateState.ClimateKeeperMode?): Boolean? =
    when {
        mode == null -> null
        mode.Off != null -> false
        mode.On != null || mode.Dog != null || mode.Party != null -> true
        else -> null // Unknown, or no member set
    }
```

Swift sees the wrapper as a class with optional members of the same names. This form passes a Swift typecheck against
the framework; it has not run in a Swift program.

```swift
func chargingStateName(_ state: ChargeState.ChargingState?) -> String? {
    guard let state = state else { return nil }
    if state.Charging != nil { return "charging" }
    if state.Starting != nil { return "starting" }
    if state.Complete != nil { return "complete" }
    if state.Stopped != nil { return "stopped" }
    if state.Disconnected != nil { return "disconnected" }
    if state.NoPower != nil { return "no power" }
    if state.Calibrating != nil { return "calibrating" }
    if state.Unknown != nil { return "unknown" }
    return nil // no member set, or one this library's schema does not know
}

func isCharging(vehicle: Vehicle) async throws -> Bool? {
    let result = try await vehicle.getState(category: .charge, timeout: nil)
    guard case .success(let s) = onEnum(of: result), let data = s.value as? VehicleData else { return nil }
    guard let charging = data.charge_state?.charging_state else { return nil } // not sent
    return charging.Charging != nil
}

func climateKeeperOn(_ mode: ClimateState.ClimateKeeperMode?) -> Bool? {
    guard let mode = mode else { return nil }
    if mode.Off != nil { return false }
    if mode.On != nil || mode.Dog != nil || mode.Party != nil { return true }
    return nil // Unknown, or no member set
}
```

## `VehicleStatus` (public model)

| Field | Type | Notes |
|---|---|---|
| `lockState` | `LockState` | `UNLOCKED`, `LOCKED`, `INTERNAL_LOCKED`, `SELECTIVE_UNLOCKED`, `UNKNOWN` |
| `closures` | `Closures` | All eight are `CLOSED` if the vehicle did not send closure statuses (it omits them when everything is closed, like the Go getters) |
| `sleepStatus` | `SleepStatus` | `UNKNOWN`, `AWAKE`, `ASLEEP` (Infotainment) |
| `userPresence` | `UserPresence` | `UNKNOWN`, `NOT_PRESENT`, `PRESENT` |
| `tonneauPercentOpen` | `Int?` | Cybertruck only; `null` if not reported |
| `gear` | `Gear` | `UNKNOWN`, `PARK`, `DRIVE`, `REVERSE`, `NEUTRAL`. `UNKNOWN` if not sent (proto3 cannot tell that from `GEAR_UNKNOWN`) or outside the schema. Last constructor parameter. TESLEMETRY-EXT field 7, no Go counterpart (FR-036). Confirmed in broadcasts; Likely in query replies |

`Closures` has eight `ClosureState` fields: `frontDriverDoor`, `frontPassengerDoor`, `rearDriverDoor`,
`rearPassengerDoor`, `rearTrunk`, `frontTrunk`, `chargePort`, `tonneau`. `ClosureState` values: `CLOSED`, `OPEN`, `AJAR`,
`UNKNOWN`, `FAILED_UNLATCH`, `OPENING`, `CLOSING`. Every enum maps values the library does not know to `UNKNOWN`
(newer firmware) instead of failing. `uiDesire` is not in this model yet (planned M5).

## `WhitelistSummary` and `WhitelistEntry`

| Type | Member | Meaning |
|---|---|---|
| `WhitelistSummary` | `numberOfEntries: Int` | Number of enrolled keys |
| | `slotMask: UInt` | Bit *i* set = slot *i* occupied |
| | `occupiedSlots: List<Int>` | Occupied slot numbers, ascending |
| `WhitelistEntry` | `slot: Int` | Slot number |
| | `publicKey: PublicKeyBytes?` | 65-byte key; `null` if missing or malformed |
| | `keyIdSha1Hex: String?` | SHA-1 of the public key in hex; `null` if not sent |
| | `role: KeyRole` | See `13-lib-pairing.md` |
| | `formFactor: KeyFormFactor` | See `13-lib-pairing.md` |

`SessionInfoSummary`: `counter: UInt`, `clockTime: UInt`, `vehiclePublicKey: PublicKeyBytes?`.

## Connection and session state

These are `StateFlow`s on `Vehicle`, not vehicle queries:

| Member | Type | Values |
|---|---|---|
| `connection` | `StateFlow<TransportState>` | `Connected`, `Disconnected(reason: TransportError?)` |
| `sessions` | `StateFlow<Map<VehicleDomain, SessionStatus>>` | `None`, `Handshaking`, `Ready(fromCache)` per `VCSEC` / `INFOTAINMENT` |
| `hasKey` | `Boolean` | Connected with a private key |
| `vin` | `Vin` | `toString()` is masked for logs; `value` is the raw VIN, never log it |

`events` (`Flow<VehicleEvent>`, not a `StateFlow`) carries the broadcasts of the connection: B2.

## Wake behaviour

- `vehicleStatus()`, `keySummary()` and `listKeys()` never wake the vehicle. Use `sleepStatus` to show whether
  Infotainment is asleep.
- `getState(category)` never wakes the vehicle either, and it needs an authenticated Infotainment session and an awake
  vehicle (A8.2). On a sleeping vehicle it ends with `Uncertain(Timeout(afterSend = true))`.
- `wakeUp()` (FR-042, M4) uses only the VCSEC session. Call order with `getState`: A8.2.
- After an `Uncertain` VCSEC command result (M4), re-read with `vehicleStatus()` instead of resending.

# Part B: Broadcast data (the vehicle pushes it)

## B1. What a broadcast is on the wire

A broadcast arrives on the same `00000213-...` subscription as replies. It is a `RoutableMessage` with:

| Field | Value |
|---|---|
| `from_destination.domain` | `DOMAIN_VEHICLE_SECURITY` (2) |
| `to_destination.domain` | `DOMAIN_BROADCAST` (0), no routing address |
| `request_uuid` | empty |
| `signature_data` | absent (plaintext, unauthenticated) |
| `protobuf_message_as_bytes` | serialized `FromVCSECMessage` |

`FromVCSECMessage` (`vcsec.proto`; fields 6 to 10 reserved):

| # | Field | Type | Notes |
|---|---|---|---|
| 1 | `vehicleStatus` | `VehicleStatus` | Same message as the query reply (A2) |
| 3 | `authenticationRequest` | `AuthenticationRequest` | TESLEMETRY-EXT |
| 4 | `commandStatus` | `CommandStatus` | Replies to commands |
| 16 | `whitelistInfo` | `WhitelistInfo` | Query reply |
| 17 | `whitelistEntryInfo` | `WhitelistEntryInfo` | Query reply |
| 46 | `nominalError` | `Errors.NominalError` | Error reply |
| 44, 48, 53 | unknown | | Seen on a real car, not in any schema (B3) |

## B2. What the library does with broadcasts: `Vehicle.events`

Routing is unchanged and matches the Go original: a broadcast has no routing address, so the dispatcher's `route()`
returns the drop reason `TO_DOMAIN`. Broadcasts never reach a pending request and cannot be mixed up with replies, which
are matched by our routing address. The observation log still writes its `[drop:to-domain]` line (DEBUG, tag `observe`,
`18-lib-troubleshooting.md` §Observation log).

In addition (FR-035; the Go original drops these frames and emits nothing), every frame that passes the gate below is
delivered through `Vehicle.events`. Meaning of each message: B3. Kotlin and Swift examples: `12-lib-getting-started.md`
§Broadcast events.

```kotlin
val events: Flow<VehicleEvent>                    // member of Vehicle

sealed interface VehicleEvent {                   // dev.mirolab.teslable.model
    val raw: ByteArray                            // the whole received frame; a new copy on every access; not VIN-masked
    val unknownFields: List<String>               // paths of fields the schema does not know, for example "53", "1.8"
    class Status(val status: VehicleStatus, …) : VehicleEvent
    class AuthenticationRequest(val requestedLevel: AuthenticationLevel, val reasons: List<AuthenticationReason>, …) : VehicleEvent
    class Unrecognized(…) : VehicleEvent
}
```

### B2.1 Gate

A frame becomes events only if all of these hold. It is the observation log's detailed-decode gate without its last
condition: the payload does not have to decode (an undecodable one gives `Unrecognized`).

| Field | Required value |
|---|---|
| `from_destination` | `domain = DOMAIN_VEHICLE_SECURITY` |
| `to_destination` | `domain = DOMAIN_BROADCAST` (no routing address) |
| `request_uuid` | empty |
| `signature_data` | absent |
| `session_info` | absent |

Everything else is not an event and stays an observation log line only: replies to other clients (`no-handler`),
signed or encrypted frames, frames that do not parse as `RoutableMessage` (`unparseable`), frames without a source
(`missing-source`, which includes unwrapped frames, B3.2). The library never decrypts a broadcast, and nothing it decrypted
ever reaches an event (FR-112).

### B2.2 Event types

| Event | When | Fields |
|---|---|---|
| `VehicleEvent.Status` | The payload has `vehicleStatus` (field 1) | `status: VehicleStatus`, built with the same rules as `vehicleStatus()` (A2): absent `closureStatuses` = all `CLOSED`, values outside the schema = `UNKNOWN`, `gear` included |
| `VehicleEvent.AuthenticationRequest` | The payload has `authenticationRequest` (field 3) | `requestedLevel: AuthenticationLevel`, `reasons: List<AuthenticationReason>` (B3.2). The 20-byte token is not a field; it is in `raw` |
| `VehicleEvent.Unrecognized` | Neither field 1 nor field 3: only unknown fields (44, 48, 53 on the real car), another `FromVCSECMessage` field, no payload, or a payload that does not decode as `FromVCSECMessage` (then `unknownFields` is empty) | none beyond `raw` and `unknownFields` |

- One frame can give two events. With both fields 1 and 3, `Status` comes first, then `AuthenticationRequest`; both have
  the same `raw` and the same `unknownFields`. The schema puts the two fields in one `oneof`, so a plain Wire decode keeps
  only the last one; the library reads them separately and loses neither.
- `raw` is the whole received `RoutableMessage` frame (without the 2-byte BLE length prefix), exactly as the vehicle sent
  it in plaintext. Every access returns a new copy, so read it once. It is **not VIN-masked**: mask it before you store or
  share it. Like the observation log's detailed decode, this is a narrow exception to "no plaintext payloads" that does
  not touch FR-112: only frames the vehicle originally sent as plaintext, never anything the library decrypted.
- `equals` and `hashCode` compare `raw` by content plus the other fields; `toString()` shows `raw` by length only.

### B2.3 `unknownFields` paths

Each entry is a dot path from `FromVCSECMessage` to a field the schema does not know:

| Path | Meaning |
|---|---|
| `"n"` | Top-level field n, for example `"53"`, `"48"`; in arrival order |
| `"1.n"` | Field n inside `vehicleStatus`, for example `"1.8"` |
| `"1.1.n"`, `"1.5.n"` | Inside `closureStatuses`, inside `detailedClosureStatus` |
| `"3.n"` | Inside `authenticationRequest` |
| `"3.2.n"` | Inside its token message |

- An enum value outside the schema also appears under its field number (the model reads it as `UNKNOWN`); a new gear
  value gives `"1.7"`.
- Inside a sub-message its own fields come first, then those of its nested messages. A number that occurs several times
  is listed several times. The inside of other messages (`commandStatus` and so on) is not inspected.
- A non-empty list means part of the frame was not understood; the bytes are in `raw`.

### B2.4 Rules for apps

- **A repeat is not a change.** With nothing changing, the vehicle resends `vehicleStatus` 60.0 s after the previous
  `Status` (Likely; measured only on an awake vehicle with a person present, B3.1), and several frames can come within one
  second around a change, some of them identical. A frame can also differ only in field 8, which the model does not show,
  so it reads as an unchanged `VehicleStatus`. The library does not filter duplicates; compare with the previous value
  yourself.
- **A quiet minute is normal.** `Status` frames can be 60 s apart, so up to 60 s without one says nothing about the link.
  Judge the link from `connection`, not from the time since the last event.
- **Trunk:** opening and closing the trunk by hand from outside produced broadcasts (`rearTrunk = OPEN`, then
  `closureStatuses` absent). Opening or closing it with the interior button produced **no** broadcast, so an app that
  tracks the trunk from `events` keeps seeing it `CLOSED`. When the trunk state matters, confirm it with
  `vehicleStatus()`.
- **Waking vehicle:** the first frames of a sleeping vehicle can carry `UNKNOWN` closures and `gear = UNKNOWN`; they
  become real values within about 0.5 s (B3.1). Do not treat those `UNKNOWN` values as a change. The lock state
  reads `UNLOCKED` in them too, and since `UNLOCKED` is never encoded you cannot tell whether the vehicle was still
  locked; confirm with `vehicleStatus()` if it matters.
- **`gear = UNKNOWN` on an awake vehicle (Likely):** twice a parked, awake vehicle with a person present sent a 24-byte
  frame with no gear field, and a normal `PARK` frame followed within 0.1 s. Do not treat that `UNKNOWN` as a change.
- **Subscription:** each collection subscribes when it starts. There is no replay: a broadcast that arrived earlier is
  not delivered. Several collectors each get every event.
- **Completion:** the flow completes normally (no exception) when `connection` becomes `Disconnected`, whatever the
  reason (`null` or a `TransportError`), or when `disconnect()` is called, whichever comes first. A collection started
  after that completes at once. A frame still being handed over at that moment can be lost. Decide about reconnecting
  from `connection`, not from the end of the flow, and still call `disconnect()` after a link loss. A new connection is a
  new `Vehicle` with its own `events`.
- **Slow collector:** the library holds up to 64 frames for collectors that fall behind. When that buffer is full, new
  frames are dropped for every collector of the connection, counted per connection, and logged at `WARN` on the first
  drop and every 100th: `Dropping plain broadcast because the broadcast buffer is full (dropped N since connect)`. This is
  not an error (`16-lib-errors.md`). Keep the collector light and hand slow work to another coroutine.
- No key and no session are needed: `connect(vin, key = null)` is enough. The real-car observation used an unenrolled key.

## B3. Message catalogue

| Message | Status | In `Vehicle.events` | Where |
|---|---|---|---|
| `vehicleStatus` (field 1) | Confirmed | `VehicleEvent.Status` | B3.1 |
| `authenticationRequest` (field 3) | Confirmed (field names unverified) | `VehicleEvent.AuthenticationRequest` | B3.2 |
| Field 53 | Unknown | `"53"` in `unknownFields`; alone it is a `VehicleEvent.Unrecognized` | B3.3 |
| Field 48 | Unknown | `"48"` in `unknownFields`; alone it is a `VehicleEvent.Unrecognized` | B3.4 |
| Field 44 | Unknown | `"44"` in `unknownFields`; alone it is a `VehicleEvent.Unrecognized` | B3.6 |
| Replies for other clients | Confirmed for the signed VCSEC reply, Likely for the others (not a broadcast) | Not an event (fails the gate) | B3.5 |

### B3.1 `vehicleStatus` broadcast

Same fields as A2. What the real-car observation showed (iPhone, Model Y; 1st round 71 lines, 2nd round per scenario,
3rd round one 40 s scenario of 25 lines starting from a sleeping vehicle):

| Field | Status | Observed |
|---|---|---|
| `vehicleLockState` (2) | Confirmed | `UNLOCKED` right after connect; `INTERNAL_LOCKED` repeated while seated with doors closed; `LOCKED` after getting out; `UNLOCKED` to `LOCKED` when walking away with the connection still up (link lost about 30 s later). Around driving, no scenario given (Likely, B5): `INTERNAL_LOCKED` became `UNLOCKED` in the same frame as `PARK` (3 of 3). It is not simply "in gear": after a shift to `DRIVE` it took 16.9 to 130.4 s to appear (3 cases), it did not appear in one 44 s stretch of `REVERSE` and `DRIVE`, and it ended once in `DRIVE` just as a door opened |
| `vehicleSleepStatus` (3) | Confirmed | Rounds 1 and 2: `AWAKE` in all lines. Round 3: the first two frames were `ASLEEP` when the exterior handle of the sleeping vehicle was pulled, then `AWAKE` about 0.1 s later |
| `userPresence` (4) | Confirmed | `PRESENT` while seated, `NOT_PRESENT` after getting out. Also `PRESENT` while a person stood outside with a door open (about 2.6 s after the rear door opened; about 0.1 s after the driver door opened from outside), `NOT_PRESENT` again about 0.2 s after the rear door closed. So `PRESENT` does not mean "seated" |
| `gear` (7) | **Confirmed** | Each P/R/N/D change produced exactly one broadcast whose `gear` matched the operation order (`GEAR_REVERSE`, `GEAR_NEUTRAL`, `GEAR_DRIVE`, `GEAR_PARK`, twice in a row), about 10 s apart as operated. Works without an Infotainment session and with an unenrolled key |
| `closureStatuses` (1) | Confirmed for front driver door, rear driver door, rear trunk and charge port; Likely for front passenger door, rear passenger door and front trunk | **Present only while some field is not `CLOSED`**, absent when all are `CLOSED` (frame sizes match proto3, which never encodes a zero value). Driver door opened with the interior handle (round 2) or the exterior handle (round 3): `frontDriverDoor = OPEN`; absent again after closing. Rear driver door opened from outside: `rearDriverDoor = OPEN`. Rear trunk opened and closed by hand from outside: `rearTrunk = OPEN`, then absent. Charge port: `chargePort = OPEN` when opened (round 2) and when the latch was released by holding a door handle with a charger plugged in (round 3); absent again after unplugging and the port door closing. With the charger plugged in and latched, `chargePort` read `CLOSED`, so `CLOSED` does not mean "no cable". Trunk operated by the interior button: **no broadcast at all**. Waking vehicle: the first frames had `UNKNOWN` (value 3) for the four doors and both trunks; `frontTrunk` stayed `UNKNOWN` for one more frame. With no scenario given (Likely, B5): `frontPassengerDoor`, `rearPassengerDoor` and `frontTrunk` each read `OPEN` and were absent again later; how they were opened was not recorded. Not tested: trunk by key card, phone key or app |
| `uiDesire` (6) | Schema-only | Meaning not established |
| `detailedClosureStatus` (5) | Schema-only | Cybertruck tonneau; no data (Model Y) |
| field 8 (unknown) | Unknown | `{1: 1\|2, 2: 1\|2, 3: 1\|2}`, in all 176 `Status` frames of the later recordings (B5). Meaning unknown. **It is not a charging state:** sub-field 2 was 1 in all 75 frames in `DRIVE`, `REVERSE` or `NEUTRAL`, where no cable can be in use, so the charge port changes of rounds 2 and 3 (B4) do not define it. Sub-field 1 changes at irregular gaps, once 7 times in about 8 s. Of the 111 frames with sub-field 1 = 2, 108 were `PRESENT`, yet 17 frames with sub-field 1 = 1 were `PRESENT` too, so presence does not define it either. Sub-field 3 is not always 1: it was 2 in 7 frames, parked with nobody present, flipping every 8 to 16 s. A change of field 8 alone produces a broadcast (29 of 32 changes). Not related to lights or turn signals |

Field 8 values in the later recordings (176 frames, 32 value changes; the 2 frames with no gear field are in no column):

| `{1, 2, 3}` | `DRIVE`, `REVERSE`, `NEUTRAL` | `PARK`, `PRESENT` | `PARK`, `NOT_PRESENT` |
|---|---:|---:|---:|
| 2, 1, 1 | 75 | 28 | 2 |
| 1, 1, 1 | 0 | 16 | 33 |
| 1, 2, 1 | 0 | 1 | 8 |
| 2, 2, 1 | 0 | 3 | 1 |
| 1, 1, 2 | 0 | 0 | 7 |

Triggers seen: gear change, lock change (interior lock while seated, walk-away lock), door open/close via interior and
exterior handles (front and rear driver doors), trunk open/close by hand from outside, charge port open, latch release
and close, charging start, user presence change, waking up, a change of field 8 alone (Likely).

Frequency: a frame comes at each change, and it is also repeated with no change.

- **Repeat (Likely):** on an awake vehicle with a person present and nothing changing, the next `Status` came 60.0 s after
  the previous one: 47 gaps of 59.9 to 60.15 s (44 in `DRIVE`, 3 in `PARK`). No gap between two `Status` frames on one
  connection was longer (167 gaps, later recordings, B5).
- **What restarts the wait (Likely):** a frame with a change. 6 of the 47 gaps started at one. Frames other than `Status`
  did not restart it (3 gaps, with field 53, `whitelistInfo` or `commandStatus` in between).
- **Counterexample:** one gap between identical frames was 55.7 s, unexplained.
- **Not measured:** a sleeping vehicle, and an awake vehicle with nobody present. With `NOT_PRESENT` something kept changing
  (the largest of 42 gaps was 25.3 s), so no repeat was seen in that state.
- **Earlier rounds:** round 1 reported every 1 to 25 s while awake (26 B `UNLOCKED`, 28 B otherwise). Those frames are not
  available to check. Field 8 changes may explain it: in the later recordings 37 of the 38 gaps of 5 to 59 s ended at a frame
  that changed something. Round 2: sometimes several within 1 s. Round 3: nine frames within 0.5 s while waking, then one
  every 0.1 to 8 s while things changed.

Do not treat a repeat as a change.

Library rule: a missing `closureStatuses` in a `vehicleStatus` means "all closed" (A2), confirmed for both driver doors,
the rear trunk and the charge port. It is wrong only for a trunk operated with the interior button, which sends no
broadcast. `vehicleLockState` `UNLOCKED` is the zero value and is never encoded, so in a broadcast it cannot be told
apart from a missing lock state (seen while the vehicle was still `ASLEEP`).

### B3.2 `authenticationRequest` (field 3)

Status: **Confirmed** to occur; names of the sub-fields are unverified (Teslemetry schema, TESLEMETRY-EXT).

`AuthenticationRequest`:

| # | Field | Type | Meaning |
|---|---|---|---|
| 2 | `sessionInfo` (name unverified) | `AuthenticationRequestToken{token = 1}` | `token`: 20 bytes |
| 3 | `requestedLevel` (name unverified) | `AuthenticationLevel_E` | 0 `NONE`, 1 `UNLOCK`, 2 `DRIVE` (`AUTHENTICATION_LEVEL_` prefix) |
| 4 | `reasonsForAuth` (name unverified) | repeated `AuthenticationReason_E` | Why the vehicle asks (table below) |

`AuthenticationReason_E`: 0 `NOT_DOCUMENTED`, 1 `IDENTIFICATION`, 2 `POWER_ON_VEHICLE_REQUEST`, 3 `GTW_REQUEST`,
4 `UI_UNLOCK_PASSIVE_AUTH`, 5 `PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL`, 6 `PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL`,
7 `PASSIVE_UNLOCK_AUTOPRESENT_DOOR`, 8 `ENTERED_HIGHER_AUTH_ZONE`, 9 `WALK_UP_UNLOCK`, 10 `IMMOBILIZER`
(prefix `AUTHENTICATIONREASON_`). The related `AuthenticationResponse` (`UnsignedMessage` field 3, sent by a phone key) and
`AuthenticationRejection_E` exist in the schema; the library does not send them.

Public model (`VehicleEvent.AuthenticationRequest`, B2.2): `requestedLevel: AuthenticationLevel` is `NONE`, `UNLOCK`,
`DRIVE`, or `UNKNOWN` for a value outside the schema; proto3 cannot tell an absent level from `NONE`. `reasons:
List<AuthenticationReason>` has one entry per value sent, named as above without the prefix (`NOT_DOCUMENTED` …
`IMMOBILIZER`); a value outside the schema is `UNKNOWN` and is listed after the known ones (its original position is
lost; the exact order is in `raw`).

Observed: `requestedLevel = DRIVE`, `reasons = [PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL]` when the interior handle was pulled;
`ENTERED_HIGHER_AUTH_ZONE` was added when the charge port was opened and charging started. It came together with a
`vehicleStatus` (and field 53) in the same instant. In the later recordings (B5) all 6 wrapped frames had
`requestedLevel = DRIVE`, `reasons = [ENTERED_HIGHER_AUTH_ZONE]`, and one unwrapped frame had `IDENTIFICATION` (below).
Other reasons were not observed. In round 3 (exterior handles on a sleeping, then awake vehicle; charge port latch
release; trunk by hand) **no** `authenticationRequest` and no field 53 arrived; `PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL` was
never seen.

**Wrapping:** this vehicle normally sends it inside a `RoutableMessage` (broadcast form above). Unwrapped frames do occur
(Likely): 2 in the 12 connections of the later recordings (B5), both without `from_destination` and read as an unwrapped
`FromVCSECMessage`:

- 31 B, `authenticationRequest{requestedLevel = DRIVE, reasons = [IDENTIFICATION], token = 20 B}`, as a slow connect
  completed (B3.6).
- 12 B, `commandStatus{OPERATIONSTATUS_OK}`.

That is far from the "about every 1.1 s" reported elsewhere (Teslemetry PR #179). These frames fail the gate (B2.1), so
`Vehicle.events` does not deliver them and an app cannot see this `AuthenticationRequest`. Only the observation log shows
them, as `[drop:missing-source]` with `reinterpreted=` (`18-lib-troubleshooting.md`). `drop:unparseable`: 0 lines.

### B3.3 Field 53 (unknown)

Status: **Unknown**. `FromVCSECMessage` field 53: one message with repeated sub-field 1, each entry
`{1: small int, 2: 35 bytes}`.

- Size by entries: 54 bytes for 1 entry (ids 3, 4 and 7 seen), 95 bytes for 2 entries (ids 3 and 6), 205 to 220 bytes for
  5 entries (ids 1, 3, 4, 6, 7). The later recordings (B5) have 11 frames, four of them with 5 entries, all 220 bytes and
  all in the order 1, 3, 6, 7, 4, which is the field 48 value byte for byte (B3.4).
- **The ids are not key slots (Likely).** They overlap with slot numbers, but `keySummary` reported only slots 0 to 6 in all
  37 polls of a session in which id 7 appeared.
- **The 35-byte blob:** the first 33 bytes were identical in all 28 entries of those 11 frames, on two dates and while the
  number of enrolled keys changed. Only the last 2 bytes vary, per entry, even for the same id (28 values in 28 entries).
- **When it comes:** rounds 1 and 2 saw it with authentication events. In the later recordings only 1 of the 11 frames came
  within 3 s of an `authenticationRequest`. Three of the four 5-entry frames came within 0.7 s of field 8 sub-field 1
  changing from 2 to 1, and the fourth 0.4 s after a slow connect.
- The earlier guess of a per-key authentication challenge is weaker now: at most 16 bits differ between entries. Its
  meaning is unknown.

### B3.4 Field 48 (unknown)

Status: **Unknown**. `FromVCSECMessage` field 48: `{1: 5 bytes}`, in a 20-byte frame. All 3 frames of the later recordings
(B5) carry `01 03 06 07 04`, the id order of the 5-entry field 53 frames (B3.3).

Rate: round 1 saw it about every 5.0 s while the vehicle was awake and a person was present (gaps exist), and round 2 did
not see it. The later recordings have 3 frames in about 68 minutes connected, with no visible trigger. That does not fit a
steady heartbeat, the earlier guess. The condition is unknown.

### B3.5 Replies addressed to other clients

Not broadcasts. Replies to another BLE client reach our link too: `to_destination.routing_address` is that client's 16-byte
address, and the dispatcher drops them as `no-handler` (owner `other`). They fail the gate (B2.1), so they are never events.
Counts are from the later recordings (B5).

| Reply | Domain | Frame | Status | Seen |
|---|---|---|---|---|
| Signed, empty body, 16-byte `request_uuid` | VCSEC | 83 B | Confirmed | About every 1 to 2 minutes and right after a door event; 41 frames |
| Other VCSEC replies: encrypted with empty `request_uuid`, plaintext `whitelistInfo` and `whitelistEntryInfo`, `session_info` | VCSEC | 80 to 177 B | Likely | 178 frames |
| Encrypted reply, 16-byte `request_uuid` | Infotainment | 150 to 453 B; ciphertext up to 350 B | Likely | 1685 frames |
| `session_info` | Infotainment | 179 and 180 B | Likely | 66 frames |
| `MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED` in `signedMessageStatus`, no payload | Infotainment | 106 B | Likely | 132 frames (`16-lib-errors.md` §Response size limit) |

- The Infotainment frames came at about one per second while that client was active (median gap 0.94 s). They appeared on 7
  of the 12 connections. One 17-minute connection, mostly in `DRIVE`, carried none, so driving alone does not produce them.
- The logs do not identify the client.
- Queries can stall while this traffic runs (`16-lib-errors.md` §Queries while another BLE client is active).

### B3.6 Field 44 (unknown)

Status: **Unknown** for the meaning, **Likely** for the link with slow connects. `FromVCSECMessage` field 44: a varint, value
2 in all 4 frames seen, in a 13-byte frame. Alone it is a `VehicleEvent.Unrecognized` with `"44"` in `unknownFields`.

All 12 connections of the later recordings (B5) split cleanly by how long `connect` took:

| Connections | `connecting` to `connected` | With a field 44 frame | With another client's Infotainment frames |
|---|---:|---:|---:|
| 8 fast | 0.10 to 0.21 s | 0 of 8 | 7 of 8 |
| 4 slow | 1.52 to 13.58 s | 4 of 4, 1.1 to 3.4 s after `connected` | 0 of 4 |

- The shortest fast connection lasted 26.7 s, so a frame at that delay would have been seen.
- It is not simply "vehicle locked": one fast connect to a locked vehicle with nobody present had no field 44.
- Why a connect is slow or fast, and what the value means, are unknown.

## B4. What is not available by broadcast

| Item | Result |
|---|---|
| Turn signals (left, right), hazards, headlights, high beams | **No message at all** when operated, and no BLE schema field for them (`vcsec.proto`, `vehicle.proto`, `car_server.proto`). The only known source is Tesla Fleet Telemetry (`LightsTurnSignal` 240, `LightsHazardsActive` 239, `LightsHighBeams` 241), an internet server path outside this library's no-server scope |
| Trunk opened with the interior button | No broadcast. Opened and closed by hand from outside: broadcast (B3.1). Key card, phone key and app not tested |
| Sleeping vehicle | Only its first frames while waking (round 3, `ASLEEP` for about 0.1 s). A vehicle that stays asleep was not observed |
| Charging cable | Round 2 (charge port open, charger connected, charging start): `chargePort = OPEN`, field 8 sub-field 2 changed 2 to 1, `authenticationRequest` with `ENTERED_HIGHER_AUTH_ZONE`, field 53 with one entry. Round 3 (latch release, unplug, port door closes): `chargePort` `CLOSED` while plugged in, `OPEN` after the latch release, absent after the port door closed; field 8 sub-field 2 changed 1 to 2. No separate broadcast for the cable itself was identified, and field 8 is not one (B3.1) |
| Another phone key approaching | Not recorded |

## B5. Observation method and environment

- iPhone 16 Pro, iOS 27, Model Y (firmware not recorded). Sample `dev.mirolab.TeslableSampleDevice` builds 7 and 8, key
  `SECURE_ENCLAVE`. The key does not need to be enrolled to observe.
- 1st round 2026-10-18 (build 7, one continuous recording, 71 lines). 2nd round 2026-10-24 (build 8, detailed unknown-field
  decode, one recording per scenario). 3rd round 2026-10-24 (build 8, one 40 s recording of 25 lines: sleeping and locked
  vehicle, rear driver door opened from outside, charge port latch released while holding the handle, charger unplugged,
  rear door closed, charge port door closed, trunk opened and closed by hand from outside, driver door opened and closed
  from outside).
- Later recordings: six recordings of the Monitor app on 2026-10-31 and 2026-11-01 (builds 13, 14 and 18), 12 connections,
  about 68 minutes connected, on the phone and vehicle above. No scenario was given, so what a person did is inferred from
  the frames and these findings stay Likely or Unknown.
- Reproduce: turn on the sample's observation mode, perform the scenario, then press the "show" button (and "copy whole
  result"). Details and log syntax: `18-lib-troubleshooting.md`.

## B6. Provenance

- Official `vehicle-command` at `a4b43c1` (Apache-2.0): routing, framing, VCSEC queries.
- Teslemetry `tesla-protocol` at `e6f7afb1` (Apache-2.0): proto snapshot with TESLEMETRY-EXT fields (`gear`, `uiDesire`,
  `authenticationRequest`, extra enum values); ADR-0002 (revised 2026-10-18).
- Teslemetry `python-tesla-fleet-api` PRs #178 and #179: facts only.
- Our own captures: the real-car results note (`docs/handoff/2026-10-18-m4-push-observation-results.md`) and the log
  analysis (`docs/analysis/2026-11-01-build18-link-health.md`), rechecked against the raw logs for this page.
- AGPL repositories were not used.


---

# Commands

Scope: on `main` the session calls are `startSession()` and `disconnect()`, the 12 VCSEC control commands (M4) and the
51 Infotainment commands of M5 (climate, charging and power, body, security, media, software update, `ping()`) are
implemented. The Infotainment query `getState(category)` is in `14-lib-reading-state.md` A8. Go original for every
command: `05-command-catalog.md`.

## Implemented

| Call | Returns | Notes |
|---|---|---|
| `startSession(domains = VehicleDomain.ALL, timeout = null)` | `VehicleResult<Unit>` | Handshake per domain, in parallel; one failure cancels the others (FR-012). Domains already `Ready` from the session cache succeed without a request. Needs a key (`RequiresKey`) that is enrolled (`KeyNotPaired`). A key the phone lock blocks (iOS `WHEN_UNLOCKED` Secure Enclave key read before the lock) fails right after the first reply with `KeyStoreError.Locked`, not retried (FR-037, `13-lib-pairing.md` §Key accessibility). `timeout = null` uses `handshakeTimeout` (20 s) |
| `disconnect()` | `Unit` | Saves the session cache, wipes session keys, closes the link. Idempotent (FR-005) |

`sessions` reports `Handshaking` while `startSession` runs, then `Ready(fromCache = false)` or back to `None` on failure,
cancellation or disconnect.

## Rules every control command follows

These are implemented in the dispatcher (M2) and apply to every command:

- Authenticated with AES-GCM over the domain session; `expires_at` lifetime is `commandLifetime` (5 s).
- No command starts a session or wakes the vehicle. Without a session for its domain it returns `Failure(NoSession)` and
  sends nothing.
- Retried automatically at 1 s intervals while `error.shouldRetry()`; never resent when `mayHaveSucceeded`.
- After a failed write that the library repeats with the same bytes, a command is never authorized again: a fault for
  the repeated copy (a retriable one, or any protocol-layer rejection such as `KeyNotPaired`) is not taken as the result,
  and the call returns `Uncertain(Timeout(afterSend = true))` unless a reply that settles it arrives within `timeout`:
  the first copy's late reply, or the repeated copy's success when the first never reached the vehicle
  (`16-lib-errors.md` §Retries and timeouts).
- VCSEC commands are serialized per connection; Infotainment commands may run in parallel.
- An `Uncertain` result means "query state, do not resend" (`16-lib-errors.md`).

## Implemented: VCSEC (M4)

All VCSEC commands are authenticated with AES-GCM over the VCSEC session. Without a VCSEC session they return
`Failure(NoSession)`: the app runs `startSession(setOf(VehicleDomain.VCSEC))` first. Success is a reply without
`commandStatus`. `timeout = null` uses `commandTimeout` (5 s) for every method.

| Method | FR | Payload (`UnsignedMessage`, hex) | Notes |
|---|---|---|---|
| `lock()` | FR-040 | `1001` | `RKEAction` LOCK |
| `unlock()` | FR-041 | `1000` | `RKEAction` UNLOCK |
| `wakeUp()` | FR-042 | `101e` | `RKEAction` WAKE_VEHICLE. Needs the VCSEC session only (Go `wakeupRKE`) |
| `remoteDrive()` | FR-043 | `1014` | `RKEAction` REMOTE_DRIVE |
| `autoSecureVehicle()` | FR-044 | `101d` | `RKEAction` AUTO_SECURE_VEHICLE (Model X) |
| `openTrunk()` | FR-045 | `22022801` | Same payload as `actuateTrunk()`: a **toggle** (`rearTrunk = MOVE`) |
| `actuateTrunk()` | FR-045 | `22022801` | Toggle |
| `closeTrunk()` | FR-045 | `22022804` | `rearTrunk = CLOSE`. Powered trunks only |
| `openFrunk()` | FR-046 | `22023001` | `frontTrunk = MOVE`. There is no remote close |
| `openTonneau()` | FR-047 | `22024003` | Cybertruck. `tonneau = OPEN` |
| `closeTonneau()` | FR-047 | `22024004` | Cybertruck. `tonneau = CLOSE` |
| `stopTonneau()` | FR-047 | `22024002` | Cybertruck. `tonneau = STOP` |
| Key enrollment and removal | FR-022–027 | | See `13-lib-pairing.md` |

All return `VehicleResult<Unit>`. Failures to expect: `NoSession`, `NotConnected`, `InsufficientPrivileges` (for example
a Vehicle Monitor key, or the vehicle state forbids it), `VcsecRejected(error)`, `UnknownVcsecError(rawCode)`.

`Uncertain` (timeout after the request's first BLE write was started, including after a failed write the library
repeated, unreadable reply) means the vehicle may have acted. **Do not resend**; read
`vehicleStatus()` to see the lock state and closures (`16-lib-errors.md` §Per-command re-query). This matters most for
the toggles `openTrunk()` / `actuateTrunk()`.

`sessions` follows the real session state: after a command ends with `NoSession`, or when a session event arrives
(refreshed session info, a stale cache entry), the library re-checks the domain and `sessions` shows `Ready` or `None`
accordingly. A session restored from the cache that the vehicle no longer accepts (for example the cache was stored for
another vehicle's key) shows `None` after the first command fails with `NoSession`; `startSession` recovers it.

## Implemented: Infotainment (M5)

All 51 methods (climate 12, charging and power 19, body 5, security 4, media 8, software update 2, `ping()`) are
authenticated with AES-GCM over the INFOTAINMENT session, return `VehicleResult<Unit>` and take
`timeout: Duration? = null` last (`null` uses `commandTimeout`, 5 s). The success reply's content is dropped, as in Go.
They are checked with FakeVehicle tests; **none is confirmed on a real vehicle yet** (#118).

### Call order

1. An `INFOTAINMENT` session: `startSession(setOf(VehicleDomain.INFOTAINMENT))`, or both domains. Without it a command
   returns `Failure(NoSession)` and sends nothing.
2. An awake vehicle. The library does **not** wake it: when Infotainment sleeps, the command is sent, nothing comes back,
   and the result is `Uncertain(Timeout(afterSend = true))`. Read `vehicleStatus()` first (no session, never wakes the
   vehicle); if it is `ASLEEP`, wake it as in `14-lib-reading-state.md` A8.2 (`startSession(setOf(VehicleDomain.VCSEC))`,
   `wakeUp()`, wait for `AWAKE`) and start the INFOTAINMENT session.
3. Send the command and branch on the result:

| Result | Meaning | App action |
|---|---|---|
| `Success(Unit)` | The vehicle accepted the command | To show the new state, read it with `getState` (§Re-query) |
| `Uncertain(error)` | The vehicle may have run it: no reply in time (Infotainment asleep, reply lost), a failed write the library repeated, an unreadable reply | **Never resend.** Read the state with `getState` (§Re-query) and decide from what it shows. `setLowPowerMode`, `setKeepAccessoryPowerMode`, `honkHorn` and `flashLights` cannot be read back |
| `Failure(InvalidArgument(detail))` | A local check failed and nothing was sent (§Local checks) | Fix the argument |
| `Failure(InfotainmentRejected(reason))` | The vehicle refused (`actionStatus ERROR`) | Show a generic message. `reason` is the vehicle's own text; do not log it |
| `Failure(NoSession)`, `Failure(NotConnected)`, other errors | Call order or link | `16-lib-errors.md` |

### Local checks

Only the checks the Go original has. They run before the session check, so a bad argument returns `InvalidArgument`
even without a session, and nothing is sent:

| Method | Rule | `InvalidArgument.detail` |
|---|---|---|
| `setSeatCooler` | `seat` is `FRONT_LEFT` or `FRONT_RIGHT` | `invalid seat position for seat cooler: <SEAT>` (for example `…: SECOND_ROW_LEFT`) |
| `scheduleDeparture` | `departAtMinutes` is in `0..1440` (both ends allowed) | `invalid departure time: <n> minutes after midnight (expected 0..1440)` |
| `enableValetMode` | `pin` is exactly four ASCII digits `0`–`9` (other scripts' digits and full-width digits are rejected) | `PIN codes must be four digits` (Go `ErrInvalidPIN`; the PIN itself is never in an error or a log) |
| `setVolume` | `volume` is in `0..10` and not NaN | `invalid volume (should be in [0, 10])` (Go's text; Go lets NaN through, this library rejects it) |

Everything else is sent as given and the vehicle decides: temperatures, charge limit, amps, the other times, schedule
fields, the sunroof level, the software update delay. `autoSeatAndClimate` drops seats the Go original does not handle,
without an error (§Climate).

### Parameter types

| Type | Values | Used by |
|---|---|---|
| `SeatPosition` | `UNKNOWN`, `FRONT_LEFT`, `FRONT_RIGHT`, `SECOND_ROW_LEFT`, `SECOND_ROW_LEFT_BACK`, `SECOND_ROW_CENTER`, `SECOND_ROW_RIGHT`, `SECOND_ROW_RIGHT_BACK`, `THIRD_ROW_LEFT`, `THIRD_ROW_RIGHT` (Go `SeatPosition` order; `_BACK` is the backrest, heated on some Model S only) | `setSeatHeater` (all), `setSeatCooler` (front seats), `autoSeatAndClimate` (`UNKNOWN`, `FRONT_LEFT`, `FRONT_RIGHT`) |
| `SeatClimateLevel` | `OFF`, `LOW`, `MED`, `HIGH` (Go `Level`) | `setSeatHeater`, `setSeatCooler` |
| `ClimateKeeperMode` | `OFF`, `ON`, `DOG`, `CAMP` | `setClimateKeeperMode` |
| `CabinOverheatProtectionTemperature` | `UNSPECIFIED`, `LOW`, `MEDIUM`, `HIGH` (wire values 0–3 of `ClimateState.CopActivationTemp`) | `setCabinOverheatProtectionTemperature` |
| `ChargingPolicy` | `OFF` (that part is not sent), `ALL_DAYS`, `WEEKDAYS` | `scheduleDeparture` |
| `ChargeSchedule`, `PreconditionSchedule` | Wire generated types in `com.tesla.generated.carserver.common`, sent as given (ADR-0002, revised 2026-11-08). `id` is the uint64 in a `Long`; an id above `Long.MAX_VALUE` is a negative `Long` | `addChargeSchedule`, `addPreconditionSchedule` |

The other commands take plain values: `level: Int` (sunroof), `enabled: Boolean`, `pin: String`, `volume: Float`,
`delaySeconds: Int`.

Times are minutes after midnight in the vehicle's local time: `minutesAfterMidnight`, `departAtMinutes`,
`offPeakEndMinutes`, and the schedules' `start_time`, `end_time`, `precondition_time`. `days_of_week` is a bitmask; the
Go CLI (`cmd/tesla-control/commands.go` `dayNamesBitMask`) uses Sunday 1, Monday 2, Tuesday 4, Wednesday 8, Thursday 16,
Friday 32, Saturday 64 (every day 127, weekdays 62). The Go CLI uses the current Unix time as a new schedule's `id`.

### Climate

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `climateOn()` | FR-060 | `120452020801` | `hvacAutoAction{power_on: true}`; the protocol.md example |
| `climateOff()` | FR-060 | `12025200` | Empty `hvacAutoAction` member |
| `setClimateTemp(driverCelsius: Float, passengerCelsius: Float)` | FR-062 | `(22.0, 21.5)` → `1210720e2a021a00350000b0413d0000ac41` | Celsius, no range check. Always carries `level{TEMP_MAX}`, as in Go |
| `setSeatHeater(levels: Map<SeatPosition, SeatClimateLevel>)` | FR-061 | `{FRONT_LEFT: HIGH}` → `1209a202060a042a003a00` | One entry per seat, sent in `SeatPosition` declaration order whatever the map order; an empty map is sent |
| `setSeatCooler(level: SeatClimateLevel, seat: SeatPosition)` | FR-063 | `(LOW, FRONT_RIGHT)` → `12098a03060a0408021002` | Front seats only (§Local checks). The wire level is Go's `level + 1` |
| `setSteeringWheelHeater(enabled: Boolean)` | FR-064 | `true` → `12046a020801` | |
| `autoSeatAndClimate(seats: List<SeatPosition>, enabled: Boolean)` | FR-065 | `([FRONT_RIGHT, UNKNOWN], false)` → `12098203060a0210020a00` | Keeps `UNKNOWN`, `FRONT_LEFT`, `FRONT_RIGHT` in order with duplicates and drops the other seats; sent even when no seat is left |
| `setPreconditioningMax(enabled: Boolean, manualOverride: Boolean)` | FR-066 | `(true, true)` → `1206620408011001` | |
| `setClimateKeeperMode(mode: ClimateKeeperMode, manualOverride: Boolean)` | FR-067 | `(DOG, true)` → `1207e2020408021001` | |
| `setBioweaponDefenseMode(enabled: Boolean, manualOverride: Boolean)` | FR-068 | `(true, false)` → `12059a02020801` | |
| `setCabinOverheatProtection(enabled: Boolean, fanOnly: Boolean)` | FR-069 | `(true, true)` → `120792030408011001` | |
| `setCabinOverheatProtectionTemperature(temperature: CabinOverheatProtectionTemperature)` | FR-069 | `HIGH` → `12059204020803` | |

### Charging and power

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `chargeStart()` | FR-070 | `120432021200` | `chargingStartStopAction{start}` |
| `chargeStop()` | FR-070 | `120432022a00` | `chargingStartStopAction{stop}` |
| `setChargeLimit(percent: Int)` | FR-071 | `80` → `12042a020850` | No range check |
| `setChargingAmps(amps: Int)` | FR-072 | `32` → `1205da02020820` | No range check |
| `chargeStandardRange()` | FR-073 | `120432021a00` | `chargingStartStopAction{start_standard}` |
| `chargeMaxRange()` | FR-073 | `120432022200` | `chargingStartStopAction{start_max_range}` |
| `scheduleCharging(enabled: Boolean, minutesAfterMidnight: Int)` | FR-074 | `(true, 120)` → `1207ca020408011078` | No check of the time |
| `scheduleDeparture(departAtMinutes: Int, offPeakEndMinutes: Int, preconditioning: ChargingPolicy, offPeak: ChargingPolicy)` | FR-075 | `(480, 360, ALL_DAYS, WEEKDAYS)` → `1213d20210080110e0031a020a002202120028e802` | `enabled` is always true. `departAtMinutes` must be `0..1440`; `offPeakEndMinutes` is not checked. An `OFF` policy leaves its part out |
| `clearScheduledDeparture()` | FR-075 | `1203d20200` | `enabled = false` only |
| `setLowPowerMode(enabled: Boolean)` | FR-076 | `true` → `12059208020801` | Cannot be read back (§Re-query) |
| `setKeepAccessoryPowerMode(enabled: Boolean)` | FR-077 | `true` → `1205d208020801` | Cannot be read back (§Re-query) |
| `addChargeSchedule(schedule: ChargeSchedule)` | FR-078 | `ChargeSchedule(id = 1, enabled = true)` → `12078a060408014801` | Sent as given |
| `removeChargeSchedule(id: Long)` | FR-079 | `1_700_000_000` → `12099206060880e2cfaa06` | |
| `batchRemoveChargeSchedules(home: Boolean, work: Boolean, other: Boolean)` | FR-080 | `(true, false, true)` → `1207e2060408011801` | `other` = schedules that are neither home nor work |
| `addPreconditionSchedule(schedule: PreconditionSchedule)` | FR-081 | `PreconditionSchedule(id = 2, enabled = true)` → `12079a060408023001` | Sent as given |
| `removePreconditionSchedule(id: Long)` | FR-082 | `2` → `1205a206020802` | |
| `batchRemovePreconditionSchedules(home: Boolean, work: Boolean, other: Boolean)` | FR-083 | `(false, true, true)` → `1207da060410011801` | |
| `openChargePort()` | FR-052 | `1203f20300` | Go `OpenChargePort` (= `ChargePortOpen`) |
| `closeChargePort()` | FR-052 | `1203ea0300` | Go `CloseChargePort` (= `ChargePortClose`) |

### Body

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `ventWindows()` | FR-050 | `12059202021a00` | `vehicleControlWindowAction{vent}` |
| `closeWindows()` | FR-050 | `12059202022200` | `vehicleControlWindowAction{close}` |
| `setSunroof(level: Int)` | FR-051 | `0` → `12058202020800` | Absolute level, no check. `0` is still sent (`absolute_level` is a oneof member) |
| `honkHorn()` | FR-053 | `1203da0100` | Cannot be read back (§Re-query) |
| `flashLights()` | FR-053 | `1203d20100` | Cannot be read back (§Re-query) |

### Security

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `setSentryMode(enabled: Boolean)` | FR-084 | `true` → `1205f201020801` | |
| `enableValetMode(pin: String)` | FR-085 | `"1234"` → `120bfa01080801120431323334` | The PIN must be four ASCII digits (§Local checks). The library never logs it; the app should not either |
| `disableValetMode()` | FR-085 | `1203fa0100` | A refusal whose reason ends with `already off` (case-sensitive) is `Success`: valet mode is off already. Go checks that suffix on any error; the library on the vehicle's refusal only |
| `resetValetPin()` | FR-085 | `1203e20100` | |

### Media

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `volumeUp()` | FR-090 | `12058201020802` | `volume_delta` +1 (sint32) |
| `volumeDown()` | FR-090 | `12058201020801` | `volume_delta` −1 |
| `setVolume(volume: Float)` | FR-090 | `5.5` → `12088201051d0000b040` | `0..10`, NaN refused (§Local checks). `0` is still sent |
| `mediaNextTrack()` | FR-091 | `12039a0100` | |
| `mediaPreviousTrack()` | FR-091 | `1203a20100` | |
| `mediaNextFavorite()` | FR-091 | `12038a0100` | |
| `mediaPreviousFavorite()` | FR-091 | `1203920100` | |
| `toggleMediaPlayback()` | FR-091 | `12027a00` | A **toggle**: run twice, it ends where it started. After `Uncertain` never resend; read `media_playback_status` |

### Software update

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `scheduleSoftwareUpdate(delaySeconds: Int)` | FR-092 | `7200` → `1206ea010308a038` | Starts the update after `delaySeconds`; no check. Go takes a `time.Duration` and truncates it to seconds |
| `cancelSoftwareUpdate()` | FR-092 | `1203ca0100` | |

### Ping

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `ping()` | FR-034 | `1205f202020801` | `ping{ping_id: 1}`, as Go. The reply's `Ping` is not inspected |

`ping()` changes nothing and reads nothing. `Success` means the vehicle knows the key and Infotainment answered, which
makes it a cheap check before a series of commands. It needs the same INFOTAINMENT session and awake vehicle as every
command; `Uncertain(Timeout(afterSend = true))` means no reply (asleep or lost). Because it changes nothing, the app may
call it again after `Uncertain`; the library never resends it by itself. To read data, use `getState`.

### Re-query

After `Uncertain`, never resend: read the state the command changes. The field names are the official proto names; which
field a command changes on a real vehicle is not confirmed yet (#118). `getState` itself can come back `Uncertain` (asleep,
reply too large): then the command's result stays unknown until a later read succeeds.

| Commands | Read with | Look at |
|---|---|---|
| `climateOn`, `climateOff` | `getState(StateCategory.CLIMATE)` | `climate_state.is_climate_on` |
| `setClimateTemp` | same | `driver_temp_setting`, `passenger_temp_setting` |
| `setSeatHeater`, `setSeatCooler` | same | `seat_heater_*`, `seat_fan_front_left` / `_right` |
| `setSteeringWheelHeater`, `autoSeatAndClimate` | same | `steering_wheel_heater`, `auto_seat_climate_left` / `_right` |
| `setClimateKeeperMode`, `setBioweaponDefenseMode` | same | `climate_keeper_mode`, `bioweapon_mode_on` (cars with a HEPA filter) |
| `setCabinOverheatProtection`, `setCabinOverheatProtectionTemperature` | same | `cabin_overheat_protection`, `cop_activation_temperature` |
| `setPreconditioningMax` | same | The climate state; no single field is identified |
| `chargeStart`, `chargeStop`, `chargeStandardRange`, `chargeMaxRange` | `getState(StateCategory.CHARGE)` | `charge_state.charging_state`, `charge_limit_soc` |
| `setChargeLimit`, `setChargingAmps` | same | `charge_limit_soc`, `charging_amps`, `charge_current_request` |
| `scheduleCharging` | same | `scheduled_charging_mode`, `scheduled_charging_start_time_minutes` |
| `scheduleDeparture`, `clearScheduledDeparture` | same | `scheduled_departure_time_minutes`, `preconditioning_enabled`, `off_peak_charging_times` |
| `openChargePort`, `closeChargePort` | same | `charge_port_door_open` |
| `addChargeSchedule`, `removeChargeSchedule`, `batchRemoveChargeSchedules` | `getState(StateCategory.CHARGE_SCHEDULE)` | `charge_schedule_state.charge_schedules` (the same `ChargeSchedule` type; look for the `id`) |
| `addPreconditionSchedule`, `removePreconditionSchedule`, `batchRemovePreconditionSchedules` | `getState(StateCategory.PRECONDITIONING_SCHEDULE)` | `preconditioning_schedule_state.precondition_schedules` |
| `ventWindows`, `closeWindows` | `getState(StateCategory.CLOSURES)` | `closures_state.window_open_driver_front` (and the other three windows) |
| `setSunroof` | same | `sun_roof_percent_open`, `sun_roof_state` |
| `setSentryMode` | same | `sentry_mode_state` |
| `enableValetMode`, `disableValetMode`, `resetValetPin` | same | `valet_mode`, `valet_pin_needed` |
| `volumeUp`, `volumeDown`, `setVolume` | `getState(StateCategory.MEDIA)` | `media_state.audio_volume` |
| `mediaNextTrack`, `mediaPreviousTrack`, `mediaNextFavorite`, `mediaPreviousFavorite` | same | `now_playing_title`, `now_playing_artist` |
| `toggleMediaPlayback` | same | `media_playback_status` (`Playing`, `Paused`, `Stopped`). **Never resend the toggle** |
| `scheduleSoftwareUpdate`, `cancelSoftwareUpdate` | `getState(StateCategory.SOFTWARE_UPDATE)` | `software_update_state.status`, `scheduled_time_ms` |
| `setLowPowerMode`, `setKeepAccessoryPowerMode`, `honkHorn`, `flashLights` | None | The official `VehicleData` has no field for these. Show the result as unknown; do not resend |
| `ping` | None | Nothing changed; call `ping()` again if needed |

Some of these fields are wrapper messages, not enums or numbers: `charging_state`, `off_peak_charging_times`,
`climate_keeper_mode`, `sun_roof_state`, `sentry_mode_state`, `status`. How to read them in Kotlin and Swift:
`14-lib-reading-state.md` A8.6.

### Kotlin example

```kotlin
import com.tesla.generated.carserver.common.ChargeSchedule
import com.tesla.generated.carserver.common.MediaPlaybackStatus
import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.ChargingPolicy
import dev.mirolab.teslable.model.SeatClimateLevel
import dev.mirolab.teslable.model.SeatPosition
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult

// Before: an INFOTAINMENT session (startSession) and an awake vehicle (14-lib-reading-state.md A8.2).
suspend fun warmUp(vehicle: Vehicle): String {
    when (val result = vehicle.climateOn()) {
        is VehicleResult.Success -> Unit
        is VehicleResult.Uncertain -> {
            // The vehicle may have turned climate on. Never resend: read the state.
            val state = vehicle.getState(StateCategory.CLIMATE)
            val on = (state as? VehicleResult.Success)?.value?.climate_state?.is_climate_on
            return if (on == true) "climate on" else "unknown, read again later"
        }
        is VehicleResult.Failure -> return "not sent or refused: ${result.error.message}"
    }
    // Handle these results the same way.
    vehicle.setClimateTemp(driverCelsius = 22.0f, passengerCelsius = 21.5f)
    vehicle.setSeatHeater(mapOf(SeatPosition.FRONT_LEFT to SeatClimateLevel.HIGH, SeatPosition.FRONT_RIGHT to SeatClimateLevel.LOW))
    return "climate on"
}

suspend fun planCharging(vehicle: Vehicle, scheduleId: Long, latitude: Float, longitude: Float): String {
    // Leave at 08:00, off-peak charging ends at 06:00 (minutes after the vehicle's local midnight).
    when (val departure = vehicle.scheduleDeparture(480, 360, ChargingPolicy.ALL_DAYS, ChargingPolicy.WEEKDAYS)) {
        is VehicleResult.Success -> Unit
        is VehicleResult.Uncertain -> {
            // The vehicle may have stored it. Never resend: read the state and stop here.
            val state = vehicle.getState(StateCategory.CHARGE)
            val departAt = (state as? VehicleResult.Success)?.value?.charge_state?.scheduled_departure_time_minutes
            return if (departAt == 480) "departure set, schedule not added" else "unknown, read again later"
        }
        is VehicleResult.Failure ->
            return if (departure.error is VehicleError.InvalidArgument) {
                "departure time outside 0..1440 minutes; nothing was sent"
            } else {
                "not sent or refused: ${departure.error.message}"
            }
    }
    val schedule =
        ChargeSchedule(
            id = scheduleId, // the Go CLI uses the current Unix time
            name = "Home",
            days_of_week = 127, // every day
            start_enabled = true,
            start_time = 120, // 02:00
            enabled = true,
            latitude = latitude,
            longitude = longitude,
        )
    return when (val added = vehicle.addChargeSchedule(schedule)) {
        is VehicleResult.Success -> "departure and schedule set"
        is VehicleResult.Uncertain -> {
            // Never resend: look for the id in the charge schedules.
            val state = vehicle.getState(StateCategory.CHARGE_SCHEDULE)
            val schedules = (state as? VehicleResult.Success)?.value?.charge_schedule_state?.charge_schedules.orEmpty()
            if (schedules.any { it.id == scheduleId }) "departure and schedule set" else "schedule unknown, read again later"
        }
        is VehicleResult.Failure -> "schedule not sent or refused: ${added.error.message}"
    }
}

suspend fun togglePlayback(vehicle: Vehicle): String =
    when (val result = vehicle.toggleMediaPlayback()) {
        is VehicleResult.Success -> "toggled"
        is VehicleResult.Uncertain -> {
            // A toggle run twice ends where it started. Never resend: read the state and let the user decide.
            val state = vehicle.getState(StateCategory.MEDIA)
            when ((state as? VehicleResult.Success)?.value?.media_state?.media_playback_status) {
                MediaPlaybackStatus.Playing -> "playing"
                MediaPlaybackStatus.Paused, MediaPlaybackStatus.Stopped -> "not playing"
                null -> "unknown, read again later"
            }
        }
        is VehicleResult.Failure -> "not sent or refused: ${result.error.message}"
    }

suspend fun enterValetMode(vehicle: Vehicle, pin: String): String =
    when (val result = vehicle.enableValetMode(pin)) {
        is VehicleResult.Success -> "valet mode on"
        is VehicleResult.Uncertain -> {
            // The vehicle may have turned it on. Never resend: read the state.
            val state = vehicle.getState(StateCategory.CLOSURES)
            val on = (state as? VehicleResult.Success)?.value?.closures_state?.valet_mode
            if (on == true) "valet mode on" else "unknown, read again later"
        }
        is VehicleResult.Failure ->
            if (result.error is VehicleError.InvalidArgument) {
                "the PIN must be four digits" // the error never contains the PIN; do not log the PIN
            } else {
                "not sent or refused: ${result.error.message}"
            }
    }
```

### Swift example

Swift passes every argument, including `timeout:`; the full rules are in `17-lib-platform-notes.md` §Swift: command
arguments. This form passes a Swift typecheck against the framework; it has not run in a Swift program.

```swift
func warmUp(vehicle: Vehicle) async throws -> String {
    let result = try await vehicle.climateOn(timeout: nil)
    switch onEnum(of: result) {
    case .success:
        break
    case .uncertain:
        // The vehicle may have turned climate on. Never resend: read the state.
        let state = try await vehicle.getState(category: .climate, timeout: nil)
        if case .success(let s) = onEnum(of: state), let data = s.value as? VehicleData,
           data.climate_state?.is_climate_on?.boolValue == true {
            return "climate on"
        }
        return "unknown, read again later"
    case .failure(let f):
        return "not sent or refused: \(f.error.message)"
    }
    _ = try await vehicle.setClimateTemp(driverCelsius: 22.0, passengerCelsius: 21.5, timeout: nil)
    _ = try await vehicle.setSeatHeater(levels: [.frontLeft: .high, .frontRight: .low], timeout: nil)
    return "climate on"
}

func planCharging(vehicle: Vehicle, scheduleId: Int64, latitude: Float, longitude: Float) async throws -> String {
    // Leave at 08:00, off-peak charging ends at 06:00 (minutes after the vehicle's local midnight).
    let departure = try await vehicle.scheduleDeparture(
        departAtMinutes: 480, offPeakEndMinutes: 360, preconditioning: .allDays, offPeak: .weekdays, timeout: nil)
    switch onEnum(of: departure) {
    case .success:
        break
    case .uncertain:
        // The vehicle may have stored it. Never resend: read the state and stop here.
        let state = try await vehicle.getState(category: .charge, timeout: nil)
        if case .success(let s) = onEnum(of: state), let data = s.value as? VehicleData,
           data.charge_state?.scheduled_departure_time_minutes?.int32Value == 480 {
            return "departure set, schedule not added"
        }
        return "unknown, read again later"
    case .failure(let f):
        if case .invalidArgument = onEnum(of: f.error) {
            return "departure time outside 0..1440 minutes; nothing was sent"
        }
        return "not sent or refused: \(f.error.message)"
    }
    let schedule = ChargeSchedule(
        id: scheduleId, name: "Home", days_of_week: 127, start_enabled: true, start_time: 120,
        end_enabled: false, end_time: 0, one_time: false, enabled: true,
        latitude: latitude, longitude: longitude, unknownFields: OkioByteString.companion.EMPTY)
    let added = try await vehicle.addChargeSchedule(schedule: schedule, timeout: nil)
    switch onEnum(of: added) {
    case .success:
        return "departure and schedule set"
    case .uncertain:
        // Never resend: look for the id in the charge schedules.
        let state = try await vehicle.getState(category: .chargeSchedule, timeout: nil)
        if case .success(let s) = onEnum(of: state), let data = s.value as? VehicleData,
           (data.charge_schedule_state?.charge_schedules ?? []).contains(where: { $0.id == scheduleId }) {
            return "departure and schedule set"
        }
        return "schedule unknown, read again later"
    case .failure(let f):
        return "schedule not sent or refused: \(f.error.message)"
    }
}

func togglePlayback(vehicle: Vehicle) async throws -> String {
    let result = try await vehicle.toggleMediaPlayback(timeout: nil)
    switch onEnum(of: result) {
    case .success:
        return "toggled"
    case .uncertain:
        // A toggle run twice ends where it started. Never resend: read the state and let the user decide.
        let state = try await vehicle.getState(category: .media, timeout: nil)
        guard case .success(let s) = onEnum(of: state), let data = s.value as? VehicleData,
              let status = data.media_state?.media_playback_status else { return "unknown, read again later" }
        return status == .playing ? "playing" : "not playing"
    case .failure(let f):
        return "not sent or refused: \(f.error.message)"
    }
}

func enterValetMode(vehicle: Vehicle, pin: String) async throws -> String {
    let result = try await vehicle.enableValetMode(pin: pin, timeout: nil)
    switch onEnum(of: result) {
    case .success:
        return "valet mode on"
    case .uncertain:
        // The vehicle may have turned it on. Never resend: read the state.
        let state = try await vehicle.getState(category: .closures, timeout: nil)
        if case .success(let s) = onEnum(of: state), let data = s.value as? VehicleData,
           data.closures_state?.valet_mode?.boolValue == true {
            return "valet mode on"
        }
        return "unknown, read again later"
    case .failure(let f):
        if case .invalidArgument = onEnum(of: f.error) {
            return "the PIN must be four digits" // the error never contains the PIN; do not log the PIN
        }
        return "not sent or refused: \(f.error.message)"
    }
}
```

## Deferred to v1.1

P2 items: FR-054 `triggerHomelink`, FR-086 `clearPinToDrive`, FR-087 speed limit, FR-088 parental
controls, FR-089 guest mode, FR-093 `setVehicleName`, FR-094 `getNearbyCharging`. `SetPINToDrive` is not possible over
BLE and is excluded.


---

# Error Handling

Scope: M1 error model, M2 retries and timeouts, M3 transport and key store errors, M4 `InsufficientPrivileges`, `UnknownVcsecError` and per-command re-query for VCSEC, M5 invalid arguments and per-command re-query for the Infotainment commands, broadcast events (`Vehicle.events`, FR-035), `KeyStoreError.Locked` on a locked iPhone from the key store and the handshake (FR-037), queries while another BLE client is active, `getState` results, the response size limit and reply authenticity (FR-033). Go original: `08-errors.md` (MessageFault meanings and remedies in §3.1).

## Three outcomes (`VehicleResult<T>`)

| Outcome | Meaning | What the app does |
|---|---|---|
| `Success(value)` | The vehicle confirmed | Use the value |
| `Uncertain(error)` | A command: the vehicle may have executed it but it could not be confirmed (`error.mayHaveSucceeded == true`). A query: no data was confirmed; nothing was executed (§`getState` results) | Command: do not resend, query state again (`vehicleStatus()` for VCSEC; `getState()` for Infotainment state). Query: keep the last value and ask again later |
| `Failure(error)` | Not executed | Branch on `error` |

Swift: `switch onEnum(of: result) { case .success(let s): … case .uncertain(let u): … case .failure(let f): … }`.
Full example: `12-lib-getting-started.md`.

Caller cancellation is not converted to a value: `CancellationException` propagates.

## `VehicleError` hierarchy

Every error has `message` (English), `mayHaveSucceeded` and `temporary`. `shouldRetry()` is
`!mayHaveSucceeded && temporary` (Go `ShouldRetry`). The library already retries with this rule (M2), so apps do not
write retry loops. One exception: `KeyStoreError.Locked` is `temporary` but the library never retries it, because only
the user can unlock the phone; the app calls again after the unlock.

| Type | When | temporary | mayHaveSucceeded |
|---|---|---|---|
| `ProtocolFault(fault)` | The vehicle sent a `MessageFault` code (except `UNKNOWN_KEY_ID` → `KeyNotPaired`, `INSUFFICIENT_PRIVILEGES` → `InsufficientPrivileges`) | true only for BUSY, TIMEOUT, INVALID_SIGNATURE, INVALID_TOKEN_OR_COUNTER, INTERNAL, INCORRECT_EPOCH, TIME_EXPIRED, TIME_TO_LIVE_TOO_LONG | true only for NONE, RESPONSE_MTU_EXCEEDED |
| `KeyNotPaired` | `UNKNOWN_KEY_ID`, or session_info `KEY_NOT_ON_WHITELIST` | false | false |
| `InsufficientPrivileges` | `INSUFFICIENT_PRIVILEGES` (fault 7): the key's role or the vehicle state does not allow the command (Driver managing keys, Vehicle Monitor sending control commands, driving, valet) | false | false |
| `Busy` | `operation_status WAIT` | true | false |
| `UnknownFault(rawCode)` | A `MessageFault` code that is not in this library's proto schema (newer firmware). `message` is `unrecognized error code <rawCode>`; check for a library update | false | false |
| `UnknownKeychainCode(rawCode)` | A `whitelistOperationInformation` code that is not in this library's proto schema. `message` is `keychain operation failed: unrecognized code <rawCode>` | false | false |
| `UnknownVcsecError(rawCode)` | A VCSEC `nominalError` code that is not in this library's proto schema. `message` is `vcsec could not execute command: unrecognized error code <rawCode>` | false | false |
| `UnknownResponse` | Unrecognized `session_info.status` or `operation_status` value (no code). Includes session_info status 2 `SESSION_INFO_STATUS_INVALID_HANDLE`, which exists only in the Teslemetry schema (the Go code treats it as unknown) | false | false |
| `NotConnected`, `NoSession`, `RequiresKey` | Call order error | false | false |
| `BadResponse(detail)` | Response could not be parsed | false | true for VCSEC and Infotainment responses |
| `KeychainRejected(code)` | Key add or remove rejected | false | false |
| `VcsecRejected(error)` | VCSEC `nominalError` | false | false |
| `InfotainmentRejected(reason)` | `actionStatus ERROR` | false | false |
| `TransportError.*` | Scan, connect, write (M3) | true only for `ScanTimeout`, `ConnectFailed`, `WriteFailed` | false |
| `KeyStoreError.*` | Key store (M3; `Locked` FR-037, also from `startSession()`) | true only for `Locked` | false |
| `Timeout(afterSend)` | Command or query timed out. `afterSend` is true once the request's first BLE write was started, even if the write never completed (§Retries and timeouts). Always false for the handshake | true | `afterSend` |
| `InvalidArgument(detail)` | Bad call argument; nothing was sent (§Invalid arguments) | false | false |

**Proto schema and error codes (2026-10-18, ADR-0002 revision).** "In the proto schema" means the pinned
Teslemetry/tesla-protocol snapshot (`19-lib-architecture.md`), a superset of the official protos. Codes it defines are no
longer reported as `Unknown*` with a raw code but as the named type, with the same `temporary = false` and
`mayHaveSucceeded = false`. The names of these codes come from the schema and are not confirmed on a real vehicle:

| Code | Was | Now |
|---|---|---|
| `MessageFault` 29 `MESSAGEFAULT_ERROR_COMMAND_REQUIRES_PHYSICAL_PROXIMITY` | `UnknownFault(29)` | `ProtocolFault(fault)`, not retried |
| `whitelistOperationInformation` 29–32 (rider and predelivery key rules) | `UnknownKeychainCode(n)` | `KeychainRejected(code)` |
| `GenericError` 8 `GENERICERROR_KEY_NOT_FOUND`, 9 `GENERICERROR_NOT_SUPPORTED` | `UnknownVcsecError(n)` | `VcsecRejected(error)` |
| session_info status 2 `SESSION_INFO_STATUS_INVALID_HANDLE` | `UnknownResponse` | `UnknownResponse` (the official Go code does not know it either) |

Match on the error type, not on `message`: `message` now carries the code name instead of the number.

## Invalid arguments (`InvalidArgument`)

`InvalidArgument` means the library refused a call argument before sending anything. Nothing reached the vehicle, so
there is nothing to re-query: fix the argument. The library checks only what the Go original checks; other values are sent as given and the
vehicle decides (for example a charge limit or a temperature out of range comes back as `InfotainmentRejected` or
succeeds).

| Call | Rule | `detail` | Checked |
|---|---|---|---|
| `setSeatCooler(level, seat)` | `seat` is `FRONT_LEFT` or `FRONT_RIGHT` | `invalid seat position for seat cooler: <SEAT>` (for example `…: SECOND_ROW_LEFT`) | Before the session check: returned even without a session |
| `scheduleDeparture(departAtMinutes, …)` | `departAtMinutes` is in `0..1440` (both ends allowed) | `invalid departure time: <n> minutes after midnight (expected 0..1440)` | Before the session check |
| `enableValetMode(pin)` | `pin` is exactly four ASCII digits `0`–`9`; other scripts' digits and full-width digits are refused | `PIN codes must be four digits` (the PIN itself is never in an error or a log) | Before the session check |
| `setVolume(volume)` | `volume` is in `0..10` and not NaN | `invalid volume (should be in [0, 10])` | Before the session check |
| `addKey`, `addKeyRequest` (`PairingEvent.Failed`) | `role` is not `KeyRole.UNKNOWN` | `role UNKNOWN cannot be enrolled` | Before sending (`13-lib-pairing.md`) |
| `connect(vin, key, target)` | `target` is an advertisement of `vin` | `advertisement <local name> is not for this VIN` | Before connecting |
| Any request | The framed message is at most 1024 bytes (`14-lib-reading-state.md` A1); a very long schedule `name` could exceed it | `message exceeds 1024 bytes` | By the transport, after the session check |

The Go original returns `invalid seat position` and `invalid departure time` for the first two; the condition and the
point of the check are the same, only the wording is longer here. The PIN and volume texts are Go's own. Go lets a NaN
volume through (both comparisons are false); this library refuses it. Match on the type, not on `detail`: `detail` is
text for people and logs.

## Transport and key store errors (M3)

The library already retries connecting at 1 s intervals inside the `connect` timeout. The last column says what the app
should do.

| Error | Meaning | temporary | App action |
|---|---|---|---|
| `TransportError.ScanTimeout` | No advertisement of the vehicle was found before the `connect` deadline. A `connect` that found the vehicle, or started opening a `target` the library remembers, never ends with this error | true | Check the vehicle is in range, try again |
| `TransportError.MaxConnectionsExceeded` | The vehicle already has 3 BLE connections | false | Disconnect another device, try again |
| `TransportError.ConnectFailed(cause)` | Connect, service discovery or subscription failed, or the `connect` deadline passed after the vehicle was found or while opening a remembered `target` | true | Retry after a moment |
| `TransportError.Disconnected` | BLE link dropped | false | `Vehicle.disconnect()`, then a new `connect` |
| `TransportError.BluetoothOff` | Bluetooth is off | false | Ask the user to turn it on |
| `TransportError.PermissionDenied` | No Bluetooth permission | false | Request permission or open Settings |
| `TransportError.WriteFailed(cause)` | Write failed | true | Retried by the library; reconnect if it persists |
| `KeyStoreError.HardwareUnavailable` | `REQUIRE_HARDWARE` but no hardware key | false | Use `PREFER_HARDWARE` or check the device |
| `KeyStoreError.KeyNotFound` | No key under that alias | false | Create one with `getOrCreate` |
| `KeyStoreError.PlatformFailure(cause)` | Platform key store error. `stored key is corrupted` means the stored key is damaged | false | Corrupted keys are not replaced automatically: `delete` then `getOrCreate`, and **enroll the new key again** (`17-lib-platform-notes.md` §Key storage) |
| `KeyStoreError.Locked` | iOS: the phone is locked and the key is `WHEN_UNLOCKED`, or the phone was not unlocked since restart (`errSecInteractionNotAllowed`, -25308). Returned by `getOrCreate`, `get`, `delete`; creation does not fall back to a software key. Also returned by `startSession()` when the lock blocks a Secure Enclave key object read before the phone locked (issue #96) | true | **Do not delete or re-create the key.** Call again after the phone is unlocked. For use while locked, create a key with `KeyAccessibility.AFTER_FIRST_UNLOCK` under a new alias and enroll it (`13-lib-pairing.md` §Key accessibility). The library retries neither key store calls nor the handshake |

Call `Vehicle.disconnect()` even after the link dropped so session keys and the scope are released.

**Which error a `connect` deadline gives (#140).** The error of the last failed attempt comes first. With no failed attempt:

| Before the deadline | Error |
|---|---|
| A scan run by `connect` delivered the vehicle's advertisement (a `[connect:scan-hit]` observation line, `18-lib-troubleshooting.md`) | `ConnectFailed("vehicle found but the connection did not complete before the deadline")` |
| No scan ran: an open started from a `target` the library remembers from `scan`. That advertisement can be stale, so the `cause` does not say the vehicle was found | `ConnectFailed("connection to the remembered target did not complete before the deadline (no scan ran)")` |
| No advertisement was found and no open started | `ScanTimeout` |

Before #140 the first two rows were also `ScanTimeout`, so this changes the error type an app sees for those cases. A
remembered `target` that is out of range is one of them: it reports `ConnectFailed`, not `ScanTimeout`, because no scan ran
to show the vehicle is gone. `temporary`, `mayHaveSucceeded` and `shouldRetry()` are the same for all three, so a retry or
backoff that does not look at the type behaves as before. Code that matches `ScanTimeout` no longer sees those two cases;
code that matches `ConnectFailed` now does. `cause` is text for people and logs: match on the type, not on `cause`. The Go
original does not separate these cases: it returns the last error, otherwise one generic deadline error (`ctx.Err()`,
`ble.go:270-275`).

## Broadcast events (`Vehicle.events`)

`events` has no error values and never throws. It completes normally when the link drops or `disconnect()` is called
(`14-lib-reading-state.md` B2); the reason for a link loss is in `connection` (`Disconnected(reason)`).

| Situation | What happens | App action |
|---|---|---|
| A collector falls more than 64 frames behind | New frames are dropped for every collector of that connection and counted per connection. `WARN` (tag `Dispatcher`) on the first drop and every 100th: `Dropping plain broadcast because the broadcast buffer is full (dropped N since connect)`. **Not an error:** no `VehicleError`, and the flow keeps running | Keep the collector light and hand slow work to another coroutine. The vehicle resends its status, but that can take 60 s while awake with a person present and was not measured otherwise (Likely, `14-lib-reading-state.md` B3.1). To get the state sooner, read `vehicleStatus()` |
| A broadcast the library cannot decode | `VehicleEvent.Unrecognized` with an empty `unknownFields`; the bytes are in `raw` | Keep `raw` (VIN-masked) for analysis |

## Retries and timeouts (M2)

The library retries with the same rules as Go `vehicle-command`, with two differences, both after a failed write: Go's
BLE connector ends a command on a failed write, while this library treats a failed write as temporary and writes the
same bytes again; and after such a write a command never authorizes a new copy, because the vehicle may already have
run the first one.

| Situation | Library behaviour |
|---|---|
| `error.shouldRetry()`: `BUSY`, `TIMEOUT`, `INVALID_SIGNATURE`, `INVALID_TOKEN_OR_COUNTER`, `INTERNAL`, `INCORRECT_EPOCH`, `TIME_EXPIRED`, `TIME_TO_LIVE_TOO_LONG`, `Busy` (VCSEC `WAIT`) | After 1 s (BLE `retryInterval`) it **authorizes again with a new counter, nonce and expires_at** and resends. Session info attached to the error response is applied first. A command whose write failed does not retry (two rows down); attached session info is still applied |
| Temporary write error (`TransportError.WriteFailed`) | After 1 s it writes the **same bytes** again, without authorizing again, until a write succeeds, a non-temporary error comes or `timeout` runs out. Go's BLE connector does not retry a failed write |
| A **command** whose write was started and failed, so the same bytes went out again, gets a reply that would start a new attempt (any error in the first row) or any protocol-layer rejection: `ProtocolFault` with any code except `RESPONSE_MTU_EXCEEDED` (including `REPEATED_COUNTER`), `KeyNotPaired`, `InsufficientPrivileges`, `UnknownFault` | **No new copy is authorized.** The reply may answer a duplicate of a copy the vehicle already ran (the vehicle rejects a repeated counter with `INVALID_TOKEN_OR_COUNTER`). The library logs a `WARN` (tag `Dispatcher`) with `possible duplicate` and keeps waiting on the same request until a reply settles it: a success (the first copy's late reply, or the repeated copy's own when the first never arrived), an application-level rejection (`VcsecRejected`, `KeychainRejected`, `InfotainmentRejected`) or a `mayHaveSucceeded` error (`RESPONSE_MTU_EXCEEDED`, an unparsable reply) ends the call as usual. With no such reply the result is `Uncertain(Timeout(afterSend = true))` when `timeout` runs out. Commands: every VCSEC command, `addKey()`, `removeKey()`, and every Infotainment command (M5) |
| The same for a **read**: `vehicleStatus()`, `keySummary()`, `listKeys()`, `getState()` | Retried as in the first row (a new request or a new copy). A read changes nothing on the vehicle, so running it twice is harmless |
| `mayHaveSucceeded` errors (timeout after the request's write was started, `RESPONSE_MTU_EXCEEDED`, unparsable VCSEC or Infotainment response) | **Never resent automatically** (prevents running a toggle twice). Returned as `Uncertain`; query state again |
| Timeout (`timeout`, default 5 s) before the request's first BLE write was started: waiting for the previous VCSEC command, storing the session cache's counter reservation (ADR-0016), waiting for the link to be free (another request is being written), or a check before writing (`listKeys()` with no time left) | `Failure(Timeout(afterSend = false))` |
| Timeout while waiting to retry after a reply that asked for a retry (busy, `WAIT`, expired, …) | `Failure(Timeout(afterSend = false))` |
| Timeout after the request's first BLE write was started: the write had not completed, it failed and the library was resending, or the response did not come | `Uncertain(Timeout(afterSend = true))` |
| Handshake timeout (`handshakeTimeout`, default 20 s) | `Failure(Timeout(afterSend = false))`; no session, no side effect |
| Handshake blocked by the phone lock (iOS, FR-037) | `Failure(KeyStoreError.Locked)` right after the vehicle's reply, **not retried** although `temporary`; no session. A cached session the lock kept from being restored stays in the cache |
| VCSEC commands | One at a time per connection. Time spent waiting for the previous one counts against `timeout` |
| Caller coroutine cancelled | `CancellationException` propagates (not turned into a value) |

Defaults come from `TeslaBleConfig`: `commandTimeout` 5 s, `commandLifetime` 5 s, `handshakeTimeout` 20 s,
`connectTimeout` 30 s, `pairingTimeout` 60 s (`addKeyRequest`).

**"Sent" means the write was started (ADR-0010, revised 2026-11-07).** A command or query counts as possibly sent from
the moment its first BLE write is invoked, not when the phone confirms the write (`addKeyRequest` reports its own
events, `13-lib-pairing.md`). A failed write does not undo this: the library
resends the same bytes after a failed write, and a timeout during that resend is still `Uncertain`. Before this change a
timeout that landed inside the write was `Failure(Timeout(afterSend = false))` ("timed out before sending") although the
vehicle may have had the request. This was seen once on a real car (iOS app in the background, a `listKeys()` request):
the result came 9.65 s after the start, with the default 5 s, and the vehicle's reply to that request 9 ms later. The Go
original never meets this case, because its BLE write ignores the deadline. For an app nothing else changes:
`Failure(Timeout)` means not executed, `Uncertain(Timeout)` means possibly executed, so read the state before sending a
command again. Expect fewer `Failure(Timeout)` and more `Uncertain(Timeout)` results when the phone is slow to complete
writes, for example while the app is in the background.

**No new copy after a failed write (ADR-0010, revised 2026-11-08).** A write that reports failure may still have reached
the vehicle. If the vehicle ran that first copy and its reply was lost, the copy the library writes again carries the
same counter, and the vehicle refuses it as a replay (`INVALID_TOKEN_OR_COUNTER`). Before this change the library took
that refusal as a reason to retry, authorized a new copy with a new counter, and the vehicle could run the command
twice: a toggle such as `openTrunk()` would end where it started. Now a command whose write failed sends no new copy; it
keeps listening on the same request until a reply settles the call (usually the first copy's late reply) and otherwise
returns `Uncertain(Timeout(afterSend = true))` when `timeout` runs out.
The app does what it does for any `Uncertain`: read the state, do not resend (§Per-command re-query). For the same
reason a protocol-layer rejection such as `KeyNotPaired` or `InsufficientPrivileges` is not reported as `Failure` on
this path: it may answer the duplicate (removing your own key, for example, makes the repeated copy's key unknown).
Without a failed write nothing changes: when the vehicle answers with a fault and fresh session info, for example on the
first request with a cached session that is out of step, the library applies it and retries as in the first row. The Go
original never gets here, because its BLE connector ends the command on the failed write.

**Counter reservation in the session cache (ADR-0016, 2026-11-14).** Before an authenticated request uses an
anti-replay counter the session cache does not hold yet, the library writes a reservation (16 counters ahead) to the
cache and only then authorizes and writes the request. This happens before the first authenticated request of a
connection and then once per 16 requests; reads and commands are treated alike, unauthenticated requests never write.
For an app it changes three things:

- A process that ended without `disconnect()` no longer makes the next process's first request draw
  `INVALID_TOKEN_OR_COUNTER` and a resend about 1.2 s later (`18-lib-troubleshooting.md` lists what can still cause it).
- The cache write counts against the call's `timeout`. A timeout during it is `Failure(Timeout(afterSend = false))`:
  nothing was sent and no counter was used. If the cache throws, the request is still sent and a `WARN` is logged. The
  built-in caches never throw: they log a failed write (`WARN`, tag `cache`) and the library counts it as stored for the
  next 16 requests. If the connection is closing, the call returns `Failure(NotConnected)` without sending.
- A session that has used the last counter (`0xFFFFFFFF`, not expected in practice) returns `Failure(NoSession)` without
  sending; call `startSession()` and send again. Go retries that local rollover until the deadline.

## Per-command re-query (FR-102)

After `Uncertain`, never resend. Read the state the command changes:

| Command | Re-query with | What to look at |
|---|---|---|
| `lock`, `unlock`, `wakeUp`, `remoteDrive`, `autoSecureVehicle`, `openTrunk`, `actuateTrunk`, `closeTrunk`, `openFrunk`, `openTonneau`, `closeTonneau`, `stopTonneau` | `vehicleStatus()` | Lock state, closure states, sleep status. `openTrunk` / `actuateTrunk` toggle: compare the closure state before deciding to send again |
| `addKey`, `removeKey` | `listKeys()` | Whether the public key is in the whitelist |
| `addKeyRequest` (`TimedOut`, or a `Flow` that ended without a terminal reply) | `sessionInfo(publicKey, VehicleDomain.INFOTAINMENT)` | `Success` = enrolled; `Failure(KeyNotPaired)` = not (yet) |
| Climate: `climateOn`, `climateOff`, `setClimateTemp`, `setSeatHeater`, `setSeatCooler`, `setSteeringWheelHeater`, `autoSeatAndClimate`, `setPreconditioningMax`, `setClimateKeeperMode`, `setBioweaponDefenseMode`, `setCabinOverheatProtection`, `setCabinOverheatProtectionTemperature` | `getState(StateCategory.CLIMATE)` | The matching `climate_state` field (`15-lib-commands.md` §Re-query) |
| Charging: `chargeStart`, `chargeStop`, `setChargeLimit`, `setChargingAmps`, `chargeStandardRange`, `chargeMaxRange`, `scheduleCharging`, `scheduleDeparture`, `clearScheduledDeparture`, `openChargePort`, `closeChargePort` | `getState(StateCategory.CHARGE)` | The matching `charge_state` field |
| `addChargeSchedule`, `removeChargeSchedule`, `batchRemoveChargeSchedules` | `getState(StateCategory.CHARGE_SCHEDULE)` | `charge_schedule_state.charge_schedules`: whether the `id` is there |
| `addPreconditionSchedule`, `removePreconditionSchedule`, `batchRemovePreconditionSchedules` | `getState(StateCategory.PRECONDITIONING_SCHEDULE)` | `preconditioning_schedule_state.precondition_schedules` |
| Body and security: `ventWindows`, `closeWindows`, `setSunroof`, `setSentryMode`, `enableValetMode`, `disableValetMode`, `resetValetPin` | `getState(StateCategory.CLOSURES)` | The window, sunroof, sentry or valet field (`15-lib-commands.md` §Re-query) |
| Media: `volumeUp`, `volumeDown`, `setVolume`, `mediaNextTrack`, `mediaPreviousTrack`, `mediaNextFavorite`, `mediaPreviousFavorite`, `toggleMediaPlayback` | `getState(StateCategory.MEDIA)` | `audio_volume`, `now_playing_title`, `media_playback_status`. **Never resend `toggleMediaPlayback`**: run twice, it ends where it started |
| `scheduleSoftwareUpdate`, `cancelSoftwareUpdate` | `getState(StateCategory.SOFTWARE_UPDATE)` | `status`, `scheduled_time_ms` |
| `setLowPowerMode`, `setKeepAccessoryPowerMode`, `honkHorn`, `flashLights` | None | The official `VehicleData` has no field for these. Show the result as unknown and do not resend |
| `ping` | None | It changed nothing; the app may call it again |

For Infotainment the read can itself be `Uncertain` (Infotainment asleep, reply too large, §`getState` results): the
command's result then stays unknown until a later read succeeds. Which field a command changes on a real vehicle is not
confirmed yet (#118).

## Queries while another BLE client is active

Status: **Likely**, from real-car logs of one vehicle (`14-lib-reading-state.md` B3.5, B5). It was not reproduced in a
controlled scenario, and the mechanism is unknown.

While another BLE client was using the vehicle, its Infotainment replies reached our link at about one frame per second and
queries could get no reply at all:

| Session | Other client's Infotainment frames | `listKeys()` | `vehicleStatus()`, `keySummary()` |
|---|---:|---:|---:|
| With that traffic, 53 polls | 1690 | 6 `Success`, 47 `Uncertain(Timeout(afterSend = true))` | 106 of 106 `Success` |
| Without it, 37 polls | 0 | 37 of 37 `Success` | 74 of 74 `Success` |

- This is the ordinary lost-reply result: the request was sent and nothing came back. No late reply arrived after the
  timeout either, so a longer `timeout` is not expected to help.
- `listKeys()` is the most exposed because it sends requests one after another, the summary plus one per occupied slot
  (eight requests in about 0.8 s with seven keys). Requests sent under 0.55 s after the other client's latest frame were all
  answered, and 45 of the 47 lost ones were sent 0.55 to 0.85 s after it. Send times are inferred from the previous reply,
  because the log has no transmit lines.
- `vehicleStatus()` and `keySummary()` are not immune: earlier recordings with such traffic have 4 `Uncertain(Timeout)`
  results on them. Their causes are not established; one came 1.6 s before a link loss.
- The link stayed up: the next poll's `vehicleStatus()` succeeded every time.
- No command was sent during this traffic, so whether a command stalls the same way is unknown.

| App action | Why |
|---|---|
| Keep the last value with its age and ask again later | The same rule as any `Uncertain` on a query. It can keep failing while the other client stays active (only 6 of 53 succeeded above), so do not retry in a tight loop |
| Do not read a timed-out `listKeys()` as a key change | No data was confirmed. Only a `Success` shows the key list |
| Do not reconnect or clear the session because of it | The link is up. Check `connection` as usual |
| After `Uncertain` on a command, read the state and do not resend | §Per-command re-query. That rule does not change here |

## `getState` results (FR-033, #115, #116)

A query changes nothing on the vehicle. So **`Uncertain` on a read means no data was confirmed**, not that a command may
have run. `mayHaveSucceeded` is still true, because reads and commands share the error types. There is nothing to check
afterwards: keep the last value and ask again later. Asking again is safe, but not in a tight loop: the library sets no
interval, so it is the app's choice.

| Result | Meaning | App action |
|---|---|---|
| `Success(data)` | The vehicle answered | Check every field you use for `null`. `data` can be empty, and a success is not proof of a new measurement: compare `timestamp` with the previous reply (`14-lib-reading-state.md` A8.3) |
| `Uncertain(ProtocolFault(RESPONSE_MTU_EXCEEDED))` | The vehicle received the request but the reply was too large. The vehicle is reachable | Keep the last value with its age; see the next section |
| `Uncertain(Timeout(afterSend = true))` | Nothing came back within `timeout` after the request's write was started: Infotainment is asleep, the reply was lost, the link dropped while waiting, or the phone did not complete the write in time (app in the background) | Read `vehicleStatus()`. `ASLEEP`: the app may wake the vehicle (`14-lib-reading-state.md` A8.2). Otherwise keep the last value with its age. Check `connection` too |
| `Uncertain(BadResponse(detail))` | A reply arrived but could not be parsed | Keep the last value. Report `detail` if it repeats |
| `Failure(Timeout(afterSend = false))` | Time ran out before the request's write was started or while waiting to retry, for example when the vehicle keeps answering busy while it wakes | Ask again later, with a longer `timeout` right after a wake |
| `Failure(NoSession)` | No `INFOTAINMENT` session (nothing was sent), or the vehicle did not accept the cached one | `startSession(setOf(VehicleDomain.INFOTAINMENT))`, then ask again once |
| `Failure(InfotainmentRejected(reason))` | The vehicle refused (`actionStatus ERROR`). This wins over any data in the reply | Show a generic message. `reason` is the vehicle's own text; do not log it |
| `Failure(NotConnected)`, `TransportError.*` | The link is gone or the write failed | `disconnect()`, then a new `connect` (§Transport and key store errors) |

Any other error means what the `VehicleError` table above says. The UI tells the two main `Uncertain` cases apart by
error type: a size fault means the vehicle answered, a timeout means nothing came back. Neither is a reason to clear the
session.

## Response size limit on `getState` (FR-033, #116)

The vehicle returns no data when its reply exceeds a size limit. The largest reply seen on real vehicles is about
453 bytes; this is an observed value, not a specified one. `getState()` returns
`Uncertain(ProtocolFault(RESPONSE_MTU_EXCEEDED))`, and the client cannot make the reply smaller.

- It is most likely on `Drive`: a long destination name raises the rate, with reports of failures lasting tens of
  seconds, and of one Model S/X build (`2026.27.6.15`, South Korea) failing almost always even with no route.
- The library does not resend the request. It does not clear the session, reconnect or wake the vehicle because of this
  fault; session info attached to the reply is still applied as usual. The fault does not mean the vehicle is gone or
  asleep.
- Apps keep the last value with its age, show "unavailable" when it is too old, and do not retry in a tight loop.
- Real-time speed is not guaranteed. Behaviour per firmware is not confirmed on a real vehicle (#118).
- **Real-car evidence (Likely, not from our own `getState`):** in one session replies to another BLE client reached our link
  (`14-lib-reading-state.md` B3.5), and 132 of its 1690 Infotainment frames carried `MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED`.
  They were 106-byte frames with no payload and the fault in plaintext `signedMessageStatus`. All 132 fell within about 11
  minutes of one connection, in runs of one or two, and most runs were followed at once by a `session_info` reply; the next
  connection had none. The largest frame in that session was 453 bytes (350 bytes of ciphertext). What the client asked for
  is unknown: its requests are not visible and the payloads are encrypted.

## Reply authenticity

Like the Go original, the library accepts a plaintext reply, because firmware before 2024.38 does not encrypt
responses (`vehicle-command/pkg/protocol/protocol.md:751`). A plaintext reply carries no authentication tag, so a party on
the BLE link could forge read data. **Do not base safety decisions on `getState`.** The same goes for `vehicleStatus()`
and `vehicle.events`, which are unauthenticated plaintext by design (`14-lib-reading-state.md` A1, B1). Command replies
are not exempt: the plaintext path has no domain check, so a forged plaintext VCSEC reply can make a command such as
`lock()` return `Success` even if the vehicle never ran it. Planned (not implemented yet, ADR-0015): authenticated commands
will need a verified encrypted reply to succeed, and firmware before 2024.38 will no longer be supported.


---

# Platform Notes (Android / iOS)

Scope: M3, plus the Swift form of `getState` (FR-033) and of the Infotainment commands (M5), and the iOS Swift package (M6 stage 1, ADR-0017). Items not yet confirmed on a real device are marked so. Go original BLE behaviour: `02-ble-transport.md`.

## Support

- Android 12 (API 31) or later, iOS 16 or later. The app is assumed to be in the **foreground**; background BLE is
  out of scope.
- The JVM target is for tests and experiments. Its key store is in memory, so keys **disappear when the process ends**.

## Android

- Permissions: declare `BLUETOOTH_SCAN` (with `neverForLocation`) and `BLUETOOTH_CONNECT`, and **request them at
  runtime**. Scanning or connecting without them returns `TransportError.PermissionDenied`. `INTERNET` is not needed
  (no server calls).

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

- `PlatformContext(context)` keeps the application context.
- On connect the library requests `requestMtu(517)`; if that fails it continues with a block length of 20.
- Key store aliases are namespaced in Android Keystore: `teslable.key:<alias>` (the key) and `teslable.wrap:<alias>`
  (the AES key that wraps a software fallback key). They do not collide with the app's other Keystore aliases.
- Auto Backup: a software fallback key is stored in `filesDir/teslable/keys/<alias>.enc`. If Auto Backup restores this
  file without the Keystore wrapping key (`teslable.wrap:…`), the restored key reads as **corrupted** (§Key storage).
  Exclude `files/teslable/keys/` in `dataExtractionRules` (Android 12+) or `fullBackupContent`. The sample app uses the
  simplest option, `android:allowBackup="false"`.
- The session cache lives under `filesDir/teslable/sessions`. Losing it is harmless (the next connection handshakes
  again), but excluding it from backup the same way is cleaner.

### Android background use by the sample

The library supports use in the foreground (see Support). The Android sample's monitor app keeps its connection in the
background with a **foreground service** and has not been checked on a real device yet, so treat the library behaviour in
the background as unconfirmed:

- Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` (asked at runtime) and
  `<service android:foregroundServiceType="connectedDevice" />`. Still no `INTERNET`.
- Android 14 and later: a `connectedDevice` foreground service can only start after `BLUETOOTH_CONNECT` is granted, and
  only while the app is visible (or another start exemption applies). Do not rely on the system to restart it.
- The service owns the single `TeslaBle` and calls `vehicle.disconnect()` after every link loss, then connects again with
  a new `Vehicle` (2 s wait, doubling to 30 s). It is the app's job, not the library's, to retry.
- Key store: the sample creates its key with `KeyAccessibility.AFTER_FIRST_UNLOCK`; Android ignores the argument and
  reports `AFTER_FIRST_UNLOCK` (per API, not yet confirmed on a device, see Key storage).

## iOS

- `Info.plist` needs `NSBluetoothAlwaysUsageDescription`. **The first scan shows the permission prompt.** If the user
  denies it, calls return `TransportError.PermissionDenied`.
- `PlatformContext()` takes no arguments.
- Swift package (`11-lib-overview.md` §Installation): a **static** `Teslable.xcframework` with exactly two slices,
  `ios-arm64` (devices) and `ios-arm64-simulator` (simulator, arm64 only), minimum iOS 16.0. Being static, the library
  code is linked into the app binary.
- Embedded stub: Xcode embeds the package's framework in the app as `YourApp.app/Frameworks/Teslable.framework` (a
  Swift package needs no setting for this; it is what Embed & Sign does for a framework added by hand). For a static
  framework Xcode removes the static library from the embedded copy and injects an empty stub binary (about 33 KB) so
  the bundle stays valid for the App Store. This is Xcode's intended behaviour, the same for any static binary package:
  the code is linked into the app binary, and the app never loads the stub. Do not remove it. App Store acceptance is
  expected but **not yet verified** by this project (no upload made). Checked with Xcode 27.2 (2026-11-14).
- Crash reports: no separate dSYM ships with the package. The Kotlin debug info is inside the static library, and the
  app's Release build copies it into the app's dSYM (`YourApp.app.dSYM`), so Kotlin frames symbolicate with file and
  line (inlined frames included) from the app's dSYM alone. Upload the app's dSYM as usual. Checked with a Release
  archive of the release check's consumer app (Xcode 27.2).
- **Simulator: Apple Silicon Mac only.** There is no `x86_64` simulator slice, so an Intel Mac can build for a device
  but cannot run the library in the simulator. A simulator build that also builds `x86_64` fails to link
  (`ld: symbol(s) not found for architecture x86_64`, after `ignoring file … found architecture 'arm64', required
  architecture 'x86_64'` for the `Teslable` members). A Release build for the simulator builds every architecture, so set
  `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64` on the app target. Debug builds that build only the active
  architecture (the Xcode default) are not affected on an Apple Silicon Mac. Checked with Xcode 27.2 (2026-11-14).
- The response characteristic (0213) supports both notify and indicate; the Kable default subscription (notify) was
  confirmed on a real vehicle (ADR-0003 spike, iOS 27.0.1, Model Y). iOS has no MTU negotiation API; the library uses
  the write length the system chooses (observed block length: 512).
- The simulator has no Bluetooth. Scan and connect can only be checked on a real iPhone. On the simulator `scan(vin)`
  emits one `ScanEvent.Failed` at once whose error is `TransportError.ConnectFailed` with the `cause`
  `IllegalStateException: This device doesn't support the Bluetooth low energy central or client role` (observed with
  Xcode 27.2), not `BluetoothOff` or `PermissionDenied`. `connect` without a `target` scans the same way and returns
  that `Failure` at once (it does not retry a failed scan). The `cause` text is for people and logs; do not branch on it.
  The key store works on the simulator in a signed app (§Key storage).
- Swift interop is generated by SKIE: `suspend` → `async throws`, `Flow` → `AsyncSequence`, sealed types →
  `onEnum(of:)`. Companion members are reached through `.companion` (for example `TeslaBle.companion.create`).
- Name clash: the public `VehicleStatus` value object is exported to Swift as `VehicleStatus_`, because a generated
  VCSEC message type takes the plain name. `vehicleStatus()` results and `VehicleEvent.Status.status` are `VehicleStatus_`.

### iOS background use by the sample

The library supports use in the foreground (see Support). The iOS sample's monitor app declares
`UIBackgroundModes: [bluetooth-central]` and runs the same reconnect loop as the Android sample, and has **not** been
checked on a real iPhone yet, so treat the library behaviour in the background as unconfirmed:

- `Info.plist` (through `project.yml`): `UIBackgroundModes: [bluetooth-central]`; `UIFileSharingEnabled` and
  `LSSupportsOpeningDocumentsInPlace` only for the sample's log folder.
- The app owns the single `TeslaBle` for its lifetime and calls `vehicle.disconnect()` after every link loss, then connects
  again with a new `Vehicle` (2 s wait, doubling to 30 s). It is the app's job, not the library's, to retry.
- **Unconfirmed:** whether iOS lets the app scan and reconnect while it is in the background or the phone is locked
  (the library currently scans by device name and iOS usually needs a service UUID filter for background scans; plan Tasks 2 and 8). Until the real-car
  spike runs, the only supported background case is a link that stays up: events keep arriving while iOS keeps the app
  running.
- Key store: the sample creates its key with `KeyAccessibility.AFTER_FIRST_UNLOCK`. While the phone is locked and the key
  store answers `KeyStoreError.Locked`, the sample waits, retries, and never deletes the key.

## Key storage

| `VehicleKey.protection` | Meaning |
|---|---|
| `SECURE_ENCLAVE` | iOS Secure Enclave. The private key never leaves the chip |
| `STRONGBOX` | Android StrongBox security chip |
| `TEE` | Android Trusted Execution Environment |
| `SOFTWARE` | Software key: emulators, simulators, or when every hardware attempt failed |

- Software key format: the private key is serialized as `04‖X‖Y‖d` (97 bytes). Android stores it as a `TBK1` file
  wrapped by a Keystore AES key; iOS stores it as a Keychain generic password whose `kSecAttrAccessible` is the chosen
  accessibility (always `ThisDeviceOnly`).
- Key accessibility (FR-037, ADR-0014; API and procedure in `13-lib-pairing.md` §Key accessibility):

| Platform | `getOrCreate(alias, policy, accessibility)` | `VehicleKey.accessibility` of an existing key |
|---|---|---|
| iOS | `WHEN_UNLOCKED` (default) → `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, `AFTER_FIRST_UNLOCK` → `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. Secure Enclave key: the class of its access control (`SecAccessControlCreateWithFlags(<class>, kSecAccessControlPrivateKeyUsage)`); software key: the Keychain item's class | Read back from the item's `kSecAttrAccessible`. AfterFirstUnlock classes → `AFTER_FIRST_UNLOCK`; WhenUnlocked and WhenPasscodeSet classes → `WHEN_UNLOCKED`; not reported → `WHEN_UNLOCKED` and a `WARN` (tag `keystore`). Never changed afterwards (no migration) |
| Android | Ignored | Always `AFTER_FIRST_UNLOCK`: the `KeyGenParameterSpec` of the EC key and of the AES key that wraps a software key set neither `setUnlockedDeviceRequired` nor `setUserAuthenticationRequired`, and `filesDir` is readable after the first unlock. Per API; **not yet confirmed on a device** |
| JVM | Ignored | Always `AFTER_FIRST_UNLOCK` (in-memory keys have no lock) |

- Locked iPhone: `errSecInteractionNotAllowed` (-25308) from a Keychain lookup, read, write or delete, or from Secure
  Enclave key creation, becomes `KeyStoreError.Locked`. A `WHEN_UNLOCKED` key cannot be read or used for a handshake
  while the phone is locked; an `AFTER_FIRST_UNLOCK` key can, once the phone was unlocked after the last restart. Before
  that first unlock every key is `Locked`. A key creation that fails with `Locked` does not fall back to a software key.
- Locked iPhone, key object read before the lock (issue #96): when `SecKeyCopyKeyExchangeResult` fails with a `CFError`
  in the OSStatus domain with code -25308 (the same narrow rule), the handshake ends with
  `Failure(KeyStoreError.Locked)` right after the vehicle's reply and is not retried; a cached session that needed the
  same ECDH is not restored and stays in the cache (`13-lib-pairing.md` §Key accessibility). Other ECDH errors keep
  their old path (handshake retried until `handshakeTimeout`). A software fallback key object holds its private key in
  memory and keeps working after the lock (per API).
- Not yet confirmed on a device (sample app build 12 is ready; checks are plan Task 11): the Keychain round trip of both classes, the class
  read-back, and a handshake on a locked iPhone with an `AFTER_FIRST_UNLOCK` Secure Enclave key; also which error a
  locked iPhone reports for ECDH with a `WHEN_UNLOCKED` Secure Enclave key (the library expects OSStatus -25308; if iOS
  reports another error, `startSession()` still ends with `Timeout`). Using the key while the phone is locked also
  needs the app to keep running in the background; background BLE is not documented yet (see §Support).
- **A corrupted stored key is never replaced automatically.** When `getOrCreate` or `get` returns
  `KeyStoreError.PlatformFailure` (`stored key is corrupted`), the app tells the user, then calls `keys.delete(alias)`
  and `keys.getOrCreate(alias)`. The new key has a different public key, so it **must be enrolled on the vehicle
  again**. Silent replacement is avoided because the user would lose vehicle control without knowing why.
- On iOS, a failed Secure Enclave lookup (`Failed`) is not "no key". The library returns an error instead of
  overwriting the alias with a new key.
- There is no private key export API (ADR-0008).
- Key handles need no `close()` (issue #97). On iOS each key object owns one `SecKeyRef` (for the software fallback, the `SoftwareVehicleKey` delegate owns it); a Kotlin/Native
  `Cleaner` releases it exactly once after the object is garbage collected, never while the app or a `Vehicle` still
  references the key. Reading the key again does not accumulate handles. Android and JVM keys are ordinary
  garbage-collected objects. Rules: `13-lib-pairing.md` §Key store API (handle lifetime).
- Simulator Keychain: **plain Kotlin/Native test binaries** running on the simulator cannot use the Keychain
  (`SecItemAdd` fails with -25291). Real Keychain round trips, including the accessibility read-back, are therefore
  checked in the **sample app** (an app bundle), not in unit tests. Unit tests cover the class mapping, the parsing of
  query results and the key store's decisions with fakes.
- Simulator Keychain needs a **code-signed app**. Ad-hoc signing is enough (`CODE_SIGN_IDENTITY = -`, shown in Xcode
  as "Sign to Run Locally"). An unsigned build (`CODE_SIGNING_ALLOWED = NO`) has no Keychain entitlement: the key store
  returns `Failure(KeyStoreError.PlatformFailure("keychain status -34018"))` (`errSecMissingEntitlement`). Seen in the
  release consumer check (`tools/release/check-consumer-ios.sh` signs its simulator test ad-hoc for this reason).

## Session cache

- Location: Android `filesDir/teslable/sessions/`, iOS Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`),
  JVM in memory. Override with `TeslaBleConfig.sessionCache`. File and item names are hashes of the VIN; the raw VIN is
  not stored.
- The cache belongs to the client key. **After a key change the next connection discards the cache** and handshakes
  again. A cache made stale by the vehicle (for example a vehicle-side key change) is also deleted automatically.
- A missing or stale cache is not an error; the library just starts a new session.
- A cached session that could not be restored because the phone lock blocked the key (iOS) is not stale: the library
  writes nothing to the cache until that domain has a session again, so a disconnect while locked keeps the cache.
  Exception: if another restored session turned out stale in the same connection, the cache is rewritten without the
  stale entry (and without the blocked one), and the rest of that connection stores as usual.
- **The app process may end without `disconnect()`** (force quit, the system ending a suspended app, a crash); on iOS
  this is the usual way a process ends. The library writes the cached anti-replay counter before it uses it (a
  reservation 16 counters ahead, ADR-0016), so the first authenticated request of the next process is accepted at once,
  without a handshake and without a refusal. Before this change (builds up to 2026-11-14) that request was refused once
  with `INVALID_TOKEN_OR_COUNTER` and fresh session info, and the library applied it and resent it, which added about
  1.2 s; the result was still `Success` and the refused copy did not run. A cache entry written by such an older build
  can still cause that once after the upgrade.
- The reservation costs one cache write (an app file write on Android, a Keychain write on iOS) before the first
  authenticated request of each connection and then one per 16 requests. It counts against that call's `timeout`; a
  timeout there is `Failure(Timeout(afterSend = false))` because nothing was sent.
- The guarantee assumes the cache write succeeds and that the app holds **one live connection per vehicle and key**. If
  a write fails, the library logs it, sends the request anyway and the old behaviour can return once. Two connections
  to the same vehicle with the same key, or another app using the same key, are not protected. This includes a dropped
  link: call `disconnect()` on the old `Vehicle` and let it return before you `connect` again, because its final cache
  write could otherwise land after the new connection's reservation and lower it.

## Clocks

- The session clock runs on the monotonic clock (`TimeSource.Monotonic`). While a device **sleeps**, the monotonic
  clock stops on some platforms, so the vehicle clock estimate of a session restored from cache can lag. The first
  command may then fail with `TIME_EXPIRED` or similar; the library adopts the session info attached to the response
  and retries.
- The cache's `age` (time since it was stored) uses the wall clock.

## Connection slots and dropped links

- The vehicle accepts **at most 3** BLE connections. Call `disconnect()` when you are done to give the slot back. When
  all slots are taken, `connect` returns `TransportError.MaxConnectionsExceeded`.
- When the link drops and `Vehicle.connection` becomes `Disconnected`, **the app must still call
  `Vehicle.disconnect()`** so session keys are wiped and the scope is released. `disconnect()` is safe to call more than
  once.
- A disconnected `Vehicle` cannot be reused. Connect again with `TeslaBle.connect`.

## Swift: timeouts

- Kotlin `kotlin.time.Duration` is an inline value class. Swift sees it only as a boxed `Any?` (`id`), so Swift code
  cannot build one directly.
- `Timeouts.seconds(seconds: Double): Duration?` (Swift: `Timeouts.shared.seconds(seconds: 10)`) returns the boxed
  value to pass as any `timeout:` argument. It throws `IllegalArgumentException` for zero, negative, NaN or infinite
  input. `Timeouts.millisecondsOf(timeout:)` returns the milliseconds of a boxed value (`-1` for `nil`); it exists to
  check the round trip. The iOS sample's XCTest target (`samples/ios/Tests/TimeoutsTests.swift`) runs in
  `tools/ci/verify.sh` step (d).
- `TeslaBleConfig.withSeconds(logger:commandTimeoutSeconds:handshakeTimeoutSeconds:connectTimeoutSeconds:pairingTimeoutSeconds:)`
  (Swift: `TeslaBleConfig.companion.withSeconds(...)`) builds a config from seconds. All arguments are required;
  `commandLifetime` keeps its default. `TeslaBleConfig.companion.default(logger:)` is unchanged.
- `timeout: nil` still means "use the config default".

## Swift: generated types (`getState`)

`getState` returns the Wire generated `VehicleData`, which behaves differently in Swift from the hand-written models.
Full example: `14-lib-reading-state.md` A8.3. These forms pass a Swift typecheck against the framework; they have not run
in a Swift program.

- `vehicle` is the `Vehicle` protocol. Pass every argument: `vehicle.getState(category: .drive, timeout: nil)`, or
  `timeout: Timeouts.shared.seconds(seconds: 8)`. Without `timeout:` the compiler reports
  `missing argument for parameter 'timeout' in call`.
- The generic of `VehicleResult` is erased, so `s.value` is `AnyObject?`. Cast it: `s.value as? VehicleData`.
- Nullable numbers bridge as `KotlinInt?` and `KotlinFloat?`, not `Int32?` and `Float?`. Read `.int32Value` and
  `.floatValue`. `let speed: Int32? = data.drive_state?.speed` is rejected, and the compiler says only
  `failed to produce diagnostic for expression`, so that message will not help.
- Generated fields keep their proto names in Swift (`drive_state`, `speed_float`, `charge_state`), unlike the camelCase
  hand-written models.
- `timestamp` is a Wire `Instant`: `charge.timestamp?.getEpochSecond()` gives `Int64?`.
- `shift_state` is not an enum. `ShiftState` is a Wire oneof message: its members `P`, `R`, `N`, `D`, `SNA` and `CarServer_Invalid` are nullable, and the one that is not `nil` is the gear (`data.drive_state?.shift_state?.P != nil`). If none is set, the vehicle sent no gear. Do not compare it with a string or an enum value.
- Every other state oneof with `Void` members works the same way: `charge_state?.charging_state`, `charge_port_latch`, `closures_state?.sun_roof_state`, `sentry_mode_state`, `software_update_state?.status`, `location_state?.native_type` (`GCJ` or `WGS`) and `climate_state?.climate_keeper_mode`. The member that is not `nil` is the value (`charging_state?.Charging != nil`). A message that arrives with no member set reads the same as an absent one, so treat both as "no value". Fields of nested messages such as `ParentalControlsSettings` are `KotlinBoolean?` / `KotlinFloat?` / `KotlinInt?` like the top-level ones, while `ChargeSchedule` and `PreconditionSchedule` entries in the repeated lists have plain values (`enabled`, `one_time`), so an absent flag there reads as `false`.
- The type names are plain: `VehicleData`, `DriveState`, `StateCategory`. Only `VehicleStatus_` has the name clash.
- `StateCategory` is a real Swift enum, so a `switch` over all 12 needs no `default`: `.charge`, `.climate`, `.drive`,
  `.location`, `.closures`, `.chargeSchedule`, `.preconditioningSchedule`, `.tirePressure`, `.media`, `.mediaDetail`,
  `.softwareUpdate`, `.parentalControls`.
- The enum in `.protocolFault` is `MessageFault_E`. The size limit case is `.messagefaultErrorResponseMtuExceeded`:
  `case .protocolFault(let p) where p.fault == .messagefaultErrorResponseMtuExceeded:`.

## Swift: command arguments (Infotainment commands)

The Infotainment commands (`15-lib-commands.md` §Implemented: Infotainment) as Swift sees them. These forms pass a
Swift typecheck against the framework (debug simulator framework, 2026-11-08); they have not run in a Swift program.

- Pass every argument, including `timeout:` (`nil` for the config default):
  `try await vehicle.climateOn(timeout: nil)`. Without it the compiler reports
  `missing argument for parameter 'timeout' in call`.
- The parameter enums are real Swift enums, so a `switch` over them needs no `default`:
  `SeatPosition` (`.unknown`, `.frontLeft`, `.frontRight`, `.secondRowLeft`, `.secondRowLeftBack`, `.secondRowCenter`,
  `.secondRowRight`, `.secondRowRightBack`, `.thirdRowLeft`, `.thirdRowRight`), `SeatClimateLevel` (`.off`, `.low`,
  `.med`, `.high`), `ClimateKeeperMode` (`.off`, `.on`, `.dog`, `.camp`), `CabinOverheatProtectionTemperature`
  (`.unspecified`, `.low`, `.medium`, `.high`), `ChargingPolicy` (`.off`, `.allDays`, `.weekdays`).
- `setSeatHeater(levels:timeout:)` takes a Swift dictionary `[SeatPosition: SeatClimateLevel]`; a literal works:
  `try await vehicle.setSeatHeater(levels: [.frontLeft: .high, .secondRowCenter: .off], timeout: nil)`. An empty `[:]`
  is sent too. Swift dictionaries have no order; the library sends the seats in `SeatPosition` declaration order anyway.
- `autoSeatAndClimate(seats:enabled:timeout:)` takes a Swift array: `seats: [.frontLeft, .frontRight]`.
- Numbers: `percent`, `amps`, the minutes, the sunroof `level` and `delaySeconds` are `Int32`, schedule ids `Int64`,
  temperatures and `volume` `Float`; integer and decimal literals work. `scheduleDeparture(departAtMinutes: 480,
  offPeakEndMinutes: 360, preconditioning: .allDays, offPeak: .weekdays, timeout: nil)`,
  `setVolume(volume: 5.5, timeout: nil)`, `setSunroof(level: 0, timeout: nil)`,
  `scheduleSoftwareUpdate(delaySeconds: 7200, timeout: nil)`.
- The valet PIN is a Swift `String`: `enableValetMode(pin: pin, timeout: nil)`. Do not log it.
- After an uncertain `toggleMediaPlayback(timeout:)`, read the playback state instead of sending it again:
  `data.media_state?.media_playback_status == .playing` (`MediaPlaybackStatus` is a Swift enum: `.playing`, `.paused`,
  `.stopped`).
- `ChargeSchedule` and `PreconditionSchedule` are Wire generated classes. Swift gets no default arguments, so build them
  with every field plus `unknownFields: OkioByteString.companion.EMPTY`. `ChargeSchedule()` is rejected with
  `'init()' is unavailable`. The fields keep their proto names:

```swift
let schedule = ChargeSchedule(
    id: 1_700_000_000, name: "Home", days_of_week: 127, start_enabled: true, start_time: 120,
    end_enabled: false, end_time: 0, one_time: false, enabled: true,
    latitude: 0, longitude: 0, unknownFields: OkioByteString.companion.EMPTY)
_ = try await vehicle.addChargeSchedule(schedule: schedule, timeout: nil)
_ = try await vehicle.removeChargeSchedule(id: schedule.id, timeout: nil)   // id is Int64

let precondition = PreconditionSchedule(
    id: 2, name: "Work", days_of_week: 62, precondition_time: 450, one_time: false, enabled: true,
    latitude: 0, longitude: 0, unknownFields: OkioByteString.companion.EMPTY)
_ = try await vehicle.addPreconditionSchedule(schedule: precondition, timeout: nil)
```

- An argument the library refuses comes back as `.failure` with `.invalidArgument`:
  `if case .failure(let f) = onEnum(of: result), case .invalidArgument(let bad) = onEnum(of: f.error) { … bad.detail … }`
  (`16-lib-errors.md` §Invalid arguments).


---

# Troubleshooting

Scope: M3–M4 (scan, connect, key store, read-only queries, pairing, VCSEC commands, observation log), broadcast events (`Vehicle.events`, FR-035), key accessibility on a locked iPhone (FR-037), `getState` (FR-033), the Infotainment commands (M5) and the iOS Swift package on the simulator (M6 stage 1).
Go original troubleshooting: `08-errors.md`, `02-ble-transport.md`.

## Symptom → cause → action

| Symptom | Likely cause | Action |
|---|---|---|
| `ScanEvent.Failed(PermissionDenied)` or `Failure(PermissionDenied)` | Bluetooth permission missing or denied | Android: request `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` at runtime. iOS: add `NSBluetoothAlwaysUsageDescription`; if denied, send the user to Settings |
| `ScanEvent.Failed(BluetoothOff)` | Bluetooth is off | Ask the user to turn it on |
| `scan(vin)` emits nothing, `connect` ends with `ScanTimeout` | Out of range or wrong VIN | Move closer, check the VIN (the filter is an exact match on the advertised name `S<hash of VIN>C`) |
| iOS simulator: `scan(vin)` emits `ScanEvent.Failed(ConnectFailed)` at once, `cause` `IllegalStateException: This device doesn't support the Bluetooth low energy central or client role`; `connect` returns the same `Failure` | The simulator has no Bluetooth, and CoreBluetooth reports the central role as unsupported. It is not reported as `BluetoothOff` or `PermissionDenied` | Use a real iPhone for scan and connect. Do not show it as "Bluetooth is off" and do not retry it in a loop (`17-lib-platform-notes.md` §iOS) |
| `connect` ends with `ConnectFailed` and the `cause` says the connection did not complete before the deadline | `vehicle found …`: a scan run by this `connect` found the vehicle (its `[connect:scan-hit]` line shows the discovery), but the link did not open in time. `connection to the remembered target … (no scan ran)`: `connect` opened a `target` the library remembers, so nothing shows the vehicle is still there. A remembered `target` that is out of range reports `ConnectFailed`, not `ScanTimeout`. `ScanTimeout` always means no advertisement was found (`16-lib-errors.md` §Transport and key store errors) | Try again. After a remembered `target`, connect without it so that a scan runs. If it repeats while the car is in range, report the log lines |
| `Failure(MaxConnectionsExceeded)`, advertisement has `isConnectable == false` | The vehicle already has 3 BLE connections (phone keys, other apps) | Disconnect another device; always call `disconnect()` in your own app |
| `ConnectFailed` repeats | Link unstable or the vehicle is busy | The library already retries inside `connectTimeout` (30 s); try again later |
| `connect` returns some seconds after `connectTimeout`, for example at 32.6 s with the default 30 s | Most likely a late timer: the process was probably not running when it was due (iOS suspends a background app). `connect` does not wait for a failed attempt's cleanup | Nothing to repair. See §`connect` time budget |
| `connection` turns `Disconnected` | Link lost (range, vehicle side) | Call `vehicle.disconnect()`, then `connect` again |
| `startSession()` → `RequiresKey` | Connected with `key = null` | Connect with a `VehicleKey` |
| `startSession()` → `KeyNotPaired` | The public key is not enrolled on the vehicle | Enroll it with `addKeyRequest` (`13-lib-pairing.md`). Check with `listKeys()` or `sessionInfo` |
| `KeyStoreError.PlatformFailure("stored key is corrupted")` | Android backup restored the key file without its wrapping key, or storage damage | `delete` then `getOrCreate`, then enroll the new key. Exclude `files/teslable/keys/` from backup (`17-lib-platform-notes.md`) |
| `KeyStoreError.HardwareUnavailable` | `REQUIRE_HARDWARE` on a device without StrongBox / TEE / Secure Enclave | Use `PREFER_HARDWARE` |
| `KeyStoreError.Locked` | iOS: the phone is locked and the key is `WHEN_UNLOCKED`, or the phone was not unlocked since restart | Never delete the key. Call again after unlock. For use while locked, create an `AFTER_FIRST_UNLOCK` key under a new alias and enroll it (`13-lib-pairing.md` §Key accessibility) |
| `startSession()` → `KeyStoreError.Locked` while the iPhone is locked; `WARN` (tag `Dispatcher`) `Session info not applied for <domain>: the private key is locked (device locked)` | The `WHEN_UNLOCKED` Secure Enclave key object was read before the phone locked; the lock blocks its handshake ECDH (issue #96) | Never delete the key. Call `startSession()` again after unlock with the same key object. For use while locked, create an `AFTER_FIRST_UNLOCK` key under a new alias and enroll it (`13-lib-pairing.md` §Key accessibility) |
| `connect` restores no session while the iPhone is locked; `WARN` (tag `Dispatcher`) `Cached session for <domain> not restored: the private key is locked (device locked)`, later `INFO` (tag `SessionCacheSync`) `Keeping the session cache: …` | Same cause, during the cache restore | Nothing to repair: the cache is kept and is restored on the first connection after unlock |
| `startSession()` → `Timeout` only while the iPhone is locked; `WARN` `Session info error: MESSAGEFAULT_ERROR_BAD_PARAMETER: invalid public key` or `invalid cache: …: invalid public key` | iOS reported the locked Secure Enclave ECDH with an error other than OSStatus -25308 (the library's mapping is not yet confirmed on a device) | Report the iOS version and the log lines. Meanwhile read the key with `get(alias)` right before connecting (that read returns `Locked`), or use an `AFTER_FIRST_UNLOCK` key |
| `WARN` `Keychain did not report the protection class of key <alias>; reporting WHEN_UNLOCKED` (tag `keystore`) | The Keychain returned no protection class for the stored key (not expected) | The key reports `WHEN_UNLOCKED`. Report it with the iOS version |
| First command after restoring a cached session fails with `TIME_EXPIRED` and then succeeds | Device slept, monotonic clock lagged | Nothing; the library resyncs from the attached session info and retries |
| First authenticated request after a relaunch is refused with `INVALID_TOKEN_OR_COUNTER` and fresh session info, then succeeds about 1.2 s later | The cached counter was behind the vehicle. Since ADR-0016 the library stores the counter before using it, so a process ending without `disconnect()` (force quit, iOS ending a suspended app, a crash) no longer causes this. Remaining causes: a cache entry written by an older build (once after the upgrade), a cache write that failed (the two rows below), another connection or app using the same key | Nothing to repair: the refused copy did not run and the result is `Success`. Keep one live connection per vehicle and key, and let `disconnect()` of a dropped connection return before connecting again. If it repeats, look for the `WARN` lines in the two rows below |
| `WARN` (tag `Dispatcher`) `Sending on <domain> with a counter the session cache does not cover …`, often after `WARN` (tag `VehicleSession`) `Failed to store the counter reservation for <domain>: …` or `INFO` `Keeping the session cache: …` | The counter reservation could not be written before the request (a custom `TeslaBleConfig.sessionCache` threw, or the cache is being kept because the phone lock blocked a restore, FR-037). The request was sent anyway | Nothing for this request. After an unclean exit the next process's first request may be refused once (row above). If the cache keeps failing, check the app's `TeslaBleConfig.sessionCache` implementation or storage |
| `WARN` (tag `cache`) `Failed to store session cache: …` | The built-in cache could not write (file system, Keychain status). It logs and returns, so the library counts the reservation as stored and the next 16 authenticated requests use counters the cache may not hold | Nothing for these requests. After an unclean exit the next process's first request may be refused once (two rows above). If it repeats, check free storage or the Keychain status in the line |
| `connect` restores no session for a domain; `WARN` (tag `Dispatcher`) `invalid cache: <domain>: anti-replay counter exhausted` | The cached counter has fewer than 16 values left before `0xFFFFFFFF` (not expected in practice) | Nothing: `startSession()` handshakes and starts from the vehicle's counter |
| An authenticated call → `NoSession` after `WARN` (tag `Dispatcher`) `The anti-replay counter for <domain> is exhausted; startSession() starts a new session` | The session used the last counter `0xFFFFFFFF` (not expected in practice) | Call `startSession(setOf(domain))`, then send again: nothing was sent |
| `addKeyRequest` ends with `TimedOut` | The card was not tapped, or the on-screen confirmation was not given in time (default 60 s), or the reply was not recognized | Ask the user to tap the key card on the center console and confirm on the screen, then check `sessionInfo(publicKey, INFOTAINMENT)`. It may still have been enrolled |
| `addKeyRequest` emits `Rejected(KeychainRejected(WHITELIST_FULL))` or another `KeychainRejected` code | The vehicle refused: key list full, no permission to add, … | Remove an unused key on the vehicle's touchscreen or with `removeKey`; see `16-lib-errors.md` |
| `addKeyRequest` emits `Unknown(raw)` | A reply format not seen before (real-vehicle behaviour is not verified yet, ADR-0009) | Report `raw` as hex (no VIN) and what the car did |
| `addKeyRequest` emits `Failed(NotConnected)` | The vehicle was disconnected or the link dropped | `disconnect()`, `connect` again, repeat |
| `lock()` (or any VCSEC command) → `NoSession` | No VCSEC session yet, or the cached session is stale | Call `startSession(setOf(VehicleDomain.VCSEC))` first. If `sessions` showed `Ready(fromCache = true)` it changes to `None` after this failure (the cache belonged to another key or vehicle); `startSession` fixes it |
| `removeKey` / `addKey` → `InsufficientPrivileges` | The connection's key may not manage keys (for example a Driver key) | Use an Owner key |
| `Uncertain(Timeout(afterSend = true))` | No reply within `timeout` after the request's BLE write was started; the write itself may not have completed (`16-lib-errors.md` §Retries and timeouts) | Query state again; do not resend |
| A command → `Uncertain(Timeout(afterSend = true))` after a `WARN` (tag `Dispatcher`) `[attempt=<N>] Not signing a new copy: a write of this request failed and the same bytes were sent again, so this reply (<error>) may answer a possible duplicate …` | A write reported failure, the library wrote the same bytes again, and the vehicle answered that copy with a fault, typically `INVALID_TOKEN_OR_COUNTER` (its counter was already used). The first copy may have run and its reply was lost, so the library sent no new copy and waited for that reply until `timeout` (`16-lib-errors.md` §Retries and timeouts). The observation log shows the fault as a `[reply:vcsec]` or `[reply:infotainment]` line with the same `attempt=<N>` | Query state again; do not resend. If it repeats, check the link (writes are failing) |
| A `Timeout` result, then a `[drop:no-handler] … to=address(16B, closed) … attempt=<N>` line for that request shortly after | The vehicle answered after the deadline. Most likely the app was not running when the timer or the write completion was due (iOS suspends a background app), so both ran late on resume. Since 2026-11-07 such a request reports `Uncertain(Timeout(afterSend = true))` once its write had started; older builds could report `Failure(Timeout(afterSend = false))` for it (seen once on a real car, `listKeys()` in the background) | Query state again; do not resend. Nothing to repair. The late reply is not delivered (§Reply lines) |
| `getState()` → `NoSession` | No Infotainment session yet, or the cached session is stale | Call `startSession(setOf(VehicleDomain.INFOTAINMENT))`, then ask again. If `sessions` showed `Ready(fromCache = true)` it changes to `None` after this failure |
| `getState()` → `Uncertain(Timeout(afterSend = true))`, while `startSession` succeeded | Infotainment is asleep (a session restored from the cache says nothing about sleep; `getState` never wakes the vehicle), or the reply was lost | Read `vehicleStatus()`. `ASLEEP`: follow the wake order in `14-lib-reading-state.md` A8.2. Otherwise keep the last value and ask again later |
| `getState()` → `Failure(Timeout(afterSend = false))` | Time ran out before the request's write was started or between retries, for example while the vehicle answers busy during a wake | Ask again later; right after a wake pass a longer `timeout` |
| `getState(DRIVE)` → `Uncertain(ProtocolFault(RESPONSE_MTU_EXCEEDED))` | The reply is over the vehicle's size limit; a long destination name makes this more likely. The vehicle is not gone or asleep | Keep the last value with its age, ask again later, not in a tight loop. There is no smaller query (`16-lib-errors.md` §Response size limit) |
| `getState()` → `Success`, but a field is `null` | The vehicle did not send that field, or the reply had no `vehicleData` | Show "no value", never 0 (`14-lib-reading-state.md` A8.3). Which fields a real vehicle sends is not confirmed yet (#118) |
| `getState()` → `Success` with the same values again | `Success` is not proof of a new measurement | Compare `timestamp` with the previous reply (`14-lib-reading-state.md` A8.3) |
| An Infotainment command (`climateOn()`, `chargeStart()`, `honkHorn()` …) → `NoSession` | No Infotainment session yet, or the cached session is stale | Call `startSession(setOf(VehicleDomain.INFOTAINMENT))`, then send the command again: nothing was sent |
| Every Infotainment command → `Uncertain(Timeout(afterSend = true))`, while VCSEC commands work | Infotainment is asleep: commands never wake it, and a cached session says nothing about sleep | Read `vehicleStatus()`; if `ASLEEP`, wake the vehicle and wait for `AWAKE` (`14-lib-reading-state.md` A8.2). Read the state before sending the command again: it may have run |
| A command → `Uncertain` and the app wants to retry | The vehicle may have run it | Never resend. Read the state the command changes (`15-lib-commands.md` §Re-query). For `toggleMediaPlayback` and the trunk toggles a second send undoes the first. `honkHorn`, `flashLights`, `setLowPowerMode` and `setKeepAccessoryPowerMode` cannot be read back: show the result as unknown |
| `enableValetMode(pin)` → `InvalidArgument("PIN codes must be four digits")` | The PIN is not exactly four ASCII digits, for example full-width or other scripts' digits from the keyboard, or a space | Offer a digit-only keyboard or convert the input before calling. Never log the PIN |
| `setVolume(volume)` → `InvalidArgument("invalid volume (should be in [0, 10])")` | The value is below 0, above 10 or NaN (for example a slider value not clamped, or 0/0) | Clamp to `0..10` in the app |
| `setSeatCooler` → `InvalidArgument("invalid seat position for seat cooler: …")`, `scheduleDeparture` → `InvalidArgument("invalid departure time: …")` | A seat other than `FRONT_LEFT` / `FRONT_RIGHT`; a departure outside 0..1440 minutes after midnight | Fix the argument (`16-lib-errors.md` §Invalid arguments) |
| An Infotainment command → `Failure(InfotainmentRejected(reason))` | The vehicle refused: the feature is missing, or its state does not allow the command (for example while driving) | Show a generic message; `reason` is the vehicle's own text, do not log it. `disableValetMode` already turns a reason ending in `already off` into `Success` |
| `addChargeSchedule` → `InvalidArgument("message exceeds 1024 bytes")` | The schedule `name` is so long that the message would exceed 1024 bytes | Use a shorter name |
| A command → `Success`, but `getState` still shows the old value | The new value may not be reported yet, or the field is not the one the command changes (not confirmed on a real vehicle, #118) | Read again a little later and compare `timestamp` |
| `vehicle.events` completed | The link dropped or `disconnect()` was called (a collection started after that ends at once) | Look at `connection`; call `disconnect()`; connect again for a new `Vehicle` and new `events` |
| `vehicle.events` delivers nothing | Collection started after the broadcasts (there is no replay), the vehicle sent none (it pushes only while awake), or the frames fail the gate (`14-lib-reading-state.md` B2.1) | Make a change on the car (open a door, shift gear). Compare with the observation log: a plain broadcast has a `[drop:to-domain]` line with `from=domain(DOMAIN_VEHICLE_SECURITY) to=domain(DOMAIN_BROADCAST)` |
| `[drop:missing-source]` line with `reinterpreted=FromVCSECMessage{authenticationRequest{…}}`, but no `AuthenticationRequest` in `vehicle.events` | By design: a frame without a source fails the gate | Nothing to repair. See §Frames without a source |
| `WARN` `Dropping plain broadcast because the broadcast buffer is full (dropped N since connect)` | A collector of `vehicle.events` fell more than 64 frames behind | Keep the collector light; move slow work to another coroutine (`16-lib-errors.md`) |
| `UnknownFault`, `UnknownKeychainCode`, enum values read as `UNKNOWN` | Newer vehicle firmware | Check for a library update |
| Keychain test fails with -25291 on the simulator | Plain Kotlin/Native test binaries cannot use the Keychain | Verify Keychain behaviour in the sample app |
| iOS simulator: the key store returns `PlatformFailure("keychain status -34018")` (`errSecMissingEntitlement`) | The app is not code-signed (for example `CODE_SIGNING_ALLOWED = NO`), so it has no Keychain entitlement | Sign the app; ad-hoc signing is enough (`CODE_SIGN_IDENTITY = -`, "Sign to Run Locally") (`17-lib-platform-notes.md` §Key storage) |
| Xcode, simulator build: `ld: symbol(s) not found for architecture x86_64`, after `ignoring file … Teslable … found architecture 'arm64', required architecture 'x86_64'` | The Swift package's simulator slice is arm64 only, and this build also builds `x86_64` (a Release build builds every architecture) | Set `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64` on the app target. On an Intel Mac use a device: the simulator cannot run the library (`17-lib-platform-notes.md` §iOS) |

## `connect` time budget

`connectTimeout` (default 30 s) or `ConnectOptions.timeout` covers scanning, opening the link and the retries between
attempts. When `connect` fails on it:

- It returns at once, with the error of the last failed attempt. With no failed attempt it returns `ConnectFailed` if a scan
  found the vehicle or a remembered `target` was being opened, and `ScanTimeout` if no advertisement was found
  (`16-lib-errors.md` §Transport and key store errors). Go returns the last error too, otherwise one generic deadline error
  for all of these (`ble.go:270-275`).
- It does not wait for an attempt still running. That attempt runs in the library's own scope: it is cancelled and cleans up
  after `connect` has returned, waiting at most 5 s for the platform disconnect (`DISCONNECT_TIMEOUT`). If it still opens a
  link, the library disconnects that link.
- The only wait past the budget: a transport created at the very moment the budget ran out is closed before `connect`
  returns, which also takes at most 5 s. So the library itself adds at most 5 s to a failed `connect`.
- The budget is a coroutine timer and cannot fire while the process is not running, so a suspended app can overrun by any
  amount. On a real iPhone a 30 s budget ended 36 minutes late (`docs/analysis/2026-11-01-build18-link-health.md` F3, status Likely).
  No timeout can prevent this.

Seen once on a real iPhone: `ConnectFailed` after 32.63 s, with the reason `NotConnectedException: Disconnect detected`.
The likely cause is a late timer (status **Likely**, not confirmed: the log records no process state). `ScanTimeout`
returns, where no attempt had failed, overran the same way (at 31.35, 32.40 and 33.19 s; that build, before #140, also
returned `ScanTimeout` after finding the vehicle), and the app's next 4 s wait took 9.03 s. The log cannot exclude the one library wait above, which would need a link to open at the instant the budget ran
out. No harm was seen (#130).

## Frames without a source

Decision (#130): a frame without `from_destination` stays out of `vehicle.events` and remains a `[drop:missing-source]` line
in the observation log.

- It fails the gate (`14-lib-reading-state.md` B2.1), and the Go original drops it too (`dispatcher.go:248-251`). Without a
  source nothing says the frame came from VCSEC: `reinterpreted=` only shows how the same bytes read as a `FromVCSECMessage`.
- Delivering these frames would widen the public contract: `raw` is always a whole `RoutableMessage` frame, and any stray
  frame would become an event.
- The gain is small. A real car sent 2 such frames in 12 connections. The `authenticationRequest` (`DRIVE`, `IDENTIFICATION`)
  arrived 1 ms before the app saw `connected`, so nothing was collecting yet and, with no replay, a wider gate would not have
  delivered it. The other was a bare `commandStatus`, which has no event type. An app also cannot act on an
  `AuthenticationRequest`: the library does not send `AuthenticationResponse`.

Reconsider this if a vehicle sends such frames often: reports elsewhere describe one about every 1.1 s
(`14-lib-reading-state.md` B3.2).

## Debug logging

- Pass a logger through `TeslaBleConfig(logger = …)` or `TeslaBleConfig.default(logger = …)`. The default is
  `TeslaLogger.NoOp`.
- `DebugLogger()` writes to the platform log: Android Logcat, iOS unified log (`NSLog`), JVM `java.util.logging`. Use it
  in development builds only.
- Custom logger: implement `fun interface TeslaLogger { fun log(level: LogLevel, tag: String, message: () -> String) }`.
  `message` is evaluated lazily. A logger that throws does not stop the library.
- Levels: `DEBUG` (TX/RX hex), `INFO` (session and connection events), `WARN` (drop or discard reasons), `ERROR`
  (unrecoverable situations).
- The library logs VINs masked (`Vin.toString()`) and never logs private keys, session keys, nonces or decrypted plaintext
  at any level. Do not add `vin.value` to your own logs either, and mask VINs before sharing logs.
- Do not log `getState` results, `toString()` included: a `VehicleData` can hold the location and the destination, and
  `InfotainmentRejected.message` carries the vehicle's own text. The library logs neither (FR-112).

To report a problem, use the iOS sample's "copy whole result" button log together with `DebugLogger`. There is no probe or diagnostics entry point.

## Observation log and `vehicle.events`

Both show the vehicle's broadcasts, for different readers:

| | Observation log | `vehicle.events` |
|---|---|---|
| What it is | One `DEBUG` text line per dropped message or reply to an own request, tag `observe` (it also has `connect` scan hits, see the next section) | Public `Flow<VehicleEvent>` on `Vehicle` (FR-035) |
| Which frames | Every dropped frame (replies to other clients, unparseable frames, broadcasts) and every reply to an own request, in both domains | Only frames that pass the plain broadcast gate (`14-lib-reading-state.md` B2.1) |
| Content | A summary for people; values of unknown fields only in the detailed decode | Typed model (`Status`, `AuthenticationRequest`, `Unrecognized`), `unknownFields` paths and the whole frame in `raw` |
| VIN | Masked by the library | **Not masked** in `raw`: the app masks it before storing or sharing |
| Needs | A logger that keeps `DEBUG` (development builds) | Nothing; it works in release builds |
| Use it for | Exploring the protocol, reporting problems | App features (live state) and collecting raw frames for analysis |

- When one frame carries both `vehicleStatus` and `authenticationRequest`, the observe line shows only the one that
  comes last on the wire: the schema puts both in one `oneof`, and a Wire decode keeps only the last member. `events`
  reads the two fields separately and delivers both (`Status`, then `AuthenticationRequest`).
- Do not parse observe lines in app code: their format is for development and may change. Use `events`.

## Observation log (development)

The dispatcher writes one summary line for every message it drops and for every reply to one of its own requests, in
both domains. It is for development, to see what the vehicle pushes without being asked and how its replies line up with
requests. It changes no behaviour: broadcasts are still dropped from reply matching, and the ones that pass the plain
broadcast gate are also delivered through `vehicle.events` (previous section). What each pushed message means (confirmed
on a real car or not) is in `14-lib-reading-state.md` Part B. The BLE transport adds one line for every scan hit of
`connect`.

- Tag `observe`, level `DEBUG` (a logger that discards `DEBUG` never evaluates the line). Visible through `DebugLogger()`
  or any custom `TeslaLogger`. The iOS sample's observation mode collects only these lines.
- Line shape: `[<point>] <N>B from=<dest> to=<dest> request_uuid=<empty|16B> [status=<op>/<fault>] [session_info_request]
  [session_info=<N>B] payload=<summary> [attempt=<N>] [auth=<value>]`. `<N>B` is the received frame size. `attempt=` and
  `auth=` close the line when present (§Reply lines).
- Scan hit line (FR-038): `[connect:scan-hit] after=<ms>ms rssi=<dBm|unavailable> connectable=<true|false|unknown>
  attempt=<n>`. One line for every advertisement found by a scan that `connect` runs itself, written before the connectable
  check and before opening, so it also appears for a `connect` that fails and for each retry. `after` is time since `connect`
  started (the same measure as `hitAfterMillis`, `12-lib-getting-started.md` §Connect scan observation), `attempt` is the open
  attempt the hit was found for. The time your logger receives the line is the wall-clock time of the hit. `ble.scan(vin)`
  and a `target` reused without a scan write no line. It has no advertised name and no device identifier. Its `attempt` is
  the `connect` open attempt, not the request attempt of a reply line.

| Part | Values |
|---|---|
| `[drop:unparseable]` | Frame did not decode as `RoutableMessage`. Line is `[drop:unparseable] <N>B reinterpreted=<summary or undecodable>` |
| `[drop:missing-source]` | No `from_destination`. The line ends with `reinterpreted=<summary>`: the same bytes read as an unwrapped `FromVCSECMessage`. Never an event (§Frames without a source) |
| `[drop:missing-destination]` | No `to_destination` (not seen from the real vehicle) |
| `[drop:to-domain]` | Addressed to a domain, not a routing address (the vehicle's broadcasts, `DOMAIN_BROADCAST`) |
| `[drop:malformed]` | Bad request UUID length or bad routing address length |
| `[drop:no-handler]` | Valid routing address with no open request. A reply whose key is in the closed-request history reads `to=address(16B, closed)` and ends with ` attempt=<N>` (§Reply lines) |
| `[drop:handler-closed]`, `[drop:queue-full]` | The matching request closed meanwhile, or its response queue was full. The `[reply:…]` line of the same frame comes first |
| `[reply:vcsec]`, `[reply:infotainment]` | A reply routed to an open request, one line per received frame (§Reply lines) |
| `from=`, `to=` | `domain(DOMAIN_X)`, `address(16B, self\|open-request\|closed\|other)` (`to=` only), `none`, `empty` |
| `payload=` | `FromVCSECMessage{…}`, `Response{fields=[…]}`, `encrypted(NB)`, `NB`, `none` |
| `unknownFields=[n]` | Field numbers present on the wire but unknown to the generated proto |
| `uiDesire=`, `gear=` | `vehicleStatus` fields 6 and 7, named by the Teslemetry proto schema (`UI_DESIRE_*`, `GEAR_UNKNOWN\|PARK\|DRIVE\|REVERSE\|NEUTRAL`). `gear` is confirmed on a real car (see `14-lib-reading-state.md` Part B); `uiDesire` meaning is not. Always printed (proto3 enum: absent reads as the zero value) |
| `authenticationRequest{requestedLevel=…, reasons=[…], token=NB}` | `FromVCSECMessage` field 3, named by the Teslemetry proto schema (field names unverified). The token is shown by length only |
| `absent=[…]` | Message fields of `vehicleStatus` that were not sent (`closureStatuses`, `detailedClosureStatus`) |
| `key=sha1:xxxxxxxx` | Public key: first 4 bytes of its SHA-1 only |

### Reply lines

A `[reply:vcsec]` or `[reply:infotainment]` line is written once per received frame, after the decrypt step, so a reply
that step dropped has its line too. It ends with ` attempt=<N> auth=<value>`. If the frame then does not reach the caller,
a `[drop:handler-closed]` or `[drop:queue-full]` line follows. A frame whose processing is interrupted by the app
disconnecting gets no line and is not delivered, so a missing line does not prove that no reply came.

| Part | Meaning |
|---|---|
| `attempt=<N>` | Opaque number of the request on this connection, from 1. Every request takes a new one, including a handshake request and each retry, so one command can use several. Compare numbers only within one connection: a new connection starts at 1 again |
| `auth=gcm-ok` | `AES_GCM_Response_data` is present, the tag verified and the counter passed the request's replay check. Delivered |
| `auth=gcm-failed` | `AES_GCM_Response_data` is present and verification failed. Dropped |
| `auth=gcm-replay` | The tag verified, but the replay window rejected the counter: already seen, or older than the window. Dropped |
| `auth=gcm-no-session` | `AES_GCM_Response_data` is present, but there is no session to check it with (no private key, or the session is not established). Dropped |
| `auth=none` | No `AES_GCM_Response_data`, so nothing was checked. Delivered. A `session_info` reply reads this too: its HMAC tag is verified separately and this line does not report that |

- `auth=` reports the payload check, not a verdict on the reply. AES-GCM covers the payload and `signed_message_fault`.
  The outer `operation_status`, the first half of `status=`, is outside it, so `auth=gcm-ok` does not cover it (ADR-0015).
- The line is built from the frame as received, never from the decrypted message (FR-112). An encrypted reply reads
  `payload=encrypted(NB)` whatever `auth=` says, and `encrypted(NB)` alone only means the signature field is there.
- `to=address(16B, closed)` is a correspondence and nothing more. The reply's key (routing address, plus the request UUID
  for Infotainment) matches a request this connection closed, and `attempt=<N>` names it. It does not show that the reply
  is genuine or that the request succeeded: the library does not decrypt such a reply, which leaves the request's replay
  check untouched, and does not deliver it. That is why the line has no `auth=`.
- The history holds 32 closed requests of the connection, and the entry that went in first falls out first. A closed
  request enters at the library's next cleanup, oldest attempt first within one cleanup, so the order follows closing
  closely but not exactly. A late reply to a request no longer in the history reads `other` for VCSEC and `self` for
  Infotainment, whose requests all share one address.
- The history empties when the connection ends, whether the link dropped or the app disconnected. Nothing carries over to
  a new connection, not even a request that was still open when the link dropped and closed later.

### Detail form (exploration only)

Example: `unknownFields=[48:len(8){1:varint=3(0x3), 2:len(4)"ABCD"}, 9]`. Entries are in arrival
order, repeated fields repeated. The path number and wire type are kept and no field name is invented.

| Wire type | Shown as |
|---|---|
| varint | `varint=<unsigned decimal>(0x<hex>)` |
| fixed32 / fixed64 | `fixed32=0x<8 hex digits>` / `fixed64=0x<16 hex digits>` |
| LEN | `len(N){…}` when the whole range parses as protobuf (max depth 3, 64 fields per message, 256 bytes); else `len(N)"text"` for printable ASCII (control characters escaped); else `len(N)0x<hex>` |
| over a limit | `len(N, truncated)`; corrupt unknown-field buffer: `len(N, invalid)` |
| key-like LEN | exactly 16, 32, 33, 64 or 65 bytes: `redacted(NB)`; a 65-byte value starting with `0x04`: `sha1:xxxxxxxx` |

VINs (`[A-HJ-NPR-Z0-9]{17}`) are masked in text and in raw bytes before any hex is produced (first 3 and last 4
characters kept). Masking runs on the raw bytes before they are parsed, so masked bytes can change the value or structure that is shown (nothing leaks). This output is for exploration. It does not give the meaning of a field. On the real car a missing
`closureStatuses` meant "all closed" for the driver doors, the rear trunk and the charge port, but a trunk operated by the interior button produced no broadcast at all, so a broadcast alone cannot prove the trunk is closed (see `14-lib-reading-state.md` Part B).

### Limits and rules

- proto3 enums cannot tell "missing" from the zero value: an absent lock state reads `VEHICLELOCKSTATE_UNLOCKED`
  (sleep and presence read `UNKNOWN`). Only message fields can be reported as `absent`.
- Detailed unknown-field decoding (observation log v2) is the one narrow exception to "no plaintext payloads" in logs,
  and it does not touch FR-112: it applies only to a frame the vehicle originally sent as plaintext, never to anything
  the library decrypted (`VehicleEvent.raw` hands such frames to the app: the first five conditions below, payload
  decodable or not, `14-lib-reading-state.md` B2). The gate is all of: `from=domain(DOMAIN_VEHICLE_SECURITY)`, `to=domain(DOMAIN_BROADCAST)`, empty
  `request_uuid`, no `signature_data`, no `session_info`, and a payload that decodes as `FromVCSECMessage`. For such
  frames only `FromVCSECMessage` unknown fields 48 and 53 and `VehicleStatus` unknown fields 7 and 8 are decoded (`VehicleStatus`
  7 is now the named `gear`, so it is unknown only when its value is outside the schema; that raw value is still shown,
  for example `unknownFields=[7:varint=9(0x9)]`, because that value is outside the schema); every
  other unknown field, and every other frame (replies, encrypted, addressed to a routing address, unwrapped
  reinterpretations), keeps the number-only form.
- Ciphertext and `session_info` are logged by length only; public keys by SHA-1 prefix only; the VIN and plaintext
  payloads of encrypted messages never appear. Keep the rule when you add lines.
- The BLE transport counts inbox overflows per connection. Each dropped message logs
  `Dropping received message because inbox is full (dropped N since connect)` at `ERROR`; when the link ends, one line
  `BLE inbox overflowed N time(s) during this connection` is logged at `WARN` (only if N > 0).
- `DebugLogger` also prints the BLE `TX:`/`RX:` raw hex at `DEBUG`. Do not share such platform logs. Only what the
  vehicle encrypted is ciphertext there. Everything else appears as the vehicle sent it, including VCSEC query replies,
  broadcasts and a plaintext Infotainment reply (firmware before 2024.38, `16-lib-errors.md` §Reply authenticity), which
  can hold the location.


---

# Architecture (Contributor Summary)

A design summary for people contributing to tesla-ble-kmp. The authoritative design is `docs/sdd/SDD.md` in this
repository; this page summarizes it and does not link to SDD sections. Covers M2 plus the M3 `:adapter-ble` and `:sdk`
parts and the broadcast path behind `Vehicle.events` (FR-035). It is filled in again when the control and query APIs
(M4–M5) land.

## Modules

```
App (Android Compose / iOS SwiftUI)
        │
        ▼
     :sdk            Public facade (TeslaBle, Vehicle). Scan, connect, queries in M3; extended in M4–M6
        │
        ▼
 :application        Dispatcher · SessionState · VcsecCommands · VcsecBroadcasts · InfotainmentCommands · SessionCacheSync · VehicleSession
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
      → Dispatcher.send(message, auth, lifetime) { afterSend(true) }
          → SessionState.authorize        // Signer.encrypt: counter++, AAD, nonce
          → transport.send(bytes, onWriteInvoked)   // Transport port (implemented by :adapter-ble);
                                          // calls onWriteInvoked right before the first link.write
      → wait on PendingRequest (readUntil) // until the response is judged final
```

Infotainment commands take the same last two steps (retry → `Dispatcher.send`) through
`InfotainmentCommands.execute` → `SendWithRetry.send`, without the serialization lock.

The request counts as possibly sent from the moment its first BLE write is invoked, not when the write completes
(ADR-0010, revised 2026-11-07). A timeout after that point is `Uncertain(Timeout(afterSend = true))`, also while the
write is still running or the dispatcher resends after a failed write. A timeout before it (VCSEC lock wait, waiting
for the transport's send lock, checks before writing), or while waiting to retry after a reply that asked for a retry,
is `Failure(Timeout(afterSend = false))`. `Transport.send(message, onWriteInvoked)` is an `@InternalTeslableApi`
member of the port; its default implementation reports on entry.

When a write it invoked fails and the dispatcher writes the same bytes again, it marks the request (ADR-0010, revised
2026-11-08). For a marked command, `VcsecCommands` and `SendWithRetry` do not retry with a newly authorized copy: a reply
that would start a new attempt, or a protocol-layer rejection, may answer a duplicate of a copy the vehicle already ran,
so they keep reading the same request until a reply settles it or the timeout returns
`Uncertain(Timeout(afterSend = true))`. Reads (`VehicleInfoQueries`, `getState`) go through internal repeat-safe entry
points and keep the normal retry.

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

**Broadcasts (`Vehicle.events`, FR-035)**

```
Dispatcher receive coroutine
  → route(): to_destination is a domain (TO_DOMAIN) → drop from routing + observation log line (same drop as Go)
      → plain broadcast gate (from VCSEC domain, to BROADCAST domain, no request_uuid, signature_data, session_info)
          → plainBroadcasts.tryEmit(copy of the frame)       // SharedFlow, replay 0, buffer 64; never waits
              full → drop the new frame, count per connection, WARN on drop 1, 100, 200, …
  → Vehicle.events, one subscription per collector (channelFlow)
      → VcsecBroadcasts.events(frame): Status / AuthenticationRequest / Unrecognized (same mapping as vehicleStatus())
      → ends normally when connection is Disconnected (any reason) or disconnect() is called
```

The Go original drops these frames and emits nothing. Reply matching is unchanged: a broadcast never reaches a pending
request. The receive coroutine only copies and offers the frame; `VcsecBroadcasts` decodes the copy again in the
collector's coroutine.

## Concurrency

- **Exactly one receive coroutine per vehicle connection.** It never suspends waiting for another coroutine (channel
  receive, `Deferred.await`, `delay`); when it hands data to a channel it uses non-suspending `trySend` (`tryEmit` for
  the broadcast flow, so a slow `events` collector cannot delay replies). The one exception: when a new receive
  coroutine starts, it waits for the previous one to finish, so two never collect at once.
- **One VCSEC command at a time per connection** (serialization lock). Time spent waiting for the previous command
  counts against that command's timeout. **Infotainment commands may run in parallel.**
- Each domain (VCSEC / INFOTAINMENT) has one session state. Its internal lock guards only encrypt, decrypt and session
  info updates, so lock waits are short and bounded. The session cache write that reserves counters (§Session cache)
  happens outside that lock, in the caller's coroutine, before the request's BLE write.
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
and the scope. `Vehicle` is one object per connection; after `disconnect()`, commands fail with `NotConnected`. The
dispatcher's `plainBroadcasts` is passed to the `Vehicle`, whose `events` maps it and completes when the connection ends.

## Session cache

Sessions (encryption key state agreed in the handshake) are stored in a custom binary format (v1) so the next
connection can send commands without a new handshake. The cache belongs to the client key (SHA-1 of the public key);
caches of other keys are ignored and deleted. It is stored when a handshake completes, when session info is updated,
and on disconnect (wired up by the facade in M3). Private keys, session encryption keys and raw VINs are never stored.
Corrupted entries or entries with unrecognized values are skipped and the rest restored.

**Counter reservation (ADR-0016, issue H17; differs from Go).** The cached counter is written *before* it is used, so a
process that ends without `disconnect()` (force quit, the system ending a suspended app, a crash) does not leave the
next process a counter the vehicle has already accepted.

- Invariant: a cache entry's counter is at least the highest counter used in that epoch, provided the write succeeded.
- Before an authenticated request uses a counter the cache does not hold yet, the dispatcher stores a reservation for
  that domain only (current counter + 16, stopping at `0xFFFFFFFF`) and then encrypts. One write covers the next 16
  requests. The coverage check and the encryption run in one critical section of the domain's session state; if
  parallel requests use up a fresh reservation, or the signer or epoch changed while it was being written, the
  dispatcher reserves again. No store ever writes a counter below the stored reservation, including the store on
  disconnect.
- A reservation belongs to one signer and its epoch: a new or replaced session, an invalidated (stale) session or an
  epoch change voids it; session info for the same epoch keeps it.
- The reservation write runs in the caller's coroutine before the BLE write (inside the VCSEC lock for VCSEC
  requests), never on the receive coroutine and never under the session state's lock. If it cannot be stored (the
  FR-037 lock keep declined the write, or the cache threw), the request is sent as before and a `WARN` is logged. A
  write the cache only logged as failed (the built-in caches do this) counts as stored. A connection that is closing
  returns `NotConnected` without using a counter.
- Exhaustion: a cached entry whose counter is above `0xFFFFFFFF` − 16 is not restored (the domain handshakes). A live
  session that has used `0xFFFFFFFF` makes the next authenticated call fail with `NoSession`; `startSession()` then
  builds a new signer from the vehicle's session info, only for the same vehicle public key. If the vehicle never
  accepted that last counter, the new signer starts from the vehicle's lower counter in the same epoch (none of the
  skipped counters was accepted). Until then the old signer still decrypts replies to requests already sent.
- Not covered: a failed write that the cache adapter only logged, another signer using the same key (another app, a
  second live connection to the same VIN with the same key in this process, or an old dropped connection whose
  `disconnect()` runs after a new connection reserved; the facade does not prevent that), and a cache entry written by
  a build before this change (its first request can still be refused once and recovered).
- Cost: one cache write before the first authenticated request of a connection and then one per 16 requests; the
  vehicle sees a counter jump of usually 16 or less on the first request after an unclean exit or a reconnect. The Go
  verifier accepts forward jumps (`internal/authentication/window.go`); a real-car check is pending.

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
  Confirmed by real-vehicle captures and therefore public: `VehicleStatus.gear` (field 7) and
  `FromVCSECMessage.authenticationRequest` (field 3, as `VehicleEvent.AuthenticationRequest`; its sub-field names stay
  unverified).
- To update: check out tesla-protocol at the pinned commit, run `tools/proto/sync-protos.sh <checkout>` (no network; it
  refuses any other commit), then `./gradlew :domain:generateCommonMainProtos`, `./gradlew :domain:apiDump` and the
  tests. The pinned commit is in the script and in `docs/PATHS.md` (`PROTO_SCHEMA_COMMIT`); change both in one PR.

