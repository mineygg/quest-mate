package com.mineygg.questmate.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * One account's gateway connection: identify, heartbeat, resume and presence.
 * Everything is scoped to this instance, so accounts never share state.
 */
class Gateway(
    private val token: String,
    private val scope: CoroutineScope,
    private val installationId: String?,
    storedQos: String? = null,
    /** Called with the installation_id Discord sends in READY (only when it had none, or rejected ours). */
    private val onInstallation: (String) -> Unit = {},
    /** Called with the fresh qos_token from READY, or null when Discord turned the stored one down. */
    private val onQos: (String?) -> Unit = {}
) {
    init {
        Redactor.register(token)
    }

    private val client = Http.client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            // OkHttp hard-codes "permessage-deflate" on WebSocket upgrades; the desktop client (Chromium) offers
            // it with client_max_window_bits. Set WS_CHROMIUM_EXTENSIONS to false if the handshake ever fails.
            val r = chain.request()
            if (WS_CHROMIUM_EXTENSIONS && r.header("Upgrade").equals("websocket", ignoreCase = true)) {
                chain.proceed(r.newBuilder().header("Sec-WebSocket-Extensions", "permessage-deflate; client_max_window_bits").build())
            } else {
                chain.proceed(r)
            }
        }
        .build()

    @Volatile private var ws: WebSocket? = null

    @Volatile private var gen = 0

    @Volatile private var lastSeq: Long? = null

    @Volatile private var sessionId: String? = null

    @Volatile private var resumeUrl: String? = null

    @Volatile private var helloInterval = 0L

    @Volatile private var lastAck = 0L

    @Volatile private var lastBeatSent = 0L

    @Volatile private var closed = false

    /** True while the socket is up and the session is READY/RESUMED. */
    @Volatile private var connected = false

    @Volatile private var zstd: ZstdStream? = null

    /** Heartbeats go out as op 40 (QoS heartbeat) like the desktop client; op 1 if Discord rejects that. */
    @Volatile private var qosHeartbeat = true

    @Volatile private var qosBeatSent = false

    /** True once the post-READY sequence has run; from then on presence updates (quest games) go out. */
    @Volatile private var bootDone = false

    /** The qos_token to send in identify: the one stored from the last session. */
    @Volatile private var qosToken: String? = storedQos?.takeIf { it.isNotEmpty() }
    private var identifyLogged = false
    private var hbJob: Job? = null
    private val ready = CompletableDeferred<JSONObject>()
    private val reconnecting = AtomicBoolean(false)
    private val games = LinkedHashMap<String, JSONObject>()

    /** Connects and waits for READY. Returns the user object. */
    suspend fun login(timeoutMs: Long = 60_000): JSONObject {
        try {
            return withTimeout(timeoutMs) {
                open(resume = false)
                ready.await()
            }
        } catch (e: TimeoutCancellationException) {
            close()
            throw IOException("Gateway login timed out", e)
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException(e.message ?: "Gateway login failed", e)
        }
    }

    fun keepAlive(alive: Boolean) {
        if (alive) {
            if (hbJob == null && helloInterval > 0) startHeartbeat(helloInterval)
        } else {
            stopHeartbeat()
        }
    }

    /** Whether this connection can be handed to another run as it is. */
    fun isAlive() = !closed && connected

    fun close() {
        closed = true
        connected = false
        stopHeartbeat()
        zstd?.close()
        zstd = null
        runCatching { ws?.close(1000, null) }
        ws = null
    }

    // ── presence ─────────────────────────────────────────────────────────────
    fun addActivity(gameName: String, appId: String) {
        synchronized(games) {
            games[appId] = JSONObject()
                .put("name", gameName)
                .put("type", 0)
                .put("application_id", appId)
                .put("timestamps", JSONObject().put("start", System.currentTimeMillis()))
        }
        RunBus.debug("Presence: playing $gameName")
        sendPresence()
    }

    fun removeActivity(appId: String) {
        synchronized(games) { games.remove(appId) }
        sendPresence()
    }

    /** Drops every game, e.g. when a run ends while the connection stays open. */
    fun clearActivities() {
        val had = synchronized(games) { games.isNotEmpty().also { games.clear() } }
        if (had) {
            RunBus.debug("Presence: cleared")
            sendPresence()
        }
    }

    private fun sendPresence() {
        if (!bootDone) return // the boot sequence sends the first online presence itself, with the current games
        val list = org.json.JSONArray()
        synchronized(games) { games.values.forEach { list.put(it) } }
        send(presence("online", list))
    }

    private fun presence(status: String, activities: org.json.JSONArray) = JSONObject().put("op", 3).put(
        "d",
        JSONObject().put("status", status).put("since", 0).put("activities", activities).put("afk", false)
    )

    /** Empty voice state: not in any channel, muted, like the desktop client sends after connecting. */
    private fun voiceState() = JSONObject().put("op", 4).put(
        "d",
        JSONObject()
            .put("guild_id", JSONObject.NULL)
            .put("channel_id", JSONObject.NULL)
            .put("self_mute", true)
            .put("self_deaf", false)
            .put("self_video", false)
            .put("flags", 2)
    )

    /**
     * What the desktop client sends after identify: presence "unknown", an empty voice state, then presence
     * "online". The regular heartbeat (op 40) runs on its own timer in between.
     */
    private fun bootSequence() {
        val g = gen
        scope.launch {
            send(presence("unknown", org.json.JSONArray()))
            delay(100 + (Math.random() * 200).toLong())
            if (closed || g != gen) return@launch
            send(voiceState())
            delay(1000 + (Math.random() * 1500).toLong())
            if (closed || g != gen) return@launch
            bootDone = true
            sendPresence()
        }
    }

    // ── connection ───────────────────────────────────────────────────────────
    private fun send(o: JSONObject) {
        if (DiscordConfig.GATEWAY_ETF) ws?.send(EtfCodec.pack(o).toByteString()) else ws?.send(o.toString())
    }

    private fun open(resume: Boolean) {
        if (closed) return
        val myGen = ++gen
        qosBeatSent = false
        if (!resume) bootDone = false
        val base = if (resume && resumeUrl != null) resumeUrl else DiscordConfig.GATEWAY
        RunBus.debug("Gateway: connecting (${if (resume) "resuming the session" else "new session"})")
        // A new socket is a new zstd stream, so every connection gets its own decompression context.
        val stream = if (DiscordConfig.GATEWAY_ETF) ZstdStream() else null
        zstd?.close()
        zstd = stream
        val query = if (stream != null) "v=9&encoding=etf&compress=zstd-stream" else "v=9&encoding=json"
        val req = Request.Builder()
            .url("$base/?$query")
            .header("User-Agent", DiscordConfig.agent)
            .header("Origin", "https://discord.com")
            .header("Accept-Language", DiscordLocale.acceptLanguagePrimary)
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()
        ws = client.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (myGen == gen) RunBus.debug("Gateway: socket open")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (myGen == gen) handle(text, resume)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (myGen != gen || closed || stream == null) return
                    val msg = try {
                        EtfCodec.unpack(stream.decode(bytes.toByteArray())) as? JSONObject
                    } catch (e: Exception) {
                        // Frames still arriving while we shut down are expected: only log real failures.
                        if (!closed && myGen == gen) {
                            RunBus.log("Gateway frame not decoded: ${e.message?.take(120) ?: e.javaClass.simpleName}")
                        }
                        null
                    }
                    if (msg != null) handleMsg(msg, resume)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    runCatching { webSocket.close(if (code in 1000..4999 && code != 1005 && code != 1006) code else 1000, null) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (myGen != gen) return
                    stopHeartbeat()
                    connected = false
                    RunBus.debug("Gateway: closed ($code ${reason.ifBlank { "no reason" }})")
                    if ((code == 4001 || code == 4002) && qosHeartbeat && qosBeatSent) {
                        qosHeartbeat = false
                        RunBus.log("Discord rejected the op 40 heartbeat (code $code), falling back to op 1")
                    }
                    if (!ready.isCompleted) {
                        dropQosIfSent()
                        ready.completeExceptionally(IOException(closeMessage(code, reason)))
                    } else {
                        scheduleReconnect(resume = true)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (myGen != gen) return
                    stopHeartbeat()
                    connected = false
                    RunBus.debug("Gateway: connection error (${t.javaClass.simpleName}: ${t.message?.take(100)})")
                    if (!ready.isCompleted) {
                        ready.completeExceptionally(IOException(t.message ?: "Gateway connection failed", t))
                    } else {
                        scheduleReconnect(resume = true)
                    }
                }
            }
        )
    }

    private fun dropQosIfSent() {
        if (qosToken != null) {
            qosToken = null
            onQos(null)
            RunBus.log("Connection failed after sending a stored qos_token, it was discarded")
        }
    }

    private fun closeMessage(code: Int, reason: String) = when (code) {
        4004 -> "Discord refused the token (gateway code 4004)"
        else -> "Gateway closed ($code ${reason.ifBlank { "no reason" }})"
    }

    private fun scheduleReconnect(resume: Boolean) {
        if (closed || !reconnecting.compareAndSet(false, true)) return
        RunBus.log("Gateway connection lost, reconnecting" + if (resume && sessionId != null) " (resuming)" else "")
        scope.launch {
            delay(1000 + (Math.random() * 3000).toLong())
            reconnecting.set(false)
            open(resume && sessionId != null)
        }
    }

    private fun handle(text: String, resumeAttempt: Boolean) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        handleMsg(msg, resumeAttempt)
    }

    private fun handleMsg(msg: JSONObject, resumeAttempt: Boolean) {
        if (!msg.isNull("s")) lastSeq = msg.optLong("s")

        when (msg.optInt("op", -1)) {
            10 -> { // Hello → Identify or Resume, then heartbeat
                if (resumeAttempt && sessionId != null) {
                    send(
                        JSONObject().put("op", 6).put(
                            "d",
                            JSONObject().put("token", token).put("session_id", sessionId).put("seq", lastSeq ?: JSONObject.NULL)
                        )
                    )
                } else {
                    val id = identify()
                    RunBus.debug(
                        "Gateway: HELLO, identifying (installation ID: ${if (installationId != null) "sent" else "none"}, " +
                            "qos_token: ${if (qosToken != null) "sent" else "none"})"
                    )
                    if (qosToken == null && !identifyLogged) {
                        identifyLogged = true
                        // The token is masked here, and the log scrubs it again on the way in.
                        RunBus.debug("Identify payload: " + JSONObject(id.toString()).apply { getJSONObject("d").put("token", "***") })
                    }
                    send(id)
                }
                RunBus.debug("Gateway: heartbeat every ${msg.getJSONObject("d").getLong("heartbeat_interval") / 1000} s")
                startHeartbeat(msg.getJSONObject("d").getLong("heartbeat_interval"))
            }
            0 -> when (msg.optString("t")) { // Dispatch
                "READY" -> {
                    val d = msg.getJSONObject("d")
                    sessionId = d.optString("session_id")
                    resumeUrl = d.optString("resume_gateway_url").takeIf { it.isNotEmpty() }
                    d.optString("qos_token").takeIf { it.isNotEmpty() }?.let { onQos(it) }
                    d.optJSONObject("apex_experiments")?.optString("installation")?.takeIf { it.isNotEmpty() }
                        ?.let { onInstallation(it) }
                    connected = true
                    RunBus.debug("Gateway: READY")
                    if (!ready.isCompleted) ready.complete(d.getJSONObject("user"))
                    bootSequence()
                }
                "RESUMED" -> {
                    connected = true
                    RunBus.debug("Gateway: session resumed")
                    if (hbJob == null && helloInterval > 0) startHeartbeat(helloInterval)
                }
            }
            1 -> sendHeartbeat() // server asked for a heartbeat now
            11 -> {
                lastAck = System.currentTimeMillis()
                if (lastBeatSent > 0) RunBus.debug("Gateway: heartbeat acknowledged (${lastAck - lastBeatSent} ms)")
            }
            7 -> { // reconnect requested
                RunBus.debug("Gateway: Discord asked us to reconnect")
                stopHeartbeat()
                runCatching { ws?.close(4000, "reconnect") }
            }
            9 -> { // invalid session
                stopHeartbeat()
                val canResume = msg.optBoolean("d", false)
                RunBus.log("Gateway: session invalidated (${if (canResume) "can resume" else "starting a new one"})")
                gen++ // ignore the close of the old socket, we reconnect ourselves
                runCatching { ws?.close(4000, "invalid session") }
                if (canResume) {
                    scheduleReconnect(resume = true)
                } else {
                    sessionId = null
                    scheduleReconnect(resume = false)
                }
            }
        }
    }

    private fun identify(): JSONObject = JSONObject().put("op", 2).put(
        "d",
        JSONObject()
            .put("token", token)
            .put("capabilities", DiscordConfig.capabilities)
            .put("properties", DiscordConfig.gatewayProps(installationId))
            .put(
                "presence",
                JSONObject().put("status", "unknown").put("since", 0).put("activities", org.json.JSONArray()).put("afk", false)
            )
            .put("compress", false)
            .put("client_state", JSONObject().put("guild_versions", JSONObject()))
            .apply { qosToken?.let { put("qos_token", it) } }
    )

    // ── heartbeat ────────────────────────────────────────────────────────────
    private fun sendHeartbeat() {
        lastBeatSent = System.currentTimeMillis()
        val seq = lastSeq ?: JSONObject.NULL
        if (!qosHeartbeat) {
            send(JSONObject().put("op", 1).put("d", seq))
            return
        }
        // Op 40: the heartbeat plus QoS state. Not foregrounded while quests run in the background.
        val qos = JSONObject()
            .put("active", false)
            .put("ver", DiscordConfig.qosVersion)
            .put("reasons", org.json.JSONArray())
        qosBeatSent = true
        send(JSONObject().put("op", 40).put("d", JSONObject().put("seq", seq).put("qos", qos)))
    }

    private fun startHeartbeat(intervalMs: Long) {
        stopHeartbeat()
        helloInterval = intervalMs
        lastAck = System.currentTimeMillis()
        hbJob = scope.launch {
            delay((Math.random() * intervalMs).toLong()) // first beat is jittered, per Discord's spec
            while (isActive) {
                if (System.currentTimeMillis() - lastAck > intervalMs + GRACE_MS) {
                    // No ACK for too long: the connection is a zombie. Close it; the close handler resumes.
                    RunBus.log("Gateway: no heartbeat answer from Discord, reconnecting")
                    runCatching { ws?.close(4000, "zombie") }
                    break
                }
                sendHeartbeat()
                delay(intervalMs)
            }
        }
    }

    private fun stopHeartbeat() {
        hbJob?.cancel()
        hbJob = null
    }

    private companion object {
        const val GRACE_MS = 15_000L

        /** Offer "permessage-deflate; client_max_window_bits" like Chromium instead of OkHttp's plain "permessage-deflate". */
        const val WS_CHROMIUM_EXTENSIONS = true
    }
}
