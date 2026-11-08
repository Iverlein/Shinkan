package shinkan.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import kotlinx.coroutines.delay

/**
 * The door's volume bar (SPEC §4.6), shown for a moment after each press of a
 * volume key in place of the system's panel, whose slider would pass the
 * ceiling. It is full at the door's ceiling: as loud as this door may play.
 */
@Composable
fun VolumeBar(modifier: Modifier = Modifier) {
    val shown by DoorVolume.shown.collectAsState()
    LaunchedEffect(shown) {
        val press = shown ?: return@LaunchedEffect
        delay(SHOWN_MS)
        DoorVolume.shown.compareAndSet(press, null)
    }
    // The last press stays drawn while the bar fades out.
    val last = remember { arrayOfNulls<DoorVolume.Shown>(1) }
    shown?.let { last[0] = it }
    AnimatedVisibility(shown != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        val press = last[0] ?: return@AnimatedVisibility
        val fill = if (press.top > 0) press.index.toFloat() / press.top else 0f
        val label = stringResource(R.string.door_volume, press.index, press.top)
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 4.dp,
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Box(
                Modifier.padding(8.dp).size(width = 32.dp, height = 200.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)),
            ) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(fill.coerceIn(0f, 1f))
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                )
            }
        }
    }
}

private const val SHOWN_MS = 1500L
