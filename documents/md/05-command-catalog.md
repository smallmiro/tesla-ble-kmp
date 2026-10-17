# 05. Command Catalog

Cross-references each vehicle command by its `tesla-control` CLI name, `pkg/vehicle` Go method, `tesla-http-proxy` REST endpoint, target domain, auth requirement, transport constraint, and the protobuf actually sent. Includes commands exposed differently across the three sources (CLI-only / proxy-only / Go-only).

Related files (relative to repository root, authoritative sources):
- `cmd/tesla-control/commands.go` — CLI command table (`var commands = map[string]*Command`)
- `pkg/proxy/command.go` — proxy endpoints (`switch command` in `ExtractCommandAction`)
- `pkg/vehicle/{vcsec.go,security.go,actions.go,charge.go,climate.go,infotainment.go,state.go}` — Go methods
- `pkg/protocol/protobuf/{vcsec.proto,car_server.proto,common.proto,keys.proto}` — payload definitions

Related documents: [04-go-api-reference.md](04-go-api-reference.md), [06-cli-tools.md](06-cli-tools.md), [07-http-proxy.md](07-http-proxy.md), [08-errors.md](08-errors.md)

---

## How to Read the Tables

| Column | Meaning |
|---|---|
| CLI | `tesla-control` command and positional arguments. `[ ]` marks optional arguments. `—` means not in the CLI. |
| Go | `(*vehicle.Vehicle)` method. |
| Proxy | `<name>` in `POST /api/1/vehicles/{VIN}/command/<name>` and the JSON body keys. `—` means the proxy does not handle it with end-to-end authentication (that path is forwarded to the Fleet API as-is). |
| Domain | `VCSEC` = `DOMAIN_VEHICLE_SECURITY`(2), `INFO` = `DOMAIN_INFOTAINMENT`(3). |
| Auth | CLI `requiresAuth` (private key required). The proxy always signs with the private key. |
| Fleet | CLI `requiresFleetAPI` (OAuth token required, VIN not required). |
| Transport | `BLE` = BLE only, `Fleet` = Fleet API only, blank = both. |
| Payload | `VCSEC`: `vcsec.UnsignedMessage.sub_message`. `INFO`: oneof field name of `carserver.Action.vehicleAction.vehicle_action_msg`. |

CLI domain selection: for most commands `Command.domain` is empty (`DomainNone`), so handshakes are performed with both VCSEC+INFO. Only `body-controller-state` sets `domain: protocol.DomainVCSEC` and handshakes VCSEC only, and for `-ble wake`, `configureFlags` restricts it to VCSEC only. Users can also restrict with the `-domain` flag.

---

## A. Lock / Remote Start / Wake (VCSEC RKEAction)

| CLI | Go | Proxy | Domain | Auth | Fleet | Transport | Payload | Notes |
|---|---|---|---|---|---|---|---|---|
| `unlock` | `Unlock` | `door_unlock` `{}` | VCSEC | ✔ | | | `RKEAction = RKE_ACTION_UNLOCK` | |
| `lock` | `Lock` | `door_lock` `{}` | VCSEC | ✔ | | | `RKEAction = RKE_ACTION_LOCK` | |
| `drive` | `RemoteDrive` | `remote_start_drive` `{}` | VCSEC | ✔ | | | `RKEAction = RKE_ACTION_REMOTE_DRIVE` | |
| `autosecure-modelx` | `AutoSecureVehicle` | — | VCSEC | ✔ | | | `RKEAction = RKE_ACTION_AUTO_SECURE_VEHICLE` | Closes Model X falcon wing doors and locks |
| `wake` | `Wakeup` | `wake_up` `{}` | BLE: VCSEC / inet: REST | ✗ (over BLE a key is actually required) | | | BLE: `RKEAction = RKE_ACTION_WAKE_VEHICLE` (authenticated send) / inet: repeat `POST api/1/vehicles/{VIN}/wake_up` every 10 seconds until `state=="online"` | For `-ble wake`, `configureFlags` adds `FlagPrivateKey` and restricts the domain to VCSEC. Sending over BLE without a key yields `ErrNoSession`. |

VCSEC RKE/Closure responses: the first message with `commandStatus == nil` is terminal (success). If `operationStatus == WAIT`, retry with `ErrBusy`.

## B. Keychain / Session (VCSEC)

