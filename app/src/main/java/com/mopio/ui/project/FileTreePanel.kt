package com.mopio.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/** Recursive file tree displayed in the left panel of the project screen. */
@Composable
fun FileTreePanel(
    root: File,
    onFileClick: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
        items(root.listFiles()?.sorted() ?: emptyList(), key = { it.absolutePath }) { file ->
            TreeNode(file = file, depth = 0, onFileClick = onFileClick)
        }
    }
}

@Composable
private fun TreeNode(file: File, depth: Int, onFileClick: (File) -> Unit) {
    var expanded by remember { mutableStateOf(depth == 0) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (file.isDirectory) expanded = !expanded
                else onFileClick(file)
            }
            .padding(start = (depth * 16 + 8).dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when {
            file.isDirectory && expanded -> Icons.Default.FolderOpen
            file.isDirectory             -> Icons.Default.Folder
            else                         -> Icons.Default.Description
        }
        Icon(
            icon, null,
            modifier = Modifier.size(18.dp),
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(6.dp))
        Text(
            file.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (file.isDirectory && expanded) {
        file.listFiles()?.sorted()?.forEach { child ->
            TreeNode(file = child, depth = depth + 1, onFileClick = onFileClick)
        }
    }
}

private fun List<File>.sorted(): List<File> =
    sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
