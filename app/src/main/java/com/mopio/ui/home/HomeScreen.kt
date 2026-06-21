package com.mopio.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mopio.container.ContainerManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: ContainerManager,
    onOpenProject: (String) -> Unit,
    onSettings: () -> Unit
) {
    val ctx = LocalContext.current
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(ctx, container))
    val state by vm.state.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MOPIO") },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(Icons.Default.Add, "New project")
            }
        }
    ) { padding ->
        if (state.recentProjects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("No projects yet", style = MaterialTheme.typography.titleMedium)
                    Text("Tap + to create a new blink project", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Text("Projects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(state.recentProjects) { proj ->
                    ProjectCard(proj) { onOpenProject(proj.path) }
                }
            }
        }
    }

    if (showNewDialog) {
        NewProjectDialog(
            onConfirm = { name ->
                showNewDialog = false
                val dir = vm.createBlinkProject(name)
                onOpenProject(dir.absolutePath)
            },
            onDismiss = { showNewDialog = false }
        )
    }
}

@Composable
private fun ProjectCard(proj: ProjectSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(proj.name, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        if (proj.board.isNotEmpty()) append(proj.board)
                        if (proj.envs.isNotEmpty()) append(" · envs: ${proj.envs.joinToString()}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NewProjectDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("blink") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            Column {
                Text("Creates a minimal ESP32 Arduino blink project.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' } },
                    label = { Text("Project name") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
