package cl.filamentbox.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/** Lectura de un DHT11: temperatura (°C) y humedad relativa (%). */
data class Climate(val t: Double, val rh: Double) {
    /** Punto de rocío (°C) - fórmula de Magnus. Informe §3.3. */
    val dewPoint: Double
        get() {
            val a = 17.62
            val b = 243.12
            val g = ln(rh.coerceIn(1.0, 100.0) / 100.0) + a * t / (b + t)
            return b * g / (a - g)
        }

    /** Humedad absoluta (g/m³). Permite comparar aire interior y exterior (informe §3.3). */
    val absHumidity: Double
        get() = 6.112 * exp(17.67 * t / (t + 243.5)) * rh * 2.1674 / (273.15 + t)

    val valid: Boolean get() = t.isFinite() && rh.isFinite() && t in 0.0..50.0 && rh in 0.0..100.0
    fun toJson(): JSONObject = JSONObject().put("t", if (t.isFinite()) t else JSONObject.NULL)
        .put("rh", if (rh.isFinite()) rh else JSONObject.NULL)

    companion object {
        fun from(o: JSONObject?): Climate =
            Climate(o?.optDouble("t", Double.NaN) ?: Double.NaN, o?.optDouble("rh", Double.NaN) ?: Double.NaN)
    }
}

/** Estado completo del dispositivo (GET /api/status y telemetría por WebSocket). */
data class DeviceStatus(
    val chamber: Climate = Climate(0.0, 0.0),      // DHT11 N.º 1 - cámara
    val outdoor: Climate = Climate(0.0, 0.0),      // DHT11 N.º 2 - exterior
    val postSilica: Climate = Climate(0.0, 0.0),   // DHT11 N.º 3 - salida de sílica
    val uv: Double = 0.0,                          // GUVA-S12SD (índice UV)
    val power: Boolean = false,                    // deshumidificador encendido
    val auto: Boolean = true,                      // control automático
    val fan: Boolean = false,                      // ventilador 12 V
    val inletOpen: Boolean = false,                // compuerta entrada (GA-N20 #1)
    val outletExterior: Boolean = false,           // compuerta salida (GA-N20 #2)
    val mode: String = "OFF",                      // STORAGE | RECIRC | PURGE | MAINT | OFF
    val silica: String = "OK",                     // OK | WARN | SATURATED
    val targetRh: Double = 30.0,
    val hysteresis: Double = 5.0,
    val uvAlert: Double = 3.0,
    val alerts: List<String> = emptyList(),
    val wifi: String = "connected",
    val sampleId: String = "",
    val sampleTs: Long = 0,
    val receivedAt: Long = 0,
    val uvUnit: String = "index",
    val positionKnown: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("chamber", chamber.toJson())
        put("outdoor", outdoor.toJson())
        put("postSilica", postSilica.toJson())
        put("uv", uv)
        put("power", power)
        put("auto", auto)
        put("fan", fan)
        put("inletOpen", inletOpen)
        put("outletExterior", outletExterior)
        put("mode", mode)
        put("silica", silica)
        put("wifi", wifi)
        put("alerts", JSONArray(alerts))
        put(
            "settings",
            JSONObject().put("targetRh", targetRh).put("hysteresis", hysteresis).put("uvAlert", uvAlert)
        )
    }

    companion object {
        fun fromJson(o: JSONObject): DeviceStatus {
            val s = o.optJSONObject("settings")
            val arr = o.optJSONArray("alerts")
            val alerts = (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
            return DeviceStatus(
                chamber = Climate.from(o.optJSONObject("chamber")),
                outdoor = Climate.from(o.optJSONObject("outdoor")),
                postSilica = Climate.from(o.optJSONObject("postSilica")),
                uv = o.optDouble("uv", 0.0),
                power = o.optBoolean("power", false),
                auto = o.optBoolean("auto", true),
                fan = o.optBoolean("fan", false),
                inletOpen = o.optBoolean("inletOpen", false),
                outletExterior = o.optBoolean("outletExterior", false),
                mode = o.optString("mode", "OFF"),
                silica = o.optString("silica", "OK"),
                targetRh = s?.optDouble("targetRh", 30.0) ?: 30.0,
                hysteresis = s?.optDouble("hysteresis", 5.0) ?: 5.0,
                uvAlert = s?.optDouble("uvAlert", 3.0) ?: 3.0,
                alerts = alerts,
                wifi = o.optString("wifi", "connected"),
                sampleId = o.optString("sampleId", ""),
                sampleTs = o.optLong("sampleTs", 0),
                receivedAt = o.optLong("receivedAt", 0),
                uvUnit = o.optString("uvUnit", "index"),
                positionKnown = o.optBoolean("positionKnown", false),
            )
        }
    }
}

/** Fila del historial guardado en Firebase. */
data class Reading(
    val ts: Long,
    val chT: Double, val chRh: Double,
    val outT: Double, val outRh: Double,
    val postT: Double, val postRh: Double,
    val uv: Double, val mode: String,
)

enum class ConnState { DISCONNECTED, CONNECTING, ONLINE, ERROR }

data class ConnConfig(
    val host: String = "filament-box.local",
    val port: Int = 80,
    val tls: Boolean = false,
    val token: String = "filament-demo-2026",
)

fun Double.f1(): String = if (isFinite()) String.format(Locale.US, "%.1f", this) else "Sin lectura"
fun Double.f0(): String = if (isFinite()) String.format(Locale.US, "%.0f", this) else "Sin lectura"

fun modeLabel(mode: String): String = when (mode) {
    "AUTO" -> "Automático"
    "STORAGE" -> "Almacenamiento (cámara aislada)"
    "RECIRC" -> "Recirculación con sílica"
    "PURGE" -> "Purga con aire exterior"
    "MAINT" -> "Manual / mantenimiento"
    else -> "Apagado"
}

fun uvLevel(uv: Double): String = when {
    uv < 3.0 -> "Bajo"
    uv < 6.0 -> "Moderado"
    else -> "Alto"
}
