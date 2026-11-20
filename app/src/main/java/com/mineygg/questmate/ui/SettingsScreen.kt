package com.mineygg.questmate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mineygg.questmate.AppInfo
import com.mineygg.questmate.MainViewModel
import com.mineygg.questmate.R
import com.mineygg.questmate.core.Battery
import com.mineygg.questmate.core.Updater
import com.mineygg.questmate.data.RunInterval
import com.mineygg.questmate.data.ThemeMode
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    pad: PaddingValues,
    onLogin: () -> Unit,
    onLicense: () -> Unit
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val hasWebhook by vm.hasWebhook.collectAsStateWithLifecycle()
    val update by vm.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    var confirmLogout by remember { mutableStateOf(false) }
    var webhookDialog by remember { mutableStateOf(false) }
    var adjustDialog by remember { mutableStateOf(false) }

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
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }

        // ── Appearance ──
        item {
            AppCard {
                SectionTitle("Appearance")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light", ThemeMode.SYSTEM to "System").forEach { (mode, label) ->
                        FilterChip(
                            selected = s.theme == mode,
                            onClick = { vm.updateSettings { it.copy(theme = mode) } },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        // ── Quests ──
        item {
            AppCard {
                SectionTitle("Quests")
                ToggleRow("Run automatically", "Every ${RunInterval.label(s.intervalHours)}, in the background", s.autoRun) { on ->
                    vm.updateSettings { it.copy(autoRun = on) }
                }
                OutlinedButton(
                    onClick = { adjustDialog = true },
                    enabled = s.autoRun,
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Adjust") }
                VSpace(6)
                HorizontalDivider(color = cs.outlineVariant)
                ToggleRow("Orb quests only", "Skip quests that don't pay Orbs", s.orbsOnly) { on ->
                    vm.updateSettings { it.copy(orbsOnly = on) }
                }
                HorizontalDivider(color = cs.outlineVariant)
                ToggleRow("Notifications", "When a quest is completed and for sign-in alerts", s.notify) { on ->
                    vm.updateSettings { it.copy(notify = on) }
                }
                HorizontalDivider(color = cs.outlineVariant)
                ToggleRow("Detailed log", "Record requests, connection steps and progress. Tokens are never logged", s.verboseLog) { on ->
                    vm.updateSettings { it.copy(verboseLog = on) }
                }
                HorizontalDivider(color = cs.outlineVariant)
                BatteryRow()
            }
        }

        // ── Webhook ──
        item {
            AppCard {
                SectionTitle("Discord webhook")
                Text(
                    if (hasWebhook) {
                        "A webhook is saved. Finished quests are posted to it."
                    } else {
                        "Optional: post finished quests to a channel of your server."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
                VSpace(12)
                OutlinedButton(
                    onClick = { webhookDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(if (hasWebhook) "Replace or remove" else "Add webhook") }
            }
        }

        // ── Updates ──
        item {
            AppCard {
                SectionTitle("Updates")
                ToggleRow("Check for updates", "Installed: ${Updater.currentVersion(context)}", s.checkUpdates) { on ->
                    vm.updateSettings { it.copy(checkUpdates = on) }
                }
                VSpace(4)
                UpdateControls(update, onCheck = { vm.checkUpdates() }, onUpdate = vm::startUpdate)
            }
        }

        // ── Open source ──
        item {
            AppCard {
                SectionTitle("Open source")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_github),
                        contentDescription = null,
                        tint = cs.onSurface,
                        modifier = Modifier.size(30.dp)
                    )
                    HSpace(14)
                    Column(Modifier.weight(1f)) {
                        Text("Quest Mate on GitHub", style = MaterialTheme.typography.bodyLarge)
                        Text("Source code, issues and releases", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                VSpace(12)
                OutlinedButton(
                    onClick = { uriHandler.openUri(AppInfo.REPO_URL) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Open repository") }
                VSpace(4)
                HorizontalDivider(color = cs.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onLicense).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("License", style = MaterialTheme.typography.bodyLarge)
                        Text(AppInfo.LICENSE_NAME, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = cs.onSurfaceVariant)
                }
            }
        }

        // ── Help keep Android open ──
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = cs.error.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, cs.error.copy(alpha = 0.55f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("Help keep Android open", cs.error)
                    Text(
                        "Quest Mate is installed as an APK, outside the Play Store. Google's plans to limit installing apps " +
                            "from outside its store could affect apps like it. These resources explain what is " +
                            "changing and how to help.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface
                    )
                    VSpace(12)
                    Button(
                        onClick = { uriHandler.openUri(AppInfo.KEEP_ANDROID_OPEN_URL) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.error, contentColor = cs.onError)
                    ) { Text("keepandroidopen.org") }
                    VSpace(8)
                    OutlinedButton(
                        onClick = { uriHandler.openUri(AppInfo.KEEP_ANDROID_OPEN_PETITION_URL) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, cs.error.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error)
                    ) { Text("Sign the petition on Change.org") }
                }
            }
        }

        // ── Account ──
        item {
            AppCard {
                SectionTitle("Account")
                Text(
                    profile?.let { "Signed in as ${it.username}" } ?: "Not signed in",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant
                )
                VSpace(12)
                OutlinedButton(
                    onClick = onLogin,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(if (profile != null) "Sign in again" else "Connect Discord") }
                if (profile != null) {
                    TextButton(onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Sign out & delete token", color = cs.error)
                    }
                }
            }
        }

        item {
            Text(
                "Quest Mate ${Updater.currentVersion(context)} · your token is stored encrypted here and only sent to Discord",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign out?") },
            text = { Text("Your saved token will be deleted. Automatic runs stop until you sign in again.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.signOut()
                    confirmLogout = false
                }) { Text("Sign out", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } }
        )
    }

    if (adjustDialog) {
        IntervalDialog(
            current = s.intervalHours,
            onSave = { hours ->
                vm.updateSettings { it.copy(intervalHours = hours) }
                adjustDialog = false
            },
            onCancel = { adjustDialog = false }
        )
    }

    if (webhookDialog) {
        WebhookDialog(vm, hasWebhook, onClose = { webhookDialog = false })
    }
}

/** Slider that snaps to the allowed run intervals; 3 days is marked as recommended. */
@Composable
private fun IntervalDialog(current: Int, onSave: (Int) -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val options = RunInterval.OPTIONS
    var index by remember { mutableStateOf(options.indexOf(current).coerceAtLeast(0).toFloat()) }
    val hours = options[index.roundToInt().coerceIn(0, options.lastIndex)]

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Run every…") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(RunInterval.label(hours), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (hours == RunInterval.RECOMMENDED) {
                        HSpace(10)
                        Pill("RECOMMENDED", LocalExtra.current.success)
                    }
                }
                VSpace(8)
                Slider(
                    value = index,
                    onValueChange = { index = it.roundToInt().toFloat() },
                    valueRange = 0f..options.lastIndex.toFloat(),
                    steps = options.size - 2
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    options.forEach { h ->
                        Text(
                            RunInterval.short(h),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (h == RunInterval.RECOMMENDED) FontWeight.Bold else FontWeight.Normal,
                            color = if (h == hours) cs.primary else cs.onSurfaceVariant
                        )
                    }
                }
                VSpace(10)
                Text(
                    "3 days is recommended: quests last about a week, so this catches each one without checking more than needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(hours) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (hours != RunInterval.RECOMMENDED) {
                    TextButton(onClick = { index = options.indexOf(RunInterval.RECOMMENDED).toFloat() }) { Text("Use recommended") }
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun WebhookDialog(vm: MainViewModel, hasWebhook: Boolean, onClose: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Discord webhook") },
        text = {
            Column {
                Text(
                    "Server Settings → Integrations → Webhooks. The URL is stored encrypted and is never shown again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VSpace(10)
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    singleLine = true,
                    label = { Text(if (hasWebhook) "New webhook URL" else "Webhook URL") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    vm.saveWebhook(url) { problem ->
                        busy = false
                        if (problem == null) onClose() else error = problem
                    }
                }
            ) { Text(if (busy) "Testing…" else "Save") }
        },
        dismissButton = {
            Row {
                if (hasWebhook) {
                    TextButton(onClick = {
                        vm.removeWebhook()
                        onClose()
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") }
            }
        }
    )
}

@Composable
internal fun SectionTitle(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
    )
    VSpace(6)
}

/** Shows whether Android lets the app run in the background freely, and opens the system prompt to allow it. */
@Composable
private fun BatteryRow() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var unrestricted by remember { mutableStateOf(Battery.isUnrestricted(context)) }
    // The prompt is a system screen, so check again when the user comes back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) unrestricted = Battery.isUnrestricted(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Unrestricted battery usage", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (unrestricted) {
                    "Allowed. Runs happen on time while the app is closed."
                } else {
                    "Restricted. Android may delay or stop runs while the app is closed."
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
        if (!unrestricted) {
            HSpace(12)
            OutlinedButton(onClick = { Battery.request(context) }, shape = RoundedCornerShape(12.dp)) { Text("Allow") }
        }
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    dot: Color? = null,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Dot(dot)
            HSpace(12)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
