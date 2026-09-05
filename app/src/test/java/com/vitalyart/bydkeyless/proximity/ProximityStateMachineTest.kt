package com.vitalyart.bydkeyless.proximity

import com.vitalyart.bydkeyless.model.*
import org.junit.Assert.*
import org.junit.Test

class ProximityStateMachineTest {
    private fun machine(unlock: Boolean = true, lock: Boolean = true) = ProximityStateMachine().apply {
        configure(KeylessMode.AUTO_UNLOCK_LOCK, ProximityCalibration(-55, -85), unlock, lock)
    }
    private fun approach(m: ProximityStateMachine) {
        m.sample(-50, true, 0, true, true)
        assertTrue(m.sample(-50, true, 3_100, true, true).requestUnlock)
        m.markUnlocked()
    }

    @Test fun unlocksAndLocksWithContinuousSamples() {
        val m = machine(); approach(m)
        var locks = 0
        for (t in 4_000L..40_000L step 1_000) {
            if (m.sample(-95, true, t, true, true).requestLock) { locks++; m.markLocked() }
        }
        assertEquals(1, locks)
        assertTrue(m.state().armed)
    }

    @Test fun unlockOnlyRearmsAfterDeparture() {
        val m = machine(lock = false); approach(m)
        for (t in 4_000L..40_000L step 1_000) m.sample(-95, true, t, true, true)
        assertTrue((41_000L..60_000L step 1_000).any { m.sample(-50, true, it, true, true).requestUnlock })
    }

    @Test fun retriesDefiniteRejectionWithCooldownAndLimit() {
        val m = machine()
        m.sample(-50, true, 0, true, true)
        assertTrue(m.sample(-50, true, 3_100, true, true).requestUnlock)
        m.commandFailed(3_100, uncertain = false)
        for (t in 4_000L..8_000L step 1_000) assertFalse(m.sample(-50, true, t, true, true).requestUnlock)
        assertTrue(m.sample(-50, true, 9_000, true, true).requestUnlock)
        m.commandFailed(9_000, uncertain = false)
        for (t in 10_000L..30_000L step 1_000) assertFalse(m.sample(-50, true, t, true, true).requestUnlock)
    }

    @Test fun unknownOutcomeDoesNotBlindlyRetryEvenAfterReconnect() {
        val m = machine()
        m.sample(-50, true, 0, true, true)
        m.sample(-50, true, 3_100, true, true)
        m.commandFailed(3_100, uncertain = true)
        m.resetSignal()
        for (t in 4_000L..40_000L step 1_000) assertFalse(m.sample(-50, true, t, true, true).requestUnlock)
        assertFalse(m.state().armed)
    }

    @Test fun manualConfirmationResumesLockingAfterUnknownOutcome() {
        val m = machine(); approach(m)
        for (t in 4_000L..40_000L step 1_000) m.sample(-95, false, t, true, true)
        assertTrue(m.sample(-95, true, 41_000, true, true).requestLock)
        m.commandFailed(41_000, uncertain = true)
        m.markUnlocked() // A new manual unlock acknowledgement reconciles the state.
        assertTrue(m.sample(-95, true, 42_000, true, true).requestLock)
    }

    @Test fun closingDoorsInFarZoneCanTriggerLock() {
        val m = machine(); approach(m)
        for (t in 4_000L..40_000L step 1_000) assertFalse(m.sample(-95, false, t, true, true).requestLock)
        assertTrue(m.sample(-95, true, 41_000, true, true).requestLock)
    }

    @Test fun longGapDoesNotProveProximity() {
        val m = machine()
        m.sample(-50, true, 0, true, true)
        assertFalse(m.sample(-50, true, 3_600_000, true, true).requestUnlock)
        assertTrue(m.sample(-50, true, 3_603_100, true, true).requestUnlock)
    }

    @Test fun lockOnlyWorksAfterManualUnlock() {
        val m = machine(unlock = false)
        m.markUnlocked()
        var requested = false
        for (t in 0L..30_000L step 1_000) requested = m.sample(-95, true, t, false, true).requestLock || requested
        assertTrue(requested)
    }

    @Test fun manualLockDoesNotImmediatelyAutoUnlock() {
        val m = machine(); approach(m); m.markLocked()
        for (t in 4_000L..30_000L step 1_000) assertFalse(m.sample(-50, true, t, true, true).requestUnlock)
    }

    @Test fun neverLocksWithUnknownClosures() {
        val m = machine(); approach(m)
        for (t in 4_000L..40_000L step 1_000) assertFalse(m.sample(-95, null, t, true, true).requestLock)
    }
}
