package cl.filamentbox.app.net

import cl.filamentbox.app.data.ConnConfig
import cl.filamentbox.app.data.DeviceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cliente del ESP32-S3 (informe §12):
 *   HTTP/REST  -> comandos y configuración (GET /api/status, POST /api/power, /mode, /fan, /vents, /settings)
 *   WebSocket  -> telemetría en tiempo real (ws://host/ws)
 * Todas las peticiones llevan "Authorization: Bearer <token>" (brecha de seguridad §16.1).
 */
class DeviceClient(private val cfg: ConnConfig) {
    init { require(cfg.tls || cfg.host in setOf("localhost", "127.0.0.1")) { "Usa TLS para conexiones de red" } }

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()
    private var socket: WebSocket? = null

    private fun base(scheme: String) = "$scheme://${cfg.host}:${cfg.port}"
    private fun httpBase() = base(if (cfg.tls) "https" else "http")
    private fun wsBase() = base(if (cfg.tls) "wss" else "ws")

    private fun Request.Builder.auth(): Request.Builder =
        header("Authorization", "Bearer ${cfg.token}")

    private fun parse(resp: Response): JSONObject = resp.use {
        when {
            it.code == 401 -> throw IOException("No autorizado: revisa el token en Ajustes")
            !it.isSuccessful -> throw IOException("Error HTTP ${it.code}")
            else -> JSONObject(it.body?.string() ?: "{}")
        }
    }

    suspend fun getStatus(): DeviceStatus = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${httpBase()}/api/status").auth().get().build()
        DeviceStatus.fromJson(parse(http.newCall(req).execute()))
    }

    /** POST de un comando; el dispositivo responde con el estado actualizado. */
    suspend fun post(path: String, body: JSONObject): DeviceStatus = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${httpBase()}$path").auth()
            .post(body.toString().toRequestBody(json)).build()
        DeviceStatus.fromJson(parse(http.newCall(req).execute()))
    }

    fun openTelemetry(
        onStatus: (DeviceStatus) -> Unit,
        onLost: (String) -> Unit,
    ) {
        val req = Request.Builder().url("${wsBase()}/ws").auth().build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    onStatus(DeviceStatus.fromJson(JSONObject(text)))
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onLost(
                    if (response?.code == 401) "No autorizado: revisa el token"
                    else t.message ?: "Conexión perdida"
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onLost("Conexión cerrada")
            }
        })
    }

    fun close() {
        socket?.close(1000, "bye")
        socket = null
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
    }
}
