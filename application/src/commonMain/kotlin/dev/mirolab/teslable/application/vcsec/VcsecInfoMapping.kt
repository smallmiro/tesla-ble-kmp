package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.keys.Role
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.model.ClosureState
import dev.mirolab.teslable.model.Closures
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.LockState
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.SleepStatus
import dev.mirolab.teslable.model.UserPresence
import dev.mirolab.teslable.model.VehicleStatus
import dev.mirolab.teslable.model.WhitelistEntry
import dev.mirolab.teslable.model.WhitelistSummary
import dev.mirolab.teslable.protocol.unknownVarint
import com.tesla.generated.vcsec.KeyFormFactor as WireFormFactor
import com.tesla.generated.vcsec.VehicleStatus as WireVehicleStatus

/** Wire VCSEC 응답 → 공개 값 객체(SDD §7.3). 모르는 enum 값은 `unknownFields`로 찾아 `UNKNOWN`으로(Wire 기본값으로 보이지 않게). */
@OptIn(InternalTeslableApi::class)
internal object VcsecInfoMapping {
    private const val LOCK_STATE_TAG = 2 // vcsec.proto VehicleStatus.vehicleLockState
    private const val KEY_ROLE_TAG = 7 // WhitelistEntryInfo.keyRole
    private const val FORM_FACTOR_TAG = 1 // KeyMetadata.keyFormFactor
    private const val FRONT_DRIVER_DOOR_TAG = 1 // ClosureStatuses.frontDriverDoor
    private const val FRONT_PASSENGER_DOOR_TAG = 2 // ClosureStatuses.frontPassengerDoor
    private const val REAR_DRIVER_DOOR_TAG = 3 // ClosureStatuses.rearDriverDoor
    private const val REAR_PASSENGER_DOOR_TAG = 4 // ClosureStatuses.rearPassengerDoor
    private const val REAR_TRUNK_TAG = 5 // ClosureStatuses.rearTrunk
    private const val FRONT_TRUNK_TAG = 6 // ClosureStatuses.frontTrunk
    private const val CHARGE_PORT_TAG = 7 // ClosureStatuses.chargePort
    private const val TONNEAU_TAG = 8 // ClosureStatuses.tonneau

    fun status(wire: WireVehicleStatus): VehicleStatus =
        VehicleStatus(
            lockState = if (wire.unknownFields.unknownVarint(LOCK_STATE_TAG) != null) LockState.UNKNOWN else lock(wire.vehicleLockState),
            closures = wire.closureStatuses?.let(::closures),
            sleepStatus =
                when (wire.vehicleSleepStatus) {
                    VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE -> SleepStatus.AWAKE
                    VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP -> SleepStatus.ASLEEP
                    else -> SleepStatus.UNKNOWN
                },
            userPresence =
                when (wire.userPresence) {
                    UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT -> UserPresence.NOT_PRESENT
                    UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT -> UserPresence.PRESENT
                    else -> UserPresence.UNKNOWN
                },
            tonneauPercentOpen = wire.detailedClosureStatus?.tonneauPercentOpen,
        )

    private fun lock(state: VehicleLockState_E): LockState =
        when (state) {
            VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED -> LockState.UNLOCKED
            VehicleLockState_E.VEHICLELOCKSTATE_LOCKED -> LockState.LOCKED
            VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED -> LockState.INTERNAL_LOCKED
            VehicleLockState_E.VEHICLELOCKSTATE_SELECTIVE_UNLOCKED -> LockState.SELECTIVE_UNLOCKED
        }

