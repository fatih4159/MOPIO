package com.mopio.git

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * Pure-JVM Git operations via JGit.
 * Auth uses a GitHub Personal Access Token (PAT) stored in EncryptedSharedPreferences (Phase 6).
 * No shell dependency — runs on the host Android process, no container needed.
 */
class GitController {

    fun clone(url: String, destDir: File, pat: String? = null): Flow<String> = flow {
        emit("Cloning $url")
        emit("→ ${destDir.absolutePath}")
        try {
            val creds = if (pat != null)
                UsernamePasswordCredentialsProvider(pat, "") else null
            // Collect progress into a list (JGit monitor callbacks are not suspendable)
            val progress = mutableListOf<String>()
            Git.cloneRepository()
                .setURI(url)
                .setDirectory(destDir)
                .setCloneAllBranches(false)
                .apply { if (creds != null) setCredentialsProvider(creds) }
                .setProgressMonitor(LoggingMonitor { progress.add(it) })
                .call()
                .close()
            progress.forEach { emit(it) }
            emit("Clone complete.")
        } catch (e: TransportException) {
            emit("[ERROR] Transport: ${e.message}")
        } catch (e: Exception) {
            emit("[ERROR] ${e.javaClass.simpleName}: ${e.message}")
        }
    }.flowOn(Dispatchers.IO)

    fun addAndCommit(
        repoDir: File,
        message: String,
        authorName: String,
        authorEmail: String
    ): Flow<String> = flow {
        try {
            Git.open(repoDir).use { git ->
                git.add().addFilepattern(".").call()
                val rev = git.commit()
                    .setMessage(message)
                    .setAuthor(authorName, authorEmail)
                    .call()
                emit("Committed ${rev.name.take(8)}: $message")
            }
        } catch (e: Exception) {
            emit("[ERROR] ${e.message}")
        }
    }.flowOn(Dispatchers.IO)

    fun push(repoDir: File, pat: String): Flow<String> = flow {
        try {
            Git.open(repoDir).use { git ->
                val creds = UsernamePasswordCredentialsProvider(pat, "")
                git.push().setCredentialsProvider(creds).call().forEach { r ->
                    emit("Push ${r.uri}: ${r.messages}")
                }
            }
        } catch (e: Exception) {
            emit("[ERROR] ${e.message}")
        }
    }.flowOn(Dispatchers.IO)

    fun pull(repoDir: File, pat: String? = null): Flow<String> = flow {
        try {
            Git.open(repoDir).use { git ->
                val creds = if (pat != null) UsernamePasswordCredentialsProvider(pat, "") else null
                val result = git.pull().apply {
                    if (creds != null) setCredentialsProvider(creds)
                }.call()
                emit(if (result.isSuccessful) "Pull successful." else "Pull failed: ${result.mergeResult?.mergeStatus}")
            }
        } catch (e: Exception) {
            emit("[ERROR] ${e.message}")
        }
    }.flowOn(Dispatchers.IO)

    fun listBranches(repoDir: File): Flow<String> = flow {
        try {
            Git.open(repoDir).use { git ->
                git.branchList().call().forEach { ref ->
                    emit(ref.name.removePrefix("refs/heads/"))
                }
            }
        } catch (e: Exception) {
            emit("[ERROR] ${e.message}")
        }
    }.flowOn(Dispatchers.IO)

    fun status(repoDir: File): Flow<String> = flow {
        try {
            Git.open(repoDir).use { git ->
                val s = git.status().call()
                if (s.isClean) { emit("Working tree clean"); return@flow }
                s.added.forEach    { emit("A  $it") }
                s.changed.forEach  { emit("M  $it") }
                s.removed.forEach  { emit("D  $it") }
                s.untracked.forEach{ emit("?? $it") }
                s.modified.forEach { emit("M  $it (unstaged)") }
            }
        } catch (e: Exception) {
            emit("[ERROR] ${e.message}")
        }
    }.flowOn(Dispatchers.IO)

    private class LoggingMonitor(private val onLine: (String) -> Unit) : ProgressMonitor {
        private var task = ""
        private var done = 0
        private var total = 0
        override fun start(totalTasks: Int) {}
        override fun beginTask(title: String, totalWork: Int) {
            task = title; total = totalWork; done = 0
            onLine("$title (0/$totalWork)")
        }
        override fun update(completed: Int) {
            done += completed
            if (total > 0 && done % maxOf(1, total / 10) == 0) onLine("$task ($done/$total)")
        }
        override fun endTask() { onLine("$task done") }
        override fun isCancelled(): Boolean = false
        override fun showDuration(enabled: Boolean) {}
    }
}
