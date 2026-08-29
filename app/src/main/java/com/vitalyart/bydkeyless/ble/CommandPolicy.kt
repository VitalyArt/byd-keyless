package com.vitalyart.bydkeyless.ble

import com.vitalyart.bydkeyless.model.*

/** A fail-closed policy. A command is never constructed or written until this returns null. */
object CommandPolicy {
    fun rejection(
        profile: VehicleProfile?,
        state: BleConnectionState,
        command: VehicleCommand,
        experimentalEnabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ): CommandError? = when {
        profile == null -> CommandError.NO_PROFILE
        command.transport != CommandTransport.BLE -> CommandError.UNSUPPORTED_TRANSPORT
        state != BleConnectionState.READY -> CommandError.KEY_NOT_READY
        !profile.hasValidKey(now) -> CommandError.KEY_EXPIRED
        command.experimental && !experimentalEnabled -> CommandError.EXPERIMENTAL_DISABLED
        command.functionCode == null || command.functionCode !in profile.capabilities ->
            CommandError.CAPABILITY_NOT_CONFIRMED
        else -> null
    }
}
