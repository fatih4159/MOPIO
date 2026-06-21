package com.mopio.ui.build

import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.mopio.MopioApplication
import com.mopio.R
import com.mopio.container.ContainerManager
import com.mopio.platformio.AnsiStripper
import com.mopio.platformio.BuildErrorParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class BuildStatus { IDLE, BUILDING, SUCCESS, FAILED, CANCELLED }

data class ConsoleLine(val text: String, val type: AnsiStripper.LineType)

data class BuildState(
    val status: BuildStatus = BuildStatus.IDLE,
    val lines: List<ConsoleLine> = emptyList(),
    val errors: List<BuildErrorParser.BuildDiagnostic> = emptyList(),
    val selectedEnv: String = ""
)

class BuildViewModel(
    app: Application,
    private val projectDir: File,
    private val container: ContainerManager
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(BuildState())
    val state: StateFlow<BuildState> = _state.asStateFlow()
    private var buildJob: Job? = null
    private val nm = app.getSystemService(NotificationManager::class.java)

    fun build(env: String? = null) {
        if (_state.value.status == BuildStatus.BUILDING) return
        _state.update { it.copy(status = BuildStatus.BUILDING, lines = emptyList(), errors = emptyList(),
            selectedEnv = env ?: "") }
        postNotification(getApplication<Application>().getString(R.string.notification_title_building), ongoing = true)

        buildJob = viewModelScope.launch {
            val collectedLines = mutableListOf<String>()
            var lastExit = -1

            container.pioBuild(projectDir, env).collect { raw ->
                val ann = AnsiStripper.annotate(raw)
                collectedLines.add(ann.text)
                _state.update { it.copy(lines = it.lines + ConsoleLine(ann.text, ann.type)) }
                if (raw.startsWith("[EXIT ")) {
                    lastExit = raw.removePrefix("[EXIT ").trimEnd(']').toIntOrNull() ?: -1
                }
            }

            val diags = BuildErrorParser.parseLines(collectedLines)
            val finalStatus = when {
                lastExit == 0 -> BuildStatus.SUCCESS
                buildJob?.isCancelled == true -> BuildStatus.CANCELLED
                else -> BuildStatus.FAILED
            }
            _state.update { it.copy(status = finalStatus, errors = diags) }
            val doneText = when (finalStatus) {
                BuildStatus.SUCCESS   -> "Build successful — ${projectDir.name}"
                BuildStatus.FAILED    -> "Build failed — ${projectDir.name}"
                BuildStatus.CANCELLED -> "Build cancelled"
                else -> ""
            }
            if (doneText.isNotEmpty()) postNotification(doneText, ongoing = false)
        }
    }

    fun cancel() {
        buildJob?.cancel()
        _state.update { it.copy(status = BuildStatus.CANCELLED) }
        nm.cancel(NOTIF_ID)
    }

    private fun postNotification(text: String, ongoing: Boolean) {
        val app = getApplication<Application>()
        val n = NotificationCompat.Builder(app, MopioApplication.CHANNEL_BUILD_FLASH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(app.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    override fun onCleared() {
        super.onCleared()
        nm.cancel(NOTIF_ID)
    }

    class Factory(private val projectDir: File, private val container: ContainerManager) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val app = checkNotNull(extras[APPLICATION_KEY])
            return BuildViewModel(app, projectDir, container) as T
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
    }
}
