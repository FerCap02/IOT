package cl.filamentbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cl.filamentbox.app.AppViewModel
import cl.filamentbox.app.data.ConnState

private val PRESETS = listOf(
    "STORAGE" to "Almacenamiento: cámara aislada, ventilador OFF",
    "RECIRC" to "Recirculación con sílica: cámara → ventilador → sílica",
    "PURGE" to "Purga con aire exterior: entrada y salida abiertas",
)

@Composable
fun ControlScreen(vm: AppViewModel) {
    val s = vm.status
    val online = s != null && vm.connState == ConnState.ONLINE
    val on = s?.power == true
    val auto = s?.auto != false

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PowerCard(vm, s)

        SectionCard("Modo de operación") {
            SwitchRow("Control automático (humedad relativa + histéresis)", auto, online && on) { enable ->
                val manualMode = s?.mode?.takeIf { it in PRESETS.map { p -> p.first } } ?: "STORAGE"
                vm.setMode(if (enable) "AUTO" else manualMode)
            }
            PRESETS.forEach { (key, label) ->
                FilterChip(
                    selected = !auto && s?.mode == key,
                    onClick = { vm.setMode(key) },
                    enabled = online && on,
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionCard("Control manual (compuertas y ventilador)") {
            val manual = online && on && !auto
            if (!manual) {
                Text(
                    if (!on) "Enciende el deshumidificador para operar manualmente."
                    else "Desactiva el control automático para usar estos controles.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SwitchRow("Ventilador (12 V)", s?.fan == true, manual) { vm.setFan(it) }
            SwitchRow(
                "Compuerta de entrada: " + if (s?.inletOpen == true) "abierta (aire exterior)" else "cerrada",
                s?.inletOpen == true, manual && s?.positionKnown == true,
            ) { vm.setVents(it, s?.outletExterior == true) }
            SwitchRow(
                "Compuerta de salida: " + if (s?.outletExterior == true) "expulsión al exterior" else "retorno a la cámara",
                s?.outletExterior == true, manual && s?.positionKnown == true,
            ) { vm.setVents(s?.inletOpen == true, it) }
        }

        SectionCard("Estados del sistema (informe §7)") {
            Text("Almacenamiento: entrada cerrada · salida cerrada · ventilador OFF", style = MaterialTheme.typography.bodySmall)
            Text("Recirculación: entrada cerrada · retorno interno · ventilador ON", style = MaterialTheme.typography.bodySmall)
            Text("Purga exterior: entrada abierta · salida exterior · ventilador ON", style = MaterialTheme.typography.bodySmall)
            Text("Manual/mantenimiento: configurable", style = MaterialTheme.typography.bodySmall)
        }
    }
}
