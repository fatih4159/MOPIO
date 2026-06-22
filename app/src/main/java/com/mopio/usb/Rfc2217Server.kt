package com.mopio.usb

import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal RFC2217 (Telnet COM-PORT-OPTION) server bridging a [UsbSerialPort] to TCP.
 *
 * Implements the subset of RFC2217 that pyserial's `rfc2217://` client uses:
 *  - Telnet IAC WILL/DO negotiation for COM-PORT-OPTION (option 44)
 *  - Sub-options: SET_BAUDRATE, SET_CONTROL (DTR/RTS), PURGE_DATA
 *  - Transparent data passthrough with IAC 0xFF byte-stuffing
 *
 * SET_CONTROL DTR/RTS → UsbSerialPort.setDTR/setRTS is the critical path for
 * ESP32 auto-reset into download mode. See prompt.md §7.1.
 *
 * Single-client design (only one connection accepted at a time).
 */
class Rfc2217Server(private val serialPort: UsbSerialPort) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    var listenPort: Int = 0
        private set

    /** Bind to an ephemeral port and start accepting. Returns the chosen port number. */
    fun start(): Int {
        val ss = ServerSocket(0).also { serverSocket = it }
        listenPort = ss.localPort
        Log.i(TAG, "RFC2217 server listening on 127.0.0.1:$listenPort")
        acceptJob = scope.launch { acceptLoop(ss) }
        return listenPort
    }

    fun stop() {
        acceptJob?.cancel()
        serverSocket?.runCatching { close() }
        serverSocket = null
        listenPort = 0
        Log.d(TAG, "RFC2217 server stopped")
    }

    // ── Accept loop ──────────────────────────────────────────────────────────

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (coroutineContext.isActive) {
            val client = try { ss.accept() } catch (e: IOException) { break }
            Log.d(TAG, "Client connected: ${client.inetAddress.hostAddress}")
            handleClient(client)
            Log.d(TAG, "Client session ended")
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.use { sock ->
            val inp = sock.getInputStream()
            val out = sock.getOutputStream()

            // Announce COM-PORT-OPTION support
            sendWill(out, OPT_COM_PORT)
            sendDo(out, OPT_COM_PORT)
            out.flush()

            // USB→TCP relay runs concurrently
            val usbRelay = scope.launch { usbToTcpRelay(out) }
            try {
                tcpToUsbParser(inp, out)
            } finally {
                usbRelay.cancel()
            }
        }
    }

    // ── USB → TCP relay ──────────────────────────────────────────────────────

    private suspend fun usbToTcpRelay(out: OutputStream) {
        val buf = ByteArray(USB_BUF)
        while (coroutineContext.isActive) {
            val n = try {
                serialPort.read(buf, USB_READ_TIMEOUT_MS)
            } catch (e: IOException) {
                break
            }
            if (n > 0) {
                try {
                    out.write(iacStuff(buf, n))
                    out.flush()
                } catch (e: IOException) {
                    break
                }
            }
        }
    }

    // ── TCP → USB parser ─────────────────────────────────────────────────────

    private fun tcpToUsbParser(inp: InputStream, out: OutputStream) {
        val reader = TelnetReader(inp)
        while (true) {
            when (val b = reader.next()) {
                -1  -> return
                IAC -> handleIac(reader, out)
                else -> serialPort.write(byteArrayOf(b.toByte()), USB_WRITE_TIMEOUT_MS)
            }
        }
    }

    private fun handleIac(r: TelnetReader, out: OutputStream) {
        when (val cmd = r.next()) {
            -1   -> return
            IAC  -> serialPort.write(byteArrayOf(0xFF.toByte()), USB_WRITE_TIMEOUT_MS) // escaped data 0xFF
            WILL, WONT, DO, DONT -> r.next() // consume option byte; we initiate, so just ignore
            SB   -> handleSb(r, out)
            else -> Log.v(TAG, "IAC 0x${cmd.toString(16)} (ignored)")
        }
    }

    private fun handleSb(r: TelnetReader, out: OutputStream) {
        val opt = r.next()
        if (opt != OPT_COM_PORT) { r.skipToSe(); return }
        val sub = r.next()
        val data = r.readUntilSe()

        when (sub) {
            CPO_SET_BAUDRATE -> {
                if (data.size >= 4) {
                    val baud = (data[0].i shl 24) or (data[1].i shl 16) or (data[2].i shl 8) or data[3].i
                    if (baud > 0) {
                        Log.d(TAG, "SET_BAUDRATE $baud")
                        serialPort.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    }
                    sendSb(out, CPO_SET_BAUDRATE + 100, data)
                }
            }
            CPO_SET_CONTROL -> {
                if (data.isNotEmpty()) {
                    applyControl(data[0].i)
                    sendSb(out, CPO_SET_CONTROL + 100, data)
                }
            }
            CPO_PURGE_DATA -> {
                Log.d(TAG, "PURGE_DATA")
                serialPort.purgeHwBuffers(true, true)
            }
            else -> Log.v(TAG, "COM-PORT sub 0x${sub.toString(16)} (ignored)")
        }
    }

    /**
     * Apply a SET_CONTROL value — this is what drives the ESP32 auto-reset sequence.
     * esptool toggles DTR/RTS in a specific sequence to enter the ROM bootloader.
     */
    private fun applyControl(value: Int) {
        when (value) {
            CTRL_DTR_OFF -> { serialPort.dtr = false; Log.d(TAG, "DTR=0") }
            CTRL_DTR_ON  -> { serialPort.dtr = true;  Log.d(TAG, "DTR=1") }
            CTRL_RTS_OFF -> { serialPort.rts = false; Log.d(TAG, "RTS=0") }
            CTRL_RTS_ON  -> { serialPort.rts = true;  Log.d(TAG, "RTS=1") }
            else         -> Log.v(TAG, "SET_CONTROL $value (no-op)")
        }
    }

    // ── Telnet framing helpers ───────────────────────────────────────────────

    private fun sendWill(out: OutputStream, opt: Int) =
        out.write(byteArrayOf(IAC.toByte(), WILL.toByte(), opt.toByte()))

    private fun sendDo(out: OutputStream, opt: Int) =
        out.write(byteArrayOf(IAC.toByte(), DO.toByte(), opt.toByte()))

    private fun sendSb(out: OutputStream, sub: Int, data: ByteArray) {
        out.write(byteArrayOf(IAC.toByte(), SB.toByte(), OPT_COM_PORT.toByte(), sub.toByte()))
        out.write(iacStuff(data, data.size))
        out.write(byteArrayOf(IAC.toByte(), SE.toByte()))
        out.flush()
    }

    /** Double any 0xFF bytes in [buf[0..len)] — required for Telnet data transparency. */
    private fun iacStuff(buf: ByteArray, len: Int): ByteArray {
        var extras = 0
        for (i in 0 until len) if (buf[i] == 0xFF.toByte()) extras++
        if (extras == 0) return if (len == buf.size) buf else buf.copyOf(len)
        val out = ByteArray(len + extras)
        var j = 0
        for (i in 0 until len) {
            out[j++] = buf[i]
            if (buf[i] == 0xFF.toByte()) out[j++] = 0xFF.toByte()
        }
        return out
    }

    // ── Telnet stream reader ─────────────────────────────────────────────────

    private inner class TelnetReader(private val inp: InputStream) {
        fun next(): Int = inp.read()

        fun skipToSe() {
            var prev = -1
            while (true) {
                val b = inp.read(); if (b == -1) return
                if (prev == IAC && b == SE) return
                prev = b
            }
        }

        fun readUntilSe(): ByteArray {
            val acc = mutableListOf<Byte>()
            var prev = -1
            while (true) {
                val b = inp.read(); if (b == -1) return acc.toByteArray()
                if (prev == IAC && b == SE) {
                    if (acc.isNotEmpty()) acc.removeAt(acc.lastIndex) // drop trailing IAC
                    return acc.toByteArray()
                }
                if (prev == IAC && b == IAC) { acc.add(0xFF.toByte()); prev = -1; continue }
                prev = b
                if (b != IAC) acc.add(b.toByte())
            }
        }
    }

    // ── Extension ────────────────────────────────────────────────────────────

    private val Byte.i: Int get() = toInt() and 0xFF

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "Rfc2217Server"

        // Telnet commands
        private const val IAC  = 255
        private const val WILL = 251
        private const val WONT = 252
        private const val DO   = 253
        private const val DONT = 254
        private const val SB   = 250
        private const val SE   = 240

        // COM-PORT-OPTION (RFC2217) — Telnet option 44
        private const val OPT_COM_PORT    = 44

        // Client→server sub-commands
        private const val CPO_SET_BAUDRATE = 1
        private const val CPO_SET_CONTROL  = 5
        private const val CPO_PURGE_DATA   = 12

        // SET_CONTROL values relevant to ESP32 auto-reset
        private const val CTRL_DTR_ON  = 8
        private const val CTRL_DTR_OFF = 9
        private const val CTRL_RTS_ON  = 11
        private const val CTRL_RTS_OFF = 12

        private const val USB_BUF              = 4096
        private const val USB_READ_TIMEOUT_MS  = 100
        private const val USB_WRITE_TIMEOUT_MS = 2000
    }
}
