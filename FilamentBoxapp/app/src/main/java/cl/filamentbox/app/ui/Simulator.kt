package cl.filamentbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cl.filamentbox.app.AppViewModel
import cl.filamentbox.app.data.f0
import cl.filamentbox.app.data.f1
import cl.filamentbox.app.data.modeLabel
import cl.filamentbox.app.sim.SimLive
import cl.filamentbox.app.sim.SimVar
import kotlinx.coroutines.delay

private val SCENARIOS = listOf(
    "DRY" to "Día seco\n(HR exterior 20 %)",
    "RAIN" to "Día lluvioso\n(HR exterior 95 %)",
    "SUN" to "Sol directo\n(UV 8.5)",
    "LID" to "Abrir tapa\n(+15 % HR cámara)",
    "SILICA_SPENT" to "Sílica agotada",
    "SILICA_NEW" to "Sílica nueva",
    "AUTO" to "Todo automático",
)

private data class SimSpec(val v: SimVar, val label: String, val unit: String, val min: Float, val max: Float)

private val SPECS = listOf(
    SimSpec(SimVar.CH_T, "Temperatura cámara (DHT11 N.º 1)", "°C", 10f, 40f),
    SimSpec(SimVar.CH_RH, "Humedad cámara (DHT11 N.º 1)", "%", 5f, 95f),
    SimSpec(SimVar.OUT_T, "Temperatura exterior (DHT11 N.º 2)", "°C", 0f, 40f),
    SimSpec(SimVar.OUT_RH, "Humedad exterior (DHT11 N.º 2)", "%", 10f, 95f),
    SimSpec(SimVar.UV, "Índice UV (GUVA‑S12SD)", "", 0f, 11f),
    SimSpec(SimVar.SILICA, "Desgaste de la sílica", "%", 0f, 100f),
)

/** Panel para controlar el "ESP32 virtual" de ESTE teléfono: valores de sensores y escenarios. */
@Composable
fun SimulatorScreen(vm: AppViewModel) {
    var live by remember { mutableStateOf<SimLive?>(null) }
    LaunchedEffect(vm.simRunning) {
        while (vm.simRunning) {
            live = vm.simSnapshot()
            delay(1000)
        }
        live = null
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!vm.simRunning) {
            SectionCard("Simulador apagado") {
                Text(
                    "Activa el simulador para que este teléfono se comporte como el ESP32: " +
                        "podrás cambiar los valores de los sensores y cargar escenarios, " +
                        "y el otro teléfono lo verá en tiempo real.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { vm.startSim(vm.cfg.token) }) { Text("Iniciar simulador") }
                Text(
                    "Usa el token guardado en Ajustes (${vm.cfg.token.length} caracteres).",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            return@Column
        }

        val l = live
        SectionCard("Simulador activo") {
            Text("Dirección: ${vm.simIp ?: "sin Wi‑Fi"}:${AppViewModel.SIM_PORT}", fontWeight = FontWeight.Bold)
            if (l == null) {
                Text("Iniciando…")
            } else {
                val s = l.status
                KeyValue("Teléfonos conectados", l.clients.toString())
                KeyValue("Modo", modeLabel(s.mode))
                KeyValue("Ventilador", if (s.fan) "ENCENDIDO" else "Apagado")
                KeyValue("Cámara", "${s.chamber.t.f1()} °C · ${s.chamber.rh.f0()} % HR")
                KeyValue("Exterior", "${s.outdoor.t.f1()} °C · ${s.outdoor.rh.f0()} % HR")
                KeyValue("Post‑sílica", "${s.postSilica.t.f1()} °C · ${s.postSilica.rh.f0()} % HR")
                if (s.alerts.isNotEmpty()) {
                    Text("Alertas: " + s.alerts.joinToString(" · "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionCard("Escenarios de demostración") {
            Text(
                "Cargan varios valores a la vez para ver cómo reacciona el sistema. " +
                    "Prueba «Día seco» + «Abrir tapa» (purga exterior) y luego «Día lluvioso» + «Abrir tapa» (recirculación con sílica).",
                style = MaterialTheme.typography.bodySmall,
            )
            SCENARIOS.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { (key, label) ->
                        OutlinedButton(onClick = { vm.simScenario(key) }, modifier = Modifier.weight(1f)) {
                            Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        SectionCard("Valores de los sensores") {
            Text(
                "Activa «Manual» en un sensor para fijar su valor con el deslizador. " +
                    "En «Auto» el simulador lo genera solo.",
                style = MaterialTheme.typography.bodySmall,
            )
            SPECS.forEach { spec ->
                val liveValue = when (spec.v) {
                    SimVar.CH_T -> l?.status?.chamber?.t
                    SimVar.CH_RH -> l?.status?.chamber?.rh
                    SimVar.OUT_T -> l?.status?.outdoor?.t
                    SimVar.OUT_RH -> l?.status?.outdoor?.rh
                    SimVar.UV -> l?.status?.uv
                    SimVar.SILICA -> l?.silicaPct
                } ?: spec.min.toDouble()
                SimSliderRow(vm, spec, liveValue, vm.simForced[spec.v])
            }
        }

        OutlinedButton(onClick = { vm.stopSim() }, modifier = Modifier.fillMaxWidth()) { Text("Detener simulador") }
    }
}

@Composable
private fun SimSliderRow(vm: AppViewModel, spec: SimSpec, live: Double, forced: Double?) {
    val manual = forced != null
    val value = (forced ?: live).toFloat().coerceIn(spec.min, spec.max)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${spec.label}: ${value.toDouble().f1()} ${spec.unit}",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            )
            Text(if (manual) "Manual" else "Auto", style = MaterialTheme.typography.labelSmall)
            Switch(
                checked = manual,
                onCheckedChange = { on -> vm.simForce(spec.v, if (on) live else null) },
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Slider(
            value = value,
            onValueChange = { vm.simForce(spec.v, it.toDouble()) },
            valueRange = spec.min..spec.max,
            enabled = manual,
        )
    }
}
