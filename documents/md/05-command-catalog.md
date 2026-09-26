# 05. 명령 카탈로그

차량 명령 하나하나를 `tesla-control` CLI 이름, `pkg/vehicle` Go 메서드, `tesla-http-proxy` REST 엔드포인트, 대상 도메인, 인증 요구, 전송 제약, 실제로 전송되는 protobuf 로 교차 정리한다. 세 출처가 서로 다르게 노출하는 명령(CLI 전용 / 프록시 전용 / Go 전용)을 모두 포함한다.

관련 파일 (저장소 루트 기준, 최종 출처):
- `cmd/tesla-control/commands.go` — CLI 명령 표 (`var commands = map[string]*Command`)
- `pkg/proxy/command.go` — 프록시 엔드포인트 (`ExtractCommandAction` 의 `switch command`)
- `pkg/vehicle/{vcsec.go,security.go,actions.go,charge.go,climate.go,infotainment.go,state.go}` — Go 메서드
- `pkg/protocol/protobuf/{vcsec.proto,car_server.proto,common.proto,keys.proto}` — 페이로드 정의

관련 문서: [04-go-api-reference.md](04-go-api-reference.md), [06-cli-tools.md](06-cli-tools.md), [07-http-proxy.md](07-http-proxy.md), [08-errors.md](08-errors.md)

---

## 표 읽는 법

| 열 | 의미 |
|---|---|
| CLI | `tesla-control` 명령과 위치 인자. `[ ]` 는 선택 인자. `—` 는 CLI 에 없음. |
| Go | `(*vehicle.Vehicle)` 메서드. |
| 프록시 | `POST /api/1/vehicles/{VIN}/command/<이름>` 의 `<이름>` 과 JSON 본문 키. `—` 는 프록시가 종단 간 인증으로 처리하지 않음(그 경로는 Fleet API 로 그대로 포워딩됨). |
| 도메인 | `VCSEC` = `DOMAIN_VEHICLE_SECURITY`(2), `INFO` = `DOMAIN_INFOTAINMENT`(3). |
| Auth | CLI `requiresAuth` (개인 키 필요). 프록시는 항상 개인 키로 서명한다. |
| Fleet | CLI `requiresFleetAPI` (OAuth 토큰 필요, VIN 불필요). |
| 전송 | `BLE` = BLE 전용, `Fleet` = Fleet API 전용, 빈칸 = 둘 다. |
| 페이로드 | `VCSEC`: `vcsec.UnsignedMessage.sub_message`. `INFO`: `carserver.Action.vehicleAction.vehicle_action_msg` 의 oneof 필드명. |

CLI 의 도메인 결정: 대부분 명령은 `Command.domain` 이 비어 있어(`DomainNone`) 핸드셰이크를 VCSEC+INFO 둘 다 수행한다. `body-controller-state` 만 `domain: protocol.DomainVCSEC` 로 VCSEC 만 핸드셰이크하며, `-ble wake` 는 `configureFlags` 가 VCSEC 만으로 제한한다. `-domain` 플래그로 사용자도 제한할 수 있다.

---

## A. 잠금 / 원격 시동 / 웨이크 (VCSEC RKEAction)

| CLI | Go | 프록시 | 도메인 | Auth | Fleet | 전송 | 페이로드 | 비고 |
|---|---|---|---|---|---|---|---|---|
| `unlock` | `Unlock` | `door_unlock` `{}` | VCSEC | ✔ | | | `RKEAction = RKE_ACTION_UNLOCK` | |
| `lock` | `Lock` | `door_lock` `{}` | VCSEC | ✔ | | | `RKEAction = RKE_ACTION_LOCK` | |
| `drive` | `RemoteDrive` | `remote_start_drive` `{}` | VCSEC | ✔ | | | `RKEAction = RKE_ACTION_REMOTE_DRIVE` | |
| `autosecure-modelx` | `AutoSecureVehicle` | — | VCSEC | ✔ | | | `RKEAction = RKE_ACTION_AUTO_SECURE_VEHICLE` | Model X 팔콘윙 닫고 잠금 |
| `wake` | `Wakeup` | `wake_up` `{}` | BLE: VCSEC / inet: REST | ✗ (BLE 시 실제로는 키 필요) | | | BLE: `RKEAction = RKE_ACTION_WAKE_VEHICLE` (인증 전송) / inet: `POST api/1/vehicles/{VIN}/wake_up` 를 `state=="online"` 까지 10초 간격 반복 | `-ble wake` 는 `configureFlags` 가 `FlagPrivateKey` 를 추가하고 도메인을 VCSEC 로 제한. 키 없이 BLE 로 보내면 `ErrNoSession`. |

VCSEC RKE/Closure 응답: `commandStatus == nil` 인 첫 메시지가 종단(성공). `operationStatus == WAIT` 면 `ErrBusy` 로 재시도.

## B. 키체인 / 세션 (VCSEC)

