package com.vitalyart.bydkeyless.ble

import com.vitalyart.bydkeyless.model.*
import org.junit.Assert.*
import org.junit.Test

class CommandAvailabilityTest {
    private fun profile(capabilities: Set<String>) = VehicleProfile(
        vin = "TESTVIN", macAddress = "00:00:00:00:00:00", digitalKey = "key",
        keyNumber = 1, keyValidTo = null, capabilities = capabilities,
    )

    @Test fun hidesCommandsWithoutConfirmedCapability() {
        val result = CommandAvailability.evaluate(profile(emptySet()), true, BleConnectionState.READY, VehicleCommand.TURN_ON_AC, true)
        assertFalse(result.supported)
        assertFalse(result.enabled)
    }

    @Test fun cloudCommandDoesNotRequireBle() {
        val result = CommandAvailability.evaluate(profile(setOf("1021")), true, BleConnectionState.IDLE, VehicleCommand.CLOSE_TRUNK, false)
        assertTrue(result.supported)
        assertTrue(result.enabled)
    }

    @Test fun bleCommandRequiresAuthenticatedKey() {
        val result = CommandAvailability.evaluate(profile(setOf("1005")), true, BleConnectionState.CONNECTING, VehicleCommand.LOCK, false)
        assertTrue(result.supported)
        assertFalse(result.enabled)
        assertEquals(UnavailableReason.KEY_NOT_READY, result.reason)
    }
}
