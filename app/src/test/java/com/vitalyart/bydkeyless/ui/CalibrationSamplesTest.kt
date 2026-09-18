package com.vitalyart.bydkeyless.ui

import org.junit.Assert.*
import org.junit.Test

class CalibrationSamplesTest {
    @Test fun ignoresCachedDuplicateStaleInvalidAndCrossConnectionSamples() {
        val samples = CalibrationSamples(2, 10)
        assertFalse(samples.add(2, 10, 0, -60))
        assertFalse(samples.add(1, 11, 0, -60))
        assertFalse(samples.add(2, 11, 4_001, -60))
        assertFalse(samples.add(2, 11, -1, -60))
        assertFalse(samples.add(2, 11, 0, null))
        assertFalse(samples.add(2, 11, 0, 0))
        assertTrue(samples.add(2, 11, 0, -60))
        assertFalse(samples.add(2, 11, 0, -60))
        assertEquals(1, samples.count)
        assertNull(samples.median())
    }
    @Test fun usesMedianOfEightAndStopsAcceptingSamples() {
        val samples = CalibrationSamples(1, 0)
        listOf(-60, -57, -59, -60, -62, -61, -58, -63).forEachIndexed { i, value -> samples.add(1, i + 1L, 0, value) }
        assertEquals(-60, samples.median())
        assertFalse(samples.add(1, 9, 0, -110))
        assertEquals(8, samples.count)
    }
    @Test fun rejectsUnstableSignal() {
        val samples = CalibrationSamples(1, 0)
        repeat(8) { samples.add(1, it + 1L, 0, if (it == 0) -80 else -60) }
        assertNull(samples.median())
    }
    @Test fun acceptsTwelveDbSpread() {
        val samples = CalibrationSamples(1, 0)
        repeat(8) { samples.add(1, it + 1L, 0, if (it == 0) -72 else -60) }
        assertEquals(-60, samples.median())
    }
}
