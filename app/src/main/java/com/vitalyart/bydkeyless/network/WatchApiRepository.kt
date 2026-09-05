package com.vitalyart.bydkeyless.network

import android.util.Log
import com.vitalyart.bydkeyless.model.*
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.math.abs

data class WatchRegion(val code: String, val name: String, val node: String, val timeZone: String)

private const val WATCH_BRAND = "BYD"
private const val WATCH_MODEL = "Keyless"
private const val WATCH_NAME = "BYD Keyless"
private const val WATCH_LOG_TAG = "BydWatchApi"
private const val NETWORK_ATTEMPTS = 3

enum class WatchNetworkFailure { DNS, TIMEOUT, TLS, CONNECTION }

class WatchNetworkException(
    val failure: WatchNetworkFailure,
    val endpoint: String,
    cause: Throwable,
) : IOException("$failure while requesting $endpoint", cause)

object WatchRegions {
    private val nodeBaseUrls = mapOf(
        "1" to "https://dilinkappoversea-eu.byd.auto",
        "2" to "https://dilinkappoversea-sg.byd.auto",
        "3" to "https://dilinkappoversea-au.byd.auto",
        "4" to "https://dilinkappoversea-br.byd.auto",
        "5" to "https://dilinkappoversea-jp.byd.auto",
        "6" to "https://dilinkappoversea-uz.byd.auto",
        "7" to "https://dilinkappoversea-no.byd.auto",
        "8" to "https://dilinkappoversea-mx.byd.auto",
        "9" to "https://dilinkappoversea-id.byd.auto",
        "10" to "https://dilinkappoversea-tr.byd.auto",
        "11" to "https://dilinkappoversea-kr-ali.byd.auto",
        "12" to "https://dilinkappoversea-in.byd.auto",
        "13" to "https://dilinkappoversea-vn.byd.auto",
        "14" to "https://dilinkappoversea-sa.byd.auto",
        "15" to "https://dilinkappoversea-om.byd.auto",
        "16" to "https://dilinkappoversea-kz.byd.auto",
    )

