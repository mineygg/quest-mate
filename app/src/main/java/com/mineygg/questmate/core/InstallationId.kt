package com.mineygg.questmate.core

import com.mineygg.questmate.data.Store

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
}
