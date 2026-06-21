package com.mopio.container

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Manages the proot-based glibc Linux container used for all PlatformIO operations.
 *
 * Key design constraints (from prompt.md §2):
 *  - proot ships as libproot.so in nativeLibraryDir — the only dir Android allows exec from without root.
 *  - Guest binaries are NEVER exec'd directly; always launched via proot's loader.
 *  - The project dir is bind-mounted so build artefacts are visible from the host app.
 *
 * Phase 0: exec() and checkProotExecutable() are the primary entry points.
 * Phase 1: bootstrap() downloads and extracts the full Debian aarch64 rootfs.
 */
class ContainerManager(private val context: Context) {

    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    val platformioDir: File get() = File(context.filesDir, "platformio")

    private val prootBin: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    val isBootstrapped: Boolean
        get() = File(rootfsDir, "bin/bash").exists() && File(rootfsDir, "usr/bin/python3").exists()

    val isProotAvailable: Boolean
        get() = prootBin.exists()

    // ── Command execution ────────────────────────────────────────────────────

    /**
     * Run a shell command inside the proot container.
     * Emits stdout+stderr lines followed by "[EXIT <code>]".
     *
     * Environment mirrors what PlatformIO expects (HOME, PATH, PLATFORMIO_CORE_DIR).
     * The project dir, if supplied, is bind-mounted into /workspace inside the container.
     */
    fun exec(
        cmd: String,
        projectDir: File? = null,
        extraEnv: Map<String, String> = emptyMap()
    ): Flow<String> = flow {
        if (!isProotAvailable) {
            emit("[ERROR] proot not found at ${prootBin.absolutePath}")
            emit("[ERROR] Build a static aarch64 proot and place it as jniLibs/arm64-v8a/libproot.so")
            emit("[EXIT 127]")
            return@flow
        }
        if (!isBootstrapped) {
            emit("[ERROR] Rootfs not bootstrapped. Run bootstrap() first.")
            emit("[EXIT 127]")
            return@flow
        }

        platformioDir.mkdirs()

        val args = buildList {
            add(prootBin.absolutePath)
            add("--rootfs=${rootfsDir.absolutePath}")
            // Core filesystem bindings
            add("--bind=/proc")
            add("--bind=/dev")
            add("--bind=/sys")
            // PlatformIO data dir bind-mounted at a fixed in-container path
            add("--bind=${platformioDir.absolutePath}:/data/platformio")
            // Optional: bind the host project directory into the container
            if (projectDir != null && projectDir.exists()) {
                add("--bind=${projectDir.absolutePath}:/workspace")
            }
            add("/bin/sh")
            add("-c")
            add(cmd)
        }

        val env = buildMap {
            put("HOME", "/root")
            put("PATH", "/root/.platformio/penv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("PLATFORMIO_CORE_DIR", "/data/platformio")
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            putAll(extraEnv)
        }

        Log.d(TAG, "exec: ${args.joinToString(" ")}")
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .also { it.environment().putAll(env) }
            .start()

        try {
            process.inputStream.bufferedReader().forEachLine { line ->
                emit(line)
                Log.v(TAG, line)
            }
            val code = process.waitFor()
            emit("[EXIT $code]")
        } catch (e: IOException) {
            emit("[ERROR] IO: ${e.message}")
        } finally {
            process.destroyForcibly()
        }
    }.flowOn(Dispatchers.IO)

    // ── proot availability probe ─────────────────────────────────────────────

    /**
     * Tries to execute proot --version to verify the OS hasn't blocked it (Reality #3).
     * Returns true only if exec succeeds with exit 0.
     */
    suspend fun checkProotExecutable(): Boolean = withContext(Dispatchers.IO) {
        if (!prootBin.exists()) return@withContext false
        try {
            // proot --version exits 0 and prints version info
            val p = ProcessBuilder(prootBin.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            val output = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor() == 0
            Log.d(TAG, "proot --version: ok=$ok output=${output.take(80)}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "proot exec blocked", e)
            false
        }
    }

    // ── Bootstrap (Phase 1) ──────────────────────────────────────────────────

    /**
     * Download and extract the Debian aarch64 rootfs (idempotent).
     * Delegates to [RootfsInstaller] for download + Commons Compress extraction.
     * Emits progress lines suitable for display in the setup wizard.
     */
    fun bootstrap(): Flow<String> = flow {
        emit("proot available: $isProotAvailable")
        if (isBootstrapped) {
            emit("Rootfs already bootstrapped at ${rootfsDir.absolutePath}")
            return@flow
        }
        if (!isProotAvailable) {
            emit("[ERROR] proot binary missing. See jniLibs/arm64-v8a/README.md")
            return@flow
        }
        RootfsInstaller(context).install().collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    // ── PlatformIO helpers ───────────────────────────────────────────────────

    /** Install PlatformIO via pip3 inside the container. */
    fun installPlatformIO(): Flow<String> =
        exec("pip3 install --quiet --upgrade platformio && pio --version")

    /** Stream `pio run -e <env>` from the given project directory. */
    fun pioBuild(projectDir: File, env: String? = null): Flow<String> {
        val envFlag = if (env != null) "-e $env" else ""
        return exec("cd /workspace && pio run $envFlag 2>&1", projectDir = projectDir)
    }

    /** Stream `pio run -e <env> -t upload --upload-port <port>`. */
    fun pioUpload(projectDir: File, env: String? = null, uploadPort: String): Flow<String> {
        val envFlag = if (env != null) "-e $env" else ""
        return exec(
            "cd /workspace && pio run $envFlag -t upload --upload-port $uploadPort 2>&1",
            projectDir = projectDir
        )
    }

    companion object {
        private const val TAG = "ContainerManager"
    }
}
