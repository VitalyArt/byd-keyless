package com.vitalyart.bydkeyless.ui

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vitalyart.bydkeyless.R
import com.vitalyart.bydkeyless.KeylessApplication
import com.vitalyart.bydkeyless.ble.ActionAvailability
import com.vitalyart.bydkeyless.ble.CommandAvailability
import com.vitalyart.bydkeyless.model.*
import com.vitalyart.bydkeyless.network.WatchRegions
import com.vitalyart.bydkeyless.service.KeylessService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class MainUiState(
    val loading: Boolean = false,
    val qrSession: WatchQrSession? = null,
    val qrStatus: WatchQrStatus = WatchQrStatus.UNKNOWN,
    val token: WatchToken? = null,
    val profile: VehicleProfile? = null,
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
    val experimental: Boolean = false,
    val calibrated: Boolean = false,
    val unlockDistanceMeters: Double = ProximityCalibration.DEFAULT_UNLOCK_DISTANCE_METERS,
    val lockDistanceMeters: Double = ProximityCalibration.DEFAULT_LOCK_DISTANCE_METERS,
    val manualUnlockVerified: Boolean = false,
    val manualLockVerified: Boolean = false,
    val watchCountryCode: String = "UZ",
    val language: String = "system",
    val permissionState: PermissionState = PermissionState.UNKNOWN,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as KeylessApplication).graph
    private val _ui = MutableStateFlow(initialState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()
    private var polling: Job? = null
    private var messageJob: Job? = null

    init {
        viewModelScope.launch { graph.ble.connectionState.collect { _ui.update { s -> s.copy(bleState = it) } } }
        viewModelScope.launch { graph.ble.telemetry.collect { _ui.update { s -> s.copy(telemetry = it) } } }
        viewModelScope.launch { graph.proximity.state.collect { _ui.update { s -> s.copy(proximity = it) } } }
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
            }.onFailure { _ui.update { it.copy(loading = false, error = UiMessage(R.string.error_network)) } }
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
                _ui.update { state -> state.copy(error = UiMessage(R.string.error_qr_retry)) }
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
        runCatching {
            val token = graph.auth.gainToken(session)
            val profile = graph.auth.loadVehicleProfile(token)
            require(profile.hasValidKey()) { "BYD did not issue a valid Bluetooth key" }
            graph.store.saveSession(token, profile)
            token to profile
        }.onSuccess { (token, profile) ->
            _ui.update { it.copy(loading = false, token = token, profile = profile, qrSession = null, error = null) }
        }.onFailure { _ui.update { it.copy(loading = false, error = UiMessage(R.string.error_network)) } }
    }

    fun startKey() {
        viewModelScope.launch {
            val current = _ui.value.profile ?: return@launch
            val token = _ui.value.token ?: return@launch
            // Capabilities and key validity can change server-side. Refresh whenever
            // the user starts the key, but retain a still-valid offline key if BYD is
            // temporarily unreachable.
            val profile = runCatching { graph.auth.loadVehicleProfile(token) }.fold(
                onSuccess = { refreshed ->
                    graph.store.saveSession(token, refreshed)
                    _ui.update { it.copy(profile = refreshed, error = null) }
                    refreshed
                },
                onFailure = {
                    if (!current.hasValidKey()) {
                        _ui.update { it.copy(error = UiMessage(R.string.error_network)) }
                        return@launch
                    }
                    current
                },
            )
            graph.ble.connect(profile)
            if (graph.store.keylessMode != KeylessMode.OFF) runCatching { KeylessService.start(getApplication()) }
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

    fun captureNear() { _ui.value.telemetry.rssi?.let { _ui.update { s -> s.copy(nearSample = it) } } }
    fun captureFar() { _ui.value.telemetry.rssi?.let { _ui.update { s -> s.copy(farSample = it) } } }
    fun saveCalibration() {
        val near = _ui.value.nearSample ?: return
        val far = _ui.value.farSample ?: return
        if (near <= far) { showError(UiMessage(R.string.error_near_signal)); return }
        graph.store.saveCalibration(ProximityCalibration(near, far, _ui.value.unlockDistanceMeters, _ui.value.lockDistanceMeters))
        _ui.update { it.copy(calibrated = true, commandResult = UiMessage(R.string.calibration_saved), error = null) }
        scheduleMessageClear()
        configureKeyless(_ui.value.mode, _ui.value.autoUnlock, _ui.value.autoLock)
    }

    fun configureKeyless(mode: KeylessMode = _ui.value.mode, autoUnlock: Boolean = _ui.value.autoUnlock, autoLock: Boolean = _ui.value.autoLock) {
        graph.store.keylessMode = mode; graph.store.autoUnlock = autoUnlock; graph.store.autoLock = autoLock
        graph.proximity.configure(mode, graph.store.calibration(), autoUnlock, autoLock)
        _ui.update { it.copy(mode = mode, autoUnlock = autoUnlock, autoLock = autoLock) }
        if (mode == KeylessMode.OFF) KeylessService.stop(getApplication()) else runCatching { KeylessService.start(getApplication()) }
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

    fun logout() {
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
            nearSample = calibration?.nearRssi,
            farSample = calibration?.farRssi,
            calibrated = calibration != null,
            unlockDistanceMeters = calibration?.unlockDistanceMeters ?: graph.store.unlockDistanceMeters,
            lockDistanceMeters = calibration?.lockDistanceMeters ?: graph.store.lockDistanceMeters,
            manualUnlockVerified = graph.store.manualUnlockVerified,
            manualLockVerified = graph.store.manualLockVerified,
            watchCountryCode = WatchRegions.byCode(graph.store.watchCountryCode).code,
            language = graph.store.language,
        )
    }

    private fun scheduleMessageClear() {
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(5_000L)
            _ui.update { it.copy(error = null, commandResult = null) }
        }
    }
}
