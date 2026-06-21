package com.mopio.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Single point of USB serial port ownership.
 *
 * Serialises access between the serial monitor and the flasher so only one
 * consumer holds the port at a time. See prompt.md §10 "USB port ownership conflicts".
 */
class UsbPortBroker(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var activePort: UsbSerialPort? = null

    // ── Discovery ────────────────────────────────────────────────────────────

    fun findDrivers(): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    fun connectedDevices(): List<UsbDevice> =
        usbManager.deviceList.values.toList()

    // ── Permission ───────────────────────────────────────────────────────────

    /**
     * Requests USB permission if not already granted and suspends until the
     * system dialog is resolved. Returns true if permission was granted.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val action = "com.mopio.USB_PERM_${device.deviceId}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.d(TAG, "USB permission granted=$granted for ${device.deviceName}")
                    if (cont.isActive) cont.resume(granted)
                }
            }
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            val pi = PendingIntent.getBroadcast(
                context, device.deviceId, Intent(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pi)
            cont.invokeOnCancellation { context.unregisterReceiver(receiver) }
        }
    }

    // ── Port lifecycle ───────────────────────────────────────────────────────

    /**
     * Opens the first port on the driver and configures line parameters.
     * Releases any previously held port first.
     * Caller must call [releasePort] when done.
     */
    fun acquirePort(
        driver: UsbSerialDriver,
        baud: Int = 115_200,
        dataBits: Int = 8,
        stopBits: Int = UsbSerialPort.STOPBITS_1,
        parity: Int = UsbSerialPort.PARITY_NONE
    ): UsbSerialPort {
        releasePort()
        val connection = usbManager.openDevice(driver.device)
            ?: error("Failed to open USB device — permission not granted?")
        val port = driver.ports[0].also {
            it.open(connection)
            it.setParameters(baud, dataBits, stopBits, parity)
            it.dtr = false
            it.rts = false
        }
        activePort = port
        Log.i(TAG, "Acquired ${driver.device.deviceName} @ ${baud}baud")
        return port
    }

    fun releasePort() {
        activePort?.runCatching { close() }
        activePort = null
    }

    val hasOpenPort: Boolean get() = activePort != null

    companion object {
        private const val TAG = "UsbPortBroker"

        // Written by MainActivity.onNewIntent when USB_DEVICE_ATTACHED fires.
        // Screens can observe this to react to a newly plugged-in board.
        private val _lastAttachedDevice = MutableStateFlow<UsbDevice?>(null)
        val lastAttachedDevice: StateFlow<UsbDevice?> = _lastAttachedDevice.asStateFlow()

        fun onDeviceAttached(device: UsbDevice) {
            _lastAttachedDevice.value = device
            Log.i(TAG, "USB device attached: ${device.deviceName} (${device.vendorId}:${device.productId})")
        }
    }
}
