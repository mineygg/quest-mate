package com.mineygg.questmate.core

import android.content.Context
import com.mineygg.questmate.data.QuestRecord
import com.mineygg.questmate.data.Store
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Runs the quests of the signed-in account: connect, find the active quests, enroll, and send the progress.
 */
class QuestRunner(private val ctx: Context, private val store: Store) {
    private var completed = 0

    /** Returns how many quests were completed. */
    suspend fun run(trigger: String): Int {
        if (!Battery.isUnrestricted(ctx)) {
            RunBus.log("Unrestricted battery use is off, not connecting")
            return 0
        }
        val token = store.token
        if (token == null) {
            RunBus.log("Not signed in")
            return 0
        }
        RunBus.begin(trigger)
        try {
            RunBus.step("Connecting to Discord…")
            ClientConfig.refresh(store) // newer client values, if the repo has any
            DiscordLocale.refreshCountry(store) // cached for a week; fills in a missing region of the phone's locale
            // Reuses the connection from a recent run if it is still open.
            val (gateway, user) = GatewayPool.acquire(ctx, store, token)
            val username = user.optString("global_name").ifBlank { user.optString("username") }
            RunBus.setUser(username)

            val api = DiscordApi(token)
            val settings = store.settings.value
            val feed = api.fetchQuests()
            val now = System.currentTimeMillis()
            feed.accessSuspendedUntilMs?.takeIf { it > now }?.let {
                RunBus.step("Quests are suspended for this account until ${formatTime(it)}")
                return 0
            }
            // Quests this app can't do (activity achievements, console only) are set aside up front in one line,
            // so they don't take a slot or a pause between quests.
            val cannot = Quest.unsupported(feed.quests, settings.orbsOnly)
            if (cannot.isNotEmpty()) {
                RunBus.log("Can't be done from the app: " + cannot.joinToString("; ") { "${it.name} (${it.unsupportedReason})" })
            }
            val doable = Quest.active(feed.quests, settings.orbsOnly)
            val enrollBlockedUntil = feed.enrollmentBlockedUntilMs?.takeIf { it > now }
            val active = if (enrollBlockedUntil == null) doable else doable.filter { it.isEnrolled }
            if (enrollBlockedUntil != null && active.size < doable.size) {
                RunBus.log("Enrolling is blocked until ${formatTime(enrollBlockedUntil)}, skipping ${doable.size - active.size} new quest(s)")
            }
            if (active.isEmpty()) {
                RunBus.step("No active quests")
                return 0
            }
            RunBus.step("${active.size} quest${if (active.size == 1) "" else "s"} to do")
            RunBus.setQuests(
                active.map {
                    QuestProgress(it.id, it.name, it.rewardText, "WAITING", it.task?.current ?: 0.0, it.task?.target ?: 0.0)
                }
            )

            gateway.keepAlive(true)
            for ((i, quest) in active.withIndex()) {
                try {
                    processQuest(quest, api, gateway, username)
                } catch (e: ApiException) {
                    if (e.isAuth) throw e
                    RunBus.log("[${quest.name}] ${e.message?.take(160)}")
                    RunBus.update(quest.id) { it.copy(status = "FAILED") }
                }
                if (i < active.lastIndex) {
                    val wait = 10 + (Math.random() * 11).toInt() // 10–20 s between quests
                    RunBus.log("Waiting ${wait}s before the next quest")
                    delay(wait * 1000L)
                }
            }
            RunBus.step("Finished")
            return completed
        } catch (e: CancellationException) {
            RunBus.log("Stopped")
            throw e
        } catch (e: ApiException) {
            if (e.isAuth) {
                store.clearToken()
                RunBus.log("Discord no longer accepts the saved sign-in")
                if (store.settings.value.notify) Notifier.alert(ctx, "Sign in again", "Discord rejected the saved login.")
            } else {
                RunBus.log("Failed: ${e.message}")
            }
            return completed
        } catch (e: Exception) {
            RunBus.log("Failed: ${e.message}")
            if (e.message?.contains("4004") == true) {
                store.clearToken()
                if (store.settings.value.notify) Notifier.alert(ctx, "Sign in again", "Discord rejected the saved login.")
            }
            return completed
        } finally {
            // Stays open for a couple of minutes so back-to-back runs reuse it, unless we were signed out.
            if (store.token == null) GatewayPool.shutdown() else GatewayPool.release()
            store.saveLog(RunBus.log.value)
            RunBus.end()
        }
    }

    private fun formatTime(ms: Long): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

