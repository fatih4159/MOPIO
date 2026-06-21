package com.mopio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mopio.container.ContainerManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented smoke test for ContainerManager.
 * Must run on an arm64-v8a device with libproot.so present and rootfs extracted.
 *
 * These tests correspond to Phase 0 Spike A validation.
 */
@RunWith(AndroidJUnit4::class)
class ContainerSmokeTest {

    private lateinit var manager: ContainerManager

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        manager = ContainerManager(ctx)
    }

    @Test
    fun prootBinary_existsInNativeLibDir() {
        val prootBin = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .applicationInfo.nativeLibraryDir,
            "libproot.so"
        )
        // This test will FAIL until libproot.so is placed in jniLibs/arm64-v8a/.
        // That is intentional — it gates Phase 0 Spike A.
        assertTrue(
            "libproot.so not found at ${prootBin.absolutePath}. " +
                    "Build a static aarch64 proot and place it as jniLibs/arm64-v8a/libproot.so.",
            prootBin.exists()
        )
    }

    @Test
    fun prootBinary_isExecutable() {
        val ok = runBlocking { manager.checkProotExecutable() }
        assertTrue("proot should be executable (Reality #3 check)", ok)
    }

    @Test
    fun exec_returnsLinuxInfo_whenBootstrapped() {
        if (!manager.isBootstrapped) {
            return // Skip — rootfs not present
        }
        val lines = mutableListOf<String>()
        runBlocking {
            manager.exec("uname -a && echo 'SMOKE_OK'").collect { lines.add(it) }
        }
        assertTrue(
            "Expected 'Linux' in output but got: $lines",
            lines.any { it.contains("Linux") }
        )
        assertTrue(
            "Expected SMOKE_OK sentinel",
            lines.any { it.contains("SMOKE_OK") }
        )
    }
}