    val all = listOf(
        WatchRegion("NO", "Norway", "1", "Europe/Oslo"), WatchRegion("NL", "Netherlands", "1", "Europe/Amsterdam"),
        WatchRegion("DE", "Germany", "1", "Europe/Berlin"), WatchRegion("DK", "Denmark", "1", "Europe/Copenhagen"),
        WatchRegion("SE", "Sweden", "1", "Europe/Stockholm"), WatchRegion("FR", "France", "1", "Europe/Paris"),
        WatchRegion("AT", "Austria", "1", "Europe/Vienna"), WatchRegion("LU", "Luxembourg", "1", "Europe/Luxembourg"),
        WatchRegion("BE", "Belgium", "1", "Europe/Brussels"), WatchRegion("FI", "Finland", "1", "Europe/Helsinki"),
        WatchRegion("IT", "Italy", "1", "Europe/Rome"), WatchRegion("ES", "Spain", "1", "Europe/Madrid"),
        WatchRegion("PT", "Portugal", "1", "Europe/Lisbon"), WatchRegion("GB", "UK", "1", "Europe/London"),
        WatchRegion("IE", "Ireland", "1", "Europe/Dublin"), WatchRegion("IS", "Iceland", "1", "Atlantic/Reykjavik"),
        WatchRegion("IL", "Israel", "1", "Asia/Jerusalem"), WatchRegion("HU", "Hungary", "1", "Europe/Budapest"),
        WatchRegion("MT", "Malta", "1", "Europe/Malta"), WatchRegion("GR", "Greece", "1", "Europe/Athens"),
        WatchRegion("CH", "Swiss Confederation", "1", "Europe/Zurich"), WatchRegion("PL", "Poland", "1", "Europe/Warsaw"),
        WatchRegion("CY", "The Republic of Cyprus", "1", "Asia/Nicosia"), WatchRegion("EE", "Estonia", "1", "Europe/Tallinn"),
        WatchRegion("LV", "Latvia", "1", "Europe/Riga"), WatchRegion("LT", "Lithuania", "1", "Europe/Vilnius"),
        WatchRegion("CZ", "Czech", "1", "Europe/Prague"), WatchRegion("RO", "Romania", "1", "Europe/Bucharest"),
        WatchRegion("SK", "Slovakia", "1", "Europe/Bratislava"), WatchRegion("SI", "Slovenia", "1", "Europe/Ljubljana"),
        WatchRegion("BG", "Bulgaria", "1", "Europe/Sofia"), WatchRegion("HR", "Croatia", "1", "Europe/Zagreb"),
        WatchRegion("LI", "Liechtenstein", "1", "Europe/Vaduz"), WatchRegion("ME", "Montenegro", "1", "Europe/Podgorica"),
        WatchRegion("RS", "Serbia", "1", "Europe/Belgrade"), WatchRegion("BA", "Bosnia and Herzegovina", "1", "Europe/Sarajevo"),
        WatchRegion("MK", "North Macedonia", "1", "Europe/Skopje"), WatchRegion("AL", "Albania", "1", "Europe/Tirane"),
        WatchRegion("MD", "Moldova", "1", "Europe/Chisinau"), WatchRegion("MC", "Monaco", "1", "Europe/Monaco"),
        WatchRegion("VA", "Vatican City", "1", "Europe/Vatican"), WatchRegion("XK", "Kosovo", "1", "Europe/Pristina"),
        WatchRegion("UA", "Ukraine", "1", "Europe/Kyiv"),
        WatchRegion("SG", "Singapore", "2", "Asia/Singapore"), WatchRegion("TH", "Thailand", "2", "Asia/Bangkok"),
        WatchRegion("MY", "Malaysia", "2", "Asia/Kuala_Lumpur"), WatchRegion("HK", "HongKong,China", "2", "Asia/Hong_Kong"),
        WatchRegion("MO", "Macao,China", "2", "Asia/Macau"), WatchRegion("KH", "Cambodia", "2", "Asia/Phnom_Penh"),
        WatchRegion("LA", "Laos", "2", "Asia/Vientiane"), WatchRegion("PH", "Philippines", "2", "Asia/Manila"),
        WatchRegion("BRN", "Brunei", "2", "Asia/Brunei"), WatchRegion("MM", "Myanmar", "2", "Asia/Yangon"),
        WatchRegion("NP", "Nepal", "2", "Asia/Kathmandu"), WatchRegion("BD", "Bangladesh", "2", "Asia/Dhaka"),
        WatchRegion("PK", "Pakistan", "2", "Asia/Karachi"), WatchRegion("LK", "Sri Lanka", "2", "Asia/Colombo"),
        WatchRegion("PF", "French Polynesia", "2", "Pacific/Tahiti"), WatchRegion("NC", "New Caledonia", "2", "Pacific/Noumea"),
        WatchRegion("MN", "Mongolia", "2", "Asia/Ulaanbaatar"), WatchRegion("BT", "Bhutan", "2", "Asia/Thimphu"),
        WatchRegion("MV", "Maldives", "2", "Indian/Maldives"),
        WatchRegion("AU", "Australia", "3", "Australia/Sydney"), WatchRegion("NZ", "New Zealand", "3", "Pacific/Auckland"),
        WatchRegion("BR", "Brazil", "4", "America/Sao_Paulo"), WatchRegion("JP", "Japan", "5", "Asia/Tokyo"),
        WatchRegion("UZ", "Uzbekistan", "6", "Asia/Tashkent"),
        WatchRegion("PS", "Palestine", "7", "Asia/Gaza"), WatchRegion("AE", "United Arab Emirates", "7", "Asia/Dubai"),
        WatchRegion("IQ", "Iraq", "7", "Asia/Baghdad"), WatchRegion("KW", "Kuwait", "7", "Asia/Kuwait"),
        WatchRegion("QA", "Qatar", "7", "Asia/Qatar"), WatchRegion("MA", "Morocco", "7", "Africa/Casablanca"),
        WatchRegion("BH", "Bahrain", "7", "Asia/Bahrain"), WatchRegion("JO", "Jordan", "7", "Asia/Amman"),
        WatchRegion("ZA", "South Africa", "7", "Africa/Johannesburg"), WatchRegion("RE", "Reunion Island", "7", "Indian/Reunion"),
        WatchRegion("MU", "Mauritius", "7", "Indian/Mauritius"), WatchRegion("EG", "Egypt", "7", "Africa/Cairo"),
        WatchRegion("MX", "Mexico", "8", "America/Mexico_City"), WatchRegion("CL", "Chile", "8", "America/Santiago"),
        WatchRegion("UY", "Uruguay", "8", "America/Montevideo"), WatchRegion("CO", "Colombia", "8", "America/Bogota"),
        WatchRegion("DO", "Dominican Republic", "8", "America/Santo_Domingo"), WatchRegion("CR", "Costa Rica", "8", "America/Costa_Rica"),
        WatchRegion("PE", "Peru", "8", "America/Lima"), WatchRegion("EC", "Ecuador", "8", "America/Guayaquil"),
        WatchRegion("PY", "Paraguay", "8", "America/Asuncion"), WatchRegion("BO", "Bolivia", "8", "America/La_Paz"),
        WatchRegion("PA", "Panama", "8", "America/Panama"), WatchRegion("GT", "Guatemala", "8", "America/Guatemala"),
        WatchRegion("SV", "El Salvador", "8", "America/El_Salvador"), WatchRegion("HN", "Honduras", "8", "America/Tegucigalpa"),
        WatchRegion("NI", "Nicaragua", "8", "America/Managua"), WatchRegion("AR", "Argentina", "8", "America/Argentina/Buenos_Aires"),
        WatchRegion("BZ", "Belize", "8", "America/Belize"), WatchRegion("BS", "Bahamas", "8", "America/Nassau"),
        WatchRegion("AW", "Aruba", "8", "America/Aruba"), WatchRegion("CW", "Curaçao", "8", "America/Curacao"),
        WatchRegion("BQ", "Bonaire", "8", "America/Kralendijk"), WatchRegion("TT", "Trinidad and Tobago", "8", "America/Port_of_Spain"),
        WatchRegion("JM", "Jamaica", "8", "America/Jamaica"), WatchRegion("SR", "Suriname", "8", "America/Paramaribo"),
        WatchRegion("KY", "Cayman Islands", "8", "America/Cayman"), WatchRegion("AG", "Antigua and Barbuda", "8", "America/Antigua"),
        WatchRegion("GY", "Guyana", "8", "America/Guyana"), WatchRegion("LC", "Saint Lucia", "8", "America/St_Lucia"),
        WatchRegion("BB", "Barbados", "8", "America/Barbados"),
        WatchRegion("ID", "Indonesia", "9", "Asia/Jakarta"), WatchRegion("TR", "Türkiye", "10", "Europe/Istanbul"),
        WatchRegion("KR", "Republic of Korea", "11", "Asia/Seoul"), WatchRegion("IN", "India", "12", "Asia/Kolkata"),
        WatchRegion("VNM", "Vietnam", "13", "Asia/Ho_Chi_Minh"), WatchRegion("SA", "Saudi Arabia", "14", "Asia/Riyadh"),
        WatchRegion("OM", "Oman", "15", "Asia/Muscat"), WatchRegion("KZ", "Kazakhstan", "16", "Asia/Almaty"),
    )
    fun byCode(code: String) = all.firstOrNull { it.code == code } ?: all.first()
    fun baseUrlFor(code: String): String = nodeBaseUrls.getValue(byCode(code).node)
}

