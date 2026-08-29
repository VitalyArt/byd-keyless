package com.vitalyart.bydkeyless.proximity

import com.vitalyart.bydkeyless.model.*
import org.junit.Assert.*
import org.junit.Test

class ProximityStateMachineTest {
    @Test fun unlocksOnceAndRearamsOnlyAfterSuccessfulLock() {
        val machine = ProximityStateMachine()
        machine.configure(KeylessMode.AUTO_UNLOCK_LOCK, ProximityCalibration(-55, -85), autoUnlock = true, autoLock = true)
        var time = 0L
        machine.sample(-52, true, time, canUnlock = true, canLock = true)
        time += 3_100
        val unlock = machine.sample(-52, true, time, canUnlock = true, canLock = true)
        assertTrue(unlock.requestUnlock)
        machine.markUnlocked()
        time += 1_000
        assertFalse(machine.sample(-52, true, time, true, true).requestUnlock)
        // Feed enough samples for the EWMA to cross the calibrated far threshold.
        repeat(8) { machine.sample(-95, true, time + 1_000 + it * 1_000L, true, true) }
        val lock = machine.sample(-95, true, time + 18_100, true, true)
        assertTrue(lock.requestLock)
        machine.markLocked()
        assertTrue(machine.sample(-95, true, time + 19_000, true, true).state.armed)
    }

    @Test fun neverLocksWithUnknownClosures() {
        val machine = ProximityStateMachine()
        machine.configure(KeylessMode.AUTO_UNLOCK_LOCK, ProximityCalibration(-55, -85), true, true)
        machine.sample(-50, null, 0, true, true)
        assertTrue(machine.sample(-50, null, 3_100, true, true).requestUnlock)
        machine.markUnlocked()
        machine.sample(-95, null, 4_000, true, true)
        assertFalse(machine.sample(-95, null, 14_100, true, true).requestLock)
    }
}
