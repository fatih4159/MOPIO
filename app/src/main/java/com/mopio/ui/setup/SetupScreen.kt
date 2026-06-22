package com.mopio.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(container: ContainerManager, onFinished: () -> Unit) {
    val vm: SetupViewModel = viewModel(factory = SetupViewModel.Factory(container))
    val state by vm.state.collectAsState()

    LaunchedEffect(state.step) {
        if (state.step == SetupStep.DONE) onFinished()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("MOPIO Setup") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Step indicators
            StepIndicator(
                steps = listOf("Welcome", "proot", "Rootfs", "Ready"),
                current = state.step.ordinal
            )
            Spacer(Modifier.height(32.dp))

            when (state.step) {
                SetupStep.WELCOME     -> WelcomeStep { vm.advance() }
                SetupStep.PROOT_CHECK -> ProotCheckStep(state) { vm.advance() }
                SetupStep.BOOTSTRAP   -> BootstrapStep(state)
                SetupStep.DONE        -> DoneStep(onFinished)
            }
        }
    }
}

@Composable
private fun StepIndicator(steps: List<String>, current: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { i, label ->
            val done    = i < current
            val active  = i == current
            val color   = when {
                done   -> StatusPass
                active -> MaterialTheme.colorScheme.primary
                else   -> MaterialTheme.colorScheme.outline
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Surface(
                    color = color,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (done) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        else Text("${i+1}", color = Color.White, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = color)
            }
            if (i < steps.lastIndex) Box(
                Modifier
                    .weight(0.3f)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Memory, null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Welcome to MOPIO", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "MOPIO is a fully offline ESP32 IDE for your Android device.\n\n" +
            "It uses a Linux container (proot + Debian aarch64) to run " +
            "PlatformIO and the Espressif toolchains entirely on-device.\n\n" +
            "First run requires ~2 GB of free storage and a Wi-Fi connection " +
            "to download the toolchain (one-time only).",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Get Started") }
    }
}

@Composable
private fun ProotCheckStep(state: SetupState, onNext: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Checking proot", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (state.prootOk == false && state.step == SetupStep.PROOT_CHECK) {
            // Check still running or not yet started
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Checking proot binary…")
        }
        if (state.prootOk) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, null, tint = StatusPass)
                Spacer(Modifier.width(8.dp))
                Text("proot is executable ✓", color = StatusPass)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Download Rootfs") }
        } else if (state.step == SetupStep.PROOT_CHECK && !state.prootOk) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Close, null, tint = StatusFail)
                Spacer(Modifier.width(8.dp))
                Text("proot not found!", color = StatusFail, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Run scripts/get_proot.sh in the project root to download the static " +
                "aarch64 proot binary, then rebuild the app.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun BootstrapStep(state: SetupState) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.bootstrapLogs.size) {
        if (state.bootstrapLogs.isNotEmpty()) listState.animateScrollToItem(state.bootstrapLogs.lastIndex)
    }
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Installing Rootfs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (state.bootstrapLogs.isNotEmpty()) {
                IconButton(onClick = {
                    val text = buildString {
                        state.bootstrapLogs.forEach { appendLine(it) }
                        state.error?.let { appendLine(); appendLine("Error: $it") }
                    }
                    clipboard.setText(AnnotatedString(text))
                }) {
                    Icon(Icons.Default.ContentCopy, "Copy log")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Downloading Debian aarch64 rootfs and PlatformIO…", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (!state.bootstrapDone) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(ConsoleBackground, MaterialTheme.shapes.small)
                .padding(8.dp)
        ) {
            LazyColumn(state = listState) {
                items(state.bootstrapLogs) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = ConsoleForeground)
                }
            }
        }
        state.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = StatusFail.copy(alpha = 0.1f))) {
                Text(err, modifier = Modifier.padding(12.dp), color = StatusFail)
            }
        }
    }
}

@Composable
private fun DoneStep(onContinue: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Check, null, modifier = Modifier.size(80.dp), tint = StatusPass)
        Spacer(Modifier.height(16.dp))
        Text("Ready!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("The container is set up. Open or create a PlatformIO project to get started.")
        Spacer(Modifier.height(32.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Open Projects") }
    }
}