| CLI | Go | 프록시 | 도메인 | Auth | Fleet | 전송 | 페이로드 | 비고 |
|---|---|---|---|---|---|---|---|---|
| `add-key PUBLIC_KEY ROLE FORM_FACTOR` | `AddKeyWithRole(pub, role, ff)` / `AddKey(pub, isOwner, ff)` | — | VCSEC | ✔ | | | `WhitelistOperation{ addKeyToWhitelistAndAddPermissions: PermissionChange{key, keyRole}, metadataForKey: {keyFormFactor} }` | 기존 Owner 키로 다른 키 추가. 종단: `whitelistOperationStatus` 채워진 메시지. 실패 시 `KeychainError`. |
| `add-key-request PUBLIC_KEY ROLE FORM_FACTOR` | `SendAddKeyRequestWithRole` / `SendAddKeyRequest` | — | VCSEC | ✗ | | **BLE** | `vcsec.ToVCSECMessage{ signedMessage: { protobufMessageAsBytes: <UnsignedMessage(WhitelistOperation 위와 동일)>, signatureType: SIGNATURE_TYPE_PRESENT_KEY } }` — **RoutableMessage 로 감싸지 않고** `conn.Send` 로 직접 전송 | NFC 카드 탭 + 화면 확인으로 승인. 전송 즉시 반환, 승인 여부 미보장. inet 에서 호출 시 `ErrRequiresBLE`. |
| `remove-key PUBLIC_KEY` | `RemoveKey(pub)` | — | VCSEC | ✔ | | | `WhitelistOperation{ removePublicKeyFromWhitelist: PublicKey{PublicKeyRaw} }` | |
| `list-keys` | `KeySummary()` 후 `slotMask` 의 각 비트에 `KeyInfoBySlot(slot)` | — | VCSEC | ✗ | | | `InformationRequest{ INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO }` → `InformationRequest{ GET_WHITELIST_ENTRY_INFO, slot }` | `AuthMethodNone`. 출력: `hex(pubkey)\t<Role>\t<KeyFormFactor>` |
| `session-info PUBLIC_KEY DOMAIN` | `SessionInfo(pub, domain)` | — | 인자 | ✗ | | | `RoutableMessage.session_info_request{ public_key }` | 임의 공개 키의 등록 여부/세션 상태 확인. DOMAIN 은 `vcsec` 또는 `infotainment` (소문자). |
| `rename-key PUBLIC_KEY NAME` | `account.UpdateKey(pub, name)` | (포워딩) `POST api/1/users/keys` | Fleet API | ✗ | ✔ | **Fleet** | HTTP JSON `{public_key(hex), kind:"mobile_device", model:"3rd Party Application", name, tag}` | 차량 Locks 화면 표시 이름. 최초 등록 계정만 수정 가능. |
| `body-controller-state` | `BodyControllerState()` | — | VCSEC (`Command.domain` 설정됨) | ✗ | | | `InformationRequest{ INFORMATION_REQUEST_TYPE_GET_STATUS }` | Infotainment 수면 중에도 BLE 로 동작. 출력 protojson (`EmitDefaultValues`). |

## C. 클로저 / 물리 동작

| CLI | Go | 프록시 | 도메인 | Auth | Fleet | 전송 | 페이로드 | 비고 |
|---|---|---|---|---|---|---|---|---|
| `trunk-open` | `OpenTrunk` | `actuate_trunk` `{"which_trunk":"rear"}` 또는 `{}` | VCSEC | ✔ | | | `closureMoveRequest{ rearTrunk: CLOSURE_MOVE_TYPE_MOVE }` | `OpenTrunk` 와 `ActuateTrunk` 는 동일 페이로드(토글). |
| `trunk-move` | `ActuateTrunk` | `actuate_trunk` | VCSEC | ✔ | | | 위와 동일 | |
| `trunk-close` | `CloseTrunk` | — | VCSEC | ✔ | | | `closureMoveRequest{ rearTrunk: CLOSURE_MOVE_TYPE_CLOSE }` | 파워 트렁크 차종만 |
| `frunk-open` | `OpenFrunk` | `actuate_trunk` `{"which_trunk":"front"}` | VCSEC | ✔ | | | `closureMoveRequest{ frontTrunk: CLOSURE_MOVE_TYPE_MOVE }` | 원격 닫기 없음. `which_trunk` 가 front/rear 외 값이면 프록시 `NominalError("invalid_value")`. |
| `tonneau-open` | `OpenTonneau` | `open_tonneau` `{}` | VCSEC | ✔ | | | `closureMoveRequest{ tonneau: CLOSURE_MOVE_TYPE_OPEN }` | Cybertruck |
| `tonneau-close` | `CloseTonneau` | `close_tonneau` `{}` | VCSEC | ✔ | | | `closureMoveRequest{ tonneau: CLOSURE_MOVE_TYPE_CLOSE }` | |
| `tonneau-stop` | `StopTonneau` | `stop_tonneau` `{}` | VCSEC | ✔ | | | `closureMoveRequest{ tonneau: CLOSURE_MOVE_TYPE_STOP }` | |
| `charge-port-open` | `OpenChargePort` (= `ChargePortOpen`) | `charge_port_door_open` `{}` | INFO | ✔ | | | `chargePortDoorOpen {}` | |
| `charge-port-close` | `CloseChargePort` (= `ChargePortClose`) | `charge_port_door_close` `{}` | INFO | ✔ | | | `chargePortDoorClose {}` | |
| `windows-vent` | `VentWindows` | `window_control` `{"command":"vent"}` | INFO | ✔ | | | `vehicleControlWindowAction{ vent: {} }` | 프록시: lat/lon 불필요. 다른 command 값 → 400 "command must be 'vent' or 'close'" |
| `windows-close` | `CloseWindows` | `window_control` `{"command":"close"}` | INFO | ✔ | | | `vehicleControlWindowAction{ close: {} }` | |
| — | `ChangeSunroofState(level int32)` | — | INFO | | | | `vehicleControlSunroofOpenCloseAction{ absolute_level }` | Go 전용 |
| `honk` | `HonkHorn` | `honk_horn` `{}` | INFO | ✔ | | | `vehicleControlHonkHornAction {}` | |
| `flash-lights` | `FlashLights` | `flash_lights` `{}` | INFO | ✔ | | | `vehicleControlFlashLightsAction {}` | |
| — | `TriggerHomelink(lat, lon float32)` | `trigger_homelink` `{"lat":n,"lon":n}` | INFO | | | | `vehicleControlTriggerHomelinkAction{ location: LatLong{latitude, longitude} }` | 프록시/Go 전용 |

