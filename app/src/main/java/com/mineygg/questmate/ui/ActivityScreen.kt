package com.mineygg.questmate.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineygg.questmate.MainViewModel
import com.mineygg.questmate.data.QuestRecord

@Composable
fun ActivityScreen(vm: MainViewModel, pad: PaddingValues) {
    val history by vm.history.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    var showTech by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = pad.calculateTopPadding() + 12.dp,
            bottom = pad.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "What the app did for you",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (running) item { LiveCard(status) }

        if (history.isEmpty() && !running) item { EmptyState() }

        items(history) { record -> RecordCard(record) }

        if (history.isNotEmpty()) {
            item { TextButton(onClick = vm::clearHistory, modifier = Modifier.fillMaxWidth()) { Text("Clear history") } }
        }

        item { TechnicalCard(showTech, onToggle = { showTech = !showTech }, log = log) }
    }
}

@Composable
private fun LiveCard(status: String) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text("Quests are running", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    status.ifEmpty { "Working…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    AppCard {
        Text("Nothing yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        VSpace(4)
        Text(
            "Completed quests will show up here, with the reward and when it happened.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecordCard(r: QuestRecord) {
    val cs = MaterialTheme.colorScheme
    val extra = LocalExtra.current
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Default.CheckCircle, extra.success)
            HSpace(12)
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    r.reward ?: "Completed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = extra.success
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(r.user, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                VSpace(4)
                Text(
                    DateUtils.getRelativeTimeSpanString(r.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TechnicalCard(expanded: Boolean, onToggle: () -> Unit, log: List<String>) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current

    AppCard {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Technical details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Raw log, for troubleshooting", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = cs.primary)
        }
        if (expanded) {
            VSpace(8)
            if (log.isEmpty()) {
                Text("No log yet.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cs.surfaceVariant)
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        log.takeLast(120).forEach { line ->
                            Text(line, color = cs.onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                    }
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) { Text("Copy log") }
            }
        }
    }
}
