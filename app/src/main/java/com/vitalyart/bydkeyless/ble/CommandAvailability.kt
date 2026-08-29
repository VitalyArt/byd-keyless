package com.vitalyart.bydkeyless.ble

import com.vitalyart.bydkeyless.model.*

enum class UnavailableReason { KEY_NOT_READY, NO_SESSION, EXPERIMENTAL_DISABLED, KEY_EXPIRED }

data class ActionAvailability(
    val supported: Boolean,
    val enabled: Boolean,
    val reason: UnavailableReason? = null,
)

object CommandAvailability {
    fun evaluate(
        profile: VehicleProfile?,
        hasSession: Boolean,
        state: BleConnectionState,
        command: VehicleCommand,
        experimentalEnabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ): ActionAvailability {
        if (profile == null) return ActionAvailability(supported = false, enabled = false)
        val supported = command.functionCode != null && command.functionCode in profile.capabilities
        if (!supported) return ActionAvailability(supported = false, enabled = false)
        if (!profile.hasValidKey(now)) return ActionAvailability(true, false, UnavailableReason.KEY_EXPIRED)
        if (command.experimental && !experimentalEnabled) {
            return ActionAvailability(true, false, UnavailableReason.EXPERIMENTAL_DISABLED)
        }
        return when (command.transport) {
            CommandTransport.CLOUD -> if (hasSession) ActionAvailability(true, true)
            else ActionAvailability(true, false, UnavailableReason.NO_SESSION)
            CommandTransport.BLE -> if (state == BleConnectionState.READY) ActionAvailability(true, true)
            else ActionAvailability(true, false, UnavailableReason.KEY_NOT_READY)
        }
    }
}