## D. 공조 (INFO)

| CLI | Go | 프록시 | 도메인 | Auth | Fleet | 전송 | 페이로드 | 비고 |
|---|---|---|---|---|---|---|---|---|
| `climate-on` | `ClimateOn` | `auto_conditioning_start` `{}` | INFO | ✔ | | | `hvacAutoAction{ power_on: true }` | |
| `climate-off` | `ClimateOff` | `auto_conditioning_stop` `{}` | INFO | ✔ | | | `hvacAutoAction{ power_on: false }` | |
| `climate-set-temp TEMP` | `ChangeClimateTemp(driverC, passengerC)` | `set_temps` `{"driver_temp":n,"passenger_temp":n}` (둘 다 선택, 누락 시 0) | INFO | ✔ | | | `hvacTemperatureAdjustmentAction{ driver_temp_celsius, passenger_temp_celsius, level: { TEMP_MAX } }` | CLI 는 운전석=조수석 같은 값. `TEMP` 파싱은 아래 참조. |
| `seat-heater SEAT LEVEL` | `SetSeatHeater(map[SeatPosition]Level)` | `remote_seat_heater_request` `{"seat_position":idx,"level":n}` | INFO | ✔ | | | `hvacSeatHeaterActions{ hvacSeatHeaterAction: [{ SEAT_HEATER_<LEVEL>: {}, CAR_SEAT_<POS>: {} }] }` | 좌석·레벨 모두 Void oneof. |
| — | `SetSeatCooler(level, seat)` | `remote_seat_cooler_request` `{"seat_position":n,"seat_cooler_level":n}` | INFO | | | | `hvacSeatCoolerActions{ hvacSeatCoolerAction: [{ seat_cooler_level: level+1, seat_position }] }` | FrontLeft/FrontRight 만. 프록시는 `seat_cooler_level - 1` 을 `Level` 로 넘김. |
| `auto-seat-and-climate POSITIONS [STATE]` | `AutoSeatAndClimate([]SeatPosition, enabled)` | `remote_auto_seat_climate_request` `{"auto_seat_position":n,"auto_climate_on":b}` | INFO | ✔ | | | `autoSeatClimateAction{ carseat: [{ on, seat_position }] }` | CLI: `L`/`R`/`LR`, STATE 기본 on. 프록시는 좌석 1개. |
| `steering-wheel-heater STATE` | `SetSteeringWheelHeater(bool)` | `remote_steering_wheel_heater_request` `{"on":b}` | INFO | ✔ | | | `hvacSteeringWheelHeaterAction{ power_on }` | |
| — | `SetPreconditioningMax(on, manualOverride)` | `set_preconditioning_max` `{"on":b,"manual_override":b?}` | INFO | | | | `hvacSetPreconditioningMaxAction{ on, manual_override }` | |
| — | `SetBioweaponDefenseMode(on, manualOverride)` | `set_bioweapon_mode` `{"on":b,"manual_override":b}` (둘 다 필수) | INFO | | | | `hvacBioweaponModeAction{ on, manual_override }` | |
| — | `SetCabinOverheatProtection(on, fanOnly)` | `set_cabin_overheat_protection` `{"on":b,"fan_only":b?}` | INFO | | | | `setCabinOverheatProtectionAction{ on, fan_only }` | |
| — | `SetCabinOverheatProtectionTemperature(Level)` | `set_cop_temp` `{"cop_temp":n}` | INFO | | | | `setCopTempAction{ cop_activation_temp: ClimateState.CopActivationTemp(n) }` | 0 Unspecified, 1 Low, 2 Medium, 3 High |
| — | `SetClimateKeeperMode(mode, override)` | `set_climate_keeper_mode` `{"climate_keeper_mode":n,"manual_override":b?}` | INFO | | | | `hvacClimateKeeperAction{ ClimateKeeperAction: n, manual_override }` | 0 Off, 1 On, 2 Dog, 3 Camp |

## E. 충전 / 전원 (INFO)

