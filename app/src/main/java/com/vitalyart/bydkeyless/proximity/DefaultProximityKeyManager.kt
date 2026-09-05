package com.vitalyart.bydkeyless.proximity

import android.os.SystemClock
import com.vitalyart.bydkeyless.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DefaultProximityKeyManager(
    private val ble: BleVehicleController,
    private val canUnlock: () -> Boolean,
    private val canLock: () -> Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ProximityKeyManager {
    private val _state = MutableStateFlow(ProximityState())
    override val state: StateFlow<ProximityState> = _state
    private val machine = ProximityStateMachine()
    private var commandJob: Job? = null
    private var configurationVersion = 0L
    private var generation = -1L
    private var lastRssiSequence = -1L
    private var lastDoorStateAt: Long? = null

    override fun configure(mode: KeylessMode, calibration: ProximityCalibration?, autoUnlock: Boolean, autoLock: Boolean) {
        configurationVersion += 1
        if (commandJob?.isActive == true) machine.commandFailed(clock(), uncertain = true)
        commandJob?.cancel()
        machine.configure(mode, calibration, autoUnlock, autoLock)
        _state.value = machine.state()
    }

    override suspend fun onTelemetry(telemetry: VehicleTelemetry) {
        if (generation != telemetry.connectionGeneration) {
            generation = telemetry.connectionGeneration
            lastRssiSequence = -1L
            lastDoorStateAt = null
            machine.resetSignal()
            _state.value = machine.state()
        }
        if (telemetry.doorStateAtMillis != null && telemetry.doorStateAtMillis != lastDoorStateAt) {
            lastDoorStateAt = telemetry.doorStateAtMillis
            when (telemetry.doorState) {
                DoorState.UNLOCKED -> machine.markUnlocked()
                DoorState.LOCKED -> machine.markLocked()
                DoorState.UNKNOWN -> Unit
            }
            _state.value = machine.state()
        }
        val now = clock()
        val rssi = telemetry.rssi ?: return
        val sampledAt = telemetry.rssiAtMillis ?: return
        if (telemetry.rssiSequence == lastRssiSequence) return
        lastRssiSequence = telemetry.rssiSequence
        if (now - sampledAt !in 0..ProximityStateMachine.MAX_SAMPLE_GAP_MS || ble.connectionState.value != BleConnectionState.READY) {
            machine.resetSignal()
            _state.value = machine.state()
            return
        }
        val closures = telemetry.allClosuresClosed.takeIf { telemetry.closuresAtMillis?.let { now - it in 0..30_000L } == true }
        val decision = machine.sample(rssi, closures, sampledAt, canUnlock(), canLock())
        _state.value = decision.state
        val command = when {
            decision.requestUnlock -> VehicleCommand.UNLOCK
            decision.requestLock -> VehicleCommand.LOCK
            else -> return
        }
        // Telemetry continues while this independent transaction waits for its ACK.
        val version = configurationVersion
        commandJob = scope.launch {
            try {
                val result = ble.execute(command)
                if (version != configurationVersion) return@launch
                when (result) {
                    CommandResult.Success -> if (command == VehicleCommand.UNLOCK) machine.markUnlocked() else machine.markLocked()
                    is CommandResult.Rejected -> machine.commandFailed(clock(), uncertain = false)
                    is CommandResult.Failure -> machine.commandFailed(clock(), uncertain = true)
                }
            } catch (cancelled: CancellationException) {
                if (version == configurationVersion) machine.commandFailed(clock(), uncertain = true)
                throw cancelled
            } catch (failure: Exception) {
                if (version == configurationVersion) machine.commandFailed(clock(), uncertain = true)
            } finally {
                _state.value = machine.state()
            }
        }
    }
}
