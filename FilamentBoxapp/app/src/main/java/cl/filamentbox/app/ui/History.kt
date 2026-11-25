package cl.filamentbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.filamentbox.app.AppViewModel
import cl.filamentbox.app.data.f0
import cl.filamentbox.app.data.f1
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Historial almacenado en Firebase Realtime Database (una lectura cada 10 s mientras hay conexión). */
@Composable
fun HistoryScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.refreshHistory() }
    val h = vm.history
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val cPrimary = MaterialTheme.colorScheme.primary
    val cSecondary = MaterialTheme.colorScheme.secondary
    val cTertiary = MaterialTheme.colorScheme.tertiary

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refreshHistory() }) { Text("Actualizar") }
            OutlinedButton(onClick = { vm.clearHistory() }) { Text("Borrar historial") }
        }

        if (h.size < 2) {
            Text(
                "Aún no hay suficientes datos. La app guarda una lectura cada 10 s mientras esté conectada al dispositivo.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            SectionCard("Temperatura (°C)") {
                Text("● Cámara   ● Exterior   ● Post‑sílica", style = MaterialTheme.typography.labelSmall)
                MultiLineChart(
                    listOf(
                        h.map { it.chT } to cPrimary,
                        h.map { it.outT } to cSecondary,
                        h.map { it.postT } to cTertiary,
                    )
                )
            }
            SectionCard("Humedad relativa (%)") {
                Text("● Cámara   ● Exterior   ● Post‑sílica", style = MaterialTheme.typography.labelSmall)
                MultiLineChart(
                    listOf(
                        h.map { it.chRh } to cPrimary,
                        h.map { it.outRh } to cSecondary,
                        h.map { it.postRh } to cTertiary,
                    )
                )
            }
            SectionCard("Salida del sensor UV (V, sin calibrar)") {
                MultiLineChart(listOf(h.map { it.uv } to cPrimary))
            }
            SectionCard("Últimas lecturas (T° / HR)") {
                Text(
                    "Hora      Cámara      Exterior    Post‑sílica",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
                h.takeLast(15).reversed().forEach {
                    Text(
                        "${fmt.format(Date(it.ts))}  " +
                            "${it.chT.f1()}°/${it.chRh.f0()}%  ".padEnd(12) +
                            "${it.outT.f1()}°/${it.outRh.f0()}%  ".padEnd(12) +
                            "${it.postT.f1()}°/${it.postRh.f0()}%",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    )
                }
            }
        }
    }
}
