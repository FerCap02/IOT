package cl.filamentbox.app.sim

import cl.filamentbox.app.data.Climate
import cl.filamentbox.app.data.DeviceStatus
import java.util.Random
import kotlin.math.sin

/** Variables del simulador que se pueden forzar a mano desde el panel de simulación. */
enum class SimVar { CH_T, CH_RH, OUT_T, OUT_RH, UV, SILICA }

/** Foto del simulador para el panel (valores reales + cantidad de teléfonos conectados). */
data class SimLive(val status: DeviceStatus, val silicaPct: Double, val clients: Int)

/**
 * "ESP32-S3 virtual": reproduce la lógica descrita en el informe para poder probar la app
 * entre dos teléfonos sin el hardware real.
 *  - 3 DHT11 (cámara, exterior, post-sílica) + GUVA-S12SD (UV).
 *  - Máquina de estados §7: Almacenamiento / Recirculación con sílica / Purga exterior / Manual.
 *  - Decisión purga vs recirculación comparando HUMEDAD ABSOLUTA interior/exterior (§3.3, §5.2).
 *  - Degradación de la sílica y advertencia de mantenimiento (§5.3, §14).
 *  - Valores forzables (sliders) y escenarios predefinidos para demostraciones.
 */
class SimulatedDevice {
    private val rnd = Random()
    private var t = 0

    private var chT = 24.0
    private var chRh = 48.0
    private var outT = 18.0
    private var outRh = 60.0
    private var postT = 24.0
    private var postRh = 48.0
    private var uv = 0.5

    private var power = true
    private var auto = true
    private var fan = false
    private var inletOpen = false
    private var outletExt = false

    private var targetRh = 30.0
    private var hyst = 5.0
    private var uvAlert = 3.0

    private var silicaLoad = 0.0 // 0 = nueva, 1 = saturada

    /** Valores que el usuario fijó a mano; reemplazan a la simulación automática. */
    private val forced = HashMap<SimVar, Double>()

    private fun noise(a: Double) = (rnd.nextDouble() - 0.5) * 2 * a
    private fun setActuators(f: Boolean, inlet: Boolean, outlet: Boolean) {
        fan = f; inletOpen = inlet; outletExt = outlet
    }

    private fun deriveMode(): String = when {
        !power -> "OFF"
        !fan && !inletOpen && !outletExt -> "STORAGE"
        fan && !inletOpen && !outletExt -> "RECIRC"
        fan && inletOpen && outletExt -> "PURGE"
        else -> "MAINT"
    }

    private fun applyForced() {
        forced[SimVar.CH_T]?.let { chT = it }
        forced[SimVar.CH_RH]?.let { chRh = it }
        forced[SimVar.OUT_T]?.let { outT = it }
        forced[SimVar.OUT_RH]?.let { outRh = it }
        forced[SimVar.UV]?.let { uv = it }
        forced[SimVar.SILICA]?.let { silicaLoad = (it / 100.0).coerceIn(0.0, 1.0) }
    }

    @Synchronized
    fun tick() {
        t++
        // Informe §2: cada vez que se abre el contenedor entra aire húmedo (se simula cada ~4 min)
        if (power && t % 240 == 0) chRh += 12.0
        // Ambiente exterior variable
        outT = 18.0 + 4.0 * sin(t / 120.0) + noise(0.1)
        outRh = (55.0 + 12.0 * sin(t / 90.0 + 1.0) + noise(0.3)).coerceIn(20.0, 90.0)
        uv = (1.5 + 2.5 * sin(t / 45.0) + noise(0.1)).coerceAtLeast(0.0)
        applyForced()

        if (!power) {
            setActuators(false, false, false)
        } else if (auto) {
            val absIn = Climate(chT, chRh).absHumidity
            val absOut = Climate(outT, outRh).absHumidity
            if (chRh > targetRh + hyst) {
                if (absOut < absIn - 1.0) setActuators(true, true, true)   // purga con aire exterior
                else setActuators(true, false, false)                     // recirculación con sílica
            } else if (chRh <= targetRh) {
                setActuators(false, false, false)                         // almacenamiento
            }
        }

        val satAbsCh = Climate(chT, 100.0).absHumidity
        val recirc = power && fan && !inletOpen && !outletExt
        val purge = power && fan && inletOpen && outletExt
        when {
            purge -> {
                val rhEq = Climate(outT, outRh).absHumidity / satAbsCh * 100.0
                chRh += (rhEq - chRh) * 0.05
            }
            recirc -> {
                chRh -= 0.30 * (1.0 - silicaLoad)
                silicaLoad = (silicaLoad + 0.002).coerceAtMost(1.0) // acelerado para la demo
            }
            else -> chRh += 0.03 // filtración gradual de humedad (sellado imperfecto, informe §2)
        }
        chRh = (chRh + noise(0.05)).coerceIn(5.0, 95.0)
        chT += (24.0 - chT) * 0.01 + noise(0.02)
        applyForced()

        if (recirc) {
            postRh = chRh * (1.0 - 0.55 * (1.0 - silicaLoad)) + noise(0.2)
            postT = chT + 0.6
        } else {
            postRh = chRh + noise(0.1)
            postT = chT + noise(0.05)
        }
    }