| CLI | Go | 프록시 | 도메인 | Auth | Fleet | 전송 | 페이로드 | 비고 |
|---|---|---|---|---|---|---|---|---|
| `charging-set-limit PERCENT` | `ChangeChargeLimit(int32)` | `set_charge_limit` `{"percent":n}` | INFO | ✔ | | | `chargingSetLimitAction{ percent }` | |
| `charging-set-amps AMPS` | `SetChargingAmps(int32)` | `set_charging_amps` `{"charging_amps":n}` | INFO | ✔ | | | `setChargingAmpsAction{ charging_amps }` | |
| `charging-start` | `ChargeStart` | `charge_start` `{}` | INFO | ✔ | | | `chargingStartStopAction{ start: {} }` | |
| `charging-stop` | `ChargeStop` | `charge_stop` `{}` | INFO | ✔ | | | `chargingStartStopAction{ stop: {} }` | |
| — | `ChargeMaxRange` | `charge_max_range` `{}` | INFO | | | | `chargingStartStopAction{ start_max_range: {} }` | |
| — | `ChargeStandardRange` | `charge_standard` `{}` | INFO | | | | `chargingStartStopAction{ start_standard: {} }` | |
| `charging-schedule MINS` | `ScheduleCharging(true, MINS*min)` | `set_scheduled_charging` `{"enable":b,"time":분}` | INFO | ✔ | | | `scheduledChargingAction{ enabled, charging_time(분) }` | |
| `charging-schedule-cancel` | `ScheduleCharging(false, 0)` | `set_scheduled_charging` `{"enable":false}` | INFO | ✔ | | | `scheduledChargingAction{ enabled:false }` | |
| — | `ScheduleDeparture(departAt, offPeakEnd, precondPolicy, offPeakPolicy)` / `ClearScheduledDeparture` | `set_scheduled_departure` `{"enable":b, "departure_time":분, "end_off_peak_time":분, "off_peak_charging_enabled":b, "off_peak_charging_weekdays_only":b, "preconditioning_enabled":b, "preconditioning_weekdays_only":b}` | INFO | | | | `scheduledDepartureAction{ enabled, departure_time, preconditioning_times{all_week|weekdays}, off_peak_charging_times{...}, off_peak_hours_end_time }` | `enable:false` → `ClearScheduledDeparture`. 정책: weekdays_only → Weekdays, enabled → AllDays, 아니면 Off. |
| `charging-schedule-add DAYS TIME LATITUDE LONGITUDE [REPEAT] [ID] [ENABLED]` | `AddChargeSchedule(*ChargeSchedule)` | `add_charge_schedule` `{"days_of_week":"Mon,Tues" , "lat":n, "lon":n, "start_time":분?, "start_enabled":b, "end_time":분?, "end_enabled":b, "id":n?, "enabled":b, "one_time":b?}` | INFO | ✔ | | | `addChargeScheduleAction: ChargeSchedule{ id, days_of_week, start_enabled, start_time, end_enabled, end_time, one_time, enabled, latitude, longitude }` | ID 기본 `time.Now().Unix()` (프록시는 `id` 가 0/누락일 때). **CLI 는 선택 인자 `ID` 를 선언만 하고 파싱하지 않는다**(항상 새 ID). CLI 는 생성된 ID 를 stdout 에 출력. |
| `charging-schedule-remove TYPE [ID]` | `RemoveChargeSchedule(id)` (TYPE=id) / `BatchRemoveChargeSchedules(home, work, other)` | `remove_charge_schedule` `{"id":n}` | INFO | ✔ | | | `removeChargeScheduleAction{ id }` / `batchRemoveChargeSchedulesAction{ home, work, other }` | 프록시에는 batch 없음. TYPE: `home|work|other|id` (대소문자 무관). |
| `precondition-schedule-add DAYS TIME LATITUDE LONGITUDE [REPEAT] [ID] [ENABLED]` | `AddPreconditionSchedule(*PreconditionSchedule)` | `add_precondition_schedule` `{"days_of_week":s, "lat":n, "lon":n, "precondition_time":분, "one_time":b?, "id":n?, "enabled":b}` | INFO | ✔ | | | `addPreconditionScheduleAction: PreconditionSchedule{ id, days_of_week, precondition_time, one_time, enabled, latitude, longitude }` | CLI 는 ID 파싱함. |
| `precondition-schedule-remove TYPE [ID]` | `RemovePreconditionSchedule(id)` / `BatchRemovePreconditionSchedules(home, work, other)` | `remove_precondition_schedule` `{"id":n}` | INFO | ✔ | | | `removePreconditionScheduleAction{ id }` / `batchRemovePreconditionSchedulesAction{...}` | |
| `low-power-mode STATE` | `SetLowPowerMode(bool)` | `set_low_power_mode` `{"enable":b}` | INFO | ✔ | | | `setLowPowerModeAction{ low_power_mode }` | 강제 저전력 중이면 `low_power_mode_enforced` |
| `keep-accessory-power STATE` | `SetKeepAccessoryPowerMode(bool)` | `keep_accessory_power_mode` `{"enable":b}` | INFO | ✔ | | | `setKeepAccessoryPowerModeAction{ keep_accessory_power_mode }` | |
| — | — | `set_managed_charge_current_request`, `set_managed_charger_location`, `set_managed_scheduled_charging_time` | (REST) | | | **Fleet** | 없음 | `ErrCommandUseRESTAPI` → Fleet API 로 그대로 포워딩 |

