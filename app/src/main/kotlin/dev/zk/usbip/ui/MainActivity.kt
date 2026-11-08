package dev.zk.usbip.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zk.usbip.DeviceUi
import dev.zk.usbip.ServerUi
import dev.zk.usbip.UsbIpApp
import dev.zk.usbip.service.UsbIpService

class MainActivity : ComponentActivity() {
    private val app get() = application as UsbIpApp

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        app.maybeAutoStart()
        setContent { AppTheme { MainScreen(app) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        app.refreshDevices() // launched again by USB_DEVICE_ATTACHED
    }

    override fun onResume() {
        super.onResume()
        app.refreshDevices()
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Screen { Main, Settings, Activity }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(app: UsbIpApp) {
    var screen by rememberSaveable { mutableStateOf(Screen.Main) }
    BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.Main -> "USB/IP Server"
                            Screen.Settings -> "Settings"
                            Screen.Activity -> "Activity"
                        },
                    )
                },
                navigationIcon = {
                    if (screen != Screen.Main) {
                        IconButton(onClick = { screen = Screen.Main }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (screen == Screen.Main) {
                        IconButton(onClick = { screen = Screen.Activity }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Activity")
                        }
                        IconButton(onClick = { screen = Screen.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (screen) {
            Screen.Main -> DevicesScreen(app, modifier)
            Screen.Settings -> SettingsScreen(app, modifier)
            Screen.Activity -> ActivityScreen(app, modifier)
        }
    }
}

@Composable
private fun DevicesScreen(app: UsbIpApp, modifier: Modifier) {
    val server by app.server.collectAsStateWithLifecycle()
    val devices by app.devices.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val hasUsbHost = remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ServerCard(server, onToggle = { on -> if (on) UsbIpService.start(ctx) else UsbIpService.stop(ctx) })
        }
        if (!hasUsbHost) {
            item { Notice("This device doesn't report USB host (OTG) support, so USB devices may not be detected.") }
        }
        item { SectionTitle("USB devices") }
        if (devices.isEmpty()) {
            item { Notice("No USB devices. Plug one in with a USB OTG adapter or a powered OTG hub.") }
        }
        items(devices, key = { it.deviceName }) { d ->
            DeviceCard(
                d, server,
                onShare = { app.setShared(d.deviceName, it) },
                onGrant = { app.requestPermission(d.deviceName) },
                onRename = { app.rename(d.deviceName, it) },
            )
        }
    }
}

@Composable
private fun SettingsScreen(app: UsbIpApp, modifier: Modifier) {
    val server by app.server.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SettingsCard(app, server.running) }
        item {
            Notice(
                "USB/IP has no authentication or encryption. Anyone who can reach this phone on the " +
                    "network can attach shared devices, so use a trusted network or set allowed client IPs.",
            )
        }
    }
}

@Composable
private fun ActivityScreen(app: UsbIpApp, modifier: Modifier) {
    val log by app.log.collectAsStateWithLifecycle()
    if (log.isEmpty()) {
        Column(modifier.padding(16.dp)) { Notice("Nothing yet. Connections, attaches and plugged-in devices show up here.") }
        return
    }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(16.dp)) {
        items(log) { line -> Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Notice(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun ServerCard(server: ServerUi, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Server", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (server.running) "Running on port ${server.port}" else "Stopped",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(checked = server.running, onCheckedChange = onToggle)
            }
            if (server.running) {
                Spacer(Modifier.padding(4.dp))
                if (server.addresses.isEmpty()) {
                    Text("No network connection. Join Wi-Fi or turn on the hotspot.", style = MaterialTheme.typography.bodySmall)
                } else {
                    SelectionContainer {
                        Column {
                            server.addresses.forEach {
                                Text("$it:${server.port}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            server.error?.let {
                Spacer(Modifier.padding(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DeviceCard(d: DeviceUi, server: ServerUi, onShare: (Boolean) -> Unit, onGrant: () -> Unit, onRename: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var renaming by remember { mutableStateOf(false) }
    if (renaming) {
        RenameDialog(
            current = d.title,
            automatic = d.automaticName,
            onDismiss = { renaming = false },
            onSave = { renaming = false; onRename(it) },
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { renaming = true }) {
                    Text(d.title, style = MaterialTheme.typography.titleMedium)
                    Text(d.subtitle, style = MaterialTheme.typography.bodySmall)
                    Text("Tap to rename", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                if (d.notShareableReason == null) {
                    Text("Share", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = d.shared, onCheckedChange = onShare)
                }
            }
            when {
                d.notShareableReason != null -> Text(
                    d.notShareableReason,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                d.shared && !d.hasPermission -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Needs USB permission", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onGrant) { Text("Grant") }
                }
                d.attachedBy != null -> Text(
                    "Attached by ${d.attachedBy}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                d.shared && server.running -> {
                    val ip = server.addresses.firstOrNull() ?: "<phone-ip>"
                    val cmd = "usbip attach -r $ip -b ${d.busId}"
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(cmd)) }) { Text("Copy") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(app: UsbIpApp, running: Boolean) {
    var port by remember { mutableStateOf(app.prefs.port.toString()) }
    var allowed by remember { mutableStateOf(app.prefs.allowedClients) }
    var autoStart by remember { mutableStateOf(app.prefs.autoStart) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { v ->
                    port = v.filter(Char::isDigit).take(5)
                    port.toIntOrNull()?.takeIf { it in 1..65535 }?.let { app.prefs.port = it }
                },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = allowed,
                onValueChange = { allowed = it; app.prefs.allowedClients = it },
                label = { Text("Allowed client IPs (blank = any)") },
                placeholder = { Text("192.168.1.20, 192.168.1.21") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start automatically")
                    Text("When the app opens, a shared device is plugged in, or the phone boots", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = autoStart, onCheckedChange = { autoStart = it; app.prefs.autoStart = it })
            }
            if (running) {
                Text("Changes to port and allowed IPs apply the next time the server starts.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, automatic: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(if (current == automatic) "" else current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename device") },
        text = {
            Column {
                Text("Give it a name you'll recognize. Computers that attach it see this name too.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.padding(4.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(automatic) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Leave empty to use: $automatic", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
