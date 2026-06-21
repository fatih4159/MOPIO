package com.mopio.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mopio.usb.SerialMonitor
import com.mopio.usb.UsbPortBroker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MonitorLine(val text: String, val timestamp: String)

enum class MonitorStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class MonitorState(
    val status: MonitorStatus = MonitorStatus.DISCONNECTED,
    val lines: List<MonitorLine> = emptyList(),
    val baud: Int = 115_200,
    val showTimestamps: Boolean = false,
    val autoScroll: Boolean = true,
    val hexView: Boolean = false,
    val error: String? = null
)

class MonitorViewModel(private val usbBroker: UsbPortBroker) : ViewModel() {

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()
    private var monitor: SerialMonitor? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lineBuffer = StringBuilder()

    fun connect(baud: Int = _state.value.baud) {
        if (_state.value.status == MonitorStatus.CONNECTED) return
        viewModelScope.launch {
            _state.update { it.copy(status = MonitorStatus.CONNECTING, error = null) }

            val drivers = usbBroker.findDrivers()
            if (drivers.isEmpty()) {
                _state.update { it.copy(status = MonitorStatus.DISCONNECTED,
                    error = "No USB serial device found.") }
                return@launch
            }
            val driver = drivers.first()
            if (!usbBroker.requestPermission(driver.device)) {
                _state.update { it.copy(status = MonitorStatus.DISCONNECTED,
                    error = "USB permission denied.") }
                return@launch
            }

            val port = usbBroker.acquirePort(driver, baud = baud)
            val mon = SerialMonitor(port).also { monitor = it }
            mon.start()
            _state.update { it.copy(status = MonitorStatus.CONNECTED) }

            mon.data.collect { bytes ->
                handleBytes(bytes)
            }
        }
    }

    fun disconnect() {
        monitor?.stop()
        usbBroker.releasePort()
        monitor = null
        _state.update { it.copy(status = MonitorStatus.DISCONNECTED) }
    }

    fun send(text: String, lineEnding: String = "\r\n") {
        monitor?.send((text + lineEnding).toByteArray(Charsets.UTF_8))
    }

    fun setBaud(baud: Int) = _state.update { it.copy(baud = baud) }
    fun toggleTimestamps() = _state.update { it.copy(showTimestamps = !it.showTimestamps) }
    fun toggleAutoScroll() = _state.update { it.copy(autoScroll = !it.autoScroll) }
    fun toggleHexView() = _state.update { it.copy(hexView = !it.hexView) }
    fun clear() = _state.update { it.copy(lines = emptyList()) }

    private fun handleBytes(bytes: ByteArray) {
        val showHex = _state.value.hexView
        if (showHex) {
            val hex = bytes.joinToString(" ") { "%02X".format(it) }
            addLine(hex)
            return
        }
        // Decode as UTF-8 and split on newlines
        val text = bytes.toString(Charsets.UTF_8)
        lineBuffer.append(text)
        var idx: Int
        while (lineBuffer.indexOf('\n').also { idx = it } >= 0) {
            val line = lineBuffer.substring(0, idx).trimEnd('\r')
            lineBuffer.delete(0, idx + 1)
            addLine(line)
        }
    }

    private fun addLine(text: String) {
        val ts = timeFmt.format(Date())
        _state.update { s ->
            val newLines = (s.lines + MonitorLine(text, ts)).takeLast(MAX_LINES)
            s.copy(lines = newLines)
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }

    class Factory(private val usbBroker: UsbPortBroker) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MonitorViewModel(usbBroker) as T
    }

    companion object {
        private const val MAX_LINES = 5_000
        val BAUD_RATES = listOf(9600, 19200, 38400, 57600, 74880, 115200, 230400, 460800, 921600)
    }
}
