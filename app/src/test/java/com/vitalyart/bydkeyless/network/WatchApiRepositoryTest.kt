package com.vitalyart.bydkeyless.network

import com.vitalyart.bydkeyless.model.CommandResult
import com.vitalyart.bydkeyless.model.VehicleCommand
import com.vitalyart.bydkeyless.model.WatchToken
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WatchApiRepositoryTest {
    private lateinit var server: MockWebServer
    private val countryKey = WatchCryptography.md5("UZ")
    private val tokenKey = WatchCryptography.md5("encryption-token")

    @Before fun setUp() { server = MockWebServer() }
    @After fun tearDown() { server.shutdown() }

    @Test fun includesAllProductionWatchRegionsAndRoutes() {
        assertEquals(116, WatchRegions.all.size)
        assertEquals(116, WatchRegions.all.map { it.code }.toSet().size)
        assertTrue(WatchRegions.all.all { it.baseUrlIsProduction() })
        assertEquals("https://dilinkappoversea-eu.byd.auto", WatchRegions.baseUrlFor("DE"))
        assertEquals("https://dilinkappoversea-sg.byd.auto", WatchRegions.baseUrlFor("TH"))
        assertEquals("https://dilinkappoversea-no.byd.auto", WatchRegions.baseUrlFor("AE"))
        assertEquals("https://dilinkappoversea-vn.byd.auto", WatchRegions.baseUrlFor("VNM"))
        assertEquals("https://dilinkappoversea-kz.byd.auto", WatchRegions.baseUrlFor("KZ"))
    }

    private fun WatchRegion.baseUrlIsProduction(): Boolean = WatchRegions.baseUrlFor(code).startsWith("https://")

    @Test fun usesBydKeylessDeviceIdentityByDefault() {
        val config = WatchConfig(watchImei = "0123456789ABCDEF0123456789ABCDEF")
        assertEquals("BYD", config.brand)
        assertEquals("Keyless", config.model)
    }

    @Test fun completesQrTokenAndVehicleBluetoothFlow() = runTest {
        var poll = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/watch/login/getServerCurrentTime" -> success(JSONObject().put("serverTime", 1_700_000_000_000), countryKey)
                "/watch/login/create/qrcode" -> success(JSONObject()
                    .put("watchImei", "0123456789ABCDEF0123456789ABCDEF")
                    .put("uuid", "123e4567-e89b-12d3-a456-426614174000").put("status", 0), countryKey)
                "/watch/login/check/qrcode" -> success(JSONObject().put("status", listOf(0, 1, 2)[poll++]), countryKey)
                "/watch/login/gain/token" -> success(JSONObject()
                    .put("watchTokenInfo", JSONObject()
                        .put("accountStatus", 0).put("userId", "user-id")
                        .put("watchImei", "0123456789ABCDEF0123456789ABCDEF")
                        .put("encryToken", "encryption-token").put("signToken", "sign-token")
                        .put("timeStamp", 1_700_000_000_000).put("language", "ru")
                        .put("vin", "LGXCE6CB1N0000001").put("userType", "1"))
                    .put("controlPwd", "control-password"), countryKey)
                "/watch/login/gain/vehicle" -> success(JSONObject()
                    .put("modelNameOut", "BYD SEALION 7")
                    .put("cfVechicle", JSONObject()
                        .put("cfFixedList", JSONArray(listOf(
                            JSONObject().put("functionNo", "1005"),
                            JSONObject().put("functionNo", "1006"),
                            JSONObject().put("functionNo", "1020"),
                        )))
                        .put("watchBluetoothDto", JSONObject()
                        .put("macAddress", "01:23:45:67:89:AB")
                        .put("watchBluetoothInfo", JSONObject().put("keyNumber", 0).put("dkey", ""))
                        .put("functionCode", JSONArray(listOf("2001"))))), tokenKey)
                "/watch/login/gain/bluetooth" -> success(JSONObject()
                    .put("dkey", "test-digital-key").put("keyNumber", 42)
                    .put("vinRssi", "AABBCCDDEEFF0011223344")
                    .put("keyValidTo", 1_800_000_000_000), tokenKey)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val repository = BydWatchAuthRepository(
            WatchConfig(
                watchImei = "0123456789ABCDEF0123456789ABCDEF", brand = "SAMSUNG", model = "SM-R890",
                baseUrlOverride = server.url("/").toString(),
            ),
            now = { 1_700_000_000_000 }, nonce = { "00112233445566778899AABBCCDDEEFF" },
        )

        assertEquals(1_700_000_000_000, repository.synchronizeServerTime())
        val qr = repository.createQrSession()
        assertEquals("watchQRCode://", qr.qrPayload.substringBefore("//") + "//")
        assertEquals(listOf(0, 1, 2), List(3) { repository.checkQrSession(qr).code })
        val token = repository.gainToken(qr)
        val profile = repository.loadVehicleProfile(token)
        assertEquals("BYD SEALION 7", profile.modelName)
        assertEquals("test-digital-key", profile.digitalKey)
        assertEquals(42, profile.keyNumber)
        assertEquals("AABBCCDDEEFF0011223344", profile.vinRssi)
        assertTrue(profile.capabilities.containsAll(setOf("1005", "1006", "1020")))
        assertTrue(profile.capabilities.contains("2001"))
    }

    @Test fun reportsExpiredLocallyAndBackendErrorsSafely() = runTest {
        server.enqueue(MockResponse().setBody(JSONObject().put("code", "500").put("message", "backend error").toString()))
        server.start()
        val repository = BydWatchAuthRepository(
            WatchConfig(
                watchImei = "0123456789ABCDEF0123456789ABCDEF", brand = "TEST", model = "PHONE",
                baseUrlOverride = server.url("/").toString(),
            ), now = { 1_700_000_200_000 },
        )
        val expired = com.vitalyart.bydkeyless.model.WatchQrSession(
            "0123456789ABCDEF0123456789ABCDEF", "uuid", com.vitalyart.bydkeyless.model.WatchQrStatus.WAITING_FOR_SCAN,
            "watchQRCode://payload", 1_700_000_000_000,
        )
        assertEquals(4, repository.checkQrSession(expired).code)
        val failure = runCatching { repository.createQrSession() }.exceptionOrNull()
        assertTrue(failure?.message?.contains("backend error") == true)
    }

    @Test fun closesTrunkThroughWatchControlAndPollsUntilConfirmed() = runTest {
        var resultPoll = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/watch/control/vehicleControl" -> success(JSONObject().put("requestSerial", "command-reference"), tokenKey)
                "/watch/control/vehicleControlResult" -> success(
                    JSONObject().put("res", if (resultPoll++ == 0) 1 else 2),
                    tokenKey,
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val repository = BydWatchAuthRepository(
            WatchConfig(
                watchImei = "0123456789ABCDEF0123456789ABCDEF",
                brand = "SAMSUNG",
                model = "SM-R890",
                baseUrlOverride = server.url("/").toString(),
            ),
            now = { 1_700_000_000_000 },
            nonce = { "00112233445566778899AABBCCDDEEFF" },
        )
        val token = WatchToken(
            0, "user-id", "0123456789ABCDEF0123456789ABCDEF", "encryption-token", "sign-token",
            1_700_000_000_000, "ru", "LGXCE6CB1N0000001", "1", null,
        )

        assertTrue(repository.executeCloudCommand(token, VehicleCommand.CLOSE_TRUNK) is CommandResult.Success)
        assertEquals(2, resultPoll)
    }

    private fun success(data: JSONObject, key: String): MockResponse {
        val encrypted = WatchCryptography.encrypt(data.toString(), key)
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("code", "0").put("respondData", encrypted).toString())
    }
}
