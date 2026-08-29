package com.vitalyart.bydkeyless.network

import org.junit.Assert.*
import org.junit.Test

class WatchCryptographyTest {
    @Test fun qrPayloadMatchesOfficialWearImplementation() {
        assertEquals(
            "watchQRCode://E43244DDB9BBC2D04855A535CDE30BB5B91DD704766CD25A00AB128FC68CDD42DF8BE348A0FCA2DFA3B099BB296FDC6E0CA95D386BDED8B8D099267DA4948C2E95167243A65A36E4CF2E3C56F96DB807D71D5BD21C027BB0A18A9B59727F65E7E5C219D8A26515F6C526BBD1DFB643A8",
            WatchCryptography.qrPayload("0123456789ABCDEF0123456789ABCDEF", "123e4567-e89b-12d3-a456-426614174000", "UZ"),
        )
    }

    @Test fun createRequestCryptoMatchesPhpGoldenVector() {
        val inner = mapOf("timeStamp" to "1700000000000", "random" to "00112233445566778899AABBCCDDEEFF", "networkType" to "wifi", "version" to "341")
        val metadata = mapOf(
            "identifier" to "UZ", "watchImei" to "0123456789ABCDEF0123456789ABCDEF", "watchModel" to "SM-R890",
            "watchBrand" to "SAMSUNG", "reqTimestamp" to "1700000000000", "watchName" to "SAMSUNGSM-R890",
            "watchAppVersion" to "341", "watchOs" to "0", "language" to "ru", "countryCode" to "UZ",
        )
        assertEquals("9164FB68B2c5B0924Adf1D967D921E606A8dD4cd", WatchCryptography.sign(inner + metadata, "UZ"))
        assertEquals(
            "AC049E0402A129FC30284DCDB4F4D14E8CDD8186D9925E36A0A074B1491F118CE9E06B2DBBB3A64FFC1D5DCF9A49759C1620A84FBC13529D2B708C69943F22E964EE0526A1D8E3F9C444B67A8507F090E0BA0710F8593031DB5D590195B3B7605CAF75DA94A8E130D52465697838C2E3",
            WatchCryptography.encryptFields(inner, WatchCryptography.md5("UZ")),
        )
    }

    @Test fun decryptsTwoLayerResponse() {
        val key = WatchCryptography.md5("UZ")
        val inner = WatchCryptography.encrypt("{\"status\":2}", key)
        val outer = WatchCryptography.encrypt("\"$inner\"", key)
        assertEquals(2, WatchCryptography.decryptResponse(outer, key).getInt("status"))
    }
}
