package com.mopio.container

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.coroutineScope
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
    private val prootTmpDir: File
        get() = File(context.filesDir, "proot-tmp").also { it.mkdirs() }

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
            put("PROOT_TMP_DIR", prootTmpDir.absolutePath)
            putAll(extraEnv)
        }

        Log.d(TAG, "exec: ${args.joinToString(" ")}")
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .also { it.environment().putAll(env) }
            .start()

        try {
            process.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    emit(line)
                    Log.v(TAG, line)
                    line = reader.readLine()
                }
            }
            val code = process.waitFor()
            emit("[EXIT $code]")
        } finally {
            process.destroyForcibly()
        }
    }.flowOn(Dispatchers.IO)
        .catch { e ->
            if (e is IOException) emit("[ERROR] IO: ${e.message}")
            else throw e
        }

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
        emitAll(RootfsInstaller(context).install())

        // Fix up critical symlinks/binary permissions in case the filesystem
        // does not support symlinks (common on Android /data partitions).
        fixupRootfs()

        // The minimal Debian rootfs does not include python3.
        // Install it now so pip3 / PlatformIO work later.
        if (!File(rootfsDir, "usr/bin/python3").exists()) {
            emit("Setting up rootfs networking and apt sources…")
            val setupCode = runInProot(
                "echo 'nameserver 1.1.1.1' > /etc/resolv.conf && " +
                "echo 'nameserver 8.8.8.8' >> /etc/resolv.conf && " +
                "mkdir -p /etc/apt && " +
                "echo 'deb http://deb.debian.org/debian bookworm main contrib non-free non-free-firmware' > /etc/apt/sources.list && " +
                "echo 'deb http://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware' >> /etc/apt/sources.list && " +
                "echo 'deb http://security.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware' >> /etc/apt/sources.list"
            ) { line -> emit("  $line") }
            if (setupCode != 0) {
                emit("[ERROR] Rootfs network/sources setup failed (exit $setupCode)")
                return@flow
            }

            emit("Installing python3 + pip (apt-get update && apt-get install -y python3 python3-pip)…")
            val code = runInProot(
                "DEBIAN_FRONTEND=noninteractive apt-get update -qq 2>&1 && " +
                "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends python3 python3-pip ca-certificates 2>&1"
            ) { line -> emit("  $line") }
            if (code != 0) {
                emit("[ERROR] python3 installation failed (exit $code)")
                emit("See lines above for apt errors. Common causes:")
                emit("  - No network inside proot (check /etc/resolv.conf)")  
                emit("  - Missing apt sources.list")
                emit("  - Check /data/user/0/com.mopio.debug/files/rootfs/etc/apt/sources.list")
                return@flow
            }
            emit("python3 installed")
        }
        emit("Bootstrap complete!")
    }.flowOn(Dispatchers.IO)

    /**
     * Run a shell command inside the container without requiring [isBootstrapped].
     * Used during bootstrap before python3 is available.
     * Returns the exit code.
     */
    private suspend fun runInProot(cmd: String, onLine: (suspend (String) -> Unit)? = null): Int = coroutineScope {
        val args = buildList {
            add(prootBin.absolutePath)
            add("--rootfs=${rootfsDir.absolutePath}")
            add("--bind=/proc")
            add("--bind=/dev")
            add("--bind=/sys")
            add("/bin/sh")
            add("-c")
            add(cmd)
        }
        Log.d(TAG, "runInProot: ${args.joinToString(" ")}")
        try {
            val p = ProcessBuilder(args)
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().putAll(
                        mapOf(
                            "HOME" to "/root",
                            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                            "TERM" to "xterm-256color",
                            "LANG" to "C.UTF-8",
                            "PROOT_TMP_DIR" to prootTmpDir.absolutePath
                        )
                    )
                }
                .start()
            p.inputStream.bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    Log.v(TAG, line)
                    onLine?.invoke(line)
                    line = reader.readLine()
                }
            }
            p.waitFor()
        } catch (e: IOException) {
            Log.e(TAG, "runInProot failed", e)
            127
        }
    }

    /**
     * Post-extraction fixup: ensures critical binaries (shell, dynamic linker)
     * exist and are executable. Android's /data partition often disallows
     * symlinks, so symlinks in the rootfs may have been replaced with text
     * files or copies that lack proper permissions.
     */
    private fun fixupRootfs() {
        val candidates = listOf("bin/sh", "usr/bin/sh", "bin/dash", "usr/bin/dash", "bin/bash", "usr/bin/bash")

        // Find any working shell binary
        var shellBin: File? = null
        for (rel in candidates) {
            val f = File(rootfsDir, rel)
            if (f.isFile && f.canExecute()) {
                shellBin = f
                break
            }
        }

        // If no shell is executable, search more broadly
        if (shellBin == null) {
            shellBin = File(rootfsDir, "usr/bin/dash")
            if (!shellBin.isFile) shellBin = File(rootfsDir, "bin/dash")
            if (!shellBin.isFile) shellBin = File(rootfsDir, "usr/bin/bash")
            if (!shellBin.isFile) shellBin = File(rootfsDir, "bin/bash")
            if (shellBin!!.isFile) {
                shellBin!!.setExecutable(true, false)
            } else {
                shellBin = null
            }
        }

        // Ensure /bin/sh and /usr/bin/sh point to a working shell
        if (shellBin != null) {
            for (shPath in listOf("bin/sh", "usr/bin/sh")) {
                val shFile = File(rootfsDir, shPath)
                if (!shFile.exists() || !shFile.canExecute()) {
                    shFile.parentFile?.mkdirs()
                    shellBin.copyTo(shFile, overwrite = true)
                    shFile.setExecutable(true, false)
                }
            }
        }

        // Ensure the dynamic linker exists and is executable
        val ldLinks = listOf(
            "lib/ld-linux-aarch64.so.1",
            "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
        )
        for (rel in ldLinks) {
            val ld = File(rootfsDir, rel)
            if (ld.isFile && ld.canExecute()) break
            if (ld.isFile && !ld.canExecute()) {
                ld.setExecutable(true, false)
            }
        }
    }

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
