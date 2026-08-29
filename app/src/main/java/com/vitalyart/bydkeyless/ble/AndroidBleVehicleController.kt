package com.vitalyart.bydkeyless.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.vitalyart.bydkeyless.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.android.ble.BleManager
import java.util.UUID
import kotlin.math.pow

@SuppressLint("MissingPermission")
class AndroidBleVehicleController(
    private val context: Context,
    private val native: BydNativeFacade,
    private val keylessMode: () -> KeylessMode = { KeylessMode.OFF },
    private val clock: () -> Long = System::currentTimeMillis,
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
    private val commandMutex = Mutex()
    private var profile: VehicleProfile? = null
    private var gatt: BluetoothGatt? = null
    private var sendCharacteristic: BluetoothGattCharacteristic? = null
    private var receiveCharacteristic: BluetoothGattCharacteristic? = null
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
    private val nordic = BydNordicManager(context)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (_state.value != BleConnectionState.SCANNING) return
            val target = profile?.macAddress
            val addressMatches = target.isNullOrBlank() || result.device.address.equals(target, true)
            val nameMatches = result.device.name?.contains("BYD", true) == true
            if (addressMatches || (target.isNullOrBlank() && nameMatches)) {
                adapter?.bluetoothLeScanner?.stopScan(this)
                // Match the production BYD client: it lets the controller finish
                // tearing down the scanner before registering a GATT client. On
                // Samsung's stack an immediate scan -> connect transition may
                // report a healthy GATT connection while silently losing the first
                // protocol exchange.
                scope.launch {
                    delay(SCAN_TO_CONNECT_DELAY_MS)
                    if (_state.value == BleConnectionState.SCANNING && !manualDisconnect) {
                        connectGatt(result.device)
                    }
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: errorCode=$errorCode")
            recoverConnection("scan failed ($errorCode)")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _state.value = BleConnectionState.DISCOVERING
                // The BYD/Nordic client lets the BLE controller settle before
                // service discovery. Immediate discovery is unreliable on the
                // Samsung Android 16 stack used during the vehicle test.
                scope.launch {
                    delay(CONNECTION_TO_DISCOVERY_DELAY_MS)
                    if (this@AndroidBleVehicleController.gatt === gatt && _state.value == BleConnectionState.DISCOVERING) {
                        gatt.discoverServices()
                    }
                }
            } else if (manualDisconnect) {
                closeGatt(); _state.value = BleConnectionState.IDLE
            } else recoverConnection("GATT disconnected status=$status state=$newState")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { recoverConnection("service discovery failed ($status)"); return }
            val service = runCatching { gatt.getService(native.serviceUuid()) }.getOrNull()
            sendCharacteristic = service?.getCharacteristic(runCatching { native.sendUuid() }.getOrNull())
            receiveCharacteristic = service?.getCharacteristic(runCatching { native.receiveUuid() }.getOrNull())
            val receive = receiveCharacteristic
            if (sendCharacteristic == null || receive == null) { recoverConnection("required characteristics unavailable"); return }
            Log.d(
                TAG,
                "Protocol characteristics discovered: sendProperties=${sendCharacteristic?.properties} " +
                    "receiveProperties=${receive.properties}",
            )
            // Nordic's request queue starts this operation on a later main-loop
            // turn. The production BYD trace has a stable ~200 ms gap after the
            // services callback; subscribing synchronously can return success but
            // leave this BLE3 peripheral without an active notification route.
            scope.launch {
                delay(SERVICES_TO_SUBSCRIBE_DELAY_MS)
                if (this@AndroidBleVehicleController.gatt === gatt && _state.value == BleConnectionState.DISCOVERING) {
                    enableNotifications(gatt, receive)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "Descriptor write completed: status=$status uuid=${descriptor.uuid}")
            if (status == BluetoothGatt.GATT_SUCCESS) scheduleAuthentication() else recoverConnection("notification descriptor failed ($status)")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(TAG, "Characteristic update received: bytes=${value.size} api=33 uuid=${characteristic.uuid}")
            handleIncoming(value)
        }
        @Deprecated("API 33 callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value
            Log.d(TAG, "Characteristic update received: bytes=${value?.size ?: 0} api=legacy uuid=${characteristic.uuid}")
            handleIncoming(value ?: return)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // Do not log the payload: authentication and command frames may contain
            // derived key material. The status and UUID are enough for diagnostics.
            Log.d(TAG, "Characteristic write completed: status=$status uuid=${characteristic.uuid}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (_state.value == BleConnectionState.AUTHENTICATING) failAuthentication()
                else if (_state.value == BleConnectionState.READY) recoverConnection("characteristic write failed ($status)")
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            markBleActivity()
            smoothedRssi = smoothedRssi?.let { it * 0.75 + rssi * 0.25 } ?: rssi.toDouble()
            val smooth = smoothedRssi ?: rssi.toDouble()
            _telemetry.value = _telemetry.value.copy(
                rssi = rssi, nativeArea = native.rssiArea(rssi),
                approximateMeters = 10.0.pow((-59.0 - smooth) / 22.0).coerceIn(0.1, 50.0),
            )
        }
    }

    override suspend fun connect(profile: VehicleProfile) {
        if (!profile.hasValidKey()) { _state.value = BleConnectionState.ERROR; return }
        if (!native.loaded || !hasPermissions() || adapter?.isEnabled != true) { _state.value = BleConnectionState.ERROR; return }
        // MainViewModel starts the foreground service after initiating the visible
        // connection. Both share this controller; the service must adopt an active
        // attempt instead of disconnecting it and starting a second GATT session.
        if (this.profile == profile && _state.value in ACTIVE_CONNECTION_STATES) return
        disconnect()
        manualDisconnect = false
        reconnectAttempt = 0
        this.profile = profile
        startScan()
    }

    override suspend fun disconnect() {
        manualDisconnect = true
        lastPassiveEntryAt = Long.MIN_VALUE
        lastBleActivityAt = Long.MIN_VALUE
        reconnectAttempt = 0
        reconnectJob?.cancel()
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        rssiJob?.cancel(); watchdogJob?.cancel(); pendingCommand?.cancel(); closeGatt(); _state.value = BleConnectionState.IDLE
    }

    override suspend fun execute(command: VehicleCommand, experimentalEnabled: Boolean): CommandResult = commandMutex.withLock {
        CommandPolicy.rejection(profile, _state.value, command, experimentalEnabled)?.let {
            return@withLock CommandResult.Rejected(it)
        }
        val frame = native.commandFrame(command) ?: return@withLock CommandResult.Rejected(CommandError.NATIVE_UNAVAILABLE)
        val deferred = CompletableDeferred<Boolean>().also { pendingCommand = it }
        if (!write(frame)) {
            pendingCommand = null
            recoverConnection("command frame could not be queued")
            return@withLock CommandResult.Failure(CommandError.GATT_WRITE_FAILED)
        }
        val success = withTimeoutOrNull(COMMAND_TIMEOUT_MS) { deferred.await() }
        pendingCommand = null
        if (success == null) {
            recoverConnection("command acknowledgement timeout")
            return@withLock CommandResult.Failure(CommandError.VEHICLE_TIMEOUT)
        }
        if (success) {
            when (command) {
                VehicleCommand.LOCK -> _telemetry.value = _telemetry.value.copy(doorState = DoorState.LOCKED)
                VehicleCommand.UNLOCK -> _telemetry.value = _telemetry.value.copy(doorState = DoorState.UNLOCKED)
                else -> Unit
            }
            CommandResult.Success
        } else CommandResult.Failure(CommandError.VEHICLE_TIMEOUT)
    }

    private fun connectGatt(device: BluetoothDevice) {
        _state.value = BleConnectionState.CONNECTING
        nordic.connect(device)
            .useAutoConnect(false)
            .timeout(15_000L)
            .fail { _, status ->
                Log.w(TAG, "Nordic connect failed: status=$status")
                if (!manualDisconnect) recoverConnection("Nordic connect failed ($status)")
            }
            .enqueue()
    }

    private fun startScan() {
        if (manualDisconnect || profile?.hasValidKey() != true || adapter?.isEnabled != true || !hasPermissions()) {
            _state.value = BleConnectionState.ERROR
            return
        }
        _state.value = BleConnectionState.SCANNING
        adapter?.bluetoothLeScanner?.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
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
        Log.w(TAG, "Recovering BLE connection: $reason")
        _state.value = BleConnectionState.DISCONNECTED
        pendingCommand?.takeIf { !it.isCompleted }?.complete(false)
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        closeGatt()
        scheduleReconnect()
    }

    private fun startReadyWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && _state.value == BleConnectionState.READY) {
                delay(WATCHDOG_INTERVAL_MS)
                if (ConnectionHealthPolicy.isReadyConnectionStale(_state.value, clock(), lastBleActivityAt)) {
                    recoverConnection("no BLE activity for ${ConnectionHealthPolicy.READY_STALE_TIMEOUT_MS}ms")
                    return@launch
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
        if (!write(native.randomExchangeFrame(p.keyNumber))) failAuthentication()
    }

    private fun scheduleAuthentication() {
        scope.launch {
            // The original BYD client lets the vehicle commit its CCCD state before
            // sending the first protocol frame. Sending immediately is silently lost
            // by some BLE3 controllers.
            delay(CCCD_TO_AUTH_DELAY_MS)
            if (_state.value == BleConnectionState.DISCOVERING) beginAuthentication()
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, receive: BluetoothGattCharacteristic) {
        val localSubscriptionEnabled = gatt.setCharacteristicNotification(receive, true)
        Log.d(TAG, "Local notification subscription: accepted=$localSubscriptionEnabled")
        val cccd = receive.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (cccd == null) {
            scheduleAuthentication()
            return
        }
        // Match BYD's Nordic transport exactly. On this Samsung build the API 33
        // overload reports success but notifications are not consistently delivered.
        @Suppress("DEPRECATION")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(cccd)
    }

    private fun handleIncoming(rawFrame: ByteArray) {
        markBleActivity()
        val frame = native.decodeIncomingFrame(rawFrame)
        if (frame == null) {
            Log.w(TAG, "Native frame decoding rejected incoming update bytes=${rawFrame.size}")
            return
        }
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
                    if (!write(native.authenticationFrame(key, p.vinRssi ?: DEFAULT_VIN_RSSI))) failAuthentication()
                    return
                }
                AuthenticationStage.WAITING_FOR_AUTH_RESULT -> if (native.authenticationPassed(frame)) {
                    authenticationTimeoutJob?.cancel()
                    authenticationStage = AuthenticationStage.NONE
                    _state.value = BleConnectionState.READY
                    reconnectAttempt = 0
                    markBleActivity()
                    startReadyWatchdog()
                    Log.i(TAG, "BLE authentication completed; controller is ready")
                    rssiJob?.cancel()
                    rssiJob = scope.launch { while (isActive && _state.value == BleConnectionState.READY) { nordic.readVehicleRssi(); delay(1_000) } }
                    return
                }
                AuthenticationStage.NONE -> Unit
            }
        }
        if (_state.value == BleConnectionState.READY && native.isMicroSwitchPressed(frame)) {
            val acknowledged = write(native.microSwitchResponse())
            val now = clock()
            if (acknowledged && PassiveEntryPolicy.shouldUnlock(keylessMode(), _state.value, profile, now, lastPassiveEntryAt)) {
                lastPassiveEntryAt = now
                scope.launch {
                    delay(PASSIVE_ENTRY_ACK_DELAY_MS)
                    when (val result = execute(VehicleCommand.UNLOCK)) {
                        CommandResult.Success -> Log.i(TAG, "Passive entry unlock confirmed by vehicle")
                        is CommandResult.Rejected -> Log.w(TAG, "Passive entry unlock rejected: ${result.error}")
                        is CommandResult.Failure -> Log.w(TAG, "Passive entry unlock failed: ${result.error}")
                    }
                }
            }
        }
        native.commandResponse(frame)?.let { ack ->
            _telemetry.value = _telemetry.value.copy(allClosuresClosed = ack.allClosuresClosed)
            pendingCommand?.takeIf { !it.isCompleted }?.complete(ack.success)
        }
    }

    private fun write(frame: ByteArray): Boolean {
        return nordic.writeFrame(frame)
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
        authenticationTimeoutJob?.cancel(); authenticationStage = AuthenticationStage.NONE
        rssiJob?.cancel(); watchdogJob?.cancel(); lastBleActivityAt = Long.MIN_VALUE
        nordic.disconnect().enqueue()
        gatt?.close(); gatt = null; sendCharacteristic = null; receiveCharacteristic = null
    }

    /**
     * The official BYD clients use Nordic's serialized request queue. Keeping this
     * transport intact matters on Samsung's Android 16 Bluetooth stack: direct GATT
     * calls were acknowledged, but the first BLE3 response was never routed back.
     */
    private inner class BydNordicManager(context: Context) : BleManager(context) {
        private var send: BluetoothGattCharacteristic? = null
        private var receive: BluetoothGattCharacteristic? = null

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = runCatching { gatt.getService(native.serviceUuid()) }.getOrNull()
            send = service?.getCharacteristic(runCatching { native.sendUuid() }.getOrNull())
            receive = service?.getCharacteristic(runCatching { native.receiveUuid() }.getOrNull())
            sendCharacteristic = send
            receiveCharacteristic = receive
            Log.d(TAG, "Nordic services: sendProperties=${send?.properties} receiveProperties=${receive?.properties}")
            return send != null && receive != null
        }

        override fun initialize() {
            val input = receive ?: return
            _state.value = BleConnectionState.DISCOVERING
            setNotificationCallback(input).with { _, data ->
                val value = data.value ?: return@with
                Log.d(TAG, "Nordic characteristic update received: bytes=${value.size}")
                handleIncoming(value)
            }
            enableNotifications(input)
                .done {
                    Log.d(TAG, "Nordic notification subscription ready")
                    scheduleAuthentication()
                }
                .fail { _, status ->
                    Log.w(TAG, "Nordic notification subscription failed: status=$status")
                    failAuthentication()
                }
                .enqueue()
        }

        override fun onServicesInvalidated() {
            send = null
            receive = null
            sendCharacteristic = null
            receiveCharacteristic = null
        }

        fun writeFrame(frame: ByteArray): Boolean {
            val output = send ?: return false
            writeCharacteristic(output, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                .done { Log.d(TAG, "Nordic characteristic write completed: bytes=${frame.size}") }
                .fail { _, status ->
                    Log.w(TAG, "Nordic characteristic write failed: status=$status")
                    if (_state.value == BleConnectionState.AUTHENTICATING) failAuthentication()
                    else if (_state.value == BleConnectionState.READY) recoverConnection("Nordic write failed ($status)")
                }
                .enqueue()
            return true
        }

        fun readVehicleRssi() {
            readRssi()
                .with { _, rssi -> updateRssi(rssi) }
                .fail { _, status -> Log.w(TAG, "RSSI read failed: status=$status") }
                .enqueue()
        }
    }

    private fun updateRssi(rssi: Int) {
        markBleActivity()
        smoothedRssi = smoothedRssi?.let { it * 0.75 + rssi * 0.25 } ?: rssi.toDouble()
        val smooth = smoothedRssi ?: rssi.toDouble()
        _telemetry.value = _telemetry.value.copy(
            rssi = rssi,
            nativeArea = native.rssiArea(rssi),
            approximateMeters = 10.0.pow((-59.0 - smooth) / 22.0).coerceIn(0.1, 50.0),
        )
    }

    private enum class AuthenticationStage { NONE, WAITING_FOR_RANDOM_EXCHANGE, WAITING_FOR_AUTH_RESULT }

    private companion object {
        const val TAG = "BydBleController"
        const val SCAN_TO_CONNECT_DELAY_MS = 500L
        // Measured on the current official BYD AUTO client on the target phone:
        // connection callback -> discovery ~= 303 ms, CCCD completion -> first
        // random frame ~= 308 ms. These windows are part of the BLE3 handshake.
        const val CONNECTION_TO_DISCOVERY_DELAY_MS = 300L
        const val CCCD_TO_AUTH_DELAY_MS = 300L
        const val SERVICES_TO_SUBSCRIBE_DELAY_MS = 200L
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
