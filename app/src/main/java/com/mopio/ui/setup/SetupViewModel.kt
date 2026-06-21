package com.mopio.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mopio.container.ContainerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStep { WELCOME, PROOT_CHECK, BOOTSTRAP, DONE }

data class SetupState(
    val step: SetupStep = SetupStep.WELCOME,
    val prootOk: Boolean = false,
    val bootstrapLogs: List<String> = emptyList(),
    val bootstrapDone: Boolean = false,
    val error: String? = null
)

class SetupViewModel(private val container: ContainerManager) : ViewModel() {

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()

    fun advance() {
        when (_state.value.step) {
            SetupStep.WELCOME     -> checkProot()
            SetupStep.PROOT_CHECK -> startBootstrap()
            SetupStep.BOOTSTRAP   -> {} // wait for bootstrap to finish
            SetupStep.DONE        -> {}
        }
    }

    private fun checkProot() {
        _state.update { it.copy(step = SetupStep.PROOT_CHECK) }
        viewModelScope.launch {
            val ok = container.checkProotExecutable()
            _state.update { it.copy(prootOk = ok) }
        }
    }

    fun startBootstrap() {
        _state.update { it.copy(step = SetupStep.BOOTSTRAP, bootstrapLogs = emptyList(), error = null) }
        viewModelScope.launch {
            container.bootstrap().collect { line ->
                _state.update { it.copy(bootstrapLogs = it.bootstrapLogs + line) }
                if (line == "Bootstrap complete!") {
                    _state.update { it.copy(bootstrapDone = true, step = SetupStep.DONE) }
                }
                if (line.startsWith("[ERROR]")) {
                    _state.update { it.copy(error = line) }
                }
            }
        }
    }

    class Factory(private val container: ContainerManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SetupViewModel(container) as T
    }
}