| CLI | Go | Proxy | Domain | Auth | Fleet | Transport | Payload | Notes |
|---|---|---|---|---|---|---|---|---|
| `add-key PUBLIC_KEY ROLE FORM_FACTOR` | `AddKeyWithRole(pub, role, ff)` / `AddKey(pub, isOwner, ff)` | — | VCSEC | ✔ | | | `WhitelistOperation{ addKeyToWhitelistAndAddPermissions: PermissionChange{key, keyRole}, metadataForKey: {keyFormFactor} }` | Adds another key using an existing Owner key. Terminal: message with `whitelistOperationStatus` populated. On failure, `KeychainError`. |
| `add-key-request PUBLIC_KEY ROLE FORM_FACTOR` | `SendAddKeyRequestWithRole` / `SendAddKeyRequest` | — | VCSEC | ✗ | | **BLE** | `vcsec.ToVCSECMessage{ signedMessage: { protobufMessageAsBytes: <UnsignedMessage(same WhitelistOperation as above)>, signatureType: SIGNATURE_TYPE_PRESENT_KEY } }` — sent directly via `conn.Send` **without wrapping in a RoutableMessage** | Approved by NFC card tap + on-screen confirmation. Returns immediately after sending; approval is not guaranteed. Calling over inet yields `ErrRequiresBLE`. |
| `remove-key PUBLIC_KEY` | `RemoveKey(pub)` | — | VCSEC | ✔ | | | `WhitelistOperation{ removePublicKeyFromWhitelist: PublicKey{PublicKeyRaw} }` | |
| `list-keys` | `KeySummary()`, then `KeyInfoBySlot(slot)` for each bit in `slotMask` | — | VCSEC | ✗ | | | `InformationRequest{ INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO }` → `InformationRequest{ GET_WHITELIST_ENTRY_INFO, slot }` | `AuthMethodNone`. Output: `hex(pubkey)\t<Role>\t<KeyFormFactor>` |
| `session-info PUBLIC_KEY DOMAIN` | `SessionInfo(pub, domain)` | — | Argument | ✗ | | | `RoutableMessage.session_info_request{ public_key }` | Checks enrollment/session state of an arbitrary public key. DOMAIN is `vcsec` or `infotainment` (lowercase). |
| `rename-key PUBLIC_KEY NAME` | `account.UpdateKey(pub, name)` | (forwarded) `POST api/1/users/keys` | Fleet API | ✗ | ✔ | **Fleet** | HTTP JSON `{public_key(hex), kind:"mobile_device", model:"3rd Party Application", name, tag}` | Display name on the vehicle's Locks screen. Only the account that originally enrolled it can modify it. |
| `body-controller-state` | `BodyControllerState()` | — | VCSEC (`Command.domain` set) | ✗ | | | `InformationRequest{ INFORMATION_REQUEST_TYPE_GET_STATUS }` | Works over BLE even while Infotainment is asleep. Output protojson (`EmitDefaultValues`). |

## C. Closures / Physical Actions

| CLI | Go | Proxy | Domain | Auth | Fleet | Transport | Payload | Notes |
|---|---|---|---|---|---|---|---|---|
| `trunk-open` | `OpenTrunk` | `actuate_trunk` `{"which_trunk":"rear"}` or `{}` | VCSEC | ✔ | | | `closureMoveRequest{ rearTrunk: CLOSURE_MOVE_TYPE_MOVE }` | `OpenTrunk` and `ActuateTrunk` have the same payload (toggle). |
| `trunk-move` | `ActuateTrunk` | `actuate_trunk` | VCSEC | ✔ | | | Same as above | |
| `trunk-close` | `CloseTrunk` | — | VCSEC | ✔ | | | `closureMoveRequest{ rearTrunk: CLOSURE_MOVE_TYPE_CLOSE }` | Power-trunk models only |
| `frunk-open` | `OpenFrunk` | `actuate_trunk` `{"which_trunk":"front"}` | VCSEC | ✔ | | | `closureMoveRequest{ frontTrunk: CLOSURE_MOVE_TYPE_MOVE }` | No remote close. If `which_trunk` is anything other than front/rear, the proxy returns `NominalError("invalid_value")`. |
| `tonneau-open` | `OpenTonneau` | `open_tonneau` `{}` | VCSEC | ✔ | | | `closureMoveRequest{ tonneau: CLOSURE_MOVE_TYPE_OPEN }` | Cybertruck |
| `tonneau-close` | `CloseTonneau` | `close_tonneau` `{}` | VCSEC | ✔ | | | `closureMoveRequest{ tonneau: CLOSURE_MOVE_TYPE_CLOSE }` | |
| `tonneau-stop` | `StopTonneau` | `stop_tonneau` `{}` | VCSEC | ✔ | | | `closureMoveRequest{ tonneau: CLOSURE_MOVE_TYPE_STOP }` | |
| `charge-port-open` | `OpenChargePort` (= `ChargePortOpen`) | `charge_port_door_open` `{}` | INFO | ✔ | | | `chargePortDoorOpen {}` | |
| `charge-port-close` | `CloseChargePort` (= `ChargePortClose`) | `charge_port_door_close` `{}` | INFO | ✔ | | | `chargePortDoorClose {}` | |
| `windows-vent` | `VentWindows` | `window_control` `{"command":"vent"}` | INFO | ✔ | | | `vehicleControlWindowAction{ vent: {} }` | Proxy: lat/lon not required. Other command values → 400 "command must be 'vent' or 'close'" |
| `windows-close` | `CloseWindows` | `window_control` `{"command":"close"}` | INFO | ✔ | | | `vehicleControlWindowAction{ close: {} }` | |
| — | `ChangeSunroofState(level int32)` | — | INFO | | | | `vehicleControlSunroofOpenCloseAction{ absolute_level }` | Go only |
| `honk` | `HonkHorn` | `honk_horn` `{}` | INFO | ✔ | | | `vehicleControlHonkHornAction {}` | |
| `flash-lights` | `FlashLights` | `flash_lights` `{}` | INFO | ✔ | | | `vehicleControlFlashLightsAction {}` | |
| — | `TriggerHomelink(lat, lon float32)` | `trigger_homelink` `{"lat":n,"lon":n}` | INFO | | | | `vehicleControlTriggerHomelinkAction{ location: LatLong{latitude, longitude} }` | Proxy/Go only |

