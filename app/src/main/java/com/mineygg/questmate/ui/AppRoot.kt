package com.mineygg.questmate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mineygg.questmate.MainViewModel
import com.mineygg.questmate.core.Battery

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Activity("Activity", Icons.Default.Info),
    Settings("Settings", Icons.Default.Settings)
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var showLogin by rememberSaveable { mutableStateOf(false) }
    var showLicense by rememberSaveable { mutableStateOf(false) }

    // Keeps each screen's saved state (scroll position, toggles) while another screen is shown in its place.
    val stateHolder = rememberSaveableStateHolder()

    // The system prompt and the settings page are other screens, so check again every time the app comes back.
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(Battery.isUnrestricted(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) unrestricted = Battery.isUnrestricted(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = showLogin) { showLogin = false }
    BackHandler(enabled = showLicense) { showLicense = false }

    // Nothing else is shown (and nothing connects or runs) while Android restricts the app's battery use.
    if (!unrestricted) {
        BatteryGateScreen(onAllow = { Battery.request(context) })
        return
    }

    if (showLicense) {
        LicenseScreen(onClose = { showLicense = false })
        return
    }

    if (showLogin) {
        LoginScreen(vm, onClose = { showLogin = false })
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    ) { pad ->
        stateHolder.SaveableStateProvider(tab) {
            when (tab) {
                Tab.Home -> HomeScreen(vm, pad, onLogin = { showLogin = true })
                Tab.Activity -> ActivityScreen(vm, pad)
                Tab.Settings -> SettingsScreen(vm, pad, onLogin = { showLogin = true }, onLicense = { showLicense = true })
            }
        }
    }
}
