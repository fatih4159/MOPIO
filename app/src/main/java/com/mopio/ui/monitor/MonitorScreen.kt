package com.mopio.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mopio.ui.theme.ConsoleBackground
import com.mopio.ui.theme.ConsoleForeground
import com.mopio.usb.UsbPortBroker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(usbBroker: UsbPortBroker, onBack: () -> Unit) {
    val vm: MonitorViewModel = viewModel(factory = MonitorViewModel.Factory(usbBroker))
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    var sendText by remember { mutableStateOf("") }

    LaunchedEffect(state.lines.size) {
        if (state.autoScroll && state.lines.isNotEmpty()) {
            listState.animateScrollToItem(state.lines.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        vm.disconnect()
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Serial Monitor") },
                actions = {
                    // Baud rate picker
                    var showBaud by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { showBaud = true }) {
                            Text("${state.baud}")
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(showBaud, { showBaud = false }) {
                            MonitorViewModel.BAUD_RATES.forEach { baud ->
                                DropdownMenuItem(
                                    text = { Text("$baud") },
                                    onClick = { vm.setBaud(baud); showBaud = false }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { vm.toggleTimestamps() }) {
                        Icon(
                            Icons.Default.Schedule, "Timestamps",
                            tint = if (state.showTimestamps) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { vm.toggleHexView() }) {
                        Icon(
                            Icons.Default.Code, "Hex view",
                            tint = if (state.hexView) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { vm.toggleAutoScroll() }) {
                        Icon(
                            Icons.Default.VerticalAlignBottom, "Auto-scroll",
                            tint = if (state.autoScroll) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { vm.clear() }) { Icon(Icons.Default.ClearAll, "Clear") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Connection bar
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(state.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (state.status) {
                        MonitorStatus.DISCONNECTED -> "Disconnected"
                        MonitorStatus.CONNECTING   -> "Connecting…"
                        MonitorStatus.CONNECTED    -> "Connected @ ${state.baud} baud"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                if (state.status == MonitorStatus.CONNECTED) {
                    OutlinedButton(
                        onClick = { vm.disconnect() },
                        modifier = Modifier.height(32.dp)
                    ) { Text("Disconnect", fontSize = 12.sp) }
                } else {
                    Button(
                        onClick = { vm.connect() },
                        enabled = state.status == MonitorStatus.DISCONNECTED,
                        modifier = Modifier.height(32.dp)
                    ) { Text("Connect", fontSize = 12.sp) }
                }
            }

            state.error?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall)
            }

            // Output log
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(ConsoleBackground)
                    .padding(8.dp)
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.lines) { line ->
                        Row {
                            if (state.showTimestamps) {
                                Text(
                                    line.timestamp + "  ",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = ConsoleForeground.copy(alpha = 0.5f)
                                )
                            }
                            Text(
                                line.text,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = ConsoleForeground,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            // Send bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = sendText,
                    onValueChange = { sendText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Send…", fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { vm.send(sendText); sendText = "" },
                    enabled = state.status == MonitorStatus.CONNECTED && sendText.isNotEmpty()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: MonitorStatus) {
    val color = when (status) {
        MonitorStatus.CONNECTED    -> com.mopio.ui.theme.StatusPass
        MonitorStatus.CONNECTING   -> MaterialTheme.colorScheme.tertiary
        MonitorStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Surface(color = color, shape = MaterialTheme.shapes.small, modifier = Modifier.size(10.dp)) {}
}