## D. Climate (INFO)

| CLI | Go | Proxy | Domain | Auth | Fleet | Transport | Payload | Notes |
|---|---|---|---|---|---|---|---|---|
| `climate-on` | `ClimateOn` | `auto_conditioning_start` `{}` | INFO | ✔ | | | `hvacAutoAction{ power_on: true }` | |
| `climate-off` | `ClimateOff` | `auto_conditioning_stop` `{}` | INFO | ✔ | | | `hvacAutoAction{ power_on: false }` | |
| `climate-set-temp TEMP` | `ChangeClimateTemp(driverC, passengerC)` | `set_temps` `{"driver_temp":n,"passenger_temp":n}` (both optional, 0 if missing) | INFO | ✔ | | | `hvacTemperatureAdjustmentAction{ driver_temp_celsius, passenger_temp_celsius, level: { TEMP_MAX } }` | CLI uses the same value for driver and passenger. See below for `TEMP` parsing. |
| `seat-heater SEAT LEVEL` | `SetSeatHeater(map[SeatPosition]Level)` | `remote_seat_heater_request` `{"seat_position":idx,"level":n}` | INFO | ✔ | | | `hvacSeatHeaterActions{ hvacSeatHeaterAction: [{ SEAT_HEATER_<LEVEL>: {}, CAR_SEAT_<POS>: {} }] }` | Both seat and level are Void oneofs. |
| — | `SetSeatCooler(level, seat)` | `remote_seat_cooler_request` `{"seat_position":n,"seat_cooler_level":n}` | INFO | | | | `hvacSeatCoolerActions{ hvacSeatCoolerAction: [{ seat_cooler_level: level+1, seat_position }] }` | FrontLeft/FrontRight only. The proxy passes `seat_cooler_level - 1` as `Level`. |
| `auto-seat-and-climate POSITIONS [STATE]` | `AutoSeatAndClimate([]SeatPosition, enabled)` | `remote_auto_seat_climate_request` `{"auto_seat_position":n,"auto_climate_on":b}` | INFO | ✔ | | | `autoSeatClimateAction{ carseat: [{ on, seat_position }] }` | CLI: `L`/`R`/`LR`, STATE defaults to on. The proxy takes one seat. |
| `steering-wheel-heater STATE` | `SetSteeringWheelHeater(bool)` | `remote_steering_wheel_heater_request` `{"on":b}` | INFO | ✔ | | | `hvacSteeringWheelHeaterAction{ power_on }` | |
| — | `SetPreconditioningMax(on, manualOverride)` | `set_preconditioning_max` `{"on":b,"manual_override":b?}` | INFO | | | | `hvacSetPreconditioningMaxAction{ on, manual_override }` | |
| — | `SetBioweaponDefenseMode(on, manualOverride)` | `set_bioweapon_mode` `{"on":b,"manual_override":b}` (both required) | INFO | | | | `hvacBioweaponModeAction{ on, manual_override }` | |
| — | `SetCabinOverheatProtection(on, fanOnly)` | `set_cabin_overheat_protection` `{"on":b,"fan_only":b?}` | INFO | | | | `setCabinOverheatProtectionAction{ on, fan_only }` | |
| — | `SetCabinOverheatProtectionTemperature(Level)` | `set_cop_temp` `{"cop_temp":n}` | INFO | | | | `setCopTempAction{ cop_activation_temp: ClimateState.CopActivationTemp(n) }` | 0 Unspecified, 1 Low, 2 Medium, 3 High |
| — | `SetClimateKeeperMode(mode, override)` | `set_climate_keeper_mode` `{"climate_keeper_mode":n,"manual_override":b?}` | INFO | | | | `hvacClimateKeeperAction{ ClimateKeeperAction: n, manual_override }` | 0 Off, 1 On, 2 Dog, 3 Camp |

## E. Charging / Power (INFO)

