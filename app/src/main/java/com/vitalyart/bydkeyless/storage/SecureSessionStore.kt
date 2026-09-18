package com.vitalyart.bydkeyless.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vitalyart.bydkeyless.model.*
import com.vitalyart.bydkeyless.network.generateWatchImei
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("keyless_state", Context.MODE_PRIVATE)
    private val alias = "byd_keyless_session_v1"

    fun watchImei(): String = prefs.getString("watch_imei", null) ?: generateWatchImei().also {
        prefs.edit().putString("watch_imei", it).apply()
    }

    var watchCountryCode: String
        get() = prefs.getString("watch_country_code", "UZ") ?: "UZ"
        set(value) { prefs.edit().putString("watch_country_code", value).apply() }

    var language: String
        get() = prefs.getString("language", "system") ?: "system"
        set(value) { prefs.edit().putString("language", value).apply() }

    var updatePrereleases: Boolean
        get() = prefs.getBoolean("update_prereleases", false)
        set(value) { prefs.edit().putBoolean("update_prereleases", value).apply() }
    var updateLastCheckAt: Long
        get() = prefs.getLong("update_last_check_at", 0L)
        set(value) { prefs.edit().putLong("update_last_check_at", value).apply() }
    var updateLastPromptAt: Long
        get() = prefs.getLong("update_last_prompt_at", 0L)
        set(value) { prefs.edit().putLong("update_last_prompt_at", value).apply() }
    var updateEtag: String?
        get() = prefs.getString("update_etag", null)
        set(value) { prefs.edit().putStringOrRemove("update_etag", value).apply() }
    var updateCachedRelease: String?
        get() = prefs.getString("update_cached_release", null)
        set(value) { prefs.edit().putStringOrRemove("update_cached_release", value).apply() }
    var updateDownloadId: Long
        get() = prefs.getLong("update_download_id", -1L)
        set(value) { prefs.edit().putLong("update_download_id", value).apply() }
    var updateDownloadRelease: String?
        get() = prefs.getString("update_download_release", null)
        set(value) { prefs.edit().putStringOrRemove("update_download_release", value).apply() }

    fun saveSession(token: WatchToken, profile: VehicleProfile) {
        bindVehicle(profile.vin)
        val json = JSONObject().apply {
            put("token", JSONObject().apply {
                put("accountStatus", token.accountStatus); put("userId", token.userId); put("watchImei", token.watchImei)
                put("encryptionToken", token.encryptionToken); put("signToken", token.signToken); put("timestamp", token.timestamp)
                put("language", token.language); put("vin", token.vin); put("userType", token.userType)
                put("controlPassword", token.controlPassword)
            })
            put("profile", JSONObject().apply {
                put("modelName", profile.modelName); put("vin", profile.vin); put("macAddress", profile.macAddress)
                put("digitalKey", profile.digitalKey); put("keyNumber", profile.keyNumber); put("keyValidTo", profile.keyValidTo)
                put("vinRssi", profile.vinRssi)
                put("capabilities", JSONArray(profile.capabilities.toList()))
            })
        }
        prefs.edit().putString("session", encrypt(json.toString())).apply()
    }

    fun loadSession(): Pair<WatchToken, VehicleProfile>? = runCatching {
        val root = JSONObject(decrypt(prefs.getString("session", null) ?: return null))
        val t = root.getJSONObject("token")
        val p = root.getJSONObject("profile")
        val caps = p.optJSONArray("capabilities")
        val token = WatchToken(
            t.optInt("accountStatus"), t.getString("userId"), t.getString("watchImei"), t.getString("encryptionToken"),
            t.getString("signToken"), t.optLong("timestamp"), t.optString("language"), t.getString("vin"),
            t.getString("userType"), t.optString("controlPassword").takeIf { it.isNotBlank() },
        )
        val profile = VehicleProfile(
            p.optString("modelName", "BYD vehicle"), p.getString("vin"), p.optString("macAddress").takeIf { it.isNotBlank() },
            p.optString("digitalKey").takeIf { it.isNotBlank() }, p.optLong("keyNumber"),
            p.optLong("keyValidTo").takeIf { it > 0 }, buildSet { if (caps != null) repeat(caps.length()) { add(caps.getString(it)) } },
            p.optString("vinRssi").takeIf { it.isNotBlank() },
        )
        bindVehicle(profile.vin)
        token to profile
    }.getOrNull()

    fun clearSession() {
        prefs.edit()
            .remove("session")
            .remove("active_vehicle_vin")
            .putString("keyless_mode", KeylessMode.OFF.name)
            .putBoolean("auto_unlock", false)
            .putBoolean("auto_lock", false)
            .putBoolean("hotspot_on_connect", false)
            .putBoolean("hotspot_off_disconnect", false)
            .remove("hotspot_off_delay_ms")
            .putBoolean("experimental", false)
            .putBoolean("verified_unlock", false)
            .putBoolean("verified_lock", false)
            .remove("near_rssi")
            .remove("far_rssi")
            .remove("unlock_distance_meters")
            .remove("lock_distance_meters")
            .apply()
    }

    var keylessMode: KeylessMode
        get() = runCatching { KeylessMode.valueOf(prefs.getString("keyless_mode", KeylessMode.OFF.name)!!) }.getOrDefault(KeylessMode.OFF)
        set(value) { prefs.edit().putString("keyless_mode", value.name).apply() }
    var autoUnlock: Boolean
        get() = prefs.getBoolean("auto_unlock", false)
        set(value) { prefs.edit().putBoolean("auto_unlock", value).apply() }
    var autoLock: Boolean
        get() = prefs.getBoolean("auto_lock", false)
        set(value) { prefs.edit().putBoolean("auto_lock", value).apply() }
    var hotspotOnConnect: Boolean
        get() = prefs.getBoolean("hotspot_on_connect", false)
        set(value) { prefs.edit().putBoolean("hotspot_on_connect", value).apply() }
    var hotspotOffOnDisconnect: Boolean
        get() = prefs.getBoolean("hotspot_off_disconnect", false)
        set(value) { prefs.edit().putBoolean("hotspot_off_disconnect", value).apply() }
    var hotspotOffDelayMillis: Long
        get() = prefs.getLong("hotspot_off_delay_ms", DEFAULT_HOTSPOT_OFF_DELAY_MS)
        set(value) { prefs.edit().putLong("hotspot_off_delay_ms", value.coerceAtLeast(0L)).apply() }
    var experimentalEnabled: Boolean
        get() = prefs.getBoolean("experimental", false)
        set(value) { prefs.edit().putBoolean("experimental", value).apply() }
    var manualUnlockVerified: Boolean
        get() = prefs.getBoolean("verified_unlock", false)
        set(value) { prefs.edit().putBoolean("verified_unlock", value).apply() }
    var manualLockVerified: Boolean
        get() = prefs.getBoolean("verified_lock", false)
        set(value) { prefs.edit().putBoolean("verified_lock", value).apply() }

    fun calibration(): ProximityCalibration? {
        val near = prefs.getInt("near_rssi", Int.MIN_VALUE)
        val far = prefs.getInt("far_rssi", Int.MIN_VALUE)
        return if (near > far && far != Int.MIN_VALUE) ProximityCalibration(
            near, far, unlockDistanceMeters, lockDistanceMeters,
        ) else null
    }
    fun saveCalibration(value: ProximityCalibration): Boolean =
        prefs.edit().putInt("near_rssi", value.nearRssi).putInt("far_rssi", value.farRssi)
            .putFloat("unlock_distance_meters", value.unlockDistanceMeters.toFloat())
            .putFloat("lock_distance_meters", value.lockDistanceMeters.toFloat()).commit()
    var unlockDistanceMeters: Double
        get() = prefs.getFloat("unlock_distance_meters", ProximityCalibration.DEFAULT_UNLOCK_DISTANCE_METERS.toFloat()).toDouble()
        set(value) { prefs.edit().putFloat("unlock_distance_meters", value.toFloat()).apply() }
    var lockDistanceMeters: Double
        get() = prefs.getFloat("lock_distance_meters", ProximityCalibration.DEFAULT_LOCK_DISTANCE_METERS.toFloat()).toDouble()
        set(value) { prefs.edit().putFloat("lock_distance_meters", value.toFloat()).apply() }

    private fun bindVehicle(vin: String) {
        val active = prefs.getString("active_vehicle_vin", null)
        if (VehicleScopePolicy.shouldReset(active, vin)) {
            prefs.edit()
                .putString("keyless_mode", KeylessMode.OFF.name)
                .putBoolean("auto_unlock", false)
                .putBoolean("auto_lock", false)
                .putBoolean("hotspot_on_connect", false)
                .putBoolean("hotspot_off_disconnect", false)
                .remove("hotspot_off_delay_ms")
                .putBoolean("experimental", false)
                .putBoolean("verified_unlock", false)
                .putBoolean("verified_lock", false)
                .remove("near_rssi")
                .remove("far_rssi")
                .remove("unlock_distance_meters")
                .remove("lock_distance_meters")
                .apply()
        }
        if (active != vin) prefs.edit().putString("active_vehicle_vin", vin).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
        return cipher.doFinal(payload.copyOfRange(12, payload.size)).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object {
        const val DEFAULT_HOTSPOT_OFF_DELAY_MS = 60_000L
    }
}

private fun android.content.SharedPreferences.Editor.putStringOrRemove(key: String, value: String?) =
    if (value == null) remove(key) else putString(key, value)

object VehicleScopePolicy {
    fun shouldReset(activeVin: String?, newVin: String): Boolean = activeVin != null && activeVin != newVin
}