    private suspend fun processQuest(quest: Quest, api: DiscordApi, gateway: Gateway, username: String) {
        val info = quest.task
        if (info == null) {
            RunBus.log("Skipping ${quest.name}: unsupported task")
            RunBus.update(quest.id) { it.copy(status = "SKIPPED") }
            return
        }
        if (!quest.isEnrolled) {
            RunBus.step("Enrolling in ${quest.name}" + (quest.rewardText?.let { " · $it" } ?: ""))
            // The enroll call answers with the new user_status (it carries enrolled_at); keep it on the quest.
            api.enroll(quest)?.let { quest.json.put("user_status", it) }
        }
        val ok = when (info.kind) {
            TaskKind.VIDEO -> runVideo(quest, info, api)
            TaskKind.GAME -> runGame(quest, info, api, gateway)
        }
        if (ok) {
            completed++
            RunBus.update(quest.id) { it.copy(status = "COMPLETED", current = it.target) }
            store.addRecord(QuestRecord(System.currentTimeMillis(), username, quest.name, quest.rewardText))
            if (store.settings.value.notify) Notifier.questDone(ctx, quest.name, quest.rewardText)
            store.webhook()?.let { Webhook.questDone(it, username, quest.name, quest.rewardText) }
        } else {
            RunBus.update(quest.id) { it.copy(status = "FAILED") }
        }
    }

    /**
     * Video quests: Discord only accepts a timestamp that is at most ~10 s ahead of the real time since
     * enrollment, so progress is sent in small steps at a human-looking pace.
     */
    private suspend fun runVideo(quest: Quest, info: TaskInfo, api: DiscordApi): Boolean {
        val maxFuture = 10
        val step = 7
        val enrolledAt = quest.enrolledAtMs ?: System.currentTimeMillis()
        var reported = info.current
        var lastSend = 0L
        var nextGap = 7000 + Math.random() * 2500
        var errors = 0

        RunBus.log("[${quest.name}] Watching")
        RunBus.update(quest.id) { it.copy(status = "STARTING") }

        while (reported < info.target) {
            val now = System.currentTimeMillis()
            val realElapsed = ((now - enrolledAt) / 1000).toInt()
            val next = minOf(info.target, reported + step)
            val timeGateOk = realElapsed + maxFuture >= next
            val intervalOk = now - lastSend >= nextGap

            if (timeGateOk && intervalOk) {
                val send = minOf(info.target, next + Math.random())
                try {
                    val result = api.videoProgress(quest.id, Math.round(send * 1_000_000) / 1_000_000.0)
                    errors = 0
                    val server = result?.optJSONObject("progress")?.optJSONObject(info.key)?.optDouble("value", 0.0) ?: 0.0
                    reported = if (server > reported) minOf(info.target, server) else minOf(info.target, send)
                    lastSend = now
                    nextGap = 7000 + Math.random() * 2500
                    RunBus.update(quest.id) { it.copy(status = "WATCHING", current = reported) }
                    if (result != null && !result.isNull("completed_at")) return true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors++
                    RunBus.log("[${quest.name}] Update failed ($errors/5): ${e.message?.take(120)}")
                    if (e is ApiException && e.isAuth) throw e
                    if (errors >= 5) return false
                    val wait = (e as? ApiException)?.retryAfter?.let { Math.ceil(it * 1000).toLong() + 200 } ?: (2000L * errors)
                    delay(wait)
                    lastSend = now
                }
            }
            delay(1000)
        }

        // Final ping at the target.
        return try {
            api.videoProgress(quest.id, Math.round(info.target * 1_000_000) / 1_000_000.0)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RunBus.log("[${quest.name}] Final update failed: ${e.message?.take(120)}")
            false
        }
    }

    /** Game quests: show the game as "playing" and send a heartbeat every 30 s until the target is reached. */
    private suspend fun runGame(quest: Quest, info: TaskInfo, api: DiscordApi, gateway: Gateway): Boolean {
        val interval = 30_000L
        var lastBeat = 0L
        var elapsed = info.current
        var errors = 0

        RunBus.log("[${quest.name}] Playing ${quest.appName}")
        RunBus.update(quest.id) { it.copy(status = "PLAYING") }
        gateway.addActivity(quest.appName, quest.appId)
        try {
            while (elapsed < info.target) {
                val now = System.currentTimeMillis()
                if (now - lastBeat >= interval) {
                    try {
                        val result = api.heartbeat(quest.id, quest.appId, false)
                        errors = 0
                        val server = result?.optJSONObject("progress")?.optJSONObject(info.key)?.optDouble("value", 0.0) ?: 0.0
                        elapsed = if (server > 0) server else elapsed + interval / 1000.0
                        lastBeat = now
                        RunBus.update(quest.id) { it.copy(current = minOf(elapsed, it.target)) }
                        if (result != null && !result.isNull("completed_at")) return true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors++
                        RunBus.log("[${quest.name}] Heartbeat failed ($errors/5): ${e.message?.take(120)}")
                        if (e is ApiException && e.isAuth) throw e
                        if (errors >= 5) return false
                        delay(2000L * errors)
                        lastBeat = now - interval + 5000
                    }
                }
                delay(1000)
            }
            runCatching { api.heartbeat(quest.id, quest.appId, true) }
            return true
        } finally {
            gateway.removeActivity(quest.appId)
        }
    }
}