| CLI | Go | Proxy | Domain | Auth | Fleet | Transport | Payload | Notes |
|---|---|---|---|---|---|---|---|---|
| `charging-set-limit PERCENT` | `ChangeChargeLimit(int32)` | `set_charge_limit` `{"percent":n}` | INFO | ✔ | | | `chargingSetLimitAction{ percent }` | |
| `charging-set-amps AMPS` | `SetChargingAmps(int32)` | `set_charging_amps` `{"charging_amps":n}` | INFO | ✔ | | | `setChargingAmpsAction{ charging_amps }` | |
| `charging-start` | `ChargeStart` | `charge_start` `{}` | INFO | ✔ | | | `chargingStartStopAction{ start: {} }` | |
| `charging-stop` | `ChargeStop` | `charge_stop` `{}` | INFO | ✔ | | | `chargingStartStopAction{ stop: {} }` | |
| — | `ChargeMaxRange` | `charge_max_range` `{}` | INFO | | | | `chargingStartStopAction{ start_max_range: {} }` | |
| — | `ChargeStandardRange` | `charge_standard` `{}` | INFO | | | | `chargingStartStopAction{ start_standard: {} }` | |
| `charging-schedule MINS` | `ScheduleCharging(true, MINS*min)` | `set_scheduled_charging` `{"enable":b,"time":minutes}` | INFO | ✔ | | | `scheduledChargingAction{ enabled, charging_time(minutes) }` | |
| `charging-schedule-cancel` | `ScheduleCharging(false, 0)` | `set_scheduled_charging` `{"enable":false}` | INFO | ✔ | | | `scheduledChargingAction{ enabled:false }` | |
| — | `ScheduleDeparture(departAt, offPeakEnd, precondPolicy, offPeakPolicy)` / `ClearScheduledDeparture` | `set_scheduled_departure` `{"enable":b, "departure_time":minutes, "end_off_peak_time":minutes, "off_peak_charging_enabled":b, "off_peak_charging_weekdays_only":b, "preconditioning_enabled":b, "preconditioning_weekdays_only":b}` | INFO | | | | `scheduledDepartureAction{ enabled, departure_time, preconditioning_times{all_week|weekdays}, off_peak_charging_times{...}, off_peak_hours_end_time }` | `enable:false` → `ClearScheduledDeparture`. Policy: weekdays_only → Weekdays, enabled → AllDays, otherwise Off. |
| `charging-schedule-add DAYS TIME LATITUDE LONGITUDE [REPEAT] [ID] [ENABLED]` | `AddChargeSchedule(*ChargeSchedule)` | `add_charge_schedule` `{"days_of_week":"Mon,Tues" , "lat":n, "lon":n, "start_time":minutes?, "start_enabled":b, "end_time":minutes?, "end_enabled":b, "id":n?, "enabled":b, "one_time":b?}` | INFO | ✔ | | | `addChargeScheduleAction: ChargeSchedule{ id, days_of_week, start_enabled, start_time, end_enabled, end_time, one_time, enabled, latitude, longitude }` | ID defaults to `time.Now().Unix()` (in the proxy, when `id` is 0/missing). **The CLI declares the optional argument `ID` but does not parse it** (always a new ID). The CLI prints the generated ID to stdout. |
| `charging-schedule-remove TYPE [ID]` | `RemoveChargeSchedule(id)` (TYPE=id) / `BatchRemoveChargeSchedules(home, work, other)` | `remove_charge_schedule` `{"id":n}` | INFO | ✔ | | | `removeChargeScheduleAction{ id }` / `batchRemoveChargeSchedulesAction{ home, work, other }` | No batch in the proxy. TYPE: `home|work|other|id` (case-insensitive). |
| `precondition-schedule-add DAYS TIME LATITUDE LONGITUDE [REPEAT] [ID] [ENABLED]` | `AddPreconditionSchedule(*PreconditionSchedule)` | `add_precondition_schedule` `{"days_of_week":s, "lat":n, "lon":n, "precondition_time":minutes, "one_time":b?, "id":n?, "enabled":b}` | INFO | ✔ | | | `addPreconditionScheduleAction: PreconditionSchedule{ id, days_of_week, precondition_time, one_time, enabled, latitude, longitude }` | The CLI does parse ID. |
| `precondition-schedule-remove TYPE [ID]` | `RemovePreconditionSchedule(id)` / `BatchRemovePreconditionSchedules(home, work, other)` | `remove_precondition_schedule` `{"id":n}` | INFO | ✔ | | | `removePreconditionScheduleAction{ id }` / `batchRemovePreconditionSchedulesAction{...}` | |
| `low-power-mode STATE` | `SetLowPowerMode(bool)` | `set_low_power_mode` `{"enable":b}` | INFO | ✔ | | | `setLowPowerModeAction{ low_power_mode }` | `low_power_mode_enforced` if low power is being forced |
| `keep-accessory-power STATE` | `SetKeepAccessoryPowerMode(bool)` | `keep_accessory_power_mode` `{"enable":b}` | INFO | ✔ | | | `setKeepAccessoryPowerModeAction{ keep_accessory_power_mode }` | |
| — | — | `set_managed_charge_current_request`, `set_managed_charger_location`, `set_managed_scheduled_charging_time` | (REST) | | | **Fleet** | None | `ErrCommandUseRESTAPI` → forwarded to the Fleet API as-is |