## F. 미디어 (INFO)

| CLI | Go | 프록시 | 도메인 | Auth | Fleet | 전송 | 페이로드 | 비고 |
|---|---|---|---|---|---|---|---|---|
| `media-set-volume VOLUME` | `SetVolume(float32)` | `adjust_volume` `{"volume":n}` | INFO | ✔ | | | `mediaUpdateVolume{ volume_absolute_float }` | 범위 [0,10] 아니면 오류 |
| `media-volume-up` | `VolumeUp` | `media_volume_up` `{}` | INFO | ✔ | | | `mediaUpdateVolume{ volume_delta: 1 }` | 부호만 의미 |
| `media-volume-down` | `VolumeDown` | `media_volume_down` `{}` | INFO | ✔ | | | `mediaUpdateVolume{ volume_delta: -1 }` | |
| `media-next-track` | `MediaNextTrack` | `media_next_track` `{}` | INFO | ✔ | | | `mediaNextTrack {}` | |
| `media-previous-track` | `MediaPreviousTrack` | `media_prev_track` `{}` | INFO | ✔ | | | `mediaPreviousTrack {}` | |
| `media-next-favorite` | `MediaNextFavorite` | `media_next_fav` `{}` | INFO | ✔ | | | `mediaNextFavorite {}` | |
| `media-previous-favorite` | `MediaPreviousFavorite` | `media_prev_fav` `{}` | INFO | ✔ | | | `mediaPreviousFavorite {}` | |
| `media-toggle-playback` | `ToggleMediaPlayback` | `media_toggle_playback` `{}` | INFO | ✔ | | | `mediaPlayAction {}` | |
| — | — | `remote_boombox` | | | | | 없음 | `ErrCommandNotImplemented` → HTTP 400 `{"error":"command not implemented"}` |

## G. 소프트웨어 업데이트 (INFO)

| CLI | Go | 프록시 | 도메인 | Auth | Fleet | 전송 | 페이로드 | 비고 |
|---|---|---|---|---|---|---|---|---|
| `software-update-start DELAY` | `ScheduleSoftwareUpdate(time.Duration)` | `schedule_software_update` `{"offset_sec":n}` | INFO | ✔ | | | `vehicleControlScheduleSoftwareUpdateAction{ offset_sec }` | DELAY 는 `time.ParseDuration` (`2h`, `10m`, `30s`) |
| `software-update-cancel` | `CancelSoftwareUpdate` | `cancel_software_update` `{}` | INFO | ✔ | | | `vehicleControlCancelSoftwareUpdateAction {}` | |

## H. 보안 모드 / PIN / 게스트 / 자녀 보호 (INFO)

