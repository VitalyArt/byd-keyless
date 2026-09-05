package com.vitalyart.bydkeyless.proximity

import com.vitalyart.bydkeyless.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultProximityKeyManagerTest {
    private class Controller : BleVehicleController {
        override val connectionState = MutableStateFlow(BleConnectionState.READY)
        override val telemetry = MutableStateFlow(VehicleTelemetry())
        val ack = CompletableDeferred<CommandResult>()
        var calls = 0
        var cancelled = false
        override suspend fun connect(profile: VehicleProfile) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun execute(command: VehicleCommand, experimentalEnabled: Boolean): CommandResult {
            calls++
            try { return ack.await() } catch (e: CancellationException) { cancelled = true; throw e }
        }
    }

    @Test fun rssiUpdatesDoNotCancelOrDuplicatePendingCommand() = runTest {
        val ble = Controller()
        val manager = DefaultProximityKeyManager(ble, { true }, { true }, { testScheduler.currentTime }, backgroundScope)
        manager.configure(KeylessMode.AUTO_UNLOCK_LOCK, ProximityCalibration(-55, -85), true, true)
        var sequence = 0L
        suspend fun sample() = manager.onTelemetry(VehicleTelemetry(rssi = -50, rssiAtMillis = testScheduler.currentTime, rssiSequence = ++sequence))
        sample()
        advanceTimeBy(3_100); sample(); runCurrent()
        assertEquals(1, ble.calls)
        repeat(5) { advanceTimeBy(1_000); sample(); runCurrent() }
        assertFalse(ble.cancelled)
        assertEquals(1, ble.calls)
        ble.ack.complete(CommandResult.Success); runCurrent()
        assertFalse(manager.state.value.armed)
    }

    @Test fun telemetryOnlyUpdatesDoNotCountAsNewRssiSamples() = runTest {
        val ble = Controller()
        val manager = DefaultProximityKeyManager(ble, { true }, { true }, { testScheduler.currentTime }, backgroundScope)
        manager.configure(KeylessMode.AUTO_UNLOCK_LOCK, ProximityCalibration(-55, -85), true, true)
        val initial = VehicleTelemetry(rssi = -50, rssiAtMillis = 0, rssiSequence = 1)
        manager.onTelemetry(initial)
        advanceTimeBy(3_100)
        manager.onTelemetry(initial.copy(allClosuresClosed = true, closuresAtMillis = 3_100))
        runCurrent()
        assertEquals(0, ble.calls)
    }

    @Test fun staleClosuresDoNotPermitAutoLock() = runTest {
        val ble = Controller()
        val manager = DefaultProximityKeyManager(ble, { true }, { true }, { testScheduler.currentTime }, backgroundScope)
        manager.configure(KeylessMode.AUTO_UNLOCK_LOCK, ProximityCalibration(-55, -85), false, true)
        manager.onTelemetry(VehicleTelemetry(doorState = DoorState.UNLOCKED, doorStateAtMillis = 0))
        advanceTimeBy(31_000)
        repeat(15) { index ->
            manager.onTelemetry(VehicleTelemetry(rssi = -95, rssiAtMillis = testScheduler.currentTime, rssiSequence = index.toLong(), allClosuresClosed = true, closuresAtMillis = 0))
            advanceTimeBy(1_000); runCurrent()
        }
        assertEquals(0, ble.calls)
    }

    @Test fun disconnectResetsSignalContinuity() = runTest {
        val ble = Controller()
        val manager = DefaultProximityKeyManager(ble, { true }, { true }, { testScheduler.currentTime }, backgroundScope)
        manager.configure(KeylessMode.AUTO_UNLOCK_LOCK, ProximityCalibration(-55, -85), true, true)
        manager.onTelemetry(VehicleTelemetry(rssi = -50, rssiAtMillis = 0, rssiSequence = 1))
        advanceTimeBy(3_100)
        manager.onTelemetry(VehicleTelemetry(connectionGeneration = 1))
        manager.onTelemetry(VehicleTelemetry(rssi = -50, rssiAtMillis = 3_100, rssiSequence = 1, connectionGeneration = 1))
        runCurrent()
        assertEquals(0, ble.calls)
    }

    @Test fun disablingAutomationCancelsPendingTransaction() = runTest {
        val ble = Controller()
        val manager = DefaultProximityKeyManager(ble, { true }, { true }, { testScheduler.currentTime }, backgroundScope)
        manager.configure(KeylessMode.AUTO_UNLOCK_LOCK, ProximityCalibration(-55, -85), true, true)
        manager.onTelemetry(VehicleTelemetry(rssi = -50, rssiAtMillis = 0, rssiSequence = 1))
        advanceTimeBy(3_100)
        manager.onTelemetry(VehicleTelemetry(rssi = -50, rssiAtMillis = 3_100, rssiSequence = 2)); runCurrent()
        manager.configure(KeylessMode.OFF, null, false, false); runCurrent()
        assertTrue(ble.cancelled)
        assertEquals(ProximityZone.UNKNOWN, manager.state.value.zone)
    }
}
