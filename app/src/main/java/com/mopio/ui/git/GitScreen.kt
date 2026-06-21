package com.mopio.ui.git

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mopio.git.GitController
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(repoDir: File, gitCtrl: GitController, onBack: () -> Unit) {
    val vm: GitViewModel = viewModel(factory = GitViewModel.Factory(repoDir, gitCtrl))
    val state by vm.state.collectAsState()
    var showCommitDialog by remember { mutableStateOf(false) }
    var showPushDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Git — ${repoDir.name}") },
                actions = {
                    IconButton(onClick = { vm.refreshStatus() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            // Status section
            Text("Working tree", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                if (state.statusLines.isEmpty()) {
                    Text("  (clean)", modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(state.statusLines) { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Actions
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showCommitDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isBusy
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Commit All")
                }
                OutlinedButton(
                    onClick = { showPushDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isBusy
                ) {
                    Icon(Icons.Default.Upload, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Push")
                }
            }

            // Operation log
            if (state.log.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Log", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        state.log.forEach { line ->
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }

            if (state.isBusy) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Working…")
                }
            }
        }
    }

    if (showCommitDialog) {
        CommitDialog(
            onConfirm = { msg, name, email ->
                showCommitDialog = false
                vm.commitAll(msg, name, email)
            },
            onDismiss = { showCommitDialog = false }
        )
    }

    if (showPushDialog) {
        PushDialog(
            onConfirm = { pat -> showPushDialog = false; vm.push(pat) },
            onDismiss = { showPushDialog = false }
        )
    }
}

@Composable
private fun CommitDialog(onConfirm: (String, String, String) -> Unit, onDismiss: () -> Unit) {
    var message by remember { mutableStateOf("") }
    var name    by remember { mutableStateOf("") }
    var email   by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commit") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = message, onValueChange = { message = it },
                    label = { Text("Commit message") }, singleLine = false, minLines = 2)
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Author name") }, singleLine = true)
                OutlinedTextField(value = email, onValueChange = { email = it },
                    label = { Text("Author email") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(message, name, email) },
                enabled = message.isNotBlank() && name.isNotBlank() && email.isNotBlank()
            ) { Text("Commit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PushDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var pat by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Push to GitHub") },
        text = {
            Column {
                Text("Enter a GitHub Personal Access Token (PAT) with repo scope.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = pat, onValueChange = { pat = it },
                    label = { Text("GitHub PAT") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(pat) }, enabled = pat.isNotBlank()) { Text("Push") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
