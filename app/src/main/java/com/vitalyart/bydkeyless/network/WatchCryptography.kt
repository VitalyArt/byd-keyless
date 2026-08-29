package com.vitalyart.bydkeyless.network

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object WatchCryptography {
    private val zeroIv = ByteArray(16)

    fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).toHex(true, true)

    fun qrPayload(watchImei: String, uuid: String, countryCode: String): String {
        val plain = "watchImei=$watchImei&uuid=$uuid&countryCode=$countryCode"
        return "watchQRCode://" + encrypt(plain, md5("watch.bydautolink"))
    }

    fun compactSortedJson(values: Map<String, String>): String {
        // JSONObject does not promise iteration order. The watch protocol encrypts the
        // exact byte sequence emitted by PHP after ksort(), so build it deterministically.
        return TreeMap(values).entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            "${JSONObject.quote(key)}:${JSONObject.quote(value)}"
        }
    }

    fun encryptFields(values: Map<String, String>, keyHex: String): String =
        encrypt(compactSortedJson(values), keyHex)

    fun encrypt(plaintext: String, keyHex: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyHex.hexToBytes(), "AES"), IvParameterSpec(zeroIv))
        return cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8)).toHex(true, true)
    }

    fun decrypt(ciphertextHex: String, keyHex: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyHex.hexToBytes(), "AES"), IvParameterSpec(zeroIv))
        return cipher.doFinal(ciphertextHex.hexToBytes()).toString(StandardCharsets.UTF_8)
    }

    fun decryptResponse(value: String?, keyHex: String): JSONObject {
        if (value.isNullOrBlank()) return JSONObject()
        var current = value.trim()
        repeat(2) {
            parseObject(current)?.let { return it }
            val unquoted = runCatching { JSONObject("{\"v\":$current}").getString("v") }.getOrNull()
            if (unquoted != null) current = unquoted
            require(current.length % 2 == 0 && current.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "Watch response is neither JSON nor hexadecimal ciphertext"
            }
            current = decrypt(current, keyHex)
        }
        return parseObject(current) ?: error("Decrypted watch response is not a JSON object")
    }

    fun sign(values: Map<String, String?>, password: String): String {
        val text = TreeMap(values).entries.joinToString("&") { "${it.key}=${it.value.orEmpty()}" } +
            "&password=${md5(password)}"
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.mapIndexed { index, byte ->
            val raw = (byte.toInt() and 0xff).toString(16)
            if (index % 2 == 0) raw.uppercase(Locale.US) else raw.lowercase(Locale.US)
        }.joinToString("")
    }

    private fun parseObject(value: String) = runCatching { JSONObject(value) }.getOrNull()
}

internal fun ByteArray.toHex(uppercase: Boolean, padded: Boolean): String = joinToString("") {
    val raw = (it.toInt() and 0xff).toString(16)
    val value = if (padded) raw.padStart(2, '0') else raw
    if (uppercase) value.uppercase(Locale.US) else value
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}
