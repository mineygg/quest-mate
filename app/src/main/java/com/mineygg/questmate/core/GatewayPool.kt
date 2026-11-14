package com.mineygg.questmate.core

import android.content.Context
import com.mineygg.questmate.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Keeps the Discord gateway connection open between runs. A run borrows it with [acquire] and hands it back
 * with [release]; it is only closed after [LINGER_MS] without being used again, so starting runs back to
 * back reuses one connection instead of connecting and disconnecting each time.
 */
object GatewayPool {
    /** How long an unused connection is kept open. */
    const val LINGER_MS = 120_000L

    // The connection outlives any single run, so it gets a scope of its own.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    @Volatile private var current: Gateway? = null

    @Volatile private var currentToken: String? = null

    @Volatile private var currentUser: JSONObject? = null

    @Volatile private var idle: Job? = null

    private val openState = MutableStateFlow(false)

    /** True while a connection is open (in use, or waiting for the next run). */
    val isOpen: Boolean get() = openState.value

    /** Suspends until the connection has been closed. */
    suspend fun awaitClosed() {
        openState.first { !it }
    }

    /** The open connection for [token] (reused if it is still active, otherwise a new one) and its user. */
    suspend fun acquire(ctx: Context, store: Store, token: String): Pair<Gateway, JSONObject> = mutex.withLock {
        idle?.cancel()
        idle = null
        val gateway = current
        val user = currentUser
        if (gateway != null && user != null && currentToken == token && gateway.isAlive()) {
            RunBus.log("Reusing the open Discord connection")
            return@withLock gateway to user
        }
        drop()
        val fresh = Gateway(token, scope, InstallationId.get(ctx, token), store.qosToken) { store.qosToken = it }
        val me = fresh.login() // closes itself if it fails
        current = fresh
        currentToken = token
        currentUser = me
        openState.value = true
        fresh to me
    }

    /** Hands the connection back: quest games are cleared and it stays open for [LINGER_MS]. */
    fun release() {
        val gateway = current ?: return
        gateway.clearActivities()
        idle?.cancel()
        idle = scope.launch {
            delay(LINGER_MS)
            mutex.withLock { if (current === gateway) drop() }
        }
    }

    /** Closes the connection now (signed out, or the token was rejected). */
    fun shutdown() {
        idle?.cancel()
        idle = null
        scope.launch { mutex.withLock { drop() } }
    }

    private fun drop() {
        current?.close()
        current = null
        currentToken = null
        currentUser = null
        openState.value = false
    }
}
