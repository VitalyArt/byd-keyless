package com.vitalyart.bydkeyless.proximity

import com.vitalyart.bydkeyless.model.ProximityCalibration
import org.junit.Assert.*
import org.junit.Test

class ProximityCalibrationTest {
    @Test fun measuredPointsAreDirectThresholds() {
        val calibration = ProximityCalibration(-55, -85)
        assertEquals(-55.0, calibration.unlockThreshold, 0.0)
        assertEquals(-85.0, calibration.lockThreshold, 0.0)
    }
    @Test fun migrationPreservesLegacyThresholdsWithoutRounding() {
        val calibration = ProximityCalibration.fromLegacy(-55, -85, 1.5, 4.0)
        assertEquals(-55 + (-85 + 55) * (kotlin.math.ln(1.5) / kotlin.math.ln(5.0)), calibration.unlockThreshold, 0.0)
        assertEquals(-55 + (-85 + 55) * (kotlin.math.ln(4.0) / kotlin.math.ln(5.0)), calibration.lockThreshold, 0.0)
    }
    @Test fun referencePointMigrationHasNoOffset() {
        assertEquals(ProximityCalibration(-55, -85), ProximityCalibration.fromLegacy(-55, -85, 1.0, 5.0))
    }
    @Test fun rejectsEqualReversedAndNonFinitePoints() {
        listOf(-55.0 to -55.0, -85.0 to -55.0, Double.NaN to -85.0, -55.0 to Double.NEGATIVE_INFINITY).forEach { (near, far) ->
            assertTrue(runCatching { ProximityCalibration(near, far) }.isFailure)
        }
    }
}
