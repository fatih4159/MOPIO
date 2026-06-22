package com.mopio.ui.build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mopio.container.ContainerManager
import com.mopio.platformio.AnsiStripper
import com.mopio.ui.theme.ConsoleBackground
import com.mopio.ui.theme.StatusFail
import com.mopio.ui.theme.StatusPass
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildConsoleScreen(
    projectDir: File,
    container: ContainerManager,
    onBack: () -> Unit,
    onFlash: () -> Unit
) {
    val vm: BuildViewModel = viewModel(factory = BuildViewModel.Factory(projectDir, container))
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(state.lines.size) {
        if (autoScroll && state.lines.isNotEmpty()) {
            listState.animateScrollToItem(state.lines.lastIndex)
        }
    }

    // Auto-start build on first enter
    LaunchedEffect(Unit) {
        if (state.status == BuildStatus.IDLE) vm.build()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Build — ${projectDir.name}") },
                actions = {
                    IconButton(onClick = {
                        val text = buildString {
                            state.lines.forEach { appendLine(it.text) }
                            if (state.errors.isNotEmpty()) {
                                appendLine()
                                appendLine("--- Issues ---")
                                state.errors.forEach { d ->
                                    appendLine("${if (d.isError) "ERROR" else "WARNING"} ${d.file}:${d.line}: ${d.message}")
                                }
                            }
                        }
                        clipboard.setText(AnnotatedString(text))
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copy log")
                    }
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            if (autoScroll) Icons.Default.VerticalAlignBottom else Icons.Default.LockOpen,
                            if (autoScroll) "Auto-scroll on" else "Auto-scroll off"
                        )
                    }
                    if (state.status == BuildStatus.BUILDING) {
                        IconButton(onClick = { vm.cancel() }) {
                            Icon(Icons.Default.Stop, "Cancel", tint = StatusFail)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Status banner
            BuildStatusBanner(state.status)

            // Console output
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(ConsoleBackground)
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    items(state.lines) { line ->
                        Text(
                            text = line.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = when (line.type) {
                                AnsiStripper.LineType.ERROR   -> Color(0xFFFF6B6B)
                                AnsiStripper.LineType.WARNING -> Color(0xFFFFD93D)
                                AnsiStripper.LineType.INFO    -> Color(0xFFD4D4D4)
                            }
                        )
                    }
                    if (state.status == BuildStatus.BUILDING) {
                        item { CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(4.dp)) }
                    }
                }
            }

            // Error list
            if (state.errors.isNotEmpty()) {
                HorizontalDivider()
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    Text(
                        "  ${state.errors.size} issue(s)",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(8.dp)
                    )
                    LazyColumn {
                        items(state.errors) { diag ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { /* TODO jump to source */ }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (diag.isError) Icons.Default.Error else Icons.Default.Warning,
                                    null,
                                    tint = if (diag.isError) StatusFail else Color(0xFFFFD93D),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${File(diag.file).name}:${diag.line}  ${diag.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }

            // Bottom action row
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = { vm.build() },
                    enabled = state.status != BuildStatus.BUILDING
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Rebuild")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onFlash,
                    enabled = state.status == BuildStatus.SUCCESS
                ) {
                    Icon(Icons.Default.FlashOn, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Flash")
                }
            }
        }
    }
}

@Composable
private fun BuildStatusBanner(status: BuildStatus) {
    val (bg, text) = when (status) {
        BuildStatus.BUILDING  -> MaterialTheme.colorScheme.primaryContainer to "Building…"
        BuildStatus.SUCCESS   -> StatusPass.copy(alpha = 0.15f) to "✓ Build Successful"
        BuildStatus.FAILED    -> StatusFail.copy(alpha = 0.15f) to "✗ Build Failed"
        BuildStatus.CANCELLED -> Color.Gray.copy(alpha = 0.15f) to "Cancelled"
        BuildStatus.IDLE      -> Color.Transparent to ""
    }
    if (text.isNotEmpty()) {
        Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (status == BuildStatus.BUILDING) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
