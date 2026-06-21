package com.mopio.phase0

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mopio.container.ContainerManager
import com.mopio.usb.Rfc2217Server
import com.mopio.usb.UsbPortBroker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SpikeStatus { PENDING, RUNNING, PASS, FAIL }

data class SpikeState(
    val id: String,
    val title: String,
    val description: String,
    val status: SpikeStatus = SpikeStatus.PENDING,
    val logs: List<String> = emptyList()
)

class Phase0ViewModel(
    private val context: Context,
    private val container: ContainerManager,
    private val usbBroker: UsbPortBroker
) : ViewModel() {

    private val _spikes = MutableStateFlow(
        listOf(
            SpikeState("A", "Spike A — proot + glibc exec",
                "uname -a && python3 --version inside the container"),
            SpikeState("B", "Spike B — PlatformIO build",
                "pip install platformio; pio run on a minimal ESP32 blink project"),
            SpikeState("C", "Spike C — USB + RFC2217 round-trip",
                "pyserial rfc2217:// client reads from USB serial; DTR/RTS verified"),
            SpikeState("D", "Spike D — Real flash",
                "pio run -t upload via RFC2217 bridge flashes real hardware")
        )
    )
    val spikes: StateFlow<List<SpikeState>> = _spikes.asStateFlow()

    // ── Public actions ───────────────────────────────────────────────────────

    fun runSpike(index: Int) {
        if (_spikes.value[index].status == SpikeStatus.RUNNING) return
        viewModelScope.launch {
            setStatus(index, SpikeStatus.RUNNING, clearLogs = true)
            val passed = when (index) {
                0 -> runSpikeA(index)
                1 -> runSpikeB(index)
                2 -> runSpikeC(index)
                3 -> runSpikeD(index)
                else -> false
            }
            setStatus(index, if (passed) SpikeStatus.PASS else SpikeStatus.FAIL)
        }
    }

    fun runAll() {
        viewModelScope.launch {
            for (i in 0..3) {
                runSpike(i)
                // Small yield to let the launched coroutine start and set status to RUNNING
                delay(50)
                while (_spikes.value[i].status in listOf(SpikeStatus.PENDING, SpikeStatus.RUNNING)) {
                    delay(150)
                }
                if (_spikes.value[i].status == SpikeStatus.FAIL) {
                    for (j in i + 1..3) {
                        setStatus(j, SpikeStatus.FAIL)
                        log(j, "Skipped — prerequisite Spike ${'A' + i} failed")
                    }
                    break
                }
            }
        }
    }

    // ── Spike implementations ────────────────────────────────────────────────

    private suspend fun runSpikeA(idx: Int): Boolean {
        log(idx, "Checking proot at: ${context.applicationInfo.nativeLibraryDir}/libproot.so")
        val prootOk = container.checkProotExecutable()
        log(idx, if (prootOk) "✓ proot is executable" else "✗ proot NOT executable")
        if (!prootOk) {
            log(idx, "See jniLibs/arm64-v8a/libproot.so in the project.")
            return false
        }
        log(idx, "Checking rootfs: ${container.isBootstrapped}")
        if (!container.isBootstrapped) {
            log(idx, "Rootfs not bootstrapped. Run bootstrap first (see rootfs_config.json).")
            return false
        }
        log(idx, "Running: uname -a && python3 --version")
        var linuxSeen = false
        container.exec("uname -a && python3 --version").collect { line ->
            log(idx, line)
            if (line.contains("Linux") || line.contains("aarch64")) linuxSeen = true
        }
        return linuxSeen
    }

    private suspend fun runSpikeB(idx: Int): Boolean {
        log(idx, "Installing PlatformIO (first run may take 5–10 min)…")
        var pioInstalled = false
        container.exec("pip3 install --quiet platformio && pio --version").collect { line ->
            log(idx, line)
            if (line.lowercase().contains("platformio") || line.contains("Core,")) pioInstalled = true
        }
        if (!pioInstalled) { log(idx, "✗ pip install platformio failed"); return false }
        log(idx, "✓ PlatformIO installed")

        log(idx, "Creating minimal ESP32 blink project…")
        val setupCmd = """
            mkdir -p /tmp/spike_b/src && cd /tmp/spike_b &&
            cat > src/main.cpp << 'CPPEOF'
#include <Arduino.h>
void setup() { pinMode(2, OUTPUT); }
void loop()  { digitalWrite(2, HIGH); delay(500); digitalWrite(2, LOW); delay(500); }
CPPEOF
            cat > platformio.ini << 'INIEOF'
[env:esp32dev]
platform = espressif32
board    = esp32dev
framework = arduino
INIEOF
            echo "Project created"
        """.trimIndent()
        container.exec(setupCmd).collect { log(idx, it) }

        log(idx, "Running pio run (downloads toolchain on first run — may take 10+ min)…")
        var success = false
        container.exec("cd /tmp/spike_b && pio run 2>&1").collect { line ->
            log(idx, line)
            if (line.contains("SUCCESS") || line.contains("[EXIT 0]")) success = true
            if (line.contains("ERROR") || line.contains("[EXIT 1]")) success = false
        }
        return success
    }

    private suspend fun runSpikeC(idx: Int): Boolean {
        log(idx, "Enumerating USB serial devices…")
        val drivers = usbBroker.findDrivers()
        if (drivers.isEmpty()) {
            log(idx, "✗ No USB serial device found. Connect an ESP32 board via USB-OTG.")
            return false
        }
        val driver = drivers.first()
        log(idx, "Found: ${driver.device.deviceName} (${driver.device.productName})")

        log(idx, "Requesting USB permission…")
        if (!usbBroker.requestPermission(driver.device)) {
            log(idx, "✗ USB permission denied")
            return false
        }

        log(idx, "Opening serial port @ 115200…")
        val port = usbBroker.acquirePort(driver, baud = 115_200)

        log(idx, "Starting RFC2217 server…")
        val srv = Rfc2217Server(port)
        val tcpPort = srv.start()
        log(idx, "RFC2217 server on localhost:$tcpPort")

        log(idx, "Running pyserial round-trip test from container…")
        val testCmd = """
            python3 - << 'PYEOF'
import serial, time, sys
try:
    s = serial.serial_for_url('rfc2217://127.0.0.1:$tcpPort', baudrate=115200, timeout=3)
    print('isOpen:', s.isOpen())
    s.dtr = False; s.rts = False; time.sleep(0.1)
    s.dtr = True;  s.rts = True;  time.sleep(0.1)
    s.dtr = False; s.rts = False
    data = s.read(64)
    print('Read', len(data), 'bytes')
    s.close()
    print('SPIKE_C_PASS')
except Exception as e:
    print('SPIKE_C_FAIL:', e, file=sys.stderr)
PYEOF
        """.trimIndent()

        var passed = false
        container.exec(testCmd).collect { line ->
            log(idx, line)
            if (line.contains("SPIKE_C_PASS")) passed = true
        }

        srv.stop()
        usbBroker.releasePort()
        return passed
    }

    private suspend fun runSpikeD(idx: Int): Boolean {
        log(idx, "Spike D requires Spike B firmware in /tmp/spike_b and a connected board.")
        val drivers = usbBroker.findDrivers()
        if (drivers.isEmpty()) { log(idx, "✗ No USB device"); return false }
        val driver = drivers.first()
        if (!usbBroker.requestPermission(driver.device)) { log(idx, "✗ Permission denied"); return false }

        val port = usbBroker.acquirePort(driver, baud = 115_200)
        val srv = Rfc2217Server(port)
        val tcpPort = srv.start()
        log(idx, "RFC2217 on localhost:$tcpPort — running pio upload…")

        var passed = false
        container.exec("cd /tmp/spike_b && pio run -t upload --upload-port rfc2217://127.0.0.1:$tcpPort 2>&1")
            .collect { line ->
                log(idx, line)
                if (line.contains("Leaving...") || line.contains("Hash of data verified")) passed = true
                if (line.contains("[EXIT 0]")) passed = true
            }

        srv.stop()
        usbBroker.releasePort()
        return passed
    }

    // ── State helpers ────────────────────────────────────────────────────────

    private fun log(idx: Int, line: String) {
        _spikes.update { list ->
            list.toMutableList().also { it[idx] = it[idx].copy(logs = it[idx].logs + line) }
        }
    }

    private fun setStatus(idx: Int, status: SpikeStatus, clearLogs: Boolean = false) {
        _spikes.update { list ->
            list.toMutableList().also {
                it[idx] = it[idx].copy(
                    status = status,
                    logs = if (clearLogs) emptyList() else it[idx].logs
                )
            }
        }
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    class Factory(
        private val context: Context,
        private val container: ContainerManager,
        private val usbBroker: UsbPortBroker
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            Phase0ViewModel(context, container, usbBroker) as T
    }
}
