package shinkan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF111311)
private val Paper = Color(0xFFF5F1E8)
private val Gate = Color(0xFF8A2B1F)

@Composable
fun ShinkanTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Gate, background = Ink, surface = Ink, onBackground = Paper, onSurface = Paper)
    } else {
        lightColorScheme(primary = Gate, background = Paper, surface = Paper, onBackground = Ink, onSurface = Ink)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