| CLI | Go | 프록시 | 도메인 | Auth | Fleet | 전송 | 페이로드 | 비고 |
|---|---|---|---|---|---|---|---|---|
| `sentry-mode STATE` | `SetSentryMode(bool)` | `set_sentry_mode` `{"on":b}` | INFO | ✔ | | | `vehicleControlSetSentryModeAction{ on }` | |
| `valet-mode-on PIN` | `EnableValetMode(pin)` | `set_valet_mode` `{"on":true,"password":"1234"}` | INFO | ✔ | | | `vehicleControlSetValetModeAction{ on:true, password }` | `IsValidPIN` (4자리 숫자) 아니면 `ErrInvalidPIN` |
| `valet-mode-off` | `DisableValetMode` | `set_valet_mode` `{"on":false}` | INFO | ✔ | | | `vehicleControlSetValetModeAction{ on:false }` | "already off" 오류는 nil 로 |
| — | `ResetValetPin` | `reset_valet_pin` `{}` | INFO | | | | `vehicleControlResetValetPinAction {}` | |
| — | `SetPINToDrive(on, pin)` | `set_pin_to_drive` `{"on":b,"password":s?}` | INFO | | | **Fleet** | `vehicleControlSetPinToDriveAction{ on, password }` | BLE(비-FleetAPIConnector)에서 호출하면 `ErrRequiresEncryption`. 한 번 설정된 PIN 은 유지되며 새 PIN 은 무시됨 → 변경하려면 `ClearPINToDrive` 먼저. |
| — | `ClearPINToDrive` | `clear_pin_to_drive_admin` `{}` | INFO | | | | `vehicleControlResetPinToDriveAdminAction {}` | |
| — | `ResetPIN` (Deprecated) | `reset_pin_to_drive_pin` `{}` | INFO | | | | `vehicleControlResetPinToDriveAction {}` | |
| — | `ActivateSpeedLimit(pin)` | `speed_limit_activate` `{"pin":s}` | INFO | | | | `drivingSpeedLimitAction{ activate:true, pin }` | |
| — | `DeactivateSpeedLimit(pin)` | `speed_limit_deactivate` `{"pin":s}` | INFO | | | | `drivingSpeedLimitAction{ activate:false, pin }` | |
| — | `ClearSpeedLimitPIN(pin)` | `speed_limit_clear_pin` `{"pin":s}` | INFO | | | | `drivingClearSpeedLimitPinAction{ pin }` | |
| — | `ClearSpeedLimitPINAdminAction` | `speed_limit_clear_pin_admin` `{}` | INFO | | | | `drivingClearSpeedLimitPinAdminAction {}` | |
| — | `SpeedLimitSetLimitMPH(float64)` | `speed_limit_set_limit` `{"limit_mph":n}` | INFO | | | | `drivingSetSpeedLimitAction{ limit_mph }` | |
| `guest-mode-on` / `guest-mode-off` | `SetGuestMode(bool)` | `guest_mode` `{"enable":b}` | INFO | ✔ | | | `guestModeAction: VehicleState.GuestMode{ GuestModeActive }` | 플릿 운영자용 |
| `erase-guest-data` | `EraseGuestData` | `erase_user_data` `{}` | INFO | ✔ | | | `eraseUserDataAction {}` | Guest Mode 중에만 효과 |
| `parental-controls-on PIN` | `ParentalControlsActivate(pin)` | `parental_controls_activate` `{"pin":s}` | INFO | ✔ | | | `parentalControlsAction{ activate:true, pin }` | 4자리 검사 |
| `parental-controls-off PIN` | `ParentalControlsDeactivate(pin)` | `parental_controls_deactivate` `{"pin":s}` | INFO | ✔ | | | `parentalControlsAction{ activate:false, pin }` | |
| `parental-controls-set-speed-limit MPH` | `ParentalControlsSetSpeedLimit(float64)` | `parental_controls_set_speed_limit` `{"limit_mph":n}` | INFO | ✔ | | | `parentalControlsSetSpeedLimitAction{ limit_mph }` | 활성 중이면 실패 |
| `parental-controls-enable-setting SETTING STATE` | `ParentalControlsEnableSetting(setting, bool)` | `parental_controls_enable_setting` `{"setting":"SpeedLimit","enable":b}` | INFO | ✔ | | | `parentalControlsEnableSettingsAction{ setting, enable }` | CLI 는 kebab-case, 프록시는 enum 이름(아래 표) |
| `parental-controls-clear-pin-admin` | `ParentalControlsClearPIN` | `parental_controls_clear_pin_admin` `{}` | INFO | ✔ | | | `parentalControlsClearPinAdminAction {}` | |
| — | `SetVehicleName(name)` | `set_vehicle_name` `{"vehicle_name":s}` | INFO | | | | `setVehicleNameAction{ vehicle_name }` | |

## I. 상태 조회 / 진단 / Fleet API 직접 호출

| CLI | Go | 프록시 | 도메인 | Auth | Fleet | 전송 | 페이로드 | 비고 |
|---|---|---|---|---|---|---|---|---|
| `state CATEGORY` | `GetState(StateCategory) (*carserver.VehicleData, error)` | — (`GET .../vehicle_data` 는 Fleet API 로 포워딩) | INFO | ✔ | | | `getVehicleData{ get<Category>State: {} }` → 응답 `Response.vehicleData` | BLE 용. 출력 protojson. |
| `ping` | `Ping` | — | INFO | ✔ | | | `ping{ ping_id: 1 }` | 온라인+키 인식 확인 |
| — | `GetNearbyCharging` | — | INFO | | | | `getNearbyChargingSites{ include_meta_data:true, radius:200, count:10 }` | 응답 데이터를 반환하지 않음(오류만) |
| `product-info` | `acct.Get("api/1/products")` | (포워딩) | Fleet API | ✗ | ✔ | Fleet | HTTP GET | |
| `get ENDPOINT` | `acct.Get(endpoint)` | (포워딩) | Fleet API | ✗ | ✔ | Fleet | HTTP GET | `ENDPOINT` 는 경로만 (`api/1/...`) |
| `post ENDPOINT [FILE]` | `acct.Post(endpoint, body)` | (포워딩) | Fleet API | ✗ | ✔ | Fleet | HTTP POST | FILE 없으면 stdin |
| — | — | `navigation_request` | (REST) | | | Fleet | 없음 | `ErrCommandUseRESTAPI` → 포워딩 |
| — | — | 그 외 알 수 없는 이름 | | | | | | HTTP 400 본문 `{"response":null,"error":"invalid_command","error_description":""}` |

---

## 인자 파싱 규칙

### tesla-control (`cmd/tesla-control/commands.go`)