## F. Media (INFO)

| CLI | Go | Proxy | Domain | Auth | Fleet | Transport | Payload | Notes |
|---|---|---|---|---|---|---|---|---|
| `media-set-volume VOLUME` | `SetVolume(float32)` | `adjust_volume` `{"volume":n}` | INFO | ✔ | | | `mediaUpdateVolume{ volume_absolute_float }` | Error if outside range [0,10] |
| `media-volume-up` | `VolumeUp` | `media_volume_up` `{}` | INFO | ✔ | | | `mediaUpdateVolume{ volume_delta: 1 }` | Only the sign matters |
| `media-volume-down` | `VolumeDown` | `media_volume_down` `{}` | INFO | ✔ | | | `mediaUpdateVolume{ volume_delta: -1 }` | |
| `media-next-track` | `MediaNextTrack` | `media_next_track` `{}` | INFO | ✔ | | | `mediaNextTrack {}` | |
| `media-previous-track` | `MediaPreviousTrack` | `media_prev_track` `{}` | INFO | ✔ | | | `mediaPreviousTrack {}` | |
| `media-next-favorite` | `MediaNextFavorite` | `media_next_fav` `{}` | INFO | ✔ | | | `mediaNextFavorite {}` | |
| `media-previous-favorite` | `MediaPreviousFavorite` | `media_prev_fav` `{}` | INFO | ✔ | | | `mediaPreviousFavorite {}` | |
| `media-toggle-playback` | `ToggleMediaPlayback` | `media_toggle_playback` `{}` | INFO | ✔ | | | `mediaPlayAction {}` | |
| — | — | `remote_boombox` | | | | | None | `ErrCommandNotImplemented` → HTTP 400 `{"error":"command not implemented"}` |

## G. Software Update (INFO)

| CLI | Go | Proxy | Domain | Auth | Fleet | Transport | Payload | Notes |
|---|---|---|---|---|---|---|---|---|
| `software-update-start DELAY` | `ScheduleSoftwareUpdate(time.Duration)` | `schedule_software_update` `{"offset_sec":n}` | INFO | ✔ | | | `vehicleControlScheduleSoftwareUpdateAction{ offset_sec }` | DELAY uses `time.ParseDuration` (`2h`, `10m`, `30s`) |
| `software-update-cancel` | `CancelSoftwareUpdate` | `cancel_software_update` `{}` | INFO | ✔ | | | `vehicleControlCancelSoftwareUpdateAction {}` | |

## H. Security Modes / PIN / Guest / Parental Controls (INFO)

