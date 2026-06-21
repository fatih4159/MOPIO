package com.mopio.ui.flash

import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.mopio.MopioApplication
import com.mopio.R
import com.mopio.container.ContainerManager
import com.mopio.platformio.AnsiStripper
import com.mopio.usb.Rfc2217Server
import com.mopio.usb.UsbPortBroker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class FlashStatus { IDLE, WAITING_PERMISSION, FLASHING, SUCCESS, FAILED }

data class FlashState(
    val status: FlashStatus = FlashStatus.IDLE,
    val connectedDeviceName: String = "",
    val logs: List<String> = emptyList(),
    val error: String? = null,
    val selectedEnv: String = ""
)

class FlashViewModel(
    app: Application,
    private val projectDir: File,
    private val container: ContainerManager,
    private val usbBroker: UsbPortBroker
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(FlashState())
    val state: StateFlow<FlashState> = _state.asStateFlow()
    private var flashJob: Job? = null
    private var rfc2217: Rfc2217Server? = null
    private val nm = app.getSystemService(NotificationManager::class.java)

    fun flash(env: String? = null) {
        if (_state.value.status == FlashStatus.FLASHING) return
        flashJob = viewModelScope.launch {
            _state.update { it.copy(status = FlashStatus.IDLE, logs = emptyList(), error = null) }

            val drivers = usbBroker.findDrivers()
            if (drivers.isEmpty()) {
                _state.update { it.copy(error = "No USB serial device found. Connect the ESP32 via USB-OTG.", status = FlashStatus.FAILED) }
                return@launch
            }
            val driver: UsbSerialDriver = drivers.first()
            log("Found: ${driver.device.deviceName} (${driver.device.productName})")

            _state.update { it.copy(status = FlashStatus.WAITING_PERMISSION) }
            if (!usbBroker.requestPermission(driver.device)) {
                _state.update { it.copy(error = "USB permission denied.", status = FlashStatus.FAILED) }
                return@launch
            }

            _state.update { it.copy(status = FlashStatus.FLASHING, connectedDeviceName = driver.device.deviceName) }
            postNotification(getApplication<Application>().getString(R.string.notification_title_flashing), ongoing = true)

            val port = usbBroker.acquirePort(driver, baud = 115_200)
            val srv = Rfc2217Server(port).also { rfc2217 = it }
            val tcpPort = srv.start()
            log("RFC2217 bridge on 127.0.0.1:$tcpPort")

            val uploadPort = "rfc2217://127.0.0.1:$tcpPort"
            var exitCode = -1
            container.pioUpload(projectDir, env, uploadPort).collect { raw ->
                val line = AnsiStripper.strip(raw)
                log(line)
                if (raw.startsWith("[EXIT ")) {
                    exitCode = raw.removePrefix("[EXIT ").trimEnd(']').toIntOrNull() ?: -1
                }
            }

            srv.stop()
            usbBroker.releasePort()
            rfc2217 = null

            if (exitCode == 0) {
                log("Flash complete ✓")
                _state.update { it.copy(status = FlashStatus.SUCCESS) }
                postNotification("Flash complete — ${projectDir.name}", ongoing = false)
            } else {
                _state.update { it.copy(status = FlashStatus.FAILED,
                    error = "Flash failed (exit $exitCode). Try holding BOOT button while connecting.") }
                postNotification("Flash failed — ${projectDir.name}", ongoing = false)
            }
        }
    }

    fun cancel() {
        flashJob?.cancel()
        rfc2217?.stop()
        usbBroker.releasePort()
        _state.update { it.copy(status = FlashStatus.IDLE) }
        nm.cancel(NOTIF_ID)
    }

    private fun log(line: String) {
        _state.update { it.copy(logs = it.logs + line) }
    }

    private fun postNotification(text: String, ongoing: Boolean) {
        val app = getApplication<Application>()
        val n = NotificationCompat.Builder(app, MopioApplication.CHANNEL_BUILD_FLASH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(app.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    override fun onCleared() {
        super.onCleared()
        nm.cancel(NOTIF_ID)
    }

    class Factory(
        private val projectDir: File,
        private val container: ContainerManager,
        private val usbBroker: UsbPortBroker
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val app = checkNotNull(extras[APPLICATION_KEY])
            return FlashViewModel(app, projectDir, container, usbBroker) as T
        }
    }

    companion object {
        private const val NOTIF_ID = 1002
    }
}