data class WatchConfig(
    val countryCode: String = "UZ",
    val language: String = "ru",
    val timeZone: String = WatchRegions.byCode(countryCode).timeZone,
    val watchImei: String,
    val brand: String = WATCH_BRAND,
    val model: String = WATCH_MODEL,
    val appVersion: String = "341",
    val baseUrlOverride: String? = null,
) {
    val baseUrl: String get() = baseUrlOverride?.trimEnd('/') ?: WatchRegions.baseUrlFor(countryCode)
}

class BydWatchAuthRepository(
    private val config: WatchConfig,
    private val client: OkHttpClient = defaultWatchHttpClient(config),
    private val now: () -> Long = System::currentTimeMillis,
    private val nonce: () -> String = { UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US) },
) : WatchAuthRepository {
    private var serverOffset = 0L

    override suspend fun synchronizeServerTime(): Long {
        val response = accountRequest("/watch/login/getServerCurrentTime", Operation.TIME)
        val serverTime = response.optLong("serverTime", now())
        val offset = serverTime - now()
        serverOffset = if (abs(offset) > 10_000L) offset else 0L
        return serverTime
    }

    override suspend fun createQrSession(): WatchQrSession {
        val created = timestamp()
        val data = accountRequest("/watch/login/create/qrcode", Operation.CREATE, requestTime = created)
        val imei = data.getString("watchImei")
        val uuid = data.getString("uuid")
        return WatchQrSession(imei, uuid, WatchQrStatus.from(data.optInt("status", -1)), WatchCryptography.qrPayload(imei, uuid, config.countryCode), created)
    }

    override suspend fun checkQrSession(session: WatchQrSession): WatchQrStatus {
        if (now() >= session.expiresAtMillis) return WatchQrStatus.EXPIRED
        val data = accountRequest("/watch/login/check/qrcode", Operation.CHECK, session.uuid)
        return WatchQrStatus.from(data.optInt("status", -1))
    }

    override suspend fun gainToken(session: WatchQrSession): WatchToken {
        val data = accountRequest("/watch/login/gain/token", Operation.TOKEN, session.uuid)
        val token = data.getJSONObject("watchTokenInfo")
        return WatchToken(
            token.optInt("accountStatus"), token.getString("userId"), token.getString("watchImei"),
            token.getString("encryToken"), token.getString("signToken"), token.optLong("timeStamp"),
            token.optString("language", config.language), token.getString("vin"), token.getString("userType"),
            data.optString("controlPwd").takeIf { it.isNotBlank() },
        )
    }

    override suspend fun loadVehicleProfile(token: WatchToken): VehicleProfile {
        val vehicle = commonRequest("/watch/login/gain/vehicle", token)
        val bluetooth = commonRequest("/watch/login/gain/bluetooth", token)
        val cf = vehicle.optJSONObject("cfVechicle") ?: JSONObject()
        val dto = cf.optJSONObject("watchBluetoothDto") ?: JSONObject()
        val vehicleInfo = dto.optJSONObject("watchBluetoothInfo") ?: JSONObject()
        val capabilities = buildSet {
            // The Wear client uses two distinct server lists. Vehicle controls such
            // as 1005/1006 live in cfFixedList, while functionCode describes BLE-key
            // features (for example whether key registration is available).
            dto.optJSONArray("functionCode")?.let { functions ->
                repeat(functions.length()) { index ->
                    functions.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            cf.optJSONArray("cfFixedList")?.let { functions ->
                repeat(functions.length()) { index ->
                    functions.optJSONObject(index)?.optString("functionNo")
                        ?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
        return VehicleProfile(
            modelName = vehicle.optString("modelNameOut", "BYD vehicle"), vin = token.vin,
            macAddress = dto.optString("macAddress").takeIf { it.isNotBlank() },
            // gain/bluetooth is the authoritative active key. The vehicle payload may
            // contain a stale/empty watchBluetoothInfo object, so merge per field.
            digitalKey = sequenceOf(bluetooth, vehicleInfo).map { it.optString("dkey") }.firstOrNull { it.isNotBlank() },
            keyNumber = sequenceOf(bluetooth, vehicleInfo).map { it.optLong("keyNumber") }.firstOrNull { it > 0 } ?: 0,
            keyValidTo = sequenceOf(bluetooth, vehicleInfo).map { it.optLong("keyValidTo") }.firstOrNull { it > 0 },
            capabilities = capabilities,
            vinRssi = sequenceOf(bluetooth, vehicleInfo, dto, cf).map { it.optString("vinRssi") }.firstOrNull { it.isNotBlank() },
        )
    }

    override suspend fun logout(token: WatchToken) { commonRequest("/watch/watch/logoutWatch", token, logout = true) }

    /** Executes commands which the official Wear client routes through Watch API. */
    suspend fun executeCloudCommand(token: WatchToken, command: VehicleCommand): CommandResult {
        val commandType = when (command) {
            VehicleCommand.CLOSE_TRUNK -> "CLOSETRUNK"
            else -> return CommandResult.Rejected(CommandError.UNSUPPORTED_TRANSPORT)
        }
        return runCatching {
            val request = commonRequest(
                "/watch/control/vehicleControl",
                token,
                extra = mapOf("commandType" to commandType),
            )
            val serial = request.optString("requestSerial")
            check(serial.isNotBlank()) { "BYD did not return a command reference" }

            val delays = longArrayOf(1_000, 500, 500, 2_000, 2_000, 2_000, 5_000, 5_000, 5_000, 10_000)
            for (pollDelay in delays) {
                kotlinx.coroutines.delay(pollDelay)
                val result = commonRequest(
                    "/watch/control/vehicleControlResult",
                    token,
                    extra = mapOf("requestSerial" to serial, "commandType" to commandType),
                )
                when (result.optInt("res", -1)) {
                    2 -> return CommandResult.Success
                    1 -> Unit
                    else -> return CommandResult.Failure(CommandError.CLOUD_REJECTED)
                }
            }
            CommandResult.Failure(CommandError.VEHICLE_TIMEOUT)
        }.getOrElse { if (it is CancellationException) throw it; CommandResult.Failure(CommandError.CLOUD_REJECTED) }
    }

    private enum class Operation { TIME, CREATE, CHECK, TOKEN }

    private suspend fun accountRequest(path: String, operation: Operation, uuid: String? = null, requestTime: Long = timestamp()): JSONObject {
        val time = requestTime.toString()
        val inner = when (operation) {
            Operation.CREATE -> mapOf("timeStamp" to time, "random" to nonce(), "networkType" to "wifi", "version" to config.appVersion)
            Operation.CHECK -> mapOf("timeStamp" to time, "random" to nonce(), "networkType" to "wifi", "version" to config.appVersion, "uuid" to requireNotNull(uuid))
            Operation.TOKEN -> mapOf("timeStamp" to time, "uuid" to requireNotNull(uuid), "timeZone" to config.timeZone)
            Operation.TIME -> mapOf("timeStamp" to time, "random" to nonce(), "networkType" to "wifi", "deviceType" to "0", "version" to config.appVersion)
        }
        val metadata = metadata(time)
        val body = JSONObject(metadata).apply {
            put("sign", WatchCryptography.sign(inner + metadata, config.countryCode))
            put("encryData", WatchCryptography.encryptFields(inner, WatchCryptography.md5(config.countryCode)))
        }
        return send(path, body, WatchCryptography.md5(config.countryCode))
    }

    private suspend fun commonRequest(
        path: String,
        token: WatchToken,
        logout: Boolean = false,
        extra: Map<String, String> = emptyMap(),
    ): JSONObject {
        val time = timestamp().toString()
        val inner = (if (logout) mapOf(
            "timeStamp" to time, "random" to nonce(), "watchImei" to config.watchImei,
            "deviceType" to "0", "networkType" to "wifi", "version" to config.appVersion,
        ) else mapOf("timeStamp" to time, "random" to nonce(), "vin" to token.vin, "deviceType" to "0", "appVersion" to "2")) + extra
        val metadata = metadata(time).toMutableMap().apply { put("identifier", token.userId); put("userType", token.userType) }
        val key = WatchCryptography.md5(token.encryptionToken)
        val body = JSONObject(metadata as Map<*, *>).apply {
            put("encryData", WatchCryptography.encryptFields(inner, key))
            put("sign", WatchCryptography.sign(inner + metadata, token.signToken))
        }
        return send(path, body, key)
    }

    private fun metadata(time: String) = mapOf(
        "identifier" to config.countryCode, "watchImei" to config.watchImei, "watchModel" to config.model,
        "watchBrand" to config.brand, "reqTimestamp" to time, "watchName" to WATCH_NAME,
        "watchAppVersion" to config.appVersion, "watchOs" to "0", "language" to config.language,
        "countryCode" to config.countryCode,
    )

    private suspend fun send(path: String, body: JSONObject, key: String): JSONObject {
        var lastFailure: WatchNetworkException? = null
        val attempts = if (path == "/watch/control/vehicleControl") 1 else NETWORK_ATTEMPTS
        repeat(attempts) { attempt ->
            try {
                return sendOnce(path, body, key)
            } catch (failure: WatchNetworkException) {
                lastFailure = failure
                if (attempt + 1 < attempts) {
                    safeLog { Log.w(WATCH_LOG_TAG, "${failure.failure} for $path; retry ${attempt + 2}/$NETWORK_ATTEMPTS") }
                    delay(750L * (attempt + 1))
                }
            }
        }
        throw requireNotNull(lastFailure)
    }

    private suspend fun sendOnce(path: String, body: JSONObject, key: String): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(config.baseUrl + path)
            .header("Accept-Encoding", "identity").header("User-Agent", "okhttp/4.12.0")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        try {
            val transport = if (path == "/watch/control/vehicleControl") client.newBuilder().retryOnConnectionFailure(false).build() else client
            transport.newCall(request).awaitResponse().use { response ->
                safeLog { Log.d(WATCH_LOG_TAG, "${request.method} ${request.url.host}$path -> HTTP ${response.code}") }
                check(response.isSuccessful) { "BYD returned HTTP ${response.code}" }
                val raw = response.body?.string().orEmpty()
                var envelope = JSONObject(raw)
                if (envelope.has("response") && envelope.opt("response") is String) envelope = JSONObject(envelope.getString("response"))
                val code = envelope.optString("code", "0")
                check(code == "0") { envelope.optString("message", "BYD watch API error $code") }
                WatchCryptography.decryptResponse(envelope.optString("respondData"), key)
            }
        } catch (failure: UnknownHostException) {
            safeLog { Log.e(WATCH_LOG_TAG, "DNS failure for ${request.url.host}$path", failure) }
            throw WatchNetworkException(WatchNetworkFailure.DNS, request.url.host, failure)
        } catch (failure: SocketTimeoutException) {
            safeLog { Log.e(WATCH_LOG_TAG, "Timeout for ${request.url.host}$path", failure) }
            throw WatchNetworkException(WatchNetworkFailure.TIMEOUT, request.url.host, failure)
        } catch (failure: SSLException) {
            safeLog { Log.e(WATCH_LOG_TAG, "TLS failure for ${request.url.host}$path", failure) }
            throw WatchNetworkException(WatchNetworkFailure.TLS, request.url.host, failure)
        } catch (failure: IOException) {
            safeLog { Log.e(WATCH_LOG_TAG, "Connection failure for ${request.url.host}$path", failure) }
            throw WatchNetworkException(WatchNetworkFailure.CONNECTION, request.url.host, failure)
        }
    }

    private fun timestamp() = now() + serverOffset
}

fun generateWatchImei(): String = WatchCryptography.md5(UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US))

/** Preserve a successful system-DNS answer across a transient resolver failure. */
class ResilientWatchDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            delegate.lookup(hostname).also { addresses -> if (addresses.isNotEmpty()) cache[hostname] = addresses }
        } catch (failure: UnknownHostException) {
            cache[hostname] ?: throw failure
        }
    }
}

private inline fun safeLog(write: () -> Int) {
    runCatching { write() }
}

private fun defaultWatchHttpClient(config: WatchConfig) = OkHttpClient.Builder()
    .dns(if (config.baseUrlOverride == null) ResilientWatchDns() else Dns.SYSTEM)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private suspend fun okhttp3.Call.awaitResponse(): okhttp3.Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
            continuation.resume(response) { response.close() }
        }
    })
}