| CLI | Go | Proxy | Domain | Auth | Fleet | Transport | Payload | Notes |
|---|---|---|---|---|---|---|---|---|
| `sentry-mode STATE` | `SetSentryMode(bool)` | `set_sentry_mode` `{"on":b}` | INFO | ✔ | | | `vehicleControlSetSentryModeAction{ on }` | |
| `valet-mode-on PIN` | `EnableValetMode(pin)` | `set_valet_mode` `{"on":true,"password":"1234"}` | INFO | ✔ | | | `vehicleControlSetValetModeAction{ on:true, password }` | `ErrInvalidPIN` unless `IsValidPIN` (4 digits) |
| `valet-mode-off` | `DisableValetMode` | `set_valet_mode` `{"on":false}` | INFO | ✔ | | | `vehicleControlSetValetModeAction{ on:false }` | "already off" error becomes nil |
| — | `ResetValetPin` | `reset_valet_pin` `{}` | INFO | | | | `vehicleControlResetValetPinAction {}` | |
| — | `SetPINToDrive(on, pin)` | `set_pin_to_drive` `{"on":b,"password":s?}` | INFO | | | **Fleet** | `vehicleControlSetPinToDriveAction{ on, password }` | Calling over BLE (non-FleetAPIConnector) yields `ErrRequiresEncryption`. Once set, the PIN is kept and a new PIN is ignored → to change it, call `ClearPINToDrive` first. |
| — | `ClearPINToDrive` | `clear_pin_to_drive_admin` `{}` | INFO | | | | `vehicleControlResetPinToDriveAdminAction {}` | |
| — | `ResetPIN` (Deprecated) | `reset_pin_to_drive_pin` `{}` | INFO | | | | `vehicleControlResetPinToDriveAction {}` | |
| — | `ActivateSpeedLimit(pin)` | `speed_limit_activate` `{"pin":s}` | INFO | | | | `drivingSpeedLimitAction{ activate:true, pin }` | |
| — | `DeactivateSpeedLimit(pin)` | `speed_limit_deactivate` `{"pin":s}` | INFO | | | | `drivingSpeedLimitAction{ activate:false, pin }` | |
| — | `ClearSpeedLimitPIN(pin)` | `speed_limit_clear_pin` `{"pin":s}` | INFO | | | | `drivingClearSpeedLimitPinAction{ pin }` | |
| — | `ClearSpeedLimitPINAdminAction` | `speed_limit_clear_pin_admin` `{}` | INFO | | | | `drivingClearSpeedLimitPinAdminAction {}` | |
| — | `SpeedLimitSetLimitMPH(float64)` | `speed_limit_set_limit` `{"limit_mph":n}` | INFO | | | | `drivingSetSpeedLimitAction{ limit_mph }` | |
| `guest-mode-on` / `guest-mode-off` | `SetGuestMode(bool)` | `guest_mode` `{"enable":b}` | INFO | ✔ | | | `guestModeAction: VehicleState.GuestMode{ GuestModeActive }` | For fleet operators |
| `erase-guest-data` | `EraseGuestData` | `erase_user_data` `{}` | INFO | ✔ | | | `eraseUserDataAction {}` | Effective only during Guest Mode |
| `parental-controls-on PIN` | `ParentalControlsActivate(pin)` | `parental_controls_activate` `{"pin":s}` | INFO | ✔ | | | `parentalControlsAction{ activate:true, pin }` | 4-digit check |
| `parental-controls-off PIN` | `ParentalControlsDeactivate(pin)` | `parental_controls_deactivate` `{"pin":s}` | INFO | ✔ | | | `parentalControlsAction{ activate:false, pin }` | |
| `parental-controls-set-speed-limit MPH` | `ParentalControlsSetSpeedLimit(float64)` | `parental_controls_set_speed_limit` `{"limit_mph":n}` | INFO | ✔ | | | `parentalControlsSetSpeedLimitAction{ limit_mph }` | Fails while active |
| `parental-controls-enable-setting SETTING STATE` | `ParentalControlsEnableSetting(setting, bool)` | `parental_controls_enable_setting` `{"setting":"SpeedLimit","enable":b}` | INFO | ✔ | | | `parentalControlsEnableSettingsAction{ setting, enable }` | CLI uses kebab-case, proxy uses enum names (table below) |
| `parental-controls-clear-pin-admin` | `ParentalControlsClearPIN` | `parental_controls_clear_pin_admin` `{}` | INFO | ✔ | | | `parentalControlsClearPinAdminAction {}` | |
| — | `SetVehicleName(name)` | `set_vehicle_name` `{"vehicle_name":s}` | INFO | | | | `setVehicleNameAction{ vehicle_name }` | |

## I. State Queries / Diagnostics / Direct Fleet API Calls

| CLI | Go | Proxy | Domain | Auth | Fleet | Transport | Payload | Notes |
|---|---|---|---|---|---|---|---|---|
| `state CATEGORY` | `GetState(StateCategory) (*carserver.VehicleData, error)` | — (`GET .../vehicle_data` is forwarded to the Fleet API) | INFO | ✔ | | | `getVehicleData{ get<Category>State: {} }` → response `Response.vehicleData` | For BLE. Output protojson. |
| `ping` | `Ping` | — | INFO | ✔ | | | `ping{ ping_id: 1 }` | Checks online + key recognized |
| — | `GetNearbyCharging` | — | INFO | | | | `getNearbyChargingSites{ include_meta_data:true, radius:200, count:10 }` | Does not return response data (errors only) |
| `product-info` | `acct.Get("api/1/products")` | (forwarded) | Fleet API | ✗ | ✔ | Fleet | HTTP GET | |
| `get ENDPOINT` | `acct.Get(endpoint)` | (forwarded) | Fleet API | ✗ | ✔ | Fleet | HTTP GET | `ENDPOINT` is the path only (`api/1/...`) |
| `post ENDPOINT [FILE]` | `acct.Post(endpoint, body)` | (forwarded) | Fleet API | ✗ | ✔ | Fleet | HTTP POST | stdin if FILE is omitted |
| — | — | `navigation_request` | (REST) | | | Fleet | None | `ErrCommandUseRESTAPI` → forwarded |
| — | — | Any other unknown name | | | | | | HTTP 400 body `{"response":null,"error":"invalid_command","error_description":""}` |

---

## Argument Parsing Rules

### tesla-control (`cmd/tesla-control/commands.go`)

