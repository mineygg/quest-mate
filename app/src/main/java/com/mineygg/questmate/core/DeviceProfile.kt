package com.mineygg.questmate.core

import android.content.Context
import android.provider.Settings
import com.mineygg.questmate.data.Store
import java.security.MessageDigest

/**
 * The Windows PC the desktop client claims to run on. Only machine facts live here; the Discord client itself
 * (app version, build numbers, Electron/Chrome) and the pool of Windows builds come from [ClientConfig].
 *
 * The profile is picked once from ANDROID_ID (not from the account token), then kept in [Store]: it stays the same
 * between runs and across accounts, like a real PC keeps its OS no matter who signs in, and it does not jump
 * around when a newer [ClientConfig] changes the pool.
 *
 * The three super-properties fields are related, exactly like `winver` shows them:
 *   os_version = "10.0.<build>", os_sdk_version = "<build>", os_build_revision = the revision after the dot
 *   e.g. Windows 10 22H2 build 19045.6466 -> "10.0.19045" / "19045" / "6466"
 */
data class DeviceProfile(
    /** Base OS build: 19045 = Win10 22H2, 22631 = Win11 23H2, 26100 = Win11 24H2, 26200 = Win11 25H2 */
    val build: Int,
    /** Servicing revision (the number after the dot in the full build, e.g. 26200.9711). */
    val revision: Int,
    /** Sec-CH-UA-Platform-Version (WebView login only). */
    val chPlatformVersion: String
) {
    val osVersion: String get() = "10.0.$build"
    val osSdkVersion: String get() = build.toString()
    val osBuildRevision: String get() = revision.toString()

    companion object {
        const val OS_NAME = "Windows"
        const val ARCH = "x64"
        const val UA_PLATFORM = "Windows NT 10.0; Win64; x64" // frozen by Chrome: Win10 and Win11 look the same
        const val CH_PLATFORM = "Windows"
        const val CH_ARCH = "x86" // client-hint name for x64 CPUs
        const val CH_BITNESS = 64

        @Volatile
        var current: DeviceProfile = DeviceProfile(19045, 6466, "10.0.0")
            private set

        /** Call once from Application.onCreate, after [ClientConfig.init] and before anything builds headers. */
        fun init(ctx: Context, store: Store) {
            val saved = store.deviceProfile?.let { decode(it) }
            if (saved != null) {
                current = saved
                return
            }
            val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "fallback_android_id"
            val picked = pick(androidId, ClientConfig.current.windows)
            store.saveDeviceProfile(encode(picked))
            current = picked
        }

        fun pick(seed: String, families: List<ClientConfig.WindowsFamily>): DeviceProfile {
            val hash = MessageDigest.getInstance("SHA-256").digest("$seed:device-profile".toByteArray(Charsets.UTF_8))
            var n = (u32(hash, 0) % families.sumOf { it.weight }).toInt()
            var family = families.last()
            for (f in families) {
                if (n < f.weight) {
                    family = f
                    break
                }
                n -= f.weight
            }
            val revision = family.revisions[(u32(hash, 4) % family.revisions.size).toInt()]
            return DeviceProfile(family.build, revision, family.platformVersion)
        }

        private fun encode(p: DeviceProfile) = "${p.build}:${p.revision}:${p.chPlatformVersion}"

        private fun decode(s: String): DeviceProfile? {
            val parts = s.split(':')
            if (parts.size != 3) return null
            val build = parts[0].toIntOrNull() ?: return null
            val revision = parts[1].toIntOrNull() ?: return null
            return DeviceProfile(build, revision, parts[2])
        }

        private fun u32(b: ByteArray, off: Int): Long {
            var r = 0L
            for (i in 0 until 4) r = (r shl 8) or (b[off + i].toLong() and 0xFF)
            return r
        }
    }
}
