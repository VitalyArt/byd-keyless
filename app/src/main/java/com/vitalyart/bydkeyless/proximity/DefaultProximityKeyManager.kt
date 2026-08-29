package com.vitalyart.bydkeyless.proximity

import com.vitalyart.bydkeyless.model.*
import com.vitalyart.bydkeyless.storage.SecureSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DefaultProximityKeyManager(
    private val ble: BleVehicleController,
    private val store: SecureSessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProximityKeyManager {
    private val _state = MutableStateFlow(ProximityState())
    override val state: StateFlow<ProximityState> = _state
    private val machine = ProximityStateMachine()

    override fun configure(mode: KeylessMode, calibration: ProximityCalibration?, autoUnlock: Boolean, autoLock: Boolean) {
        machine.configure(mode, calibration, autoUnlock, autoLock)
        if (mode == KeylessMode.OFF) _state.value = ProximityState()
    }

    override suspend fun onTelemetry(telemetry: VehicleTelemetry) {
        val rssi = telemetry.rssi ?: return
        val ready = ble.connectionState.value == BleConnectionState.READY
        val decision = machine.sample(rssi, telemetry.allClosuresClosed, clock(), store.manualUnlockVerified && ready, store.manualLockVerified && ready)
        _state.value = decision.state
        if (decision.requestUnlock && ble.execute(VehicleCommand.UNLOCK) is CommandResult.Success) {
            machine.markUnlocked()
        } else if (decision.requestLock && ble.execute(VehicleCommand.LOCK) is CommandResult.Success) {
            machine.markLocked()
        }
    }
}
