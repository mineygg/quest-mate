package com.mineygg.questmate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineygg.questmate.MainViewModel
import com.mineygg.questmate.core.QuestProgress

@Composable
fun HomeScreen(vm: MainViewModel, pad: PaddingValues, onLogin: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val profile by vm.profile.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val quests by vm.quests.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = pad.calculateTopPadding() + 12.dp,
            bottom = pad.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Text("Quest Mate", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }

        item { UpdateBanner(update, onUpdate = vm::startUpdate) }

        // ── Account / run ──
        item {
            AppCard {
                SectionTitle("Discord account")
                if (profile == null) {
                    Text(
                        "Sign in once and Quest Mate does your quests on its own.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant
                    )
                    VSpace(12)
                    Button(
                        onClick = onLogin,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Connect Discord") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(profile?.username.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (running) status.ifBlank { "Working…" } else "Ready",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                        }
                        Pill(if (running) "RUNNING" else "IDLE", if (running) LocalExtra.current.success else cs.onSurfaceVariant)
                    }
                    VSpace(14)
                    if (running) {
                        OutlinedButton(
                            onClick = vm::stop,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error)
                        ) { Text("Stop") }
                    } else {
                        Button(
                            onClick = vm::runNow,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Run quests now", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }

        // ── Live quests ──
        if (quests.isNotEmpty()) {
            item { SectionTitle("This run") }
            items(quests, key = { it.id }) { QuestCard(it) }
        } else if (profile != null && !running) {
            item {
                AppCard {
                    SectionTitle("Last completed")
                    if (history.isEmpty()) {
                        Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    } else {
                        history.take(3).forEach { r ->
                            Text(r.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(r.reward, r.user).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant
                            )
                            VSpace(8)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestCard(q: QuestProgress) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    val color: Color = when (q.status) {
        "COMPLETED" -> extra.success
        "FAILED" -> cs.error
        "WATCHING", "PLAYING", "STARTING" -> cs.primary
        else -> cs.onSurfaceVariant
    }

    // Discord only confirms progress every few seconds (every 30 s for games). In between, the timer keeps running
    // by itself, and each confirmed value gently pulls it back on track, so the bar never jumps.
    val running = q.status == "WATCHING" || q.status == "PLAYING"
    var shown by remember(q.id) { mutableDoubleStateOf(q.current) }
    LaunchedEffect(q.id, q.current, q.at, q.status, q.target) {
        if (!running) {
            shown = if (q.status == "COMPLETED") q.target else q.current
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000.0
            last = now
            val confirmed = q.current + (System.currentTimeMillis() - q.at) / 1000.0
            shown += dt // time keeps moving
            shown += (confirmed - shown) * minOf(1.0, dt * 3) // and drifts toward what Discord confirmed
            shown = shown.coerceIn(0.0, if (q.target > 0) q.target else shown)
        }
    }
    val fraction = if (q.target <= 0.0) 0f else (shown / q.target).coerceIn(0.0, 1.0).toFloat()

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(q.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (q.reward != null) Text(q.reward, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Pill(q.status, color)
        }
        VSpace(10)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = color)
        VSpace(6)
        Text(
            "${duration(shown)} / ${duration(q.target)}",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant
        )
    }
}

private fun duration(seconds: Double): String {
    val s = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