    private fun closures(wire: ClosureStatuses): Closures {
        fun state(
            tag: Int,
            value: ClosureState_E,
        ): ClosureState = if (wire.unknownFields.unknownVarint(tag) != null) ClosureState.UNKNOWN else closure(value)
        return Closures(
            frontDriverDoor = state(FRONT_DRIVER_DOOR_TAG, wire.frontDriverDoor),
            frontPassengerDoor = state(FRONT_PASSENGER_DOOR_TAG, wire.frontPassengerDoor),
            rearDriverDoor = state(REAR_DRIVER_DOOR_TAG, wire.rearDriverDoor),
            rearPassengerDoor = state(REAR_PASSENGER_DOOR_TAG, wire.rearPassengerDoor),
            rearTrunk = state(REAR_TRUNK_TAG, wire.rearTrunk),
            frontTrunk = state(FRONT_TRUNK_TAG, wire.frontTrunk),
            chargePort = state(CHARGE_PORT_TAG, wire.chargePort),
            tonneau = state(TONNEAU_TAG, wire.tonneau),
        )
    }

    private fun closure(value: ClosureState_E): ClosureState =
        when (value) {
            ClosureState_E.CLOSURESTATE_CLOSED -> ClosureState.CLOSED
            ClosureState_E.CLOSURESTATE_OPEN -> ClosureState.OPEN
            ClosureState_E.CLOSURESTATE_AJAR -> ClosureState.AJAR
            ClosureState_E.CLOSURESTATE_UNKNOWN -> ClosureState.UNKNOWN
            ClosureState_E.CLOSURESTATE_FAILED_UNLATCH -> ClosureState.FAILED_UNLATCH
            ClosureState_E.CLOSURESTATE_OPENING -> ClosureState.OPENING
            ClosureState_E.CLOSURESTATE_CLOSING -> ClosureState.CLOSING
        }

    fun summary(wire: WhitelistInfo): WhitelistSummary = WhitelistSummary(wire.numberOfEntries, wire.slotMask.toUInt())

    fun entry(wire: WhitelistEntryInfo): WhitelistEntry =
        WhitelistEntry(
            slot = wire.slot,
            publicKey =
                wire.publicKey
                    ?.PublicKeyRaw
                    ?.toByteArray()
                    ?.let { runCatching { PublicKeyBytes(it) }.getOrNull() },
            keyIdSha1Hex =
                wire.keyId
                    ?.publicKeySHA1
                    ?.takeIf { it.size > 0 }
                    ?.hex(),
            role = if (wire.unknownFields.unknownVarint(KEY_ROLE_TAG) != null) KeyRole.UNKNOWN else role(wire.keyRole),
            formFactor =
                wire.metadataForKey?.let { meta ->
                    if (meta.unknownFields.unknownVarint(FORM_FACTOR_TAG) != null) KeyFormFactor.UNKNOWN else formFactor(meta.keyFormFactor)
                } ?: KeyFormFactor.UNKNOWN,
        )

    private fun role(role: Role): KeyRole =
        when (role) {
            Role.ROLE_NONE -> KeyRole.NONE
            Role.ROLE_SERVICE -> KeyRole.SERVICE
            Role.ROLE_OWNER -> KeyRole.OWNER
            Role.ROLE_DRIVER -> KeyRole.DRIVER
            Role.ROLE_FM -> KeyRole.FLEET_MANAGER
            Role.ROLE_VEHICLE_MONITOR -> KeyRole.VEHICLE_MONITOR
            Role.ROLE_CHARGING_MANAGER -> KeyRole.CHARGING_MANAGER
            Role.ROLE_GUEST -> KeyRole.GUEST
        }

    private fun formFactor(value: WireFormFactor): KeyFormFactor =
        when (value) {
            WireFormFactor.KEY_FORM_FACTOR_UNKNOWN -> KeyFormFactor.UNKNOWN
            WireFormFactor.KEY_FORM_FACTOR_NFC_CARD -> KeyFormFactor.NFC_CARD
            WireFormFactor.KEY_FORM_FACTOR_IOS_DEVICE -> KeyFormFactor.IOS_DEVICE
            WireFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE -> KeyFormFactor.ANDROID_DEVICE
            WireFormFactor.KEY_FORM_FACTOR_CLOUD_KEY -> KeyFormFactor.CLOUD_KEY
        }
}
