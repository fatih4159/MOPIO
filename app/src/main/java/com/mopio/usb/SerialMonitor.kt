package com.mopio.usb

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Reads raw bytes from a [UsbSerialPort] and emits them as a [SharedFlow].
 *
 * Runs entirely on the native Android USB layer — the monitor does NOT go through
 * the proot container (simpler, faster, no container latency). See prompt.md §5.
 *
 * Port ownership is managed by [UsbPortBroker]; callers must call [stop] before
 * handing the port to the flasher, and [start] again after flashing completes.
 */
class SerialMonitor(private val port: UsbSerialPort) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _data = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val data: SharedFlow<ByteArray> = _data.asSharedFlow()

    private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch {
            val buf = ByteArray(READ_BUF)
            while (isActive && running) {
                val n = try {
                    port.read(buf, READ_TIMEOUT_MS)
                } catch (e: IOException) {
                    Log.w(TAG, "Read error: ${e.message}")
                    break
                }
                if (n > 0) _data.tryEmit(buf.copyOf(n))
            }
        }
        Log.i(TAG, "Monitor started")
    }

    fun stop() {
        running = false
        scope.cancel()
        Log.i(TAG, "Monitor stopped")
    }

    fun send(bytes: ByteArray) {
        try {
            port.write(bytes, WRITE_TIMEOUT_MS)
        } catch (e: IOException) {
            Log.w(TAG, "Write error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SerialMonitor"
        private const val READ_BUF = 4096
        private const val READ_TIMEOUT_MS = 50
        private const val WRITE_TIMEOUT_MS = 2000
    }
}
