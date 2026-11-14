package com.mineygg.questmate.core

import android.content.Context
import com.mineygg.questmate.AppInfo
import com.mineygg.questmate.R
import com.mineygg.questmate.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * The values that go stale when Discord or Windows move on: the desktop client's version and build numbers,
 * the Electron/Chrome versions, and the pool of Windows builds the [DeviceProfile] is picked from.
 *
 * Two copies exist. The one bundled in the app (res/raw/client_config.json) is always there. A newer one is
 * downloaded from this repository's main branch (the same file), checked, and kept in [Store]; the app uses
 * whichever has the higher [version]. A download is ignored when it is invalid, has another [schema], or is
 * not newer, and any network problem simply keeps what the app already has.
 *
 * To publish new values: edit res/raw/client_config.json and raise "version" (0.0.1 -> 0.0.2). Raise "schema"
 * only when the structure changes in a way older apps can't read; they then ignore the file until updated.
 */
data class ClientConfig(
    /** Structure of the file. */
    val schema: Int,
    /** Version of the values, "major.minor.patch". Only a higher one replaces the current one. */
    val version: String,
    val discord: DiscordClient,
    val windows: List<WindowsFamily>
) {
    data class DiscordClient(
        val clientVersion: String,
        val buildNumber: Int,
        val nativeBuild: Int,
        val electronVersion: String,
        val chromeVersion: String,
        /** Gateway identify capabilities bitfield. */
        val capabilities: Int,
        /** Client heartbeat version sent in the op 40 QoS heartbeat. */
        val qosVersion: Int
    )

    /** One Windows release (base build) with the revisions that were shipped for it. */
    data class WindowsFamily(val build: Int, val platformVersion: String, val weight: Int, val revisions: List<Int>)

    companion object {
        /** The structure this app can read. */
        const val SUPPORTED_SCHEMA = 1

        private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000
        private const val MAX_BYTES = 64L * 1024

        private val URL = "https://raw.githubusercontent.com/" + AppInfo.REPO_URL.removePrefix("https://github.com/") +
            "/main/app/src/main/res/raw/client_config.json"

        private val SEMVER = Regex("""\d{1,6}\.\d{1,6}\.\d{1,6}""")
        private val DOTTED = Regex("""\d{1,6}(\.\d{1,6}){1,3}""")

        @Volatile
        private var active: ClientConfig? = null

        val current: ClientConfig get() = active ?: error("ClientConfig.init() was not called")

        /** Call once from Application.onCreate: loads the bundled values and the saved download, newest wins. */
        fun init(ctx: Context, store: Store) {
            val bundled = ctx.resources.openRawResource(R.raw.client_config).bufferedReader().use { it.readText() }
                .let { parse(it) } ?: error("res/raw/client_config.json is invalid")
            val saved = store.clientConfig?.let { parse(it) }
            active = if (saved != null && isNewer(saved.version, bundled.version)) saved else bundled
        }

        /** Downloads the file (at most every few hours) and switches to it when it is valid and newer. */
        suspend fun refresh(store: Store) {
            val now = System.currentTimeMillis()
            if (now - store.clientConfigCheckedAt < CHECK_EVERY_MS) return
            val text = try {
                download()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return
            store.setClientConfigCheckedAt(now)
            val fetched = parse(text)
            if (fetched == null) {
                RunBus.log("Client config: ignored a file this app can't use")
                return
            }
            if (!isNewer(fetched.version, current.version)) return
            store.saveClientConfig(text)
            active = fetched
            RunBus.log("Client config updated to ${fetched.version}")
        }

        private suspend fun download(): String? = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(URL).get().build()
            Http.client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val source = res.body?.source() ?: return@use null
                source.request(MAX_BYTES + 1)
                if (source.buffer.size > MAX_BYTES) null else source.buffer.readUtf8()
            }
        }

        /** The parsed file, or null when it is not valid JSON, has another schema, or any value is out of range. */
        fun parse(text: String): ClientConfig? {
            return try {
                val o = JSONObject(text)
                if (o.getInt("schema") != SUPPORTED_SCHEMA) return null
                val version = o.getString("version").takeIf { parseVersion(it) != null } ?: return null

                val d = o.getJSONObject("discord")
                val discord = DiscordClient(
                    clientVersion = d.getString("client_version").takeIf { SEMVER.matches(it) } ?: return null,
                    buildNumber = d.getInt("build_number").takeIf { it > 0 } ?: return null,
                    nativeBuild = d.getInt("native_build_number").takeIf { it > 0 } ?: return null,
                    electronVersion = d.getString("electron_version").takeIf { DOTTED.matches(it) } ?: return null,
                    chromeVersion = d.getString("chrome_version").takeIf { DOTTED.matches(it) } ?: return null,
                    capabilities = d.getInt("capabilities").takeIf { it >= 0 } ?: return null,
                    qosVersion = d.getInt("qos_version").takeIf { it > 0 } ?: return null
                )

                val arr = o.getJSONArray("windows")
                if (arr.length() !in 1..20) return null
                val windows = (0 until arr.length()).map { i ->
                    val w = arr.getJSONObject(i)
                    val revs = w.getJSONArray("revisions")
                    if (revs.length() !in 1..200) return null
                    WindowsFamily(
                        build = w.getInt("build").takeIf { it in 10000..99999 } ?: return null,
                        platformVersion = w.getString("platform_version").takeIf { SEMVER.matches(it) } ?: return null,
                        weight = w.getInt("weight").takeIf { it in 1..1000 } ?: return null,
                        revisions = (0 until revs.length()).map { j -> revs.getInt(j).takeIf { it in 0..99999 } ?: return null }
                    )
                }
                ClientConfig(SUPPORTED_SCHEMA, version, discord, windows)
            } catch (e: Exception) {
                null
            }
        }

        private fun parseVersion(v: String): List<Int>? {
            if (!SEMVER.matches(v)) return null
            return v.split('.').map { it.toInt() }
        }

        /** True when [candidate] is a valid "x.y.z" strictly higher than [installed]. */
        fun isNewer(candidate: String, installed: String): Boolean {
            val a = parseVersion(candidate) ?: return false
            val b = parseVersion(installed) ?: return true
            for (i in 0 until 3) {
                if (a[i] != b[i]) return a[i] > b[i]
            }
            return false
        }
    }
}
