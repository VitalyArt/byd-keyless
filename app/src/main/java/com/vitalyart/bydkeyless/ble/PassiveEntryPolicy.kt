package com.vitalyart.bydkeyless.ble

import com.vitalyart.bydkeyless.model.BleConnectionState
import com.vitalyart.bydkeyless.model.KeylessMode
import com.vitalyart.bydkeyless.model.VehicleCommand
import com.vitalyart.bydkeyless.model.VehicleProfile

object PassiveEntryPolicy {
    const val DEBOUNCE_MS = 3_000L

    fun shouldUnlock(
        mode: KeylessMode,
        state: BleConnectionState,
        profile: VehicleProfile?,
        now: Long,
        lastTriggerAt: Long,
    ): Boolean = mode == KeylessMode.PASSIVE_ENTRY &&
        state == BleConnectionState.READY &&
        profile?.hasValidKey(now) == true &&
        VehicleCommand.UNLOCK.functionCode in profile.capabilities &&
        (lastTriggerAt == Long.MIN_VALUE || now - lastTriggerAt >= DEBOUNCE_MS)
}
