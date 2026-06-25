package com.mopio.ui.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mopio.git.GitController
import com.mopio.git.PatStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class GitState(
    val statusLines: List<String> = emptyList(),
    val log: List<String> = emptyList(),
    val isBusy: Boolean = false,
    val hasSavedPat: Boolean = false
)

class GitViewModel(
    private val repoDir: File,
    private val git: GitController,
    private val patStorage: PatStorage
) : ViewModel() {

    private val _state = MutableStateFlow(GitState())
    val state: StateFlow<GitState> = _state.asStateFlow()

    init {
        refreshStatus()
        _state.update { it.copy(hasSavedPat = patStorage.loadPat() != null) }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val lines = mutableListOf<String>()
            git.status(repoDir).collect { lines.add(it) }
            _state.update { it.copy(statusLines = lines) }
        }
    }

    fun commitAll(message: String, authorName: String, authorEmail: String) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, log = emptyList()) }
            git.addAndCommit(repoDir, message, authorName, authorEmail).collect { line ->
                _state.update { it.copy(log = it.log + line) }
            }
            _state.update { it.copy(isBusy = false) }
            refreshStatus()
        }
    }

    fun push(pat: String? = null, savePat: Boolean = false) {
        val token = pat?.takeIf { it.isNotBlank() } ?: patStorage.loadPat()
        if (token == null) {
            _state.update { it.copy(log = listOf("[ERROR] No GitHub token. Save one in Settings → GitHub.")) }
            return
        }
        if (savePat && pat?.isNotBlank() == true) {
            patStorage.savePat(pat)
            _state.update { it.copy(hasSavedPat = true) }
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, log = emptyList()) }
            git.push(repoDir, token).collect { line ->
                _state.update { it.copy(log = it.log + line) }
            }
            _state.update { it.copy(isBusy = false) }
            refreshStatus()
        }
    }

    fun pull(pat: String? = null, savePat: Boolean = false) {
        val token = pat?.takeIf { it.isNotBlank() } ?: patStorage.loadPat()
        if (savePat && pat?.isNotBlank() == true) {
            patStorage.savePat(pat)
            _state.update { it.copy(hasSavedPat = true) }
        }
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, log = emptyList()) }
            git.pull(repoDir, token).collect { line ->
                _state.update { it.copy(log = it.log + line) }
            }
            _state.update { it.copy(isBusy = false) }
            refreshStatus()
        }
    }

    class Factory(
        private val repoDir: File,
        private val git: GitController,
        private val patStorage: PatStorage
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            GitViewModel(repoDir, git, patStorage) as T
    }
}
