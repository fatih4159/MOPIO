package com.mopio.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mopio.container.ContainerManager
import com.mopio.git.GitController
import com.mopio.platformio.PlatformIoIniParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ProjectSummary(
    val name: String,
    val path: String,
    val envs: List<String>,
    val board: String
)

data class HomeState(
    val recentProjects: List<ProjectSummary> = emptyList(),
    val isCreating: Boolean = false,
    val cloneLog: List<String> = emptyList(),
    val isCloning: Boolean = false
)

class HomeViewModel(
    private val context: Context,
    private val container: ContainerManager,
    private val git: GitController = GitController()
) : ViewModel() {

    private val projectsRoot: File get() = File(context.filesDir, "projects")
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init { scanProjects() }

    private fun scanProjects() {
        viewModelScope.launch {
            val projects = withContext(Dispatchers.IO) {
                projectsRoot.mkdirs()
                projectsRoot.listFiles()
                    ?.filter { it.isDirectory && File(it, "platformio.ini").exists() }
                    ?.map { dir ->
                        val cfg = runCatching {
                            PlatformIoIniParser.parse(File(dir, "platformio.ini"))
                        }.getOrNull()
                        ProjectSummary(
                            name  = dir.name,
                            path  = dir.absolutePath,
                            envs  = cfg?.envs?.map { it.name } ?: emptyList(),
                            board = cfg?.envs?.firstOrNull()?.board ?: "unknown"
                        )
                    }
                    ?.sortedBy { it.name }
                    ?: emptyList()
            }
            _state.update { it.copy(recentProjects = projects) }
        }
    }

    fun createBlinkProject(name: String): File {
        val dir = File(projectsRoot, name).also { it.mkdirs() }
        File(dir, "platformio.ini").writeText(BLINK_INI)
        File(dir, "src").mkdirs()
        File(dir, "src/main.cpp").writeText(BLINK_CPP)
        scanProjects()
        return dir
    }

    fun refresh() = scanProjects()

    fun cloneProject(url: String, name: String, pat: String?): File {
        val dest = File(projectsRoot, name)
        viewModelScope.launch {
            _state.update { it.copy(isCloning = true, cloneLog = emptyList()) }
            git.clone(url, dest, pat.takeIf { it?.isNotBlank() == true }).collect { line ->
                _state.update { it.copy(cloneLog = it.cloneLog + line) }
            }
            _state.update { it.copy(isCloning = false) }
            scanProjects()
        }
        return dest
    }

    class Factory(private val ctx: Context, private val container: ContainerManager) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(ctx, container) as T
    }

    companion object {
        private val BLINK_INI = """
            [env:esp32dev]
            platform = espressif32
            board    = esp32dev
            framework = arduino
            monitor_speed = 115200
        """.trimIndent()

        private val BLINK_CPP = """
            #include <Arduino.h>

            void setup() {
                Serial.begin(115200);
                pinMode(2, OUTPUT);
            }

            void loop() {
                digitalWrite(2, HIGH);
                Serial.println("LED ON");
                delay(500);
                digitalWrite(2, LOW);
                Serial.println("LED OFF");
                delay(500);
            }
        """.trimIndent()
    }
}
