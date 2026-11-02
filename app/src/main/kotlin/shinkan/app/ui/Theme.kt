package shinkan.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.iverlein.shinkan.R

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
    // Dialogs, menus and cards: paper a shade or two darker, never Material's lavender.
    surfaceContainerLowest = Paper, surfaceContainerLow = Color(0xFFF1ECE1),
    surfaceContainer = Color(0xFFEEE8DB), surfaceContainerHigh = Color(0xFFEBE4D6),
    surfaceContainerHighest = PaperShade,
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
    surfaceContainerLowest = Ink, surfaceContainerLow = Color(0xFF181A17),
    surfaceContainer = Color(0xFF1C1E1B), surfaceContainerHigh = Color(0xFF21231F),
    surfaceContainerHighest = InkShade,
)

/** The parent's pin of the theme (SPEC §8.6): the system's setting, rice paper or sumi. */
enum class ThemePin(val key: String, val label: Int) {
    SYSTEM("system", R.string.theme_system),
    PAPER("paper", R.string.theme_paper),
    SUMI("sumi", R.string.theme_sumi),
    ;

    /** Whether this pin draws sumi, given whether the system is dark. */
    fun dark(system: Boolean): Boolean = when (this) {
        SYSTEM -> system
        PAPER -> false
        SUMI -> true
    }

    companion object {
        fun of(key: String?): ThemePin = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Rice paper by day, sumi when the system is dark, unless the parent pinned one (SPEC §8.6). */
@Composable
fun ShinkanTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Sumi else RicePaper, content = content)
}
