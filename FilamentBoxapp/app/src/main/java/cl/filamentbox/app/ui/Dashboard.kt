package cl.filamentbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cl.filamentbox.app.AppViewModel
import cl.filamentbox.app.data.Climate
import cl.filamentbox.app.data.ConnState
import cl.filamentbox.app.data.DeviceStatus
import cl.filamentbox.app.data.f0
import cl.filamentbox.app.data.f1
import cl.filamentbox.app.data.modeLabel
import cl.filamentbox.app.data.uvLevel

@Composable
fun DashboardScreen(vm: AppViewModel) {
    val s = vm.status
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (vm.connState != ConnState.ONLINE) ConnBanner(vm)

        // Botón de encendido del deshumidificador
        PowerCard(vm, s)

        // Temperaturas: valor principal del panel (3 × DHT11)
        Text("Temperatura de los sensores", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TempCard("Cámara", "DHT11 N.º 1", s?.chamber, MaterialTheme.colorScheme.primaryContainer, Modifier.weight(1f))
            TempCard("Exterior", "DHT11 N.º 2", s?.outdoor, MaterialTheme.colorScheme.secondaryContainer, Modifier.weight(1f))
            TempCard("Post‑sílica", "DHT11 N.º 3", s?.postSilica, MaterialTheme.colorScheme.tertiaryContainer, Modifier.weight(1f))
        }

        HumidityCard(s)
        AirDecisionCard(s)
        SilicaCard(s)

        SectionCard("Radiación UV (GUVA‑S12SD)") {
            if (s == null) Text("Sin datos") else {
                KeyValue(if (s.uvUnit == "V") "Salida del sensor" else "Índice UV",
                    if (s.uvUnit == "V") "${s.uv.f1()} V · sin calibrar" else "${s.uv.f1()}  (${uvLevel(s.uv)})")
                KeyValue("Umbral de alerta", s.uvAlert.f1())
            }
        }

        SectionCard("Actuadores y estado del sistema") {
            if (s == null) Text("Sin datos") else {
                KeyValue("Control", if (s.auto) "Automático" else "Manual")
                KeyValue("Ventilador (12 V)", if (s.fan) "ENCENDIDO" else "Apagado")
                KeyValue("Compuerta de entrada", if (!s.positionKnown) "Sin calibrar" else if (s.inletOpen) "Abierta (aire exterior)" else "Cerrada")
                KeyValue("Compuerta de salida", if (!s.positionKnown) "Sin calibrar" else if (s.outletExterior) "Expulsión al exterior" else "Retorno a cámara")
                KeyValue("Wi‑Fi del dispositivo", if (s.wifi == "connected") "Conectado" else s.wifi)
            }
        }

        AlertsCard(s)
    }
}

@Composable
fun ConnBanner(vm: AppViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(connLabel(vm.connState), fontWeight = FontWeight.Bold)
            if (vm.connMsg.isNotEmpty()) Text(vm.connMsg, style = MaterialTheme.typography.bodySmall)
            Text(
                "Revisa la configuración Firebase y el estado de Realtime Database en Ajustes.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { vm.connect() }) { Text("Reintentar") }
        }
    }
}

/** Botón de encendido / apagado del deshumidificador. */
@Composable
fun PowerCard(vm: AppViewModel, s: DeviceStatus?) {
    val on = s?.power == true
    val ready = s != null && vm.connState == ConnState.ONLINE
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Deshumidificador de filamento", style = MaterialTheme.typography.titleMedium)
            Text(
                if (on) "ENCENDIDO" else "APAGADO",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(if (s != null) "Modo: " + modeLabel(s.mode) else "Sin datos del dispositivo", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.setPower(!on) },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (on) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
            ) { Text(if (on) "APAGAR" else "ENCENDER", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun TempCard(title: String, sensor: String, c: Climate?, container: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                if (c != null) "${c.t.f1()}°C" else "--",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Text(if (c != null) "HR ${c.rh.f0()} %" else "", style = MaterialTheme.typography.bodySmall)
            Text(sensor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RhRow(label: String, rh: Double?) {
    KeyValue(label, if (rh != null) "${rh.f0()} %" else "--")
    LinearProgressIndicator(progress = { ((rh?.takeIf { it.isFinite() } ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
}

@Composable
fun HumidityCard(s: DeviceStatus?) {
    SectionCard("Humedad relativa") {
        RhRow("Cámara de filamento", s?.chamber?.rh)
        RhRow("Ambiente exterior", s?.outdoor?.rh)
        RhRow("Salida de la sílica", s?.postSilica?.rh)
        if (s != null && s.chamber.valid) {
            val ok = s.chamber.rh <= s.targetRh + s.hysteresis
            Text(
                "Objetivo: ${s.targetRh.f0()} %  (margen de activación +${s.hysteresis.f0()} %)  →  " +
                    if (ok) "dentro del rango" else "por sobre el rango",
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Informe §3.3 / §5.2: comparar humedad absoluta interior vs exterior para decidir la purga. */
@Composable
fun AirDecisionCard(s: DeviceStatus?) {
    SectionCard("Aire interior vs exterior (decisión de compuertas)") {
        if (s == null) { Text("Sin datos"); return@SectionCard }
        if (!s.chamber.valid || !s.outdoor.valid) { Text("Sensores sin lectura válida; no se puede comparar el aire"); return@SectionCard }
        val absIn = s.chamber.absHumidity
        val absOut = s.outdoor.absHumidity
        KeyValue("Humedad absoluta cámara", "${absIn.f1()} g/m³")
        KeyValue("Humedad absoluta exterior", "${absOut.f1()} g/m³")
        KeyValue("Punto de rocío cámara", "${s.chamber.dewPoint.f1()} °C")
        KeyValue("Punto de rocío exterior", "${s.outdoor.dewPoint.f1()} °C")
        val purge = absOut < absIn - 1.0
        Text(
            if (purge) "El aire exterior es más seco: la purga con aire exterior es conveniente."
            else "El aire exterior no es favorable: conviene aislar o recircular con sílica.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Informe §5.3: efectividad del cartucho comparando cámara vs salida de sílica. */
@Composable
fun SilicaCard(s: DeviceStatus?) {
    SectionCard("Estado de la sílica gel") {
        if (s == null) { Text("Sin datos"); return@SectionCard }
        val (txt, col) = when (s.silica) {
            "OK" -> "Efectiva" to MaterialTheme.colorScheme.primary
            "WARN" -> "Efectividad reducida" to MaterialTheme.colorScheme.tertiary
            "SATURATED" -> "Saturada: requiere mantenimiento" to MaterialTheme.colorScheme.error
            else -> "Sin calibrar" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(txt, color = col, fontWeight = FontWeight.Bold)
        KeyValue("HR cámara → tras sílica", "${s.chamber.rh.f0()} % → ${s.postSilica.rh.f0()} %")
        KeyValue("Reducción medida", "${(s.chamber.rh - s.postSilica.rh).f1()} puntos de HR")
        Text(
            "La reducción solo es representativa con el ventilador encendido en recirculación.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun AlertsCard(s: DeviceStatus?) {
    val alerts = s?.alerts.orEmpty()
    if (alerts.isEmpty()) {
        SectionCard("Alertas") { Text("Sin alertas activas") }
    } else {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Alertas (${alerts.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                alerts.forEach { Text("• $it") }
            }
        }
    }
}
