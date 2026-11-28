package com.mineygg.questmate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mineygg.questmate.core.ApiException
import com.mineygg.questmate.core.DiscordApi
import com.mineygg.questmate.core.GatewayPool
import com.mineygg.questmate.core.InstallationId
import com.mineygg.questmate.core.RunBus
import com.mineygg.questmate.core.Updater
import com.mineygg.questmate.core.WebHeaders
import com.mineygg.questmate.core.WebSession
import com.mineygg.questmate.core.Webhook
import com.mineygg.questmate.data.Settings
import com.mineygg.questmate.work.Scheduler
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as QuestMateApp).store

    val settings = store.settings
    val profile = store.profile
    val history = store.history
    val hasWebhook = store.hasWebhook
    val disclaimerAccepted = store.disclaimerAccepted
    val updateState = Updater.state

    val running = RunBus.running
    val status = RunBus.status
    val log = RunBus.log
    val quests = RunBus.quests
    val runUser = RunBus.user

    init {
        Updater.restore(app, store)
        if (store.settings.value.checkUpdates) checkUpdates(manual = false)
    }

    // ── Runs ────────────────────────────────────────────────────────────────
    /** Login screen opened: have an installation_id ready for the WebView's API requests (X-Installation-Id). */
    fun prepareLoginInstallation() {
        WebHeaders.installationId = store.installationId
        WebHeaders.onInstallation = { id -> if (store.installationId == null) store.installationId = id }
        if (store.installationId == null) {
            viewModelScope.launch {
                InstallationId.getForLogin(store)?.let { WebHeaders.installationId = it }
            }
        }
    }

    fun clearLog() {
        RunBus.clear()
        store.saveLog(emptyList())
    }

    fun runNow() = Scheduler.runNow(getApplication(), "manual")

    fun stop() = Scheduler.stop(getApplication())

    // ── Sign-in ─────────────────────────────────────────────────────────────
    /** Checks the token with Discord and keeps it only if it is valid. [done] gets null, or what went wrong. */
    fun signIn(rawToken: String, done: (String?) -> Unit) {
        val token = rawToken.trim().trim('"', '\'')
        if (token.length < 20) {
            done("That doesn't look like a Discord token.")
            return
        }
        viewModelScope.launch {
            try {
                val me = DiscordApi(token, InstallationId.get(store, token)).me()
                if (me == null) {
                    done("Discord didn't answer.")
                } else {
                    store.saveAccount(token, me)
                    done(null)
                }
            } catch (e: ApiException) {
                done(if (e.isAuth) "Discord refused this token." else "Discord answered ${e.code}.")
            } catch (e: Exception) {
                done(e.message ?: "Couldn't reach Discord.")
            }
        }
    }

    fun signOut() {
        store.clearToken()
        GatewayPool.shutdown()
        WebSession.clear()
    }

    fun acceptDisclaimer() = store.acceptDisclaimer()

    // ── Settings ────────────────────────────────────────────────────────────
    fun updateSettings(transform: (Settings) -> Settings) {
        val old = store.settings.value
        val next = transform(old)
        store.saveSettings(next)
        if (next.autoRun != old.autoRun || next.intervalHours != old.intervalHours) Scheduler.schedule(getApplication(), next, replace = true)
    }

    fun clearHistory() = store.clearHistory()

    // ── Webhook ─────────────────────────────────────────────────────────────
    /** Sends a test message and keeps the URL only if Discord accepts it. [done] gets null or the problem. */
    fun saveWebhook(raw: String, done: (String?) -> Unit) {
        val url = Webhook.clean(raw)
        if (!Webhook.isWebhookUrl(url)) {
            done("That isn't a Discord webhook URL.")
            return
        }
        viewModelScope.launch {
            val problem = Webhook.sayHello(url)
            if (problem == null) store.setWebhook(url)
            done(problem)
        }
    }

    fun removeWebhook() = store.removeWebhook()

    // ── Updates ─────────────────────────────────────────────────────────────
    fun checkUpdates(manual: Boolean = true) {
        viewModelScope.launch { Updater.check(getApplication(), store, manual) }
    }

    fun startUpdate() {
        viewModelScope.launch { Updater.downloadAndInstall(getApplication()) }
    }
}