| Argument | Rule | Implementation |
|---|---|---|
| `TEMP` | `fmt.Sscanf("%f%s")`. Unit `F`/`f` → `(F-32)*5/9`, `C`/`c` → as-is, otherwise → "temperature units must be C or F". Parsing fails without a unit ("format as 22C or 72F"). | `climate-set-temp` |
| `STATE` | Exactly `on` / `off` (case-insensitive only for parental-controls-enable-setting). | Multiple commands |
| `PERCENT`, `AMPS`, `MINS` | `strconv.Atoi` | |
| `VOLUME` | `strconv.ParseFloat(_, 32)`; range check in `SetVolume` | |
| `MPH` | `strconv.ParseFloat(_, 64)` | |
| `DELAY` | `time.ParseDuration` | |
| `PIN` | Handler passes it through; `EnableValetMode`/`ParentalControls*` check `IsValidPIN` | |
| `DAYS` | Comma-separated, whitespace trimmed, uppercased, then bitwise OR. See table below. | `GetDays` |
| `TIME` (schedule) | `HH:MM` → minutes after midnight (0..1439). `charging-schedule-add` uses `START-END` format; if one side is empty the corresponding `*_enabled=false` (`-6:00`, `20:32-`). | `MinutesAfterMidnight` |
| `LATITUDE`, `LONGITUDE` | float32, error if outside [-180,180] | `GetDegree` |
| `REPEAT` | Exactly `once` → `OneTime=true`; anything else/omitted → weekly repeat | |
| `ENABLED` | true only for the string `"true"` | |
| `ID` | `strconv.ParseUint(_, 10, 64)` — only in `precondition-schedule-add` and `*-schedule-remove TYPE=id` | |
| `TYPE` (schedule-remove) | `home\|work\|other\|id` compared uppercased | |
| `SEAT` | `front-left`, `front-right`, `2nd-row-left`, `2nd-row-center`, `2nd-row-right`, `3rd-row-left`, `3rd-row-right` → `SeatPosition` | `seat-heater` |
| `LEVEL` | `off`(0) `low`(1) `medium`(2) `high`(3) → `vehicle.Level` | |
| `POSITIONS` | Whether the string contains `L`/`R`; if the length differs from the match count → "invalid seat position" (`LR` OK, `X` error) | `auto-seat-and-climate` |
| `CATEGORY` | Lowercase comparison: `charge`, `climate`, `drive`, `location`, `closures`, `charge-schedule`, `precondition-schedule`, `tire-pressure`, `media`, `media-detail`, `software-update`, `parental-controls` | `state` |
| `ROLE` | Looks up `"ROLE_"+upper(ROLE)` in `keys.Role_value`: `owner`, `driver`, `fm`, `vehicle_monitor`, `charging_manager` (`service`=1 and `guest`=8 also exist in the enum but are not in the help text; VCSEC may reject them) | `add-key`, `add-key-request` |
| `FORM_FACTOR` | Looks up `"KEY_FORM_FACTOR_"+upper(FF)` in `vcsec.KeyFormFactor_value`: `nfc_card`(1), `ios_device`(6), `android_device`(7), `cloud_key`(9), (`unknown`=0) | |
| `PUBLIC_KEY` | `protocol.LoadPublicKey` (PEM public/private key, 65-byte binary, hex) | |
| `DOMAIN` | Exactly `vcsec` / `infotainment` | `session-info` |
| `SETTING` | Lowercase comparison: `speed-limit`, `acceleration`, `safety-features`, `curfew`, `browser-blocked`, `theater-blocked`, `arcade-blocked` | `parental-controls-enable-setting` |

`DAYS` bitmask (same for CLI and proxy, `dayNamesBitMask`):

| Name (case-insensitive) | Value |
|---|---|
| `SUN`, `SUNDAY` | 1 |
| `MON`, `MONDAY` | 2 |
| `TUES`, `TUESDAY` | 4 |
| `WED`, `WEDNESDAY` | 8 |
| `THURS`, `THURSDAY` | 16 |
| `FRI`, `FRIDAY` | 32 |
| `SAT`, `SATURDAY` | 64 |
| `ALL` | 127 |
| `WEEKDAYS` | 62 |

`TUE` and `THU` are not recognized.

### Proxy (`pkg/proxy/command.go`)

- Strict JSON types: numbers are `float64`, booleans `bool`, strings `string`. Type mismatch → `NominalError("invalid <key> param")`, missing required → `NominalError("missing <key> param")`. Both return HTTP 400 in the form `{"response":{"result":false,"reason":"..."}}`.
- `seat_position` (`remote_seat_heater_request`): index 0..8 into the `seatPositions` array → `[SeatFrontLeft, SeatFrontRight, SeatSecondRowLeft, SeatSecondRowLeftBack, SeatSecondRowCenter, SeatSecondRowRight, SeatSecondRowRightBack, SeatThirdRowLeft, SeatThirdRowRight]`. Out of range → "invalid seat position". `level` is passed as `vehicle.Level(n)` directly (0..3).
- `seat_position` (`remote_seat_cooler_request`): `HvacSeatCoolerPosition_E` value 1 FrontLeft, 2 FrontRight, otherwise SeatUnknown (→ `SetSeatCooler` returns "invalid seat position"). `seat_cooler_level` is 1..4 (proto: 1 Off, 2 Low, 3 Med, 4 High); the proxy subtracts 1 and passes it as `Level`, and `SetSeatCooler` adds 1 back.
- `auto_seat_position` (`remote_auto_seat_climate_request`): 1 FrontLeft, 2 FrontRight, otherwise Unknown. `auto_climate_on` is required.
- `climate_keeper_mode`: 0 Off, 1 On, 2 Dog, 3 Camp. `manual_override` is optional.
- `cop_temp`: `vehicle.Level(n)` → `CopActivationTemp` (0 Unspecified, 1 Low, 2 Medium, 3 High).
- `time`, `departure_time`, `end_off_peak_time`: number in minutes, optional (0 if missing). Further validation is delegated to the vehicle.
- `days_of_week`: string, same bitmask names as the CLI. Required.
- `start_time`, `end_time`, `precondition_time`: minutes. `start_time`/`end_time` are optional, `precondition_time` is required.
- `id`: optional. If 0 or missing, `time.Now().Unix()`.
- `setting` (`parental_controls_enable_setting`): proto enum names `SpeedLimit`, `Acceleration`, `SafetyFeatures`, `Curfew`, `BrowserBlocked`, `TheaterBlocked`, `ArcadeBlocked`. Otherwise → `NominalError("invalid setting: <s>")`.
- `which_trunk`: optional. `front`/`rear`; otherwise → `NominalError("invalid_value")`; missing → rear.
- `command` (`window_control`): required. `vent`/`close`.

