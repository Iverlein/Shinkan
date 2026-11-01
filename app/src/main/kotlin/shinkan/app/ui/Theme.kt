package shinkan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The colours of res/values/themes.xml (SPEC §8.6).
private val Ink = Color(0xFF111311)
private val Paper = Color(0xFFF5F1E8)
private val Gate = Color(0xFF8A2B1F)
/** The lighter vermilion of the gate on an ink ground. */
private val GateLight = Color(0xFFE0654F)
private val PaperShade = Color(0xFFE6DFD0)
private val InkShade = Color(0xFF262825)

/** Rice paper: sepia paper, ink text, vermilion for the gate. */
private val RicePaper = lightColorScheme(
    primary = Gate, onPrimary = Paper,
    secondary = Gate, onSecondary = Paper,
    secondaryContainer = PaperShade, onSecondaryContainer = Ink,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = PaperShade, onSurfaceVariant = Color(0xFF4A4740),
    outline = Color(0xFF8A8478), outlineVariant = Color(0xFFCFC8B8),
)

/** Sumi: an ink ground, paper text, the lighter vermilion. */
private val Sumi = darkColorScheme(
    primary = GateLight, onPrimary = Ink,
    secondary = GateLight, onSecondary = Ink,
    secondaryContainer = InkShade, onSecondaryContainer = Paper,
    background = Ink, onBackground = Paper,
    surface = Ink, onSurface = Paper,
    surfaceVariant = InkShade, onSurfaceVariant = Color(0xFFC9C3B6),
    outline = Color(0xFF7A766C), outlineVariant = Color(0xFF3A3C38),
)

/** Rice paper by day, sumi when the system is dark (SPEC §8.6). */
@Composable
fun ShinkanTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Sumi else RicePaper, content = content)
}
