package com.inditech.dpc.domain

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based offline unlock codes. Byte-for-byte identical to
 * backend/src/domain/offlineUnlock.ts (HMAC-SHA256 + RFC 4226 dynamic truncation).
 */
object OfflineUnlockCode {
    const val DIGITS = 8

    fun window(epochSeconds: Long, windowSeconds: Long): Long = Math.floorDiv(epochSeconds, windowSeconds)

    fun generate(secretHex: String, deviceId: String, window: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hexToBytes(secretHex), "HmacSHA256"))
        val digest = mac.doFinal("UNLOCK:$deviceId:$window".toByteArray(Charsets.UTF_8))
        val offset = digest[digest.size - 1].toInt() and 0x0f
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        var mod = 1
        repeat(DIGITS) { mod *= 10 }
        return (binary % mod).toString().padStart(DIGITS, '0')
    }

    /** Returns the matched window (current or previous) or null. */
    fun verify(secretHex: String, deviceId: String, code: String, epochSeconds: Long, windowSeconds: Long): Long? {
        val normalized = code.filter { it.isDigit() }
        if (normalized.length != DIGITS) return null
        val w = window(epochSeconds, windowSeconds)
        return listOf(w, w - 1).firstOrNull { generate(secretHex, deviceId, it) == normalized }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
