package com.vitalyart.bydkeyless.ui

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitalyart.bydkeyless.BuildConfig
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.localizedString
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
    val calibration: CalibrationSession = CalibrationSession(),
    val theme: String = "system",
    val mode: KeylessMode = KeylessMode.OFF,
    val autoUnlock: Boolean = false,
    val autoLock: Boolean = false,
    val experimental: Boolean = false,
    val calibrated: Boolean = false,
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
        viewModelScope.launch { graph.store.changes.collect {
            _ui.update { state -> state.copy(mode = graph.store.keylessMode, autoUnlock = graph.store.autoUnlock,
                autoLock = graph.store.autoLock, manualUnlockVerified = graph.store.manualUnlockVerified,
                manualLockVerified = graph.store.manualLockVerified, calibrated = graph.store.calibration() != null,
                theme = graph.store.theme) }
        } }
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
                    _ui.update { it.copy(commandResult = commandMessage(R.string.quick_action_progress, quick.command), error = null, commandInProgress = quick.command) }
                QuickCommandPhase.SUCCESS -> {
                    _ui.update { it.copy(commandResult = commandMessage(R.string.quick_action_success, quick.command), error = null, commandInProgress = null) }
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

    private fun commandMessage(resource: Int, command: VehicleCommand?): UiMessage = UiMessage(resource,
        listOf(getApplication<KeylessApplication>().localizedString(_ui.value.language, command?.let(::commandLabel) ?: R.string.app_name)))

    fun execute(command: VehicleCommand, confirmed: Boolean = false) {
        if (command.dangerous && !confirmed) return
        if (_ui.value.commandInProgress != null) return
        viewModelScope.launch {
            val availability = availability(command)
            if (!availability.enabled) {
                availability.reason?.let { showError(UiMessage(it.messageResource())) }
                return@launch
            }
            _ui.update { it.copy(commandResult = commandMessage(R.string.quick_action_progress, command), error = null, commandInProgress = command) }
            val result = runCatching {
                if (command.transport == CommandTransport.CLOUD) {
                    val token = _ui.value.token
                    if (token == null) CommandResult.Rejected(CommandError.NO_SESSION)
                    else graph.auth.executeCloudCommand(token, command)
                } else if (command in com.vitalyart.bydkeyless.quick.QuickCommandContract.supportedCommands) {
                    graph.quickCommands.execute(command)
                } else {
                    val preflight = com.vitalyart.bydkeyless.quick.AndroidQuickCommandPreflight(getApplication())()
                    if (preflight != null) CommandResult.Rejected(preflight)
                    else {
                        val ready = graph.ble.connectionState.value == BleConnectionState.READY || withTimeoutOrNull(20_000L) {
                            graph.ble.connect(requireNotNull(_ui.value.profile))
                            graph.ble.connectionState.first { it == BleConnectionState.READY || it == BleConnectionState.ERROR } == BleConnectionState.READY
                        } == true
                        if (ready) graph.ble.execute(command, graph.store.experimentalEnabled)
                        else CommandResult.Failure(graph.ble.lastError ?: CommandError.CONNECTION_TIMEOUT)
                    }
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
                commandResult = if (result == CommandResult.Success) commandMessage(R.string.quick_action_success, command) else null,
                error = when (result) {
                    CommandResult.Success -> null
                    is CommandResult.Rejected -> UiMessage(result.error.messageResource())
                    is CommandResult.Failure -> UiMessage(result.error.messageResource())
                }, commandInProgress = null,
            ) }
            scheduleMessageClear()
            if (command in com.vitalyart.bydkeyless.quick.QuickCommandContract.supportedCommands) {
                val terminal = graph.quickCommands.state.value
                delay(5_000L)
                graph.quickCommands.clearTerminalState(terminal)
            }
        }
    }

    fun availability(command: VehicleCommand): ActionAvailability = CommandAvailability.evaluate(
        profile = _ui.value.profile, hasSession = _ui.value.token != null, state = BleConnectionState.READY,
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

    fun setTheme(value: String) {
        graph.store.theme = value
        _ui.update { it.copy(theme = value) }
        com.vitalyart.bydkeyless.widget.QuickControlWidget.updateAll(getApplication())
    }

    fun beginCalibration(onlyOpen: Boolean? = null, test: Boolean = false) {
        if (_ui.value.commandInProgress != null) return
        val saved = graph.store.calibration()
        _ui.update { it.copy(calibration = CalibrationSession(
            step = CalibrationStep.PREPARE, onlyOpen = onlyOpen,
            open = if (onlyOpen != null || test) saved?.unlockThreshold else null,
            close = if (onlyOpen != null || test) saved?.lockThreshold else null,
        )) }
        testing = test
    }
    private var testing = false

    fun advanceCalibration() {
        val draft = _ui.value.calibration
        when (draft.step) {
            CalibrationStep.PREPARE -> {
                if (_ui.value.bleState != BleConnectionState.READY) {
                    calibrationError(R.string.error_key_not_ready); return
                }
                // Persist first: service restarts cannot re-enable automatic commands.
                if (!graph.store.pauseAutomation()) { calibrationError(R.string.error_calibration_save); return }
                configureKeyless(KeylessMode.AUTO_UNLOCK_LOCK, false, false)
                _ui.update { it.copy(calibration = draft.copy(step = if (testing) CalibrationStep.TEST else if (draft.onlyOpen == false) CalibrationStep.CLOSE else CalibrationStep.OPEN, error = null)) }
            }
            CalibrationStep.OPEN -> if (draft.open != null) {
                _ui.update { it.copy(calibration = draft.copy(step = if (draft.onlyOpen == true) CalibrationStep.REVIEW else CalibrationStep.CLOSE, error = null)) }
            }
            CalibrationStep.CLOSE -> if (draft.close != null) _ui.update { it.copy(calibration = draft.copy(step = CalibrationStep.REVIEW, error = null)) }
            else -> Unit
        }
    }

    private fun calibrationError(resource: Int) {
        _ui.update { it.copy(calibration = it.calibration.copy(measuring = false, error = UiMessage(resource))) }
    }

    fun cancelMeasurement() {
        calibrationJob?.cancel()
        if (_ui.value.calibration.measuring) calibrationError(R.string.measurement_interrupted)
    }
    fun closeCalibration() {
        cancelMeasurement()
        _ui.update { it.copy(calibration = CalibrationSession()) }
    }
    fun previousCalibrationStep() {
        cancelMeasurement()
        _ui.update { it.copy(calibration = it.calibration.copy(step = when (it.calibration.step) {
            CalibrationStep.REVIEW -> if (it.calibration.onlyOpen == true) CalibrationStep.OPEN else CalibrationStep.CLOSE
            CalibrationStep.CLOSE -> if (it.calibration.onlyOpen == false) CalibrationStep.PREPARE else CalibrationStep.OPEN
            else -> CalibrationStep.PREPARE
        }, error = null)) }
    }
    fun captureCalibration() {
        if (calibrationJob?.isActive == true) return
        val near = _ui.value.calibration.step == CalibrationStep.OPEN
        if (_ui.value.calibration.step !in setOf(CalibrationStep.OPEN, CalibrationStep.CLOSE)) return
        calibrationJob = viewModelScope.launch {
            _ui.update { it.copy(calibration = it.calibration.copy(measuring = true, samples = 0, error = null,
                open = if (near) null else it.calibration.open, close = if (near) it.calibration.close else null)) }
            val median = measureCalibration(graph.ble.telemetry, graph.ble.connectionState, android.os.SystemClock::elapsedRealtime) { count ->
                _ui.update { it.copy(calibration = it.calibration.copy(samples = count)) }
            }
            if (median == null) { calibrationError(R.string.calibration_unstable); return@launch }
            _ui.update { it.copy(calibration = it.calibration.copy(measuring = false,
                open = if (near) median.toDouble() else it.calibration.open,
                close = if (near) it.calibration.close else median.toDouble())) }
        }
    }
    fun saveCalibration() {
        val draft = _ui.value.calibration
        val near = draft.open ?: return
        val far = draft.close ?: return
        if (near <= far) { calibrationError(R.string.error_near_signal); return }
        if (!graph.store.saveCalibration(ProximityCalibration(near, far))) {
            calibrationError(R.string.error_calibration_save); return
        }
        graph.proximity.configure(_ui.value.mode, graph.store.calibration(), false, false)
        _ui.update { it.copy(calibrated = true, calibration = draft.copy(step = CalibrationStep.SAVED, error = null)) }
    }

    fun configureKeyless(mode: KeylessMode = _ui.value.mode, autoUnlock: Boolean = _ui.value.autoUnlock, autoLock: Boolean = _ui.value.autoLock) {
        graph.store.keylessMode = mode; graph.store.autoUnlock = autoUnlock; graph.store.autoLock = autoLock
        graph.proximity.configure(mode, graph.store.calibration(), autoUnlock, autoLock)
        _ui.update { it.copy(mode = mode, autoUnlock = autoUnlock, autoLock = autoLock) }
        if (mode == KeylessMode.OFF) { startJob?.cancel(); KeylessService.stop(getApplication()) }
        else startBackgroundKey()
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

    fun logout() {
        closeCalibration()
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
            experimental = graph.store.experimentalEnabled,
            theme = graph.store.theme,
            calibrated = calibration != null,
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
