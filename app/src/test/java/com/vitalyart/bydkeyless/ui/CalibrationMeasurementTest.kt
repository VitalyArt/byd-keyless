package com.vitalyart.bydkeyless.ui

import com.vitalyart.bydkeyless.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationMeasurementTest {
    @Test fun finishesAfterEightFreshSamples() = runTest {
        val telemetry = MutableStateFlow(VehicleTelemetry(connectionGeneration = 1))
        val connection = MutableStateFlow(BleConnectionState.READY)
        val progress = mutableListOf<Int>()
        val result = async { measureCalibration(telemetry, connection, { testScheduler.currentTime }, progress::add) }
        runCurrent()
        repeat(8) {
            advanceTimeBy(1_000)
            telemetry.value = telemetry.value.copy(rssi = -60, rssiSequence = it + 1L, rssiAtMillis = testScheduler.currentTime)
            runCurrent()
        }
        assertEquals(-60, result.await())
        assertEquals(8, progress.last())
    }
    @Test fun timesOutWithoutUsingCachedSignal() = runTest {
        val telemetry = MutableStateFlow(VehicleTelemetry(rssi = -60, rssiSequence = 1, rssiAtMillis = 0))
        val result = async { measureCalibration(telemetry, MutableStateFlow(BleConnectionState.READY), { testScheduler.currentTime }, {}) }
        advanceUntilIdle()
        assertNull(result.await())
        assertEquals(12_000, testScheduler.currentTime)
    }
    @Test fun connectionLossAndGenerationChangeAbortImmediately() = runTest {
        repeat(2) { generationChange ->
            val telemetry = MutableStateFlow(VehicleTelemetry(connectionGeneration = 1))
            val connection = MutableStateFlow(BleConnectionState.READY)
            val result = async { measureCalibration(telemetry, connection, { 0 }, {}) }
            runCurrent()
            if (generationChange == 1) telemetry.value = telemetry.value.copy(connectionGeneration = 2)
            else connection.value = BleConnectionState.DISCONNECTED
            runCurrent()
            assertTrue(result.isCompleted)
            assertNull(result.await())
        }
    }
    @Test fun cancellationDoesNotPublishResult() = runTest {
        var published = false
        val job = launch { measureCalibration(MutableStateFlow(VehicleTelemetry()), MutableStateFlow(BleConnectionState.READY), { 0 }, {}); published = true }
        runCurrent(); job.cancelAndJoin(); advanceUntilIdle()
        assertFalse(published)
    }
}
