package com.mineygg.questmate.core

import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONObject

enum class TaskKind { VIDEO, GAME }

/** What a quest asks for: the task key Discord uses, how many seconds it needs and how many are done. */
data class TaskInfo(val kind: TaskKind, val key: String, val target: Double, val current: Double)

/** Thin wrapper around a quest object from /quests/@me (snake_case JSON, kept as received). */
class Quest(var json: JSONObject) {
    val id: String get() = json.getString("id")

    private val config: JSONObject get() = json.optJSONObject("config") ?: JSONObject()

    val name: String
        get() = config.optJSONObject("messages")?.optString("quest_name")?.takeIf { it.isNotBlank() } ?: id

    val appId: String get() = config.optJSONObject("application")?.optString("id").orEmpty()
    val appName: String get() = config.optJSONObject("application")?.optString("name").orEmpty()

    private val status: JSONObject? get() = json.optJSONObject("user_status")

    private fun JSONObject?.has(key: String): Boolean = this != null && !isNull(key) && optString(key).isNotEmpty()

    val isComplete: Boolean get() = status.has("completed_at")
    val isEnrolled: Boolean get() = status.has("enrolled_at")

    val enrolledAtMs: Long?
        get() = status?.takeIf { it.has("enrolled_at") }?.optString("enrolled_at")?.let(::parseTime)

    private val taskKeys: Set<String>
        get() {
            val tasks = (config.optJSONObject("task_config_v2") ?: config.optJSONObject("task_config"))
                ?.optJSONObject("tasks")
            return tasks?.keys()?.asSequence()?.toSet() ?: emptySet()
        }

    /** False for a quest that is listed but whose start time hasn't come yet. */
    val hasStarted: Boolean
        get() {
            val at = config.optString("starts_at").takeIf { it.isNotEmpty() }?.let(::parseTime) ?: return true
            return System.currentTimeMillis() >= at
        }

    /** Why this app can't do the quest, or null when it can. */
    val unsupportedReason: String?
        get() {
            if (task != null) return null
            val keys = taskKeys
            return when {
                keys.isEmpty() -> "no task listed"
                "ACHIEVEMENT_IN_ACTIVITY" in keys -> "needs an achievement inside a Discord Activity"
                keys.all { it == "PLAY_ON_PLAYSTATION" || it == "PLAY_ON_XBOX" } -> "console only"
                else -> "unsupported task: ${keys.joinToString()}"
            }
        }

    val isExpired: Boolean
        get() {
            val at = config.optString("expires_at").takeIf { it.isNotEmpty() }?.let(::parseTime) ?: return false
            return System.currentTimeMillis() > at
        }

    val orbReward: Int?
        get() {
            val rewards = config.optJSONObject("rewards_config")?.optJSONArray("rewards") ?: return null
            for (i in 0 until rewards.length()) {
                val q = rewards.optJSONObject(i)?.optInt("orb_quantity", 0) ?: 0
                if (q > 0) return q
            }
            return null
        }

    val rewardText: String? get() = orbReward?.let { "$it Orbs" }

    /** The task this app can do, or null (stream, activity and console-only quests are not supported). */
    val task: TaskInfo?
        get() {
            val tasks = (config.optJSONObject("task_config_v2") ?: config.optJSONObject("task_config"))
                ?.optJSONObject("tasks") ?: return null
            val order = listOf(
                "WATCH_VIDEO" to TaskKind.VIDEO,
                "WATCH_VIDEO_ON_MOBILE" to TaskKind.VIDEO,
                "PLAY_ON_DESKTOP" to TaskKind.GAME,
                "PLAY_ON_DESKTOP_V2" to TaskKind.GAME
            )
            for ((key, kind) in order) {
                val t = tasks.optJSONObject(key) ?: continue
                val done = status?.optJSONObject("progress")?.optJSONObject(key)?.optDouble("value", 0.0) ?: 0.0
                return TaskInfo(kind, key, t.optDouble("target", 0.0), done)
            }
            return null
        }

    companion object {
        fun parseTime(s: String): Long? =
            runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
                .recoverCatching { Instant.parse(s).toEpochMilli() }
                .getOrNull()

        private fun live(q: Quest, orbsOnly: Boolean) =
            !q.isComplete && !q.isExpired && q.hasStarted && (!orbsOnly || q.orbReward != null)

        /** Quests still to do that this app can do: not completed, not expired, started, (optionally) paying Orbs. */
        fun active(all: List<Quest>, orbsOnly: Boolean): List<Quest> =
            all.filter { live(it, orbsOnly) && it.task != null }

        /** Quests that would be worth doing but that this app can't do from here; see [unsupportedReason]. */
        fun unsupported(all: List<Quest>, orbsOnly: Boolean): List<Quest> =
            all.filter { live(it, orbsOnly) && it.task == null }
    }
}
