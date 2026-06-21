package com.mopio.ui.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mopio.platformio.PlatformIoIniParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class OpenTab(val file: File, val content: String, val dirty: Boolean = false)

data class ProjectState(
    val projectDir: File,
    val openTabs: List<OpenTab> = emptyList(),
    val activeTabIndex: Int = -1,
    val envNames: List<String> = emptyList(),
    val selectedEnv: String = ""
)

class ProjectViewModel(projectDir: File) : ViewModel() {

    private val _state = MutableStateFlow(
        ProjectState(projectDir = projectDir)
    )
    val state: StateFlow<ProjectState> = _state.asStateFlow()

    init {
        val ini = File(projectDir, "platformio.ini")
        if (ini.exists()) {
            val cfg = runCatching { PlatformIoIniParser.parse(ini) }.getOrNull()
            val envs = cfg?.envs?.map { it.name } ?: emptyList()
            _state.update { it.copy(envNames = envs, selectedEnv = envs.firstOrNull() ?: "") }
        }
    }

    fun openFile(file: File) {
        val existing = _state.value.openTabs.indexOfFirst { it.file == file }
        if (existing >= 0) {
            _state.update { it.copy(activeTabIndex = existing) }
            return
        }
        val content = runCatching { file.readText() }.getOrDefault("")
        _state.update { s ->
            val newTabs = s.openTabs + OpenTab(file, content)
            s.copy(openTabs = newTabs, activeTabIndex = newTabs.lastIndex)
        }
    }

    fun closeTab(index: Int) {
        _state.update { s ->
            val newTabs = s.openTabs.toMutableList().also { it.removeAt(index) }
            val newActive = when {
                newTabs.isEmpty() -> -1
                index >= newTabs.size -> newTabs.lastIndex
                else -> index
            }
            s.copy(openTabs = newTabs, activeTabIndex = newActive)
        }
    }

    fun selectTab(index: Int) = _state.update { it.copy(activeTabIndex = index) }

    fun updateContent(index: Int, text: String) {
        _state.update { s ->
            val newTabs = s.openTabs.toMutableList()
            newTabs[index] = newTabs[index].copy(content = text, dirty = true)
            s.copy(openTabs = newTabs)
        }
    }

    fun saveCurrentTab() {
        val s = _state.value
        val idx = s.activeTabIndex
        if (idx < 0 || idx >= s.openTabs.size) return
        val tab = s.openTabs[idx]
        runCatching { tab.file.writeText(tab.content) }
        _state.update { st ->
            val newTabs = st.openTabs.toMutableList()
            newTabs[idx] = newTabs[idx].copy(dirty = false)
            st.copy(openTabs = newTabs)
        }
    }

    fun selectEnv(env: String) = _state.update { it.copy(selectedEnv = env) }

    class Factory(private val projectDir: File) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ProjectViewModel(projectDir) as T
    }
}
