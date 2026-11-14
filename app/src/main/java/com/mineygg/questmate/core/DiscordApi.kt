package com.mineygg.questmate.core

import com.mineygg.questmate.data.Profile
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A non-2xx answer from Discord. [retryAfter] is set for 429, in seconds. */
class ApiException(val code: Int, val body: String, val retryAfter: Double? = null) : IOException("$code: $body") {
    /** 401 means Discord no longer accepts the token. */
    val isAuth: Boolean get() = code == 401
}

/** REST calls for one account. */
class DiscordApi(private val token: String) {

    suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject? = withContext(Dispatchers.IO) {
        val rb = when {
            body != null -> body.toString().toRequestBody("application/json".toMediaType())
            method == "GET" -> null
            else -> "".toRequestBody(null)
        }
        val req = Request.Builder().url(DiscordConfig.API_BASE + path).method(method, rb).apply {
            DiscordConfig.headers(token).forEach { (k, v) -> header(k, v) }
        }.build()
        Http.client.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                var retry: Double? = null
                if (res.code == 429) {
                    retry = runCatching { JSONObject(text).optDouble("retry_after", 2.0) }.getOrDefault(2.0)
                }
                throw ApiException(res.code, text, retry)
            }
            if (res.code == 204 || text.isBlank()) null else JSONObject(text)
        }
    }

    /** Checks the token and returns who it belongs to. */
    suspend fun me(): Profile? {
        val o = call("GET", "/users/@me") ?: return null
        val name = o.optString("global_name").ifBlank { o.optString("username") }
        return Profile(o.getString("id"), name)
    }

    /** The quest list, plus the times until which Discord blocks enrolling or all quest access (null = not blocked). */
    class QuestFeed(val quests: List<Quest>, val enrollmentBlockedUntilMs: Long?, val accessSuspendedUntilMs: Long?)

    suspend fun fetchQuests(): QuestFeed {
        val body = call("GET", "/quests/@me") ?: return QuestFeed(emptyList(), null, null)
        val arr = body.optJSONArray("quests")
        val quests = if (arr == null) emptyList() else (0 until arr.length()).map { Quest(arr.getJSONObject(it)) }
        fun time(key: String): Long? = if (body.isNull(key)) null else Quest.parseTime(body.optString(key))
        return QuestFeed(quests, time("quest_enrollment_blocked_until"), time("quest_access_suspended_until"))
    }

    suspend fun enroll(q: Quest): JSONObject? = call(
        "POST",
        "/quests/${q.id}/enroll",
        JSONObject()
            .put("location", 11)
            .put("is_targeted", false)
            .put("metadata_sealed", JSONObject.NULL)
            .put("traffic_metadata_sealed", q.json.opt("traffic_metadata_sealed") ?: JSONObject.NULL)
    )

    suspend fun heartbeat(questId: String, appId: String, done: Boolean): JSONObject? = call(
        "POST",
        "/quests/$questId/heartbeat",
        JSONObject()
            .put("application_id", appId)
            .put("terminal", done)
            .put("stream_key", "call:$questId:1")
    )

    suspend fun videoProgress(questId: String, seconds: Double): JSONObject? =
        call("POST", "/quests/$questId/video-progress", JSONObject().put("timestamp", seconds))
}
