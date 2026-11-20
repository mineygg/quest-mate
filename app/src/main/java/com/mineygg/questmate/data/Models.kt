package com.mineygg.questmate.data

import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    /** Run on its own, every [intervalHours] hours (one of [RunInterval.OPTIONS]). */
    val autoRun: Boolean = true,
    val intervalHours: Int = RunInterval.RECOMMENDED,
    /** Only do quests that pay Orbs. */
    val orbsOnly: Boolean = true,
    val notify: Boolean = true,
    val checkUpdates: Boolean = true,
    /** Record requests, connection steps and progress in the log. Tokens are never logged either way. */
    val verboseLog: Boolean = true
)

/** The Discord account that is signed in. The token itself never leaves the encrypted store. */
data class Profile(val id: String, val username: String)

/** One finished quest, for the Activity tab. */
data class QuestRecord(val at: Long, val user: String, val name: String, val reward: String?) {
    fun toJson(): JSONObject = JSONObject()
        .put("at", at)
        .put("user", user)
        .put("name", name)
        .put("reward", reward ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = QuestRecord(
            at = o.optLong("at"),
            user = o.optString("user"),
            name = o.optString("name"),
            reward = o.optString("reward").takeIf { it.isNotEmpty() && it != "null" }
        )

        fun listToJson(list: List<QuestRecord>): String = JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<QuestRecord> {
            if (s.isNullOrEmpty()) return emptyList()
            return runCatching {
                val a = JSONArray(s)
                (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}

/** How often the automatic run happens. The Settings slider snaps to these. */
object RunInterval {
    val OPTIONS = listOf(12, 24, 48, 72, 96, 120, 144, 168)
    const val RECOMMENDED = 72

    fun label(hours: Int): String = if (hours <= 24) "$hours hours" else "${hours / 24} days"

    fun short(hours: Int): String = if (hours < 24) "${hours}h" else "${hours / 24}d"
}
