package com.mopio.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mopio.container.ContainerManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: ContainerManager, onBack: () -> Unit, onPhase0: () -> Unit = {}) {
    var showResetConfirm by remember { mutableStateOf(false) }
    var rootfsInfo by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val rootfsDir = container.rootfsDir
        val size = rootfsDir.walkTopDown().sumOf { it.length() } / 1_000_000
        rootfsInfo = if (container.isBootstrapped) "Installed (≈${size} MB)" else "Not installed"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SettingsSectionHeader("Container")
            }
            item {
                SettingsItem(
                    icon = Icons.Default.Storage,
                    title = "Rootfs",
                    subtitle = rootfsInfo,
                    tappable = false
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Default.DeleteForever,
                    title = "Reset rootfs",
                    subtitle = "Delete the Linux container and re-run bootstrap",
                    destructive = true
                ) { showResetConfirm = true }
            }

            item { Spacer(Modifier.height(8.dp)); SettingsSectionHeader("Build") }
            item {
                SettingsItem(
                    icon = Icons.Default.Speed,
                    title = "ccache",
                    subtitle = "Compiler cache speeds up incremental builds (Phase 3 feature)",
                    tappable = false
                )
            }

            item { Spacer(Modifier.height(8.dp)); SettingsSectionHeader("Developer") }
            item {
                SettingsItem(
                    icon = Icons.Default.BugReport,
                    title = "Hardware Spikes",
                    subtitle = "Run Phase 0 spike tests (proot, USB, RFC2217)",
                ) { onPhase0() }
            }

            item { Spacer(Modifier.height(8.dp)); SettingsSectionHeader("About") }
            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "MOPIO",
                    subtitle = "v0.1.0 — On-device ESP32 IDE\nBased on PlatformIO + proot",
                    tappable = false
                )
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset rootfs?") },
            text = { Text("This deletes the Linux container (~1–2 GB). You'll need to re-bootstrap.") },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        container.rootfsDir.deleteRecursively()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    tappable: Boolean = true,
    onClick: () -> Unit = {}
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (tappable) {
                IconButton(onClick = onClick) { Icon(Icons.Default.ChevronRight, null) }
            }
        }
    }
}
