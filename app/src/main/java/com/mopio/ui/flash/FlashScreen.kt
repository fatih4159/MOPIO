package com.mopio.ui.flash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mopio.container.ContainerManager
import com.mopio.ui.theme.ConsoleBackground
import com.mopio.ui.theme.ConsoleForeground
import com.mopio.ui.theme.StatusFail
import com.mopio.ui.theme.StatusPass
import com.mopio.usb.UsbPortBroker
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashScreen(
    projectDir: File,
    container: ContainerManager,
    usbBroker: UsbPortBroker,
    onBack: () -> Unit,
    onMonitor: () -> Unit
) {
    val vm: FlashViewModel = viewModel(
        factory = FlashViewModel.Factory(projectDir, container, usbBroker)
    )
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) listState.animateScrollToItem(state.logs.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Flash — ${projectDir.name}") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {

            // Status card
            FlashStatusCard(state)
            Spacer(Modifier.height(16.dp))

            // USB connect tip
            if (state.status == FlashStatus.IDLE || state.status == FlashStatus.FAILED) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Usb, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Connect ESP32 via USB-OTG cable", style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text("CP2102, CH340, FTDI, or native USB-CDC supported.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Console log
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(ConsoleBackground, MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                if (state.logs.isEmpty()) {
                    Text("Flash log will appear here…", color = ConsoleForeground.copy(alpha = 0.4f),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    LazyColumn(state = listState) {
                        items(state.logs) { line ->
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                                color = ConsoleForeground, lineHeight = 15.sp)
                        }
                    }
                }
            }

            // Error hint
            state.error?.let { err ->
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = StatusFail.copy(alpha = 0.1f))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = StatusFail, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(err, style = MaterialTheme.typography.bodySmall, color = StatusFail)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.status == FlashStatus.FLASHING || state.status == FlashStatus.WAITING_PERMISSION) {
                    OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel")
                    }
                } else {
                    Button(
                        onClick = { vm.flash() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FlashOn, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (state.status == FlashStatus.SUCCESS) "Re-Flash" else "Flash")
                    }
                }
                if (state.status == FlashStatus.SUCCESS) {
                    Button(onClick = onMonitor, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Terminal, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Monitor")
                    }
                }
            }
        }
    }
}

@Composable
private fun FlashStatusCard(state: FlashState) {
    val (icon, tint, label) = when (state.status) {
        FlashStatus.IDLE              -> Triple(Icons.Default.Usb,         MaterialTheme.colorScheme.outline, "Ready to flash")
        FlashStatus.WAITING_PERMISSION -> Triple(Icons.Default.Lock,        MaterialTheme.colorScheme.tertiary, "Waiting for USB permission…")
        FlashStatus.FLASHING          -> Triple(Icons.Default.FlashOn,     MaterialTheme.colorScheme.primary, "Flashing…")
        FlashStatus.SUCCESS           -> Triple(Icons.Default.CheckCircle, StatusPass, "Flash complete!")
        FlashStatus.FAILED            -> Triple(Icons.Default.Error,       StatusFail, "Flash failed")
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.status == FlashStatus.FLASHING) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            } else {
                Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.titleSmall)
                if (state.connectedDeviceName.isNotEmpty()) {
                    Text(state.connectedDeviceName, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
