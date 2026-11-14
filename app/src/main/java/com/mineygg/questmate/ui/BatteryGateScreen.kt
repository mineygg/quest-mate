package com.mineygg.questmate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown instead of the whole app while Android restricts its battery use. Quest Mate keeps a connection to
 * Discord open in the background, which a restricted app can't do, so nothing runs until this is allowed.
 */
@Composable
fun BatteryGateScreen(onAllow: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(cs.background).safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconBadge(Icons.Default.Warning, cs.primary)
        VSpace(20)
        Text(
            "Allow unrestricted battery use",
            style = MaterialTheme.typography.titleLarge,
            color = cs.onBackground,
            textAlign = TextAlign.Center
        )
        VSpace(12)
        Text(
            "Quest Mate keeps a connection to Discord open in the background while it works through your quests. " +
                "When Android restricts the app's battery use it cuts that connection and stalls the quests, " +
                "so nothing connects or runs until this is allowed.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        VSpace(24)
        Button(onClick = onAllow, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Allow")
        }
        VSpace(12)
        Text(
            "If no prompt appears, open the app's settings, then Battery, and choose Unrestricted.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
