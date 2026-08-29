package com.vitalyart.bydkeyless.ble

import com.vitalyart.bydkeyless.model.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveEntryPolicyTest {
    private val profile = VehicleProfile(
        vin = "TESTVIN", macAddress = "00:00:00:00:00:00", digitalKey = "key",
        keyNumber = 1, keyValidTo = null, capabilities = setOf("1006"),
    )

    @Test fun unlocksOnlyForReadyPassiveEntryWithCapability() {
        assertTrue(PassiveEntryPolicy.shouldUnlock(KeylessMode.PASSIVE_ENTRY, BleConnectionState.READY, profile, 1, Long.MIN_VALUE))
        assertTrue(PassiveEntryPolicy.shouldUnlock(KeylessMode.PASSIVE_ENTRY, BleConnectionState.READY, profile, 10_000, 0))
        assertFalse(PassiveEntryPolicy.shouldUnlock(KeylessMode.AUTO_UNLOCK_LOCK, BleConnectionState.READY, profile, 10_000, 0))
        assertFalse(PassiveEntryPolicy.shouldUnlock(KeylessMode.PASSIVE_ENTRY, BleConnectionState.CONNECTING, profile, 10_000, 0))
        assertFalse(PassiveEntryPolicy.shouldUnlock(KeylessMode.PASSIVE_ENTRY, BleConnectionState.READY, profile.copy(capabilities = emptySet()), 10_000, 0))
    }

    @Test fun debouncesRepeatedHandleEvents() {
        assertFalse(PassiveEntryPolicy.shouldUnlock(KeylessMode.PASSIVE_ENTRY, BleConnectionState.READY, profile, 12_000, 10_000))
        assertTrue(PassiveEntryPolicy.shouldUnlock(KeylessMode.PASSIVE_ENTRY, BleConnectionState.READY, profile, 13_000, 10_000))
    }
}
