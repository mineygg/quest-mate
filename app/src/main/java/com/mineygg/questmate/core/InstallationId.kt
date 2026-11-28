package com.mineygg.questmate.core

import com.mineygg.questmate.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * The client's `installation_id`. Discord issues it; the app never makes one up.
 *
 * Like the real client: send none the first time, take the one Discord hands back (from
 * `GET /apex/experiments` or the `apex_experiments.installation` field of READY), keep it, and send it
 * on every later request and gateway identify. It belongs to the installation, not the account, so it
 * survives sign-out.
 */
object InstallationId {
    /** The stored ID, or a fresh one from Discord. Null if Discord gave none; requests then go without it. */
    suspend fun get(store: Store, token: String): String? {
        store.installationId?.let { return it }
        RunBus.debug("No installation ID yet, asking Discord for one")
        val fresh = try {
            DiscordApi(token, null).fetchInstallation()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            RunBus.debug("Couldn't get an installation ID (${e.message?.take(100)}), continuing without one")
            null
        }
        if (fresh != null) {
            store.installationId = fresh
            RunBus.log("Discord issued an installation ID for this app")
        } else {
            RunBus.debug("Discord returned no installation ID; the connection will report one if it has to")
        }
        return fresh
    }

    /**
     * At sign-in there is no token yet. Ask for the installation_id the way a first-run client does (no
     * Authorization, no id), store what Discord hands back, and return it. Null = Discord gave none; the
     * WebView's own /apex/experiments call, if it makes one, is picked up by [WebHeaders] instead.
     */
    suspend fun getForLogin(store: Store): String? {
        store.installationId?.let { return it }
        val fresh = try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url(DiscordConfig.API_BASE + "/apex/experiments?surface=2")
                    .apply { DiscordConfig.publicHeaders("GET", DiscordConfig.REFERER_HOME).forEach { (k, v) -> header(k, v) } }
                    .build()
                Http.api.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        null
                    } else {
                        JSONObject(r.body?.string().orEmpty()).optString("installation").takeIf { it.isNotBlank() }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            RunBus.debug("Login: couldn't get an installation ID (${e.javaClass.simpleName})")
            null
        }
        if (fresh != null) store.installationId = fresh
        return fresh
    }
}
