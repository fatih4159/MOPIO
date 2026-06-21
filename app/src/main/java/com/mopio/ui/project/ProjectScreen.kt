package com.mopio.ui.project

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mopio.container.ContainerManager
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    projectDir: File,
    container: ContainerManager,
    onBuild: () -> Unit,
    onFlash: () -> Unit,
    onMonitor: () -> Unit,
    onGit: () -> Unit,
    onBack: () -> Unit
) {
    val vm: ProjectViewModel = viewModel(factory = ProjectViewModel.Factory(projectDir))
    val state by vm.state.collectAsState()
    var showFileTree by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = { Text(projectDir.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { vm.saveCurrentTab() }) {
                        Icon(Icons.Default.Save, "Save")
                    }
                    IconButton(onClick = { showFileTree = !showFileTree }) {
                        Icon(Icons.Default.AccountTree, "Toggle file tree")
                    }
                    IconButton(onClick = onGit) { Icon(Icons.Default.Share, "Git") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            BottomAppBar {
                // Env selector
                if (state.envNames.isNotEmpty()) {
                    var showEnvMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { showEnvMenu = true }) {
                            Text(state.selectedEnv.ifEmpty { "env" })
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(showEnvMenu, { showEnvMenu = false }) {
                            state.envNames.forEach { env ->
                                DropdownMenuItem(
                                    text = { Text(env) },
                                    onClick = { vm.selectEnv(env); showEnvMenu = false }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onBuild)   { Icon(Icons.Default.Build,   "Build") }
                IconButton(onClick = onFlash)   { Icon(Icons.Default.FlashOn, "Flash") }
                IconButton(onClick = onMonitor) { Icon(Icons.Default.Terminal, "Monitor") }
            }
        }
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            // File tree
            if (showFileTree) {
                Surface(
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 1.dp
                ) {
                    FileTreePanel(
                        root = projectDir,
                        onFileClick = { vm.openFile(it) }
                    )
                }
                VerticalDivider()
            }

            // Editor area
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Tab bar
                if (state.openTabs.isNotEmpty()) {
                    ScrollableTabRow(
                        selectedTabIndex = state.activeTabIndex.coerceAtLeast(0),
                        edgePadding = 0.dp
                    ) {
                        state.openTabs.forEachIndexed { i, tab ->
                            Tab(
                                selected = i == state.activeTabIndex,
                                onClick = { vm.selectTab(i) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        tab.file.name + if (tab.dirty) " •" else "",
                                        fontWeight = if (i == state.activeTabIndex) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { vm.closeTab(i) },
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Icon(Icons.Default.Close, "Close", modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }

                    val activeTab = state.openTabs.getOrNull(state.activeTabIndex)
                    if (activeTab != null) {
                        EditorPanel(
                            content = activeTab.content,
                            onContentChange = { vm.updateContent(state.activeTabIndex, it) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text("Open a file from the tree →", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
