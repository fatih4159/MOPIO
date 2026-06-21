package com.mopio.phase0

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mopio.container.ContainerManager
import com.mopio.ui.theme.ConsoleBackground
import com.mopio.ui.theme.ConsoleForeground
import com.mopio.ui.theme.StatusFail
import com.mopio.ui.theme.StatusPass
import com.mopio.usb.UsbPortBroker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Phase0Screen() {
    val ctx = LocalContext.current
    val container = remember { ContainerManager(ctx) }
    val usbBroker = remember { UsbPortBroker(ctx) }
    val vm: Phase0ViewModel = viewModel(
        factory = Phase0ViewModel.Factory(ctx, container, usbBroker)
    )
    val spikes by vm.spikes.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MOPIO — Phase 0 Spikes") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    "Run all four spikes on real hardware before proceeding to Phase 1.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.runAll() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = spikes.none { it.status == SpikeStatus.RUNNING }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Run All Sequentially")
                }
            }

            items(spikes.indices.toList()) { i ->
                SpikeCard(spike = spikes[i], onRun = { vm.runSpike(i) })
            }
        }
    }
}

@Composable
private fun SpikeCard(spike: SpikeState, onRun: () -> Unit) {
    val logListState = rememberLazyListState()
    LaunchedEffect(spike.logs.size) {
        if (spike.logs.isNotEmpty()) logListState.animateScrollToItem(spike.logs.lastIndex)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpikeStatusIcon(spike.status)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(spike.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        spike.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onRun,
                    enabled = spike.status != SpikeStatus.RUNNING,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(if (spike.status == SpikeStatus.RUNNING) "…" else "Run")
                }
            }

            // Console output
            if (spike.logs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 180.dp)
                        .background(ConsoleBackground, MaterialTheme.shapes.small)
                        .padding(8.dp)
                ) {
                    LazyColumn(state = logListState) {
                        items(spike.logs) { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = ConsoleForeground,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpikeStatusIcon(status: SpikeStatus) {
    when (status) {
        SpikeStatus.PENDING -> Icon(
            Icons.Outlined.Circle, "pending",
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(24.dp)
        )
        SpikeStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.5.dp
        )
        SpikeStatus.PASS -> Icon(
            Icons.Default.CheckCircle, "pass",
            tint = StatusPass,
            modifier = Modifier.size(24.dp)
        )
        SpikeStatus.FAIL -> Icon(
            Icons.Default.Cancel, "fail",
            tint = StatusFail,
            modifier = Modifier.size(24.dp)
        )
    }
}