---

## Checklist of Files to Modify When Adding a New Command

1. **protobuf**: Check whether the needed action already exists in `pkg/protocol/protobuf/car_server.proto` (`VehicleAction.vehicle_action_msg` oneof) or `vcsec.proto` (`UnsignedMessage.sub_message`). If not, edit the `.proto` and regenerate `*.pb.go` with `make proto-gen` (requires `protoc` + `protoc-gen-go`). Never reuse or change field numbers.
2. **Go method**: Add `func (v *Vehicle) Xxx(ctx context.Context, ...) error` to the appropriate file in `pkg/vehicle/`.
   - INFO domain: `v.executeCarServerAction(ctx, &carserver.Action_VehicleAction{...})` pattern.
   - VCSEC domain: `payload := vcsec.UnsignedMessage{...}` → `proto.Marshal` → `v.getVCSECResult(ctx, encoded, v.authMethod, doneFn)` pattern. Choose the termination function (`done`) to suit the command type.
   - If there is a transport constraint, return `ErrRequiresBLE` / `ErrRequiresEncryption` via a `v.conn.(connector.FleetAPIConnector)` type assertion.
   - Perform input validation (range, PIN format) inside the method and return a plain `error` or `ErrInvalidPIN`.
3. **CLI**: Add an entry to the `commands` map in `cmd/tesla-control/commands.go` (`help`, `requiresAuth`, `requiresFleetAPI`, `args`, `optional`, `domain` (set if VCSEC-only), `handler`). Wrapping argument parsing errors with `fmt.Errorf("%w: ...", ErrCommandLineArgs)` automatically prints usage.
4. **Proxy**: Add `case "<fleet_api_endpoint_name>"` to the `switch` in `ExtractCommandAction` in `pkg/proxy/command.go`. Read parameters with `params.getString/getBool/getNumber/getDays/getPolicy/getTimeAfterMidnight`. Follow the official Fleet API endpoint names and JSON keys exactly.
5. **Tests**: `cmd/tesla-control/commands_test.go` (command table integrity), `pkg/proxy/command_test.go` (`TestExtractCommandAction` cases), and if needed `pkg/vehicle/vehicle_test.go` (payload verification with a mock connector).
6. **Docs**: The relevant table in this file, [04-go-api-reference.md](04-go-api-reference.md), `../html/07-command-reference.html`, and the repository's `cmd/tesla-control/README.md` (if needed).
7. Pass `./check-all.sh` (build, test, vet, gofmt) or `make test`.

---

## Verification Checklist

- [ ] The result of `grep -cE '^\s+"[a-z0-9-]+": \{' cmd/tesla-control/commands.go` (currently 69) matches the number of CLI commands in this document.
- [ ] Every endpoint from `grep -oE 'case "[a-z_]+"' pkg/proxy/command.go | sort -u` is in this document (74 when excluding the nested case strings `close`, `front`, `rear`, `vent`; including 4 REST fallbacks + 1 unimplemented).
- [ ] Every public command method from `grep -nE '^func \(v \*Vehicle\) [A-Z]' pkg/vehicle/*.go` appears somewhere in the tables (connection/session management methods `Connect`, `StartSession`, `Disconnect`, `SendMessage`, `Send`, `SetMaxLatency`, `PrivateKeyAvailable`, `UpdateCachedSessions`, `LoadCachedSessions`, `VIN` are covered by [04-go-api-reference.md](04-go-api-reference.md)).
- [ ] The new command's `requiresAuth`/`requiresFleetAPI`/`domain` match the actual transport path (`checkReadiness` rules are in [06-cli-tools.md](06-cli-tools.md)).
- [ ] The oneof field names in the Payload column match the `.proto`.
