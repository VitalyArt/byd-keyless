package com.vitalyart.bydkeyless.ble

import com.vitalyart.bydkeyless.model.BleConnectionState
import com.vitalyart.bydkeyless.model.CommandError
import com.vitalyart.bydkeyless.model.VehicleCommand
import com.vitalyart.bydkeyless.model.VehicleProfile
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandPolicyTest {
    private fun profile(capabilities: Set<String>, validTo: Long? = null) = VehicleProfile(
        vin = "TESTVIN", macAddress = "00:00:00:00:00:00", digitalKey = "test-key",
        keyNumber = 1, keyValidTo = validTo, capabilities = capabilities,
    )

    @Test fun permitsOnlyReadyAdvertisedCommands() {
        assertNull(CommandPolicy.rejection(profile(setOf("1005")), BleConnectionState.READY, VehicleCommand.LOCK, false, 1))
        assertEquals(CommandError.CAPABILITY_NOT_CONFIRMED, CommandPolicy.rejection(profile(emptySet()), BleConnectionState.READY, VehicleCommand.LOCK, false, 1))
        assertEquals(CommandError.KEY_NOT_READY, CommandPolicy.rejection(profile(setOf("1005")), BleConnectionState.CONNECTING, VehicleCommand.LOCK, false, 1))
    }

    @Test fun failsClosedForUnknownAndExpiredCommands() {
        assertEquals(CommandError.CAPABILITY_NOT_CONFIRMED, CommandPolicy.rejection(profile(emptySet()), BleConnectionState.READY, VehicleCommand.UWB_LOCATE, true, 1))
        assertEquals(CommandError.KEY_EXPIRED, CommandPolicy.rejection(profile(setOf("1005"), validTo = 100), BleConnectionState.READY, VehicleCommand.LOCK, false, 101))
    }
}
