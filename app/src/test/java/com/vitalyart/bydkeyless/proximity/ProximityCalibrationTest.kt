package com.vitalyart.bydkeyless.proximity

import com.vitalyart.bydkeyless.model.ProximityCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityCalibrationTest {
    @Test fun convertsConfiguredMetersToCalibratedRssiThresholds() {
        val calibration = ProximityCalibration(-55, -85, unlockDistanceMeters = 1.0, lockDistanceMeters = 5.0)
        assertEquals(-55.0, calibration.unlockThreshold, 0.001)
        assertEquals(-85.0, calibration.lockThreshold, 0.001)
    }

    @Test fun fartherConfiguredDistanceProducesWeakerThreshold() {
        val nearLock = ProximityCalibration(-55, -85, 1.5, 3.0)
        val farLock = ProximityCalibration(-55, -85, 1.5, 8.0)
        assertTrue(farLock.lockThreshold < nearLock.lockThreshold)
        assertTrue(nearLock.unlockThreshold > nearLock.lockThreshold)
    }
}
