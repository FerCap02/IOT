package cl.filamentbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cl.filamentbox.app.data.ConnState
import cl.filamentbox.app.data.f1

fun connLabel(s: ConnState) = when (s) {
    ConnState.ONLINE -> "Conectado"
    ConnState.CONNECTING -> "Conectando…"
    ConnState.ERROR -> "Sin conexión"
    ConnState.DISCONNECTED -> "Desconectado"
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Gráfico de líneas simple con escala común para todas las series. */
@Composable
fun MultiLineChart(series: List<Pair<List<Double>, Color>>) {
    val all = series.flatMap { it.first }.filter { it.isFinite() }
    if (all.isEmpty()) return
    val lo = all.min()
    val hi = all.max()
    val range = (hi - lo).coerceAtLeast(1.0)
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            for (i in 0..3) {
                val y = size.height * i / 3f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
            }
            for ((vals, color) in series) {
                if (vals.size < 2) continue
                val path = Path()
                var connected = false
                vals.forEachIndexed { i, v ->
                    if (v.isFinite()) {
                        val x = size.width * i / (vals.size - 1)
                        val y = size.height * (1f - ((v - lo) / range).toFloat())
                        if (!connected) path.moveTo(x, y) else path.lineTo(x, y)
                        connected = true
                    } else connected = false
                }
                drawPath(path, color, style = Stroke(width = 4f))
            }
        }
        Text("mín ${lo.f1()}  ·  máx ${hi.f1()}", style = MaterialTheme.typography.labelSmall)
    }
}