| 인자 | 규칙 | 구현 |
|---|---|---|
| `TEMP` | `fmt.Sscanf("%f%s")`. 단위 `F`/`f` → `(F-32)*5/9`, `C`/`c` → 그대로, 그 외 → "temperature units must be C or F". 단위 없으면 파싱 실패("format as 22C or 72F"). | `climate-set-temp` |
| `STATE` | 정확히 `on` / `off` (parental-controls-enable-setting 만 대소문자 무관). | 여러 명령 |
| `PERCENT`, `AMPS`, `MINS` | `strconv.Atoi` | |
| `VOLUME` | `strconv.ParseFloat(_, 32)`; 범위 검사는 `SetVolume` | |
| `MPH` | `strconv.ParseFloat(_, 64)` | |
| `DELAY` | `time.ParseDuration` | |
| `PIN` | 핸들러는 그대로 전달; `EnableValetMode`/`ParentalControls*` 가 `IsValidPIN` 검사 | |
| `DAYS` | 쉼표 구분, 공백 trim, 대문자화 후 비트 OR. 아래 표. | `GetDays` |
| `TIME` (스케줄) | `HH:MM` → 자정 이후 분(0..1439). `charging-schedule-add` 는 `START-END` 형식이며 한쪽이 비면 해당 `*_enabled=false` (`-6:00`, `20:32-`). | `MinutesAfterMidnight` |
| `LATITUDE`, `LONGITUDE` | float32, [-180,180] 벗어나면 오류 | `GetDegree` |
| `REPEAT` | 정확히 `once` 면 `OneTime=true`, 그 외/생략 → 주간 반복 | |
| `ENABLED` | 문자열 `"true"` 일 때만 true | |
| `ID` | `strconv.ParseUint(_, 10, 64)` — `precondition-schedule-add`, `*-schedule-remove TYPE=id` 에서만 | |
| `TYPE` (schedule-remove) | `home\|work\|other\|id` 대문자 비교 | |
| `SEAT` | `front-left`, `front-right`, `2nd-row-left`, `2nd-row-center`, `2nd-row-right`, `3rd-row-left`, `3rd-row-right` → `SeatPosition` | `seat-heater` |
| `LEVEL` | `off`(0) `low`(1) `medium`(2) `high`(3) → `vehicle.Level` | |
| `POSITIONS` | 문자열에 `L`/`R` 포함 여부; 길이가 매칭 수와 다르면 "invalid seat position" (`LR` OK, `X` 오류) | `auto-seat-and-climate` |
| `CATEGORY` | 소문자 비교: `charge`, `climate`, `drive`, `location`, `closures`, `charge-schedule`, `precondition-schedule`, `tire-pressure`, `media`, `media-detail`, `software-update`, `parental-controls` | `state` |
| `ROLE` | `"ROLE_"+upper(ROLE)` 을 `keys.Role_value` 에서 조회: `owner`, `driver`, `fm`, `vehicle_monitor`, `charging_manager` (`service`=1, `guest`=8 도 enum 에는 있으나 도움말에 없음; VCSEC 가 거부할 수 있음) | `add-key`, `add-key-request` |
| `FORM_FACTOR` | `"KEY_FORM_FACTOR_"+upper(FF)` 을 `vcsec.KeyFormFactor_value` 에서 조회: `nfc_card`(1), `ios_device`(6), `android_device`(7), `cloud_key`(9), (`unknown`=0) | |
| `PUBLIC_KEY` | `protocol.LoadPublicKey` (PEM 공개/개인 키, 65바이트 바이너리, hex) | |
| `DOMAIN` | 정확히 `vcsec` / `infotainment` | `session-info` |
| `SETTING` | 소문자 비교: `speed-limit`, `acceleration`, `safety-features`, `curfew`, `browser-blocked`, `theater-blocked`, `arcade-blocked` | `parental-controls-enable-setting` |

`DAYS` 비트마스크 (CLI 와 프록시 동일, `dayNamesBitMask`):

| 이름 (대소문자 무관) | 값 |
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

`TUE`, `THU` 는 인식하지 않는다.

### 프록시 (`pkg/proxy/command.go`)

- JSON 타입 엄격: 숫자는 `float64`, 불리언은 `bool`, 문자열은 `string`. 타입 불일치 → `NominalError("invalid <key> param")`, 필수 누락 → `NominalError("missing <key> param")`. 둘 다 HTTP 400 으로 `{"response":{"result":false,"reason":"..."}}` 형태.
- `seat_position` (`remote_seat_heater_request`): `seatPositions` 배열 인덱스 0..8 → `[SeatFrontLeft, SeatFrontRight, SeatSecondRowLeft, SeatSecondRowLeftBack, SeatSecondRowCenter, SeatSecondRowRight, SeatSecondRowRightBack, SeatThirdRowLeft, SeatThirdRowRight]`. 범위 밖 → "invalid seat position". `level` 은 `vehicle.Level(n)` 그대로 (0..3).
- `seat_position` (`remote_seat_cooler_request`): `HvacSeatCoolerPosition_E` 값 1 FrontLeft, 2 FrontRight, 그 외 SeatUnknown(→ `SetSeatCooler` 가 "invalid seat position"). `seat_cooler_level` 은 1..4 (proto: 1 Off, 2 Low, 3 Med, 4 High) 이며 프록시가 1 을 빼서 `Level` 로 넘기고 `SetSeatCooler` 가 다시 1 을 더한다.
- `auto_seat_position` (`remote_auto_seat_climate_request`): 1 FrontLeft, 2 FrontRight, 그 외 Unknown. `auto_climate_on` 필수.
- `climate_keeper_mode`: 0 Off, 1 On, 2 Dog, 3 Camp. `manual_override` 선택.
- `cop_temp`: `vehicle.Level(n)` → `CopActivationTemp` (0 Unspecified, 1 Low, 2 Medium, 3 High).
- `time`, `departure_time`, `end_off_peak_time`: 분 단위 숫자, 선택(누락 시 0). 추가 검증은 차량에 위임.
- `days_of_week`: 문자열, CLI 와 같은 비트마스크 이름. 필수.
- `start_time`, `end_time`, `precondition_time`: 분. `start_time`/`end_time` 은 선택, `precondition_time` 필수.
- `id`: 선택. 0 또는 누락이면 `time.Now().Unix()`.
- `setting` (`parental_controls_enable_setting`): proto enum 이름 `SpeedLimit`, `Acceleration`, `SafetyFeatures`, `Curfew`, `BrowserBlocked`, `TheaterBlocked`, `ArcadeBlocked`. 그 외 → `NominalError("invalid setting: <s>")`.
- `which_trunk`: 선택. `front`/`rear`, 그 외 → `NominalError("invalid_value")`, 누락 → rear.
- `command` (`window_control`): 필수. `vent`/`close`.

