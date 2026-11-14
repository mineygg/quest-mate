package com.mineygg.questmate.core

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One quest on the Home screen while it runs. */
data class QuestProgress(
    val id: String,
    val name: String,
    val reward: String?,
    val status: String, // WAITING, STARTING, WATCHING, PLAYING, COMPLETED, FAILED, SKIPPED
    val current: Double,
    val target: Double,
    /** When [current] was last reported by Discord (epoch ms), so the screen can run the timer smoothly from there. */
    val at: Long = System.currentTimeMillis()
) {
    val fraction: Float get() = if (target <= 0.0) 0f else (current / target).coerceIn(0.0, 1.0).toFloat()
}

/** Live state of the current run, shared between the worker and the UI. */
object RunBus {
    private val _running = MutableStateFlow(false)
    private val _status = MutableStateFlow("")
    private val _user = MutableStateFlow<String?>(null)
    private val _log = MutableStateFlow<List<String>>(emptyList())
    private val _quests = MutableStateFlow<List<QuestProgress>>(emptyList())
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    val running: StateFlow<Boolean> = _running.asStateFlow()
    val status: StateFlow<String> = _status.asStateFlow()
    val user: StateFlow<String?> = _user.asStateFlow()
    val log: StateFlow<List<String>> = _log.asStateFlow()
    val quests: StateFlow<List<QuestProgress>> = _quests.asStateFlow()

    fun begin(trigger: String) {
        _running.value = true
        _status.value = "Starting…"
        _user.value = null
        _quests.value = emptyList()
        _log.update { (it + "──── Quest run · $trigger · ${LocalTime.now().format(fmt)} ────").takeLast(600) }
    }

    fun end() {
        _running.value = false
        _status.value = ""
    }

    fun restore(lines: List<String>) {
        if (_log.value.isEmpty()) _log.value = lines
    }

    fun log(msg: String) {
        val line = "${LocalTime.now().format(fmt)}  $msg"
        _log.update { (it + line).takeLast(600) }
    }

    /** Sets the one-line status shown on the Home screen and logs it. */
    fun step(msg: String) {
        _status.value = msg
        log(msg)
    }

    fun setUser(name: String?) {
        _user.value = name
    }

    fun setQuests(list: List<QuestProgress>) {
        _quests.value = list
    }

    fun update(id: String, change: (QuestProgress) -> QuestProgress) =
        _quests.update { list ->
            list.map {
                if (it.id != id) {
                    it
                } else {
                    val n = change(it)
                    if (n.current != it.current) n.copy(at = System.currentTimeMillis()) else n
                }
            }
        }
}