    @Synchronized
    fun status(): DeviceStatus {
        val silica = when {
            silicaLoad < 0.6 -> "OK"
            silicaLoad < 0.85 -> "WARN"
            else -> "SATURATED"
        }
        val alerts = ArrayList<String>()
        if (power && chRh > targetRh + hyst + 10.0) alerts.add("Humedad de la cámara elevada: ${"%.0f".format(chRh)} %")
        if (uv > uvAlert) alerts.add("Exposición UV elevada: índice ${"%.1f".format(uv)}")
        if (silica == "WARN") alerts.add("Sílica con efectividad reducida")
        if (silica == "SATURATED") alerts.add("Cartucho de sílica saturado: requiere mantenimiento")
        return DeviceStatus(
            chamber = Climate(chT, chRh), outdoor = Climate(outT, outRh),
            postSilica = Climate(postT, postRh), uv = uv,
            power = power, auto = auto, fan = fan, inletOpen = inletOpen, outletExterior = outletExt,
            mode = deriveMode(), silica = silica,
            targetRh = targetRh, hysteresis = hyst, uvAlert = uvAlert,
            alerts = alerts, wifi = "connected",
        )
    }

    // -------- Comandos (los mismos que expone el ESP32 real) --------

    @Synchronized
    fun setPower(on: Boolean) {
        power = on
        if (!on) setActuators(false, false, false)
    }

    /** AUTO | STORAGE | RECIRC | PURGE */
    @Synchronized
    fun setMode(mode: String) {
        if (!power) return
        when (mode.uppercase()) {
            "AUTO" -> auto = true
            "STORAGE" -> { auto = false; setActuators(false, false, false) }
            "RECIRC" -> { auto = false; setActuators(true, false, false) }
            "PURGE" -> { auto = false; setActuators(true, true, true) }
        }
    }

    @Synchronized
    fun setFan(on: Boolean) {
        if (!power) return
        auto = false; fan = on
    }

    @Synchronized
    fun setVents(inlet: Boolean, outlet: Boolean) {
        if (!power) return
        auto = false; inletOpen = inlet; outletExt = outlet
    }

    @Synchronized
    fun setSettings(target: Double, hysteresis: Double, uvAlertLevel: Double) {
        targetRh = target.coerceIn(10.0, 60.0)
        hyst = hysteresis.coerceIn(1.0, 20.0)
        uvAlert = uvAlertLevel.coerceIn(1.0, 11.0)
    }

    // -------- Panel de simulación (solo existe en el simulador, no en el ESP32 real) --------

    /** Fija una variable a un valor (null = vuelve a la simulación automática). SILICA va en %. */
    @Synchronized
    fun force(v: SimVar, value: Double?) {
        if (value == null) forced.remove(v) else forced[v] = value
        applyForced()
    }

    @Synchronized
    fun forcedSnapshot(): Map<SimVar, Double> = HashMap(forced)

    @Synchronized
    fun silicaPercent(): Double = silicaLoad * 100.0

    /**
     * Escenarios de demostración:
     *  DRY día seco · RAIN día lluvioso · SUN sol directo · SILICA_SPENT sílica agotada ·
     *  SILICA_NEW sílica nueva · LID tapa abierta (instantáneo) · AUTO todo automático.
     */
    @Synchronized
    fun scenario(name: String) {
        when (name) {
            "DRY" -> { forced.clear(); forced[SimVar.OUT_T] = 24.0; forced[SimVar.OUT_RH] = 20.0 }
            "RAIN" -> { forced.clear(); forced[SimVar.OUT_T] = 20.0; forced[SimVar.OUT_RH] = 95.0 }
            "SUN" -> { forced.remove(SimVar.UV); forced[SimVar.UV] = 8.5 }
            "SILICA_SPENT" -> { forced.remove(SimVar.SILICA); silicaLoad = 1.0 }
            "SILICA_NEW" -> { forced.remove(SimVar.SILICA); silicaLoad = 0.0 }
            "LID" -> { forced.remove(SimVar.CH_RH); chRh = (chRh + 15.0).coerceAtMost(95.0) }
            "AUTO" -> forced.clear()
        }
        applyForced()
    }
}
