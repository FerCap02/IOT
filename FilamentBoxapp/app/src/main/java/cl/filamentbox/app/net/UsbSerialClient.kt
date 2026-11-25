package cl.filamentbox.app.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import cl.filamentbox.app.data.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

/** Native ESP32-S3 USB CDC (VID 303a); Android acts as USB host through OTG. */
class UsbSerialClient(private val context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val action = "${context.packageName}.USB_PERMISSION"
    private var job: Job? = null
    private var connection: UsbDeviceConnection? = null
    private var requestedDevice: Int? = null
    private val outgoing = Channel<JSONObject>(16)
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { /* next scan checks the grant */ }
    }

    fun send(command: JSONObject): Boolean = outgoing.trySend(command).isSuccess

    fun start(key: String, onState: (Boolean, String) -> Unit, onStatus: (DeviceStatus) -> Unit) {
        close()
        require(key.length >= 32)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        job = scope.launch(Dispatchers.IO) {
            var retry = 1000L
            while (isActive) {
                try {
                    val device = manager.deviceList.values.firstOrNull { d ->
                        d.vendorId == 0x303a && (0 until d.interfaceCount).any {
                            d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
                        }
                    }
                    if (device == null) {
                        requestedDevice = null
                        onState(false, "Conecta la ESP32-S3 mediante un cable USB OTG")
                    } else if (!manager.hasPermission(device)) {
                        onState(false, "Autoriza el acceso USB al dispositivo")
                        if (requestedDevice != device.deviceId) {
                            requestedDevice = device.deviceId
                            manager.requestPermission(device, PendingIntent.getBroadcast(context, 0,
                                Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE))
                        }
                    } else {
                        session(device, key, onState, onStatus)
                    }
                } catch (e: Exception) {
                    onState(false, e.message ?: "Conexión USB interrumpida")
                }
                delay(retry)
                retry = (retry * 2).coerceAtMost(30000)
            }
        }
    }

    private suspend fun session(device: UsbDevice, key: String,
        onState: (Boolean, String) -> Unit, onStatus: (DeviceStatus) -> Unit) {
        val data = (0 until device.interfaceCount).map { device.getInterface(it) }
            .first { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
        val control = (0 until device.interfaceCount).map { device.getInterface(it) }
            .first { it.interfaceClass == UsbConstants.USB_CLASS_COMM }
        val endpoints = (0 until data.endpointCount).map { data.getEndpoint(it) }
        val input = endpoints.first { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val output = endpoints.first { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val conn = manager.openDevice(device) ?: throw IOException("No se pudo abrir el dispositivo USB")
        connection = conn
        try {
        check(conn.claimInterface(control, true) && conn.claimInterface(data, true))
        check(conn.controlTransfer(0x21, 0x20, 0, control.id,
            byteArrayOf(0x00, 0xc2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08), 7, 1000) >= 0)
        check(conn.controlTransfer(0x21, 0x22, 1, control.id, null, 0, 1000) >= 0) // DTR, RTS low: no reset
        val protocol = SerialProtocol(key)
        fun write(line: String) {
            val bytes = line.toByteArray(Charsets.UTF_8)
            var offset = 0
            while (offset < bytes.size) {
                val n = conn.bulkTransfer(output, bytes, offset, bytes.size - offset, 1000)
                if (n <= 0) throw IOException("No se pudo enviar la instrucción USB")
                offset += n
            }
        }
        // Never replay commands queued during a previous attachment.
        while (outgoing.tryReceive().isSuccess) { }
        write("HELLO\n")
        val bytes = ByteArray(512)
        val line = StringBuilder()
        var overflow = false
        var lastPoll = 0L
        var lastReceived = android.os.SystemClock.elapsedRealtime()
        var authenticated = false
        val pending = mutableMapOf<Long, Long>()
        while (currentCoroutineContext().isActive) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastReceived > 15000) throw IOException("Sin respuesta USB; reconectando")
            if (protocol.boot != null) {
                if (now - lastPoll >= 1000) {
                    write(protocol.encode(JSONObject().put("cmd", "GET_STATUS"))); lastPoll = now
                }
                if (authenticated) outgoing.tryReceive().getOrNull()?.let {
                    val encoded = protocol.encode(it)
                    pending[JSONObject(encoded).getLong("seq")] = now
                    write(encoded)
                }
            }
            if (pending.values.any { now - it > 10000 }) throw IOException("Instrucción sin confirmación del microcontrolador")
            val n = conn.bulkTransfer(input, bytes, bytes.size, 200)
            if (n <= 0) continue
            for (i in 0 until n) {
                val c = bytes[i].toInt().toChar()
                if (c == '\r') continue
                if (c != '\n') {
                    if (line.length < 4096 && !overflow) line.append(c) else { overflow = true; line.clear() }
                    continue
                }
                val text = line.toString(); line.clear()
                if (overflow) { overflow = false; continue }
                if (!text.startsWith("{")) continue
                // Firmware emits ASCII JSON (non-ASCII alert strings are avoided).
                val reply = protocol.receive(text) ?: continue
                lastReceived = android.os.SystemClock.elapsedRealtime()
                authenticated = true
                onState(true, "USB autenticado · comunicación serial activa")
                if (reply.optString("type") == "STATUS") onStatus(DeviceStatus.fromJson(reply).validated())
                if (reply.optString("type") == "ACK") {
                    val commandConfirmed = pending.remove(reply.getLong("ack")) != null
                    if (!reply.getBoolean("ok")) throw IOException("Instrucción rechazada: ${reply.optString("reason")}")
                    // Refresh immediately after an action, including with older firmware.
                    if (commandConfirmed) {
                        write(protocol.encode(JSONObject().put("cmd", "GET_STATUS")))
                        lastPoll = android.os.SystemClock.elapsedRealtime()
                    }
                }
            }
        }
        } finally {
            conn.close()
            if (connection === conn) connection = null
        }
    }

    fun close() {
        job?.cancel(); job = null
        connection?.close(); connection = null
        if (registered) { context.unregisterReceiver(receiver); registered = false }
        while (outgoing.tryReceive().isSuccess) { }
    }
}
