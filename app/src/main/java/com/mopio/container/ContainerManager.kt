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
import java.nio.file.Files
import java.nio.file.Paths

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

    /**
     * Directory holding soname symlinks that the Android dynamic linker needs when loading
     * Termux's proot binary.
     *
     * Termux bundles libtalloc as libtalloc.so (Android only installs lib*.so from jniLibs),
     * but proot's DT_NEEDED entry says "libtalloc.so.2".  /system/bin/linker64 looks for the
     * exact filename, so we create a libtalloc.so.2 symlink here and add this directory to
     * LD_LIBRARY_PATH so the linker finds it.
     */
    private val extraLibsDir: File
        get() {
            val dir = File(context.filesDir, "proot-libs")
            dir.mkdirs()
            val talloc2 = File(dir, "libtalloc.so.2")
            val tallocReal = File(context.applicationInfo.nativeLibraryDir, "libtalloc.so")
            if (!Files.isSymbolicLink(talloc2.toPath()) && tallocReal.exists()) {
                talloc2.delete()
                try {
                    Files.createSymbolicLink(talloc2.toPath(), tallocReal.toPath())
                } catch (e: Exception) {
                    Log.w(TAG, "extraLibsDir: cannot create libtalloc.so.2 symlink: ${e.message}")
                }
            }
            return dir
        }

    val isBootstrapped: Boolean
        get() = File(rootfsDir, "bin/bash").exists() && File(rootfsDir, "usr/bin/python3").exists()

    val isProotAvailable: Boolean
        get() = prootBin.exists()

    // ── Shared proot argument builder ────────────────────────────────────────

    /**
     * Returns the fixed proot flags required on Android:
     *  --link2symlink : proot resolves symlinks internally; avoids issues where
     *                   the extractor fallback wrote a text file instead of a symlink.
     *  -0             : Fake UID/GID 0 so apt-get / pip / dpkg believe they run as root.
     */
    private fun prootBaseArgs(): List<String> = listOf(
        prootBin.absolutePath,
        "--rootfs=${rootfsDir.absolutePath}",
        "--link2symlink",
        "-0",
        "--bind=/proc",
        "--bind=/dev",
        "--bind=/sys",
        "--bind=/dev/urandom:/dev/random"
    )

    /** Environment variables shared by both exec() and runInProot(). */
    private fun prootBaseEnv(): Map<String, String> {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        return mapOf(
            "HOME"             to "/root",
            "TERM"             to "xterm-256color",
            "LANG"             to "C.UTF-8",
            "PROOT_TMP_DIR"    to prootTmpDir.absolutePath,
            // Disable seccomp acceleration — avoids crashes on some Android kernels.
            "PROOT_NO_SECCOMP" to "1",
            // Termux's proot acts as its own loader (self-as-loader mode).
            "PROOT_LOADER"     to prootBin.absolutePath,
            // Allow /system/bin/linker64 to find libtalloc.so.2 and libandroid-shmem.so
            // which live in nativeLibraryDir.  extraLibsDir holds the libtalloc.so.2
            // soname symlink because Android only installs lib*.so (no .so.2 suffix).
            "LD_LIBRARY_PATH"  to "${extraLibsDir.absolutePath}:$nativeDir"
        )
    }

    // ── Command execution ────────────────────────────────────────────────────

    /**
     * Run a shell command inside the proot container.
     * Emits stdout+stderr lines followed by "[EXIT <code>]".
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
            addAll(prootBaseArgs())
            add("--bind=${platformioDir.absolutePath}:/data/platformio")
            if (projectDir != null && projectDir.exists()) {
                add("--bind=${projectDir.absolutePath}:/workspace")
            }
            add("/bin/sh")
            add("-c")
            add(cmd)
        }

        val env = buildMap {
            putAll(prootBaseEnv())
            put("PATH", "/root/.platformio/penv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("PLATFORMIO_CORE_DIR", "/data/platformio")
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

    suspend fun checkProotExecutable(): Boolean = withContext(Dispatchers.IO) {
        if (!prootBin.exists()) return@withContext false
        try {
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

        emit("Fixing merged-usr symlinks and permissions…")
        fixupRootfs()

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
                emit("  - No network inside proot (check /etc/resolv.conf)")
                emit("  - Missing apt sources.list")
                return@flow
            }
            emit("python3 installed")
        }
        emit("Bootstrap complete!")
    }.flowOn(Dispatchers.IO)

    // ── Internal proot runner (pre-bootstrap) ────────────────────────────────

    private suspend fun runInProot(cmd: String, onLine: (suspend (String) -> Unit)? = null): Int = coroutineScope {
        val args = buildList {
            addAll(prootBaseArgs())
            add("/bin/sh")
            add("-c")
            add(cmd)
        }
        val env = buildMap {
            putAll(prootBaseEnv())
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("DEBIAN_FRONTEND", "noninteractive")
        }
        Log.d(TAG, "runInProot: ${args.joinToString(" ")}")
        try {
            val p = ProcessBuilder(args)
                .redirectErrorStream(true)
                .also { pb -> pb.environment().putAll(env) }
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

    // ── Post-extraction fixup ────────────────────────────────────────────────

    /**
     * Debian Bookworm uses a merged-usr layout: /bin, /lib, /lib64, /sbin are
     * symlinks into /usr. The tar extractor's fallback (when Files.createSymbolicLink
     * fails) writes the link target as a plain text file. This method detects those
     * text files and recreates them as real symlinks, then ensures the shell and the
     * dynamic linker are executable.
     */
    private fun fixupRootfs() {
        // Recreate merged-usr directory symlinks if they were stored as text files.
        val mergedUsrLinks = mapOf(
            "bin"   to "usr/bin",
            "lib"   to "usr/lib",
            "lib64" to "usr/lib",
            "sbin"  to "usr/sbin"
        )
        for ((name, target) in mergedUsrLinks) {
            val link      = File(rootfsDir, name)
            val targetDir = File(rootfsDir, target)
            if (!targetDir.isDirectory) continue  // target doesn't exist yet

            val isRealSymlink = try {
                Files.isSymbolicLink(link.toPath())
            } catch (_: Exception) { false }

            if (link.isDirectory) continue        // already a real dir (shouldn't happen, but safe)

            if (!isRealSymlink) {
                // Text-file fallback or missing — recreate as a proper symlink.
                link.delete()
                try {
                    Files.createSymbolicLink(link.toPath(), Paths.get(target))
                    Log.d(TAG, "fixupRootfs: created $name -> $target")
                } catch (e: Exception) {
                    Log.w(TAG, "fixupRootfs: cannot symlink $name -> $target: ${e.message}")
                }
            }
        }

        // Locate a working shell binary (dash preferred, bash as fallback).
        val shellCandidates = listOf(
            "usr/bin/dash", "bin/dash",
            "usr/bin/bash", "bin/bash"
        )
        var shellBin: File? = null
        for (rel in shellCandidates) {
            val f = File(rootfsDir, rel)
            if (f.isFile) {
                if (!f.canExecute()) f.setExecutable(true, false)
                shellBin = f
                break
            }
        }

        // Ensure /bin/sh and /usr/bin/sh exist and are executable.
        if (shellBin != null) {
            for (shPath in listOf("usr/bin/sh", "bin/sh")) {
                val shFile = File(rootfsDir, shPath)
                if (!shFile.exists() || (!shFile.canExecute() && !Files.isSymbolicLink(shFile.toPath()))) {
                    shFile.parentFile?.mkdirs()
                    try {
                        shellBin.copyTo(shFile, overwrite = true)
                        shFile.setExecutable(true, false)
                    } catch (e: Exception) {
                        Log.w(TAG, "fixupRootfs: cannot copy shell to $shPath: ${e.message}")
                    }
                }
            }
        }

        // Ensure the aarch64 dynamic linker is executable.
        val ldCandidates = listOf(
            "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "lib/ld-linux-aarch64.so.1"
        )
        for (rel in ldCandidates) {
            val ld = File(rootfsDir, rel)
            if (ld.isFile) {
                if (!ld.canExecute()) ld.setExecutable(true, false)
                break
            }
        }
    }

    // ── PlatformIO helpers ───────────────────────────────────────────────────

    fun installPlatformIO(): Flow<String> =
        exec("pip3 install --quiet --upgrade platformio && pio --version")

    fun pioBuild(projectDir: File, env: String? = null): Flow<String> {
        val envFlag = if (env != null) "-e $env" else ""
        return exec("cd /workspace && pio run $envFlag 2>&1", projectDir = projectDir)
    }

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
