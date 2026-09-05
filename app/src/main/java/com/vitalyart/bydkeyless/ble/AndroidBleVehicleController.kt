package com.vitalyart.bydkeyless.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.vitalyart.bydkeyless.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import kotlin.math.pow

@SuppressLint("MissingPermission")
class AndroidBleVehicleController(
    private val context: Context,
    private val native: BydNativeFacade,
    private val calibration: () -> ProximityCalibration? = { null },
    private val keylessMode: () -> KeylessMode = { KeylessMode.OFF },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : BleVehicleController {
    // Keep every Android GATT transition on the main looper, matching BYD's
    // Nordic request queue. Samsung may acknowledge operations issued from
    // worker threads while failing to route subsequent notifications reliably.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private val _state = MutableStateFlow(BleConnectionState.IDLE)
    override val connectionState: StateFlow<BleConnectionState> = _state
    private val _telemetry = MutableStateFlow(VehicleTelemetry())
    override val telemetry: StateFlow<VehicleTelemetry> = _telemetry
    private val _diagnostics = MutableStateFlow(BleDiagnostics())
    val diagnostics: StateFlow<BleDiagnostics> = _diagnostics
    private val commandMutex = Mutex()
    private var profile: VehicleProfile? = null
    private var pendingCommand: CompletableDeferred<Boolean>? = null
    private var rssiJob: Job? = null
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var authenticationTimeoutJob: Job? = null
    private var manualDisconnect = false
    private var smoothedRssi: Double? = null
    private var authenticationStage = AuthenticationStage.NONE
    private var lastPassiveEntryAt = Long.MIN_VALUE
    private var lastBleActivityAt = Long.MIN_VALUE
    private var reconnectAttempt = 0
    private var fastRssiUntilMillis = 0L

    fun requestCalibrationSampling() { fastRssiUntilMillis = clock() + 15_000L }
    private var nordic: BydNordicManager? = null
    private var generation = 0L
    private var scanTransitionJob: Job? = null
    private var connectionDeadlineJob: Job? = null
    private var adapterReceiverRegistered = false
    var lastError: CommandError? = null
        private set(value) {
            field = value
            _diagnostics.value = _diagnostics.value.copy(error = value)
        }
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (manualDisconnect) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    reconnectJob?.cancel()
                    startScan()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    reconnectJob?.cancel()
                    stopScan()
                    closeGatt()
                    lastError = CommandError.BLUETOOTH_OFF
                    _state.value = BleConnectionState.ERROR
                }
            }
        }
    }

    private var scanCallback: ScanCallback? = null
    private var scanEconomyJob: Job? = null

    private fun newScanCallback(session: Long) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (session != generation || scanCallback !== this || _state.value != BleConnectionState.SCANNING) return
            val target = profile?.macAddress ?: return
            if (!runCatching { result.device.address.equals(target, true) }.getOrDefault(false) || scanTransitionJob?.isActive == true) return
            stopScan()
            scanEconomyJob?.cancel()
            scanTransitionJob = scope.launch {
                delay(SCAN_TO_CONNECT_DELAY_MS)
                if (generation == session && _state.value == BleConnectionState.SCANNING && !manualDisconnect) {
                    connectGatt(result.device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (session != generation || scanCallback !== this) return
            Log.w(TAG, "BLE scan failed: errorCode=$errorCode")
            recoverConnection("scan failed ($errorCode)")
        }
    }

    override suspend fun connect(profile: VehicleProfile) = withContext(Dispatchers.Main.immediate) {
        if (this@AndroidBleVehicleController.profile == profile && _state.value in ACTIVE_CONNECTION_STATES) return@withContext
        disconnect()
        manualDisconnect = false
        this@AndroidBleVehicleController.profile = profile
        if (!adapterReceiverRegistered) {
            ContextCompat.registerReceiver(context, adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            adapterReceiverRegistered = true
        }
        startScan()
    }

    override suspend fun disconnect() = withContext(Dispatchers.Main.immediate) {
        manualDisconnect = true
        lastPassiveEntryAt = Long.MIN_VALUE
        reconnectAttempt = 0
        reconnectJob?.cancel()
        stopScan()
        closeGatt()
        if (adapterReceiverRegistered) {
            context.unregisterReceiver(adapterReceiver)
            adapterReceiverRegistered = false
        }
        lastError = null
        _state.value = BleConnectionState.IDLE
    }

    override suspend fun execute(command: VehicleCommand, experimentalEnabled: Boolean): CommandResult = withContext(Dispatchers.Main.immediate) {
        if (!commandMutex.tryLock()) return@withContext CommandResult.Rejected(CommandError.COMMAND_BUSY)
        try { run transaction@ {
            CommandPolicy.rejection(profile, _state.value, command, experimentalEnabled)?.let {
                return@transaction CommandResult.Rejected(it)
            }
            val frame = native.commandFrame(command) ?: return@transaction CommandResult.Rejected(CommandError.NATIVE_UNAVAILABLE)
            val commandWakeLock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:ble-command")
            commandWakeLock.acquire(COMMAND_TIMEOUT_MS + 1_000L)
            val session = generation
            val deferred = CompletableDeferred<Boolean>().also { pendingCommand = it }
            try {
                if (!write(frame)) {
                    recoverConnection("command frame could not be queued")
                    return@transaction CommandResult.Failure(CommandError.GATT_WRITE_FAILED)
                }
                val success = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { deferred.await() }
                if (success == null) {
                    // A late acknowledgement must never complete a later command.
                    recoverConnection("command acknowledgement timeout")
                    return@transaction CommandResult.Failure(CommandError.VEHICLE_TIMEOUT)
                }
                if (success && session == generation) {
                    when (command) {
                        VehicleCommand.LOCK, VehicleCommand.UNLOCK -> _telemetry.value = _telemetry.value.copy(
                            doorState = if (command == VehicleCommand.LOCK) DoorState.LOCKED else DoorState.UNLOCKED,
                            doorStateAtMillis = clock(),
                        )
                        else -> Unit
                    }
                    CommandResult.Success
                } else CommandResult.Failure(CommandError.VEHICLE_TIMEOUT)
            } catch (cancelled: CancellationException) {
                if (session == generation) recoverConnection("command interrupted; result unknown")
                throw cancelled
            } catch (failure: Exception) {
                if (session == generation) recoverConnection("command transport failed")
                CommandResult.Failure(CommandError.GATT_WRITE_FAILED)
            } finally {
                if (pendingCommand === deferred) pendingCommand = null
                deferred.cancel()
                if (commandWakeLock.isHeld) commandWakeLock.release()
            }
        } } finally { commandMutex.unlock() }
    }

    private fun connectGatt(device: BluetoothDevice) {
        _state.value = BleConnectionState.CONNECTING
        val transport = BydNordicManager(context).also { nordic = it }
        connectionDeadlineJob?.cancel()
        connectionDeadlineJob = scope.launch {
            delay(35_000L)
            if (nordic === transport && _state.value != BleConnectionState.READY) recoverConnection("connection setup deadline exceeded")
        }
        transport.connect(device).useAutoConnect(false).timeout(15_000L)
            .fail { _, status -> if (nordic === transport && !manualDisconnect) recoverConnection("Nordic connect failed ($status)") }
            .enqueue()
    }

    private fun stopScan() {
        val active = scanCallback ?: return
        scanCallback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(active) }
    }

    private fun startScan(economy: Boolean = false) {
        if (manualDisconnect || _state.value in ACTIVE_CONNECTION_STATES) return
        lastError = when {
            profile?.hasValidKey() != true -> CommandError.KEY_EXPIRED
            !native.loaded -> CommandError.NATIVE_UNAVAILABLE
            !hasPermissions() -> CommandError.PERMISSION_DENIED
            adapter?.isEnabled != true -> CommandError.BLUETOOTH_OFF
            !BluetoothAdapter.checkBluetoothAddress(profile?.macAddress.orEmpty().uppercase()) -> CommandError.KEY_NOT_READY
            else -> null
        }
        if (lastError != null) {
            _state.value = BleConnectionState.ERROR
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            scheduleReconnect()
            return
        }
        _state.value = BleConnectionState.SCANNING
        try {
            val callback = newScanCallback(generation).also { scanCallback = it }
            scanner.startScan(
                listOf(ScanFilter.Builder().setDeviceAddress(requireNotNull(profile?.macAddress).uppercase()).build()),
                ScanSettings.Builder().setScanMode(if (economy) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
            if (!economy) {
                scanEconomyJob?.cancel()
                scanEconomyJob = scope.launch {
                    delay(30_000L)
                    if (_state.value == BleConnectionState.SCANNING && scanCallback === callback) {
                        stopScan()
                        _state.value = BleConnectionState.DISCONNECTED
                        startScan(economy = true)
                    }
                }
            }
        } catch (error: SecurityException) {
            lastError = CommandError.PERMISSION_DENIED
            _state.value = BleConnectionState.ERROR
        } catch (error: IllegalStateException) {
            recoverConnection("scanner unavailable")
        }
    }

    private fun scheduleReconnect() {
        if (manualDisconnect) return
        reconnectJob?.cancel()
        _state.value = BleConnectionState.DISCONNECTED
        reconnectAttempt += 1
        val reconnectDelay = ConnectionHealthPolicy.reconnectDelayMillis(reconnectAttempt)
        Log.i(TAG, "Scheduling BLE reconnect attempt=$reconnectAttempt delayMs=$reconnectDelay")
        reconnectJob = scope.launch {
            delay(reconnectDelay)
            if (!manualDisconnect) startScan()
        }
    }

    private fun recoverConnection(reason: String) {
        if (manualDisconnect) return
        if (_state.value == BleConnectionState.DISCONNECTED && reconnectJob?.isActive == true) return
        _diagnostics.value = _diagnostics.value.copy(lastRecovery = reason, recoveries = _diagnostics.value.recoveries + 1)
        Log.w(TAG, "Recovering BLE connection: $reason")
        _state.value = BleConnectionState.DISCONNECTED
        pendingCommand?.takeIf { !it.isCompleted }?.complete(false)
        stopScan()
        closeGatt()
        scheduleReconnect()
    }

    private fun startReadyWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && _state.value == BleConnectionState.READY) {
                delay(WATCHDOG_INTERVAL_MS)
                if (ConnectionHealthPolicy.isReadyConnectionStale(_state.value, clock(), lastBleActivityAt)) {
                    // After CPU sleep, allow a fresh read to complete before deciding the link died.
                    nordic?.readVehicleRssi()
                    delay(WATCHDOG_INTERVAL_MS)
                    if (ConnectionHealthPolicy.isReadyConnectionStale(_state.value, clock(), lastBleActivityAt)) {
                        recoverConnection("no BLE activity for ${ConnectionHealthPolicy.READY_STALE_TIMEOUT_MS}ms")
                        return@launch
                    }
                }
            }
        }
    }

    private fun markBleActivity() { lastBleActivityAt = clock() }

    private fun beginAuthentication() {
        val p = profile ?: return
        _state.value = BleConnectionState.AUTHENTICATING
        authenticationStage = AuthenticationStage.WAITING_FOR_RANDOM_EXCHANGE
        authenticationTimeoutJob?.cancel()
        authenticationTimeoutJob = scope.launch {
            delay(AUTHENTICATION_TIMEOUT_MS)
            if (_state.value == BleConnectionState.AUTHENTICATING) {
                closeGatt()
                scheduleReconnect()
            }
        }
        val frame = runCatching { native.randomExchangeFrame(p.keyNumber) }.getOrNull()
        if (frame == null || !write(frame)) failAuthentication()
    }

    private fun scheduleAuthentication() {
        scope.launch {
            // The original BYD client lets the vehicle commit its CCCD state before
            // sending the first protocol frame. Sending immediately is silently lost
            // by some BLE3 controllers.
            val session = generation
            delay(CCCD_TO_AUTH_DELAY_MS)
            if (session == generation && _state.value == BleConnectionState.DISCOVERING) beginAuthentication()
        }
    }

    private fun handleIncoming(rawFrame: ByteArray) {
        markBleActivity()
        val frame = native.decodeIncomingFrame(rawFrame)
        if (frame == null) {
            Log.w(TAG, "Native frame decoding rejected incoming update bytes=${rawFrame.size}")
            return
        }
        _diagnostics.value = _diagnostics.value.copy(lastPacketAtMillis = clock())
        Log.d(TAG, "Native frame decoding completed bytes=${frame.size}")
        if (_state.value == BleConnectionState.AUTHENTICATING) {
            when (authenticationStage) {
                AuthenticationStage.WAITING_FOR_RANDOM_EXCHANGE -> if (native.isRandomExchangeResponse(frame)) {
                    if (!native.randomExchangePassed(frame)) {
                        failAuthentication()
                        return
                    }
                    val p = profile ?: return failAuthentication()
                    val key = p.digitalKey ?: return failAuthentication()
                    authenticationStage = AuthenticationStage.WAITING_FOR_AUTH_RESULT
                    val authentication = runCatching { native.authenticationFrame(key, p.vinRssi ?: DEFAULT_VIN_RSSI) }.getOrNull()
                    if (authentication == null || !write(authentication)) failAuthentication()
                    return
                }
                AuthenticationStage.WAITING_FOR_AUTH_RESULT -> if (native.authenticationPassed(frame)) {
                    authenticationTimeoutJob?.cancel()
                    authenticationStage = AuthenticationStage.NONE
                    connectionDeadlineJob?.cancel()
                    _state.value = BleConnectionState.READY
                    reconnectAttempt = 0
                    markBleActivity()
                    startReadyWatchdog()
                    Log.i(TAG, "BLE authentication completed; controller is ready")
                    rssiJob?.cancel()
                    rssiJob = scope.launch { while (isActive && _state.value == BleConnectionState.READY) { nordic?.readVehicleRssi(); delay(if (keylessMode() == KeylessMode.PASSIVE_ENTRY && clock() >= fastRssiUntilMillis) 4_000L else 1_000L) } }
                    return
                }
                AuthenticationStage.NONE -> Unit
            }
        }
        if (_state.value == BleConnectionState.READY && native.isMicroSwitchPressed(frame)) {
            val handleWakeLock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:handle-response")
            handleWakeLock.acquire(2_000L)
            val acknowledged = write(native.microSwitchResponse())
            val now = clock()
            if (acknowledged && PassiveEntryPolicy.shouldUnlock(keylessMode(), _state.value, profile, now, lastPassiveEntryAt, keyValid = profile?.hasValidKey() == true)) {
                lastPassiveEntryAt = now
                val session = generation
                scope.launch {
                    delay(PASSIVE_ENTRY_ACK_DELAY_MS)
                    if (session != generation || keylessMode() != KeylessMode.PASSIVE_ENTRY) return@launch
                    when (val result = execute(VehicleCommand.UNLOCK)) {
                        CommandResult.Success -> Log.i(TAG, "Passive entry unlock confirmed by vehicle")
                        is CommandResult.Rejected -> Log.w(TAG, "Passive entry unlock rejected: ${result.error}")
                        is CommandResult.Failure -> Log.w(TAG, "Passive entry unlock failed: ${result.error}")
                    }
                }
            }
        }
        native.commandResponse(frame)?.let { ack ->
            _telemetry.value = _telemetry.value.copy(allClosuresClosed = ack.allClosuresClosed, closuresAtMillis = clock())
            pendingCommand?.takeIf { !it.isCompleted }?.complete(ack.success)
        }
    }

    private fun write(frame: ByteArray): Boolean {
        return nordic?.writeFrame(frame) == true
    }

    private fun hasPermissions(): Boolean = if (Build.VERSION.SDK_INT < 31) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    } else ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun failAuthentication() {
        authenticationTimeoutJob?.cancel()
        authenticationStage = AuthenticationStage.NONE
        closeGatt()
        if (manualDisconnect) _state.value = BleConnectionState.ERROR else scheduleReconnect()
    }

    private fun closeGatt() {
        generation += 1
        scanTransitionJob?.cancel()
        scanEconomyJob?.cancel()
        connectionDeadlineJob?.cancel()
        authenticationTimeoutJob?.cancel()
        authenticationStage = AuthenticationStage.NONE
        rssiJob?.cancel(); watchdogJob?.cancel(); lastBleActivityAt = Long.MIN_VALUE
        pendingCommand?.takeIf { !it.isCompleted }?.complete(false)
        val old = nordic
        nordic = null // Ignore all callbacks from the retired transport.
        runCatching { old?.close() }.onFailure { Log.w(TAG, "BLE transport cleanup failed", it) }
        smoothedRssi = null
        _telemetry.value = VehicleTelemetry(connectionGeneration = generation)
    }

    /**
     * The official BYD clients use Nordic's serialized request queue. Keeping this
     * transport intact matters on Samsung's Android 16 Bluetooth stack: direct GATT
     * calls were acknowledged, but the first BLE3 response was never routed back.
     */
    private inner class BydNordicManager(context: Context) : BleManager(context) {
        private var send: BluetoothGattCharacteristic? = null
        private var receive: BluetoothGattCharacteristic? = null

        private var rssiPending = false

        init {
            setConnectionObserver(object : ConnectionObserver {
                override fun onDeviceConnecting(device: BluetoothDevice) = Unit
                override fun onDeviceConnected(device: BluetoothDevice) {
                    if (nordic === this@BydNordicManager) _state.value = BleConnectionState.DISCOVERING
                }
                override fun onDeviceReady(device: BluetoothDevice) = Unit // Protocol authentication is still required.
                override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
                override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                    if (nordic === this@BydNordicManager) recoverConnection("connect failed ($reason)")
                }
                override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                    if (nordic === this@BydNordicManager) recoverConnection("disconnected ($reason)")
                }
            })
        }

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            if (nordic !== this) return false
            val service = runCatching { gatt.getService(native.serviceUuid()) }.getOrNull()
            send = service?.getCharacteristic(runCatching { native.sendUuid() }.getOrNull())
            receive = service?.getCharacteristic(runCatching { native.receiveUuid() }.getOrNull())
            Log.d(TAG, "Nordic services: sendProperties=${send?.properties} receiveProperties=${receive?.properties}")
            return send != null && receive != null
        }

        override fun initialize() {
            if (nordic !== this) return
            val input = receive ?: return
            _state.value = BleConnectionState.DISCOVERING
            setNotificationCallback(input).with { _, data ->
                if (nordic !== this) return@with
                val value = data.value ?: return@with
                Log.d(TAG, "Nordic characteristic update received: bytes=${value.size}")
                handleIncoming(value)
            }
            enableNotifications(input)
                .done {
                    if (nordic !== this) return@done
                    Log.d(TAG, "Nordic notification subscription ready")
                    scheduleAuthentication()
                }
                .fail { _, status ->
                    if (nordic !== this) return@fail
                    Log.w(TAG, "Nordic notification subscription failed: status=$status")
                    failAuthentication()
                }
                .enqueue()
        }

        override fun onServicesInvalidated() {
            send = null
            receive = null
        }

        fun writeFrame(frame: ByteArray): Boolean {
            if (nordic !== this) return false
            val output = send ?: return false
            writeCharacteristic(output, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                .done { Log.d(TAG, "Nordic characteristic write completed: bytes=${frame.size}") }
                .fail { _, status ->
                    if (nordic !== this) return@fail
                    Log.w(TAG, "Nordic characteristic write failed: status=$status")
                    if (_state.value == BleConnectionState.AUTHENTICATING) failAuthentication()
                    else if (_state.value == BleConnectionState.READY) recoverConnection("Nordic write failed ($status)")
                }
                .enqueue()
            return true
        }

        fun readVehicleRssi() {
            if (rssiPending || nordic !== this) return
            rssiPending = true
            readRssi()
                .with { _, rssi -> if (nordic === this) updateRssi(rssi) }
                .done { rssiPending = false }
                .fail { _, status ->
                    rssiPending = false
                    if (nordic === this) Log.w(TAG, "RSSI read failed: status=$status")
                }
                .enqueue()
        }
    }

    private fun updateRssi(rssi: Int) {
        markBleActivity()
        smoothedRssi = smoothedRssi?.let { it * 0.75 + rssi * 0.25 } ?: rssi.toDouble()
        val smooth = smoothedRssi ?: rssi.toDouble()
        _telemetry.value = _telemetry.value.copy(
            rssi = rssi,
            doorState = _telemetry.value.doorState.takeIf { _telemetry.value.doorStateAtMillis?.let { clock() - it in 0..30_000L } == true } ?: DoorState.UNKNOWN,
            rssiAtMillis = clock(),
            rssiSequence = _telemetry.value.rssiSequence + 1,
            nativeArea = native.rssiArea(rssi),
            approximateMeters = calibration()?.approximateDistance(smooth) ?: 10.0.pow((-59.0 - smooth) / 22.0).coerceIn(0.1, 50.0),
        )
    }

    private enum class AuthenticationStage { NONE, WAITING_FOR_RANDOM_EXCHANGE, WAITING_FOR_AUTH_RESULT }

    private companion object {
        const val TAG = "BydBleController"
        const val SCAN_TO_CONNECT_DELAY_MS = 500L
        // Measured on the current official BYD AUTO client on the target phone:
        // connection callback -> discovery ~= 303 ms, CCCD completion -> first
        // random frame ~= 308 ms. These windows are part of the BLE3 handshake.
        const val CCCD_TO_AUTH_DELAY_MS = 300L
        const val DEFAULT_VIN_RSSI = "FFFFFFFFFFFFFFFFFFFFFF"
        const val AUTHENTICATION_TIMEOUT_MS = 15_000L
        const val COMMAND_TIMEOUT_MS = 10_000L
        const val WATCHDOG_INTERVAL_MS = 2_000L
        const val PASSIVE_ENTRY_ACK_DELAY_MS = 150L
        val ACTIVE_CONNECTION_STATES = setOf(
            BleConnectionState.SCANNING,
            BleConnectionState.CONNECTING,
            BleConnectionState.DISCOVERING,
            BleConnectionState.AUTHENTICATING,
            BleConnectionState.READY,
        )
    }
}
