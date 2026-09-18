package com.vitalyart.bydkeyless.ui

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitalyart.bydkeyless.BuildConfig
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.KeylessApplication
import com.vitalyart.bydkeyless.ble.ActionAvailability
import com.vitalyart.bydkeyless.ble.CommandAvailability
import com.vitalyart.bydkeyless.model.*
import com.vitalyart.bydkeyless.network.WatchRegions
import com.vitalyart.bydkeyless.network.WatchNetworkException
import com.vitalyart.bydkeyless.network.WatchNetworkFailure
import com.vitalyart.bydkeyless.service.KeylessService
import com.vitalyart.bydkeyless.quick.QuickCommandPhase
import com.vitalyart.bydkeyless.update.UpdateState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class MainUiState(
    val loading: Boolean = false,
    val qrSession: WatchQrSession? = null,
    val qrStatus: WatchQrStatus = WatchQrStatus.UNKNOWN,
    val token: WatchToken? = null,
    val profile: VehicleProfile? = null,
    val serviceRunning: Boolean = false,
    val diagnostics: BleDiagnostics = BleDiagnostics(),
    val bleError: CommandError? = null,
    val bleState: BleConnectionState = BleConnectionState.IDLE,
    val telemetry: VehicleTelemetry = VehicleTelemetry(),
    val proximity: ProximityState = ProximityState(),
    val commandResult: UiMessage? = null,
    val commandInProgress: VehicleCommand? = null,
    val error: UiMessage? = null,
    val nearSample: Int? = null,
    val farSample: Int? = null,
    val mode: KeylessMode = KeylessMode.OFF,
    val autoUnlock: Boolean = false,
    val autoLock: Boolean = false,
    val hotspotOnConnect: Boolean = false,
    val hotspotOffOnDisconnect: Boolean = false,
    val hotspotOffDelayMillis: Long = 60_000L,
    val experimental: Boolean = false,
    val calibrated: Boolean = false,
    val unlockDistanceMeters: Double = ProximityCalibration.DEFAULT_UNLOCK_DISTANCE_METERS,
    val lockDistanceMeters: Double = ProximityCalibration.DEFAULT_LOCK_DISTANCE_METERS,
    val manualUnlockVerified: Boolean = false,
    val manualLockVerified: Boolean = false,
    val watchCountryCode: String = "UZ",
    val language: String = "system",
    val permissionState: PermissionState = PermissionState.UNKNOWN,
    val currentAppVersion: String = BuildConfig.VERSION_NAME,
    val includePrereleaseUpdates: Boolean = false,
    val updateState: UpdateState = UpdateState.Idle,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as KeylessApplication).graph
    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()
    private var polling: Job? = null
    private var messageJob: Job? = null
    private var startJob: Job? = null
    private var calibrationJob: Job? = null

    init {
        viewModelScope.launch { KeylessService.running.collect { value -> _ui.update { it.copy(serviceRunning = value) } } }
        viewModelScope.launch { graph.ble.diagnostics.collect { value -> _ui.update { it.copy(diagnostics = value, bleError = value.error) } } }
        viewModelScope.launch { graph.ble.connectionState.collect { _ui.update { s -> s.copy(bleState = it, bleError = graph.ble.lastError) } } }
        viewModelScope.launch { graph.ble.telemetry.collect { _ui.update { s -> s.copy(telemetry = it) } } }
        viewModelScope.launch { graph.proximity.state.collect { _ui.update { s -> s.copy(proximity = it) } } }
        viewModelScope.launch { graph.updateManager.state.collect { value -> _ui.update { it.copy(updateState = value) } } }
        viewModelScope.launch { graph.quickCommands.state.collect { quick ->
            when (quick.phase) {
                QuickCommandPhase.IDLE -> Unit
                QuickCommandPhase.CONNECTING, QuickCommandPhase.EXECUTING ->
                    _ui.update { it.copy(commandResult = UiMessage(R.string.message_executing), error = null, commandInProgress = quick.command) }
                QuickCommandPhase.SUCCESS -> {
                    _ui.update { it.copy(commandResult = UiMessage(R.string.message_command_success), error = null, commandInProgress = null) }
                    scheduleMessageClear()
                }
                QuickCommandPhase.ERROR -> {
                    _ui.update { it.copy(error = UiMessage((quick.error ?: CommandError.KEY_NOT_READY).messageResource()), commandResult = null, commandInProgress = null) }
                    scheduleMessageClear()
                }
            }
        } }
        graph.updateManager.check(manual = false)
    }

    fun beginAuthorization() {
        polling?.cancel()
        polling = viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null, qrSession = null) }
            runCatching {
                graph.auth.synchronizeServerTime()
                graph.auth.createQrSession()
            }.onSuccess { session ->
                _ui.update { it.copy(loading = false, qrSession = session, qrStatus = session.status) }
                poll(session)
            }.onFailure { failure -> _ui.update { it.copy(loading = false, error = UiMessage(networkError(failure))) } }
        }
    }

    fun selectWatchCountry(code: String) {
        getApplication<KeylessApplication>().selectWatchCountry(code)
        _ui.update { it.copy(watchCountryCode = code, error = null) }
    }

    fun setLanguage(language: String) {
        graph.store.language = language
        _ui.update { it.copy(language = language) }
        val locales = if (language == "system") LocaleListCompat.getEmptyLocaleList()
        else LocaleListCompat.forLanguageTags(language)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private suspend fun poll(session: WatchQrSession) {
        while (System.currentTimeMillis() < session.expiresAtMillis) {
            delay(3_000L)
            val result = runCatching { graph.auth.checkQrSession(session) }
            if (result.isFailure) {
                _ui.update { state -> state.copy(error = UiMessage(networkError(result.exceptionOrNull() ?: RuntimeException()))) }
                continue
            }
            val status = result.getOrThrow()
            _ui.update { it.copy(qrStatus = status, error = null) }
            when (status) {
                WatchQrStatus.APPROVED -> { finishAuthorization(session); return }
                WatchQrStatus.INVALIDATED -> { beginAuthorization(); return }
                WatchQrStatus.EXPIRED -> return
                else -> Unit
            }
        }
        _ui.update { it.copy(qrStatus = WatchQrStatus.EXPIRED) }
    }

    private suspend fun finishAuthorization(session: WatchQrSession) {
        _ui.update { it.copy(loading = true) }
        var failure: Throwable? = null
        for (attempt in 1..3) {
            try {
                val token = graph.auth.gainToken(session)
                val profile = graph.auth.loadVehicleProfile(token)
                if (profile.hasValidKey()) {
                    graph.store.saveSession(token, profile)
                    _ui.update { it.copy(loading = false, token = token, profile = profile, qrSession = null, error = null) }
                    return
                }
                failure = IllegalArgumentException("BYD did not issue a valid Bluetooth key")
            } catch (error: Exception) {
                failure = error
            }
            if (attempt < 3) delay(2_000L)
        }
        _ui.update { it.copy(loading = false, error = UiMessage(networkError(failure ?: RuntimeException()))) }
    }

    fun startKey() {
        if (startJob?.isActive == true) return
        startJob = viewModelScope.launch {
            val current = _ui.value.profile ?: return@launch
            val token = _ui.value.token ?: return@launch
            // A usable local credential does not need to wait for the network.
            if (current.hasValidKey()) {
                graph.ble.connect(current)
                startBackgroundKey()
            }
            try {
                val refreshed = withTimeoutOrNull(if (current.hasValidKey()) 8_000L else 30_000L) {
                    graph.auth.loadVehicleProfile(token)
                }
                if (refreshed != null) {
                    graph.store.saveSession(token, refreshed)
                    _ui.update { it.copy(profile = refreshed, error = null) }
                    graph.ble.connect(refreshed)
                    startBackgroundKey()
                } else if (!current.hasValidKey()) showError(UiMessage(R.string.error_timeout))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!current.hasValidKey()) showError(UiMessage(networkError(failure)))
            }
        }
    }

    private fun startBackgroundKey() {
        if (graph.store.keylessMode != KeylessMode.OFF) {
            runCatching { KeylessService.start(getApplication()) }
                .onFailure { showError(UiMessage(R.string.error_background_start)) }
        }
    }

    fun execute(command: VehicleCommand, confirmed: Boolean = false) {
        if (command.dangerous && !confirmed) return
        if (_ui.value.commandInProgress != null) return
        viewModelScope.launch {
            val availability = availability(command)
            if (!availability.enabled) {
                availability.reason?.let { showError(UiMessage(it.messageResource())) }
                return@launch
            }
            _ui.update { it.copy(commandResult = UiMessage(R.string.message_executing), error = null, commandInProgress = command) }
            val result = runCatching {
                if (command.transport == CommandTransport.CLOUD) {
                    val token = _ui.value.token
                    if (token == null) CommandResult.Rejected(CommandError.NO_SESSION)
                    else graph.auth.executeCloudCommand(token, command)
                } else {
                    graph.ble.execute(command, graph.store.experimentalEnabled)
                }
            }.getOrElse {
                if (it is CancellationException) throw it
                CommandResult.Failure(if (command.transport == CommandTransport.CLOUD) CommandError.CLOUD_REJECTED else CommandError.GATT_WRITE_FAILED)
            }
            if (result is CommandResult.Success) {
                if (command == VehicleCommand.UNLOCK) graph.store.manualUnlockVerified = true
                if (command == VehicleCommand.LOCK) graph.store.manualLockVerified = true
            }
            _ui.update { it.copy(
                manualUnlockVerified = graph.store.manualUnlockVerified,
                manualLockVerified = graph.store.manualLockVerified,
                commandResult = when (result) {
                    CommandResult.Success -> UiMessage(R.string.message_command_success)
                    is CommandResult.Rejected -> UiMessage(result.error.messageResource())
                    is CommandResult.Failure -> UiMessage(result.error.messageResource())
                }, commandInProgress = null,
            ) }
            scheduleMessageClear()
        }
    }

    fun availability(command: VehicleCommand): ActionAvailability = CommandAvailability.evaluate(
        profile = _ui.value.profile, hasSession = _ui.value.token != null, state = _ui.value.bleState,
        command = command, experimentalEnabled = _ui.value.experimental,
    ).let { availability ->
        if (_ui.value.commandInProgress != null && availability.supported) availability.copy(enabled = false) else availability
    }

    fun updatePermissionState(value: PermissionState) {
        _ui.update { it.copy(permissionState = value) }
    }

    fun showError(message: UiMessage) {
        _ui.update { it.copy(error = message, commandResult = null) }
        scheduleMessageClear()
    }

    fun captureNear() = captureCalibration(near = true)
    fun captureFar() = captureCalibration(near = false)
    private fun captureCalibration(near: Boolean) {
        if (calibrationJob?.isActive == true) return
        calibrationJob = viewModelScope.launch {
            _ui.update { it.copy(commandResult = UiMessage(R.string.calibration_measuring), error = null) }
            val samples = mutableListOf<Int>()
            val initial = graph.ble.telemetry.value
            graph.ble.requestCalibrationSampling()
            val completed = withTimeoutOrNull(12_000L) {
                graph.ble.telemetry.filter {
                    it.rssi != null && it.connectionGeneration == initial.connectionGeneration && it.rssiSequence > initial.rssiSequence &&
                        it.rssiAtMillis?.let { at -> android.os.SystemClock.elapsedRealtime() - at in 0..4_000L } == true &&
                        graph.ble.connectionState.value == BleConnectionState.READY
                }
                    .distinctUntilChangedBy { it.connectionGeneration to it.rssiSequence }.take(8).collect { samples += requireNotNull(it.rssi) }
                true
            } == true
            if (!completed || samples.maxOrNull()!! - samples.minOrNull()!! > 12) {
                showError(UiMessage(R.string.calibration_unstable))
                return@launch
            }
            val median = samples.sorted().let { (it[3] + it[4]) / 2 }
            _ui.update { if (near) it.copy(nearSample = median, commandResult = UiMessage(R.string.calibration_sample_ready))
                else it.copy(farSample = median, commandResult = UiMessage(R.string.calibration_sample_ready)) }
            scheduleMessageClear()
        }
    }
    fun saveCalibration() {
        val near = _ui.value.nearSample ?: return
        val far = _ui.value.farSample ?: return
        if (near <= far) { showError(UiMessage(R.string.error_near_signal)); return }
        val calibration = ProximityCalibration(near, far, _ui.value.unlockDistanceMeters, _ui.value.lockDistanceMeters)
        val saved = graph.store.saveCalibration(calibration)
        val persisted = graph.store.calibration()
        if (!saved || persisted?.nearRssi != near || persisted.farRssi != far) {
            showError(UiMessage(R.string.error_calibration_save))
            return
        }
        _ui.update { it.copy(calibrated = true, commandResult = UiMessage(R.string.calibration_saved), error = null) }
        scheduleMessageClear()
        graph.proximity.configure(_ui.value.mode, persisted, _ui.value.autoUnlock, _ui.value.autoLock)
    }

    fun configureKeyless(mode: KeylessMode = _ui.value.mode, autoUnlock: Boolean = _ui.value.autoUnlock, autoLock: Boolean = _ui.value.autoLock) {
        graph.store.keylessMode = mode; graph.store.autoUnlock = autoUnlock; graph.store.autoLock = autoLock
        graph.proximity.configure(mode, graph.store.calibration(), autoUnlock, autoLock)
        _ui.update { it.copy(mode = mode, autoUnlock = autoUnlock, autoLock = autoLock) }
        if (mode == KeylessMode.OFF) { startJob?.cancel(); KeylessService.stop(getApplication()) }
        else startBackgroundKey()
    }

    fun setProximityDistances(unlockMeters: Double = _ui.value.unlockDistanceMeters, lockMeters: Double = _ui.value.lockDistanceMeters) {
        val unlock = unlockMeters.coerceIn(0.5, 3.0)
        val lock = lockMeters.coerceIn(2.0, 10.0)
        if (unlock >= lock) return
        graph.store.unlockDistanceMeters = unlock
        graph.store.lockDistanceMeters = lock
        _ui.update { it.copy(unlockDistanceMeters = unlock, lockDistanceMeters = lock) }
        graph.proximity.configure(_ui.value.mode, graph.store.calibration(), _ui.value.autoUnlock, _ui.value.autoLock)
    }

    fun setExperimental(enabled: Boolean) { graph.store.experimentalEnabled = enabled; _ui.update { it.copy(experimental = enabled) } }

    fun checkForUpdates() = graph.updateManager.check(manual = true)
    fun downloadUpdate() = graph.updateManager.download()
    fun dismissUpdatePrompt() = graph.updateManager.dismissPrompt()
    fun clearUpdateError() = graph.updateManager.clearError()
    fun setPrereleaseUpdates(enabled: Boolean) {
        _ui.update { it.copy(includePrereleaseUpdates = enabled) }
        graph.updateManager.setIncludePrereleases(enabled)
    }

    fun configureHotspot(
        onConnect: Boolean = _ui.value.hotspotOnConnect,
        offOnDisconnect: Boolean = _ui.value.hotspotOffOnDisconnect,
        offDelayMillis: Long = _ui.value.hotspotOffDelayMillis,
    ) {
        graph.store.hotspotOnConnect = onConnect
        graph.store.hotspotOffOnDisconnect = offOnDisconnect
        graph.store.hotspotOffDelayMillis = offDelayMillis
        _ui.update { it.copy(
            hotspotOnConnect = onConnect,
            hotspotOffOnDisconnect = offOnDisconnect,
            hotspotOffDelayMillis = offDelayMillis.coerceAtLeast(0L),
        ) }
        graph.hotspotAutomation.refreshSettings()
    }

    fun logout() {
        startJob?.cancel()
        calibrationJob?.cancel()
        polling?.cancel()
        viewModelScope.launch {
            val permissionState = _ui.value.permissionState
            _ui.value.token?.let { runCatching { graph.auth.logout(it) } }
            KeylessService.stop(getApplication()); graph.ble.disconnect(); graph.store.clearSession()
            _ui.value = initialState().copy(token = null, profile = null, permissionState = permissionState)
        }
    }

    private fun initialState(): MainUiState {
        val session = graph.store.loadSession()
        val calibration = graph.store.calibration()
        return MainUiState(
            token = session?.first, profile = session?.second, mode = graph.store.keylessMode,
            autoUnlock = graph.store.autoUnlock, autoLock = graph.store.autoLock,
            hotspotOnConnect = graph.store.hotspotOnConnect,
            hotspotOffOnDisconnect = graph.store.hotspotOffOnDisconnect,
            hotspotOffDelayMillis = graph.store.hotspotOffDelayMillis,
            experimental = graph.store.experimentalEnabled,
            nearSample = calibration?.nearRssi,
            farSample = calibration?.farRssi,
            calibrated = calibration != null,
            unlockDistanceMeters = calibration?.unlockDistanceMeters ?: graph.store.unlockDistanceMeters,
            lockDistanceMeters = calibration?.lockDistanceMeters ?: graph.store.lockDistanceMeters,
            manualUnlockVerified = graph.store.manualUnlockVerified,
            manualLockVerified = graph.store.manualLockVerified,
            watchCountryCode = WatchRegions.byCode(graph.store.watchCountryCode).code,
            language = graph.store.language,
            currentAppVersion = BuildConfig.VERSION_NAME,
            includePrereleaseUpdates = graph.updateManager.includePrereleases,
            updateState = graph.updateManager.state.value,
        )
    }

    private fun networkError(failure: Throwable): Int = when ((failure as? WatchNetworkException)?.failure) {
        WatchNetworkFailure.DNS -> R.string.error_dns
        WatchNetworkFailure.TIMEOUT -> R.string.error_timeout
        WatchNetworkFailure.TLS -> R.string.error_tls
        WatchNetworkFailure.CONNECTION -> R.string.error_connection
        null -> if (failure is IllegalArgumentException && failure.message?.contains("Bluetooth key") == true) {
            R.string.error_key_not_ready
        } else R.string.error_network
    }

    private fun scheduleMessageClear() {
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(5_000L)
            _ui.update { it.copy(error = null, commandResult = null) }
        }
    }
}
