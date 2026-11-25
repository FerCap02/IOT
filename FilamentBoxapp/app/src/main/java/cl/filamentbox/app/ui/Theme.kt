package cl.filamentbox.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF00796B),
    secondary = Color(0xFF3F6B8C),
    tertiary = Color(0xFFB26A00),
    error = Color(0xFFC62828),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    secondary = Color(0xFF90CAF9),
    tertiary = Color(0xFFFFB74D),
    error = Color(0xFFEF9A9A),
)

@Composable
fun FilamentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