---

## 새 명령을 추가할 때 수정할 파일 체크리스트

1. **protobuf**: 필요한 액션이 `pkg/protocol/protobuf/car_server.proto` (`VehicleAction.vehicle_action_msg` oneof) 또는 `vcsec.proto` (`UnsignedMessage.sub_message`) 에 이미 있는지 확인. 없으면 `.proto` 수정 후 `make proto-gen` (`protoc` + `protoc-gen-go` 필요) 으로 `*.pb.go` 재생성. 필드 번호는 절대 재사용/변경하지 않는다.
2. **Go 메서드**: `pkg/vehicle/` 의 알맞은 파일에 `func (v *Vehicle) Xxx(ctx context.Context, ...) error` 추가.
   - INFO 도메인: `v.executeCarServerAction(ctx, &carserver.Action_VehicleAction{...})` 패턴.
   - VCSEC 도메인: `payload := vcsec.UnsignedMessage{...}` → `proto.Marshal` → `v.getVCSECResult(ctx, encoded, v.authMethod, doneFn)` 패턴. 종단 판정 함수(`done`)를 명령 종류에 맞게 정한다.
   - 전송 제약이 있으면 `v.conn.(connector.FleetAPIConnector)` 단언으로 `ErrRequiresBLE` / `ErrRequiresEncryption` 반환.
   - 입력 검증(범위, PIN 형식)은 메서드 안에서 수행하고 일반 `error` 또는 `ErrInvalidPIN` 반환.
3. **CLI**: `cmd/tesla-control/commands.go` 의 `commands` 맵에 항목 추가 (`help`, `requiresAuth`, `requiresFleetAPI`, `args`, `optional`, `domain`(VCSEC 전용이면 설정), `handler`). 인자 파싱 오류는 `fmt.Errorf("%w: ...", ErrCommandLineArgs)` 로 감싸면 사용법이 자동 출력된다.
4. **프록시**: `pkg/proxy/command.go` 의 `ExtractCommandAction` `switch` 에 `case "<fleet_api_endpoint_name>"` 추가. 파라미터는 `params.getString/getBool/getNumber/getDays/getPolicy/getTimeAfterMidnight` 로 읽는다. Fleet API 공식 엔드포인트 이름과 JSON 키를 그대로 따른다.
5. **테스트**: `cmd/tesla-control/commands_test.go` (명령 표 무결성), `pkg/proxy/command_test.go` (`TestExtractCommandAction` 케이스), 필요 시 `pkg/vehicle/vehicle_test.go` (mock connector 로 페이로드 검증).
6. **문서**: 이 파일의 해당 표, [04-go-api-reference.md](04-go-api-reference.md), `../html/07-command-reference.html`, 저장소 `cmd/tesla-control/README.md`(필요 시).
7. `./check-all.sh` (build, test, vet, gofmt) 또는 `make test` 통과.

---

## 검증 체크리스트

- [ ] `grep -cE '^\s+"[a-z0-9-]+": \{' cmd/tesla-control/commands.go` 결과(현재 69)와 이 문서의 CLI 명령 수가 일치한다.
- [ ] `grep -oE 'case "[a-z_]+"' pkg/proxy/command.go | sort -u` 의 모든 엔드포인트가 이 문서에 있다 (중첩 case 문자열 `close`, `front`, `rear`, `vent` 제외 시 74개; REST 폴백 4개 + 미구현 1개 포함).
- [ ] `grep -nE '^func \(v \*Vehicle\) [A-Z]' pkg/vehicle/*.go` 의 모든 공개 명령 메서드가 표 어딘가에 있다 (연결·세션 관리 메서드 `Connect`, `StartSession`, `Disconnect`, `SendMessage`, `Send`, `SetMaxLatency`, `PrivateKeyAvailable`, `UpdateCachedSessions`, `LoadCachedSessions`, `VIN` 은 [04-go-api-reference.md](04-go-api-reference.md) 담당).
- [ ] 새 명령의 `requiresAuth`/`requiresFleetAPI`/`domain` 이 실제 전송 경로와 일치한다 (`checkReadiness` 규칙은 [06-cli-tools.md](06-cli-tools.md)).
- [ ] 페이로드 열의 oneof 필드명이 `.proto` 와 일치한다.
