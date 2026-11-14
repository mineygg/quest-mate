package com.mineygg.questmate.core

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Base64

/**
 * The gateway's `installation_id`: "<snowflake>.<27-char url-safe token>".
 * Derived from this device (ANDROID_ID) and the account token, so it is stable between runs
 * and nothing has to be stored.
 */
object InstallationId {
    private const val DISCORD_EPOCH = 1420070400000L
    private const val BASE_TIME = 1680000000000L // Apr 2023
    private const val SPAN_MS = 365L * 24 * 3600 * 1000 * 2 // about two years

    fun get(ctx: Context, token: String): String {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "fallback_android_id"
        val hash = MessageDigest.getInstance("SHA-256").digest("$androidId:$token".toByteArray(Charsets.UTF_8))

        // Prefix: a snowflake whose timestamp falls in 2023-2025.
        val raw = toLong(hash, 0)
        val created = BASE_TIME + (raw and 0x7FFFFFFFL) % SPAN_MS
        val snowflake = ((created - DISCORD_EPOCH) shl 22) or (raw and 0x3FFFFFL)

        // Suffix: 20 hash bytes -> 27 base64url characters.
        val suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(hash.copyOfRange(8, 28)).take(27)
        return "$snowflake.$suffix"
    }

    private fun toLong(b: ByteArray, off: Int): Long {
        var r = 0L
        for (i in 0 until 8) r = (r shl 8) or (b[off + i].toLong() and 0xFF)
        return r
    }
}
