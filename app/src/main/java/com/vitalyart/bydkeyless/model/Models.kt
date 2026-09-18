package com.vitalyart.bydkeyless.model

import kotlinx.coroutines.flow.StateFlow

enum class WatchQrStatus(val code: Int) {
    UNKNOWN(-1), WAITING_FOR_SCAN(0), WAITING_FOR_CONFIRMATION(1), APPROVED(2), INVALIDATED(3), EXPIRED(4);
    companion object { fun from(value: Int) = entries.firstOrNull { it.code == value } ?: UNKNOWN }
}

data class WatchQrSession(
    val watchImei: String,
    val uuid: String,
    val status: WatchQrStatus,
    val qrPayload: String,
    val createdAtMillis: Long,
) { val expiresAtMillis: Long get() = createdAtMillis + 150_000L }

data class WatchToken(
    val accountStatus: Int,
    val userId: String,
    val watchImei: String,
    val encryptionToken: String,
    val signToken: String,
    val timestamp: Long,
    val language: String,
    val vin: String,
    val userType: String,
    val controlPassword: String?,
)

data class VehicleProfile(
    val modelName: String = "BYD vehicle",
    val vin: String,
    val macAddress: String?,
    val digitalKey: String?,
    val keyNumber: Long,
    val keyValidTo: Long?,
    val capabilities: Set<String>,
    val vinRssi: String? = null,
) {
    fun hasValidKey(now: Long = System.currentTimeMillis()) =
        !digitalKey.isNullOrBlank() && (keyValidTo == null || keyValidTo > now)
}

enum class VehicleCommand(
    val functionCode: String?,
    val nativeId: Int,
    val transport: CommandTransport = CommandTransport.BLE,
    val dangerous: Boolean = false,
    val experimental: Boolean = false,
) {
    LOCK("1005", 9002),
    UNLOCK("1006", 9001),
    FIND_CAR("1007", 9005),
    OPEN_TRUNK("1020", 9010),
    CLOSE_TRUNK("1021", 9011, transport = CommandTransport.CLOUD),
    CLOSE_WINDOWS(null, 9005, experimental = true),
    FLASH_LIGHT(null, 9010, experimental = true),
    TURN_ON_AC(null, 10130001, experimental = true),
    TURN_OFF_AC(null, 10130003, experimental = true),
    START_ENGINE(null, 3003, dangerous = true, experimental = true),
    STOP_ENGINE("1031", 3003, dangerous = true, experimental = true),
    OPEN_ELECTRIC_REAR_DOOR(null, 0, dangerous = true, experimental = true),
    CLOSE_ELECTRIC_REAR_DOOR(null, 0, dangerous = true, experimental = true),
    PAUSE_ELECTRIC_REAR_DOOR(null, 0, experimental = true),
    UWB_LOCATE(null, 0, experimental = true),
    RPA_UNLOCK(null, 0, dangerous = true, experimental = true),
    RPA_FORWARD(null, 0, dangerous = true, experimental = true),
    RPA_BACKWARD(null, 0, dangerous = true, experimental = true),
    RPA_LEFT(null, 0, dangerous = true, experimental = true),
    RPA_RIGHT(null, 0, dangerous = true, experimental = true),
    YUNNIAN_SCENE(null, 0, dangerous = true, experimental = true),
}

enum class CommandTransport { BLE, CLOUD }

enum class CommandError {
    NO_PROFILE,
    KEY_NOT_READY,
    KEY_EXPIRED,
    EXPERIMENTAL_DISABLED,
    CAPABILITY_NOT_CONFIRMED,
    UNSUPPORTED_TRANSPORT,
    NATIVE_UNAVAILABLE,
    GATT_WRITE_FAILED,
    VEHICLE_TIMEOUT,
    CLOUD_REJECTED,
    NO_SESSION,
    BLUETOOTH_OFF,
    PERMISSION_DENIED,
    CONNECTION_TIMEOUT,
    COMMAND_BUSY,
}

enum class BleConnectionState { IDLE, SCANNING, CONNECTING, DISCOVERING, AUTHENTICATING, READY, DISCONNECTED, ERROR }
enum class DoorState { UNKNOWN, LOCKED, UNLOCKED }

data class VehicleTelemetry(
    val rssi: Int? = null,
    val nativeArea: Int? = null,
    val doorState: DoorState = DoorState.UNKNOWN,
    val allClosuresClosed: Boolean? = null,
    val rssiAtMillis: Long? = null,
    val rssiSequence: Long = 0,
    val closuresAtMillis: Long? = null,
    val doorStateAtMillis: Long? = null,
    val connectionGeneration: Long = 0,
)

data class BleDiagnostics(
    val error: CommandError? = null,
    val lastRecovery: String? = null,
    val recoveries: Int = 0,
    val lastPacketAtMillis: Long? = null,
)

sealed interface CommandResult {
    data object Success : CommandResult
    data class Rejected(val error: CommandError) : CommandResult
    data class Failure(val error: CommandError) : CommandResult
}

interface WatchAuthRepository {
    suspend fun synchronizeServerTime(): Long
    suspend fun createQrSession(): WatchQrSession
    suspend fun checkQrSession(session: WatchQrSession): WatchQrStatus
    suspend fun gainToken(session: WatchQrSession): WatchToken
    suspend fun loadVehicleProfile(token: WatchToken): VehicleProfile
    suspend fun logout(token: WatchToken)
}

interface BleVehicleController {
    val connectionState: StateFlow<BleConnectionState>
    val telemetry: StateFlow<VehicleTelemetry>
    suspend fun connect(profile: VehicleProfile)
    suspend fun disconnect()
    suspend fun execute(command: VehicleCommand, experimentalEnabled: Boolean = false): CommandResult
}

enum class KeylessMode { OFF, AUTO_UNLOCK_LOCK }
enum class ProximityZone { UNKNOWN, FAR, APPROACHING, NEAR }
data class ProximityCalibration(val unlockThreshold: Double, val lockThreshold: Double) {
    constructor(near: Int, far: Int) : this(near.toDouble(), far.toDouble())
    init {
        require(unlockThreshold.isFinite() && lockThreshold.isFinite())
        require(unlockThreshold > lockThreshold)
    }
    companion object {
        fun fromLegacy(near: Int, far: Int, unlockMeters: Double, lockMeters: Double): ProximityCalibration {
            require(near > far && unlockMeters in 0.5..3.0 && lockMeters in 2.0..10.0 && unlockMeters < lockMeters)
            fun threshold(meters: Double) = near + (far - near) * (kotlin.math.ln(meters) / kotlin.math.ln(5.0))
            return ProximityCalibration(threshold(unlockMeters), threshold(lockMeters))
        }
    }
}
data class ProximityState(val zone: ProximityZone = ProximityZone.UNKNOWN, val smoothedRssi: Double? = null, val armed: Boolean = true, val needsConfirmation: Boolean = false)

interface ProximityKeyManager {
    val state: StateFlow<ProximityState>
    fun configure(mode: KeylessMode, calibration: ProximityCalibration?, autoUnlock: Boolean, autoLock: Boolean)
    suspend fun onTelemetry(telemetry: VehicleTelemetry)
}
