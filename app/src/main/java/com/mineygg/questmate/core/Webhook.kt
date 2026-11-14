package com.mineygg.questmate.core

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Optional Discord webhook that gets a message when a quest is completed. */
object Webhook {
    private val HOSTS = setOf("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com")

    fun clean(raw: String) = raw.trim().trim('"', '\'')

    /** Only Discord's own webhook addresses are accepted, so the URL can't be pointed somewhere else. */
    fun isWebhookUrl(url: String): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        return u.scheme == "https" && u.host in HOSTS && u.path.orEmpty().startsWith("/api/webhooks/")
    }

    /** Sends a short test message. Returns null when Discord accepted it, otherwise what went wrong. */
    suspend fun sayHello(url: String): String? = post(url, "Quest Mate is connected. Finished quests will show up here.")

    suspend fun questDone(url: String, user: String, name: String, reward: String?): String? =
        post(url, "**$user** completed **$name**" + (reward?.let { " ($it)" } ?: ""))

    private suspend fun post(url: String, text: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("content", text).put("allowed_mentions", JSONObject().put("parse", org.json.JSONArray()))
            val req = Request.Builder().url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            Http.client.newCall(req).execute().use { res ->
                if (res.isSuccessful) null else "Discord answered ${res.code}"
            }
        } catch (e: IOException) {
            e.message ?: "Couldn't reach Discord"
        }
    }
}
