package cl.filamentbox.app.sim

import cl.filamentbox.app.security.Crypto
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Servidor HTTP/REST + WebSocket que imita el firmware del ESP32-S3.
 * Un teléfono ejecuta esto ("dispositivo"); el otro se conecta como controlador
 * -> demuestra la interconexión entre dos apps Android por Wi-Fi.
 *
 *   GET  /api/status | /api/sensors
 *   POST /api/power    {"on":true}
 *   POST /api/mode     {"mode":"AUTO|STORAGE|RECIRC|PURGE"}
 *   POST /api/fan      {"on":true}
 *   POST /api/vents    {"inletOpen":true,"outletExterior":false}
 *   POST /api/settings {"targetRh":30,"hysteresis":5,"uvAlert":3}
 *   WS   /ws           telemetría cada 1 s
 * Autenticación: cabecera "Authorization: Bearer <token>" (también en el WebSocket).
 */
class SimServer(
    port: Int,
    private val device: SimulatedDevice,
    private val token: String,
) : NanoWSD(port) {

    private val sockets = CopyOnWriteArraySet<Ws>()
    private val running = AtomicBoolean(false)

    inner class Ws(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {
        override fun onOpen() {
            sockets.add(this)
            try { send(device.status().toJson().toString()) } catch (_: IOException) {}
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            sockets.remove(this)
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
        override fun onException(exception: IOException?) { sockets.remove(this) }
    }

    fun clientCount(): Int = sockets.size

    fun begin() {
        start(0, true) // sin timeout de lectura: los WebSocket permanecen abiertos
        running.set(true)
        thread(isDaemon = true, name = "sim-ticker") {
            while (running.get()) {
                device.tick()
                val msg = device.status().toJson().toString()
                for (s in sockets) {
                    try { s.send(msg) } catch (_: Exception) { sockets.remove(s) }
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun end() {
        running.set(false)
        for (s in sockets) {
            try { s.close(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "stop", false) } catch (_: Exception) {}
        }
        sockets.clear()
        stop()
    }

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket = Ws(handshake)

    private fun authorized(session: NanoHTTPD.IHTTPSession): Boolean {
        val h = session.headers["authorization"] ?: return false
        if (!h.startsWith("Bearer ")) return false
        return Crypto.constantTimeEquals(
            h.removePrefix("Bearer ").trim().toByteArray(), token.toByteArray()
        )
    }

    private fun reply(status: NanoHTTPD.Response.Status, body: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body.toString())

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) {
            return reply(NanoHTTPD.Response.Status.UNAUTHORIZED, JSONObject().put("error", "unauthorized"))
        }
        return super.serve(session)
    }

    override fun serveHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri.trimEnd('/')
        return try {
            when {
                session.method == NanoHTTPD.Method.GET && uri == "/api/status" ->
                    reply(NanoHTTPD.Response.Status.OK, device.status().toJson())

                session.method == NanoHTTPD.Method.GET && uri == "/api/sensors" -> {
                    val s = device.status().toJson()
                    val out = JSONObject()
                    for (k in listOf("chamber", "outdoor", "postSilica", "uv")) out.put(k, s.get(k))
                    reply(NanoHTTPD.Response.Status.OK, out)
                }

                session.method == NanoHTTPD.Method.POST && uri.startsWith("/api/") -> {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val body = JSONObject(files["postData"] ?: "{}")
                    when (uri) {
                        "/api/power" -> device.setPower(body.getBoolean("on"))
                        "/api/mode" -> device.setMode(body.getString("mode"))
                        "/api/fan" -> device.setFan(body.getBoolean("on"))
                        "/api/vents" -> device.setVents(
                            body.getBoolean("inletOpen"), body.getBoolean("outletExterior")
                        )
                        "/api/settings" -> device.setSettings(
                            body.getDouble("targetRh"), body.getDouble("hysteresis"), body.getDouble("uvAlert")
                        )
                        else -> return reply(NanoHTTPD.Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))
                    }
                    reply(NanoHTTPD.Response.Status.OK, device.status().toJson())
                }

                else -> reply(NanoHTTPD.Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))
            }
        } catch (e: Exception) {
            reply(NanoHTTPD.Response.Status.BAD_REQUEST, JSONObject().put("error", e.message ?: "bad request"))
        }
    }

    companion object {
        /** IPv4 de la red local del teléfono (para que el otro teléfono se conecte). */
        fun localIpv4(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
