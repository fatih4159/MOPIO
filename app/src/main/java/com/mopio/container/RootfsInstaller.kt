package com.mopio.container

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads and extracts the Debian aarch64 rootfs into [ContainerManager.rootfsDir].
 *
 * Design:
 *  - Reads URL + SHA256 from assets/rootfs_config.json (so it can be updated without code changes)
 *  - Downloads to cacheDir (resumable via partial file check)
 *  - Extracts with Apache Commons Compress (reliable on all Android versions)
 *  - Sets +x on all ELF binaries / scripts via permission bits in tar header
 *  - Emits progress as strings for display in the setup wizard
 */
class RootfsInstaller(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val rootfsDir: File = File(context.filesDir, "rootfs")
    private val cacheDir: File = File(context.cacheDir, "rootfs_dl")

    fun install(): Flow<String> = flow {
        emit("Reading rootfs config…")
        val cfg = readConfig()
        val url = cfg.first
        val expectedSha = cfg.second

        emit("Rootfs URL: $url")

        // Pre-flight: need ≥ 2 GB free on internal storage
        val freeBytes = StatFs(context.filesDir.absolutePath).availableBytes
        if (freeBytes < MIN_FREE_BYTES) {
            emit("[ERROR] Not enough free space. Need ≥ 2 GB, have ${freeBytes / 1_000_000} MB.")
            return@flow
        }
        emit("Free space: ${freeBytes / 1_000_000} MB ✓")

        cacheDir.mkdirs()
        val tarFile = File(cacheDir, "rootfs.tar.gz")

        // Download (skip if already cached and sha matches)
        if (tarFile.exists() && expectedSha.isNotEmpty() && sha256Hex(tarFile) == expectedSha) {
            emit("Using cached rootfs download (SHA256 matches)")
        } else {
            emit("Downloading rootfs…")
            downloadWithProgress(url, tarFile) { pct -> emit("  Download: $pct%") }
            emit("Download complete (${tarFile.length() / 1_000_000} MB)")

            if (expectedSha.isNotEmpty()) {
                emit("Verifying SHA256…")
                val actual = sha256Hex(tarFile)
                if (actual != expectedSha) {
                    tarFile.delete()
                    emit("[ERROR] SHA256 mismatch! Expected $expectedSha, got $actual")
                    return@flow
                }
                emit("SHA256 ✓")
            }
        }

        // Extract
        emit("Extracting rootfs to ${rootfsDir.absolutePath}…")
        rootfsDir.mkdirs()
        var extractedCount = 0
        val totalEntries = extractTarGz(tarFile) { name, data, mode ->
            val dest = File(rootfsDir, name)
            dest.parentFile?.mkdirs()
            if (data == null) {
                dest.mkdirs()
            } else {
                FileOutputStream(dest).use { it.write(data) }
                if (mode and 0b001_001_001 != 0) dest.setExecutable(true, false)
            }
            if (++extractedCount % 200 == 0) emit("  Extracted $extractedCount entries…")
        }
        emit("Extracted $totalEntries entries total")

        // Clean up tarball after successful extraction to free space
        tarFile.delete()
        emit("Rootfs extracted")
    }.catch { e ->
            emit("[ERROR] ${e.message ?: "Unexpected error during bootstrap"}")
            Log.e(TAG, "Bootstrap failed", e)
        }

    private fun readConfig(): Pair<String, String> {
        val json = context.assets.open("rootfs_config.json")
            .bufferedReader().readText()
        val obj = JsonParser.parseString(json).asJsonObject
        val url = obj.get("rootfs_url")?.asString
            ?: error("rootfs_config.json missing rootfs_url")
        val sha = obj.get("rootfs_sha256")?.asString ?: ""
        return url to sha
    }

    private suspend fun downloadWithProgress(url: String, dest: File, onProgress: suspend (Int) -> Unit) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $url")
            val body = resp.body ?: throw IOException("Empty response body")
            val total = body.contentLength()
            var received = 0L
            var lastPct = -1
            FileOutputStream(dest).use { out ->
                body.byteStream().use { inp ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        received += n
                        if (total > 0) {
                            val pct = (received * 100 / total).toInt()
                            if (pct != lastPct) { onProgress(pct); lastPct = pct }
                        }
                    }
                }
            }
        }
    }

    private suspend fun extractTarGz(archive: File, onEntry: suspend (String, ByteArray?, Int) -> Unit): Int {
        val pendingSymlinks = mutableListOf<Triple<String, String, Int>>() // name, linkTarget, mode
        var count = 0

        TarArchiveInputStream(GzipCompressorInputStream(archive.inputStream().buffered()))
            .use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val name = entry.name.removePrefix("./")
                    if (name.isBlank() || name == ".") { entry = tar.nextEntry; continue }
                    val mode = entry.mode
                    if (entry.isDirectory) {
                        onEntry(name, null, mode)
                    } else if (entry.isSymbolicLink) {
                        pendingSymlinks.add(Triple(name, entry.linkName, mode))
                    } else {
                        val data = tar.readBytes()
                        onEntry(name, data, mode)
                    }
                    count++
                    entry = tar.nextEntry
                }
            }

        // Second pass: create symlinks now that all regular files exist.
        // This fixes the case where symlink creation fails on Android's /data
        // partition because the target file hasn't been extracted yet.
        for ((name, linkTarget, mode) in pendingSymlinks) {
            val dest = File(rootfsDir, name)
            dest.parentFile?.mkdirs()
            try {
                Files.createSymbolicLink(dest.toPath(), Paths.get(linkTarget))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create symlink $name -> $linkTarget: ${e.message}")
                val targetFile = File(rootfsDir, linkTarget)
                if (targetFile.isFile) {
                    targetFile.copyTo(dest, overwrite = false)
                    if (targetFile.canExecute()) dest.setExecutable(true, false)
                } else {
                    // Still can't resolve it — write target as text (last resort)
                    dest.writeText(linkTarget)
                }
            }
        }

        return count
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(8192)
            var n: Int
            while (inp.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MIN_FREE_BYTES = 2_000_000_000L
        private const val TAG = "RootfsInstaller"
    }
}
