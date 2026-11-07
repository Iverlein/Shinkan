package shinkan.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.iverlein.shinkan.R
import shinkan.core.Battery

/** Reads the battery from the sticky `ACTION_BATTERY_CHANGED`, at once and on every change while shown. */
@Composable
private fun rememberBattery(): Battery? {
    val context = LocalContext.current
    var battery by remember { mutableStateOf<Battery?>(null) }
    DisposableEffect(context) {
        fun read(intent: Intent?) {
            if (intent == null) return
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val full = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL
            battery = Battery.of(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                plugged, full,
            )
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = read(intent)
        }
        read(
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            ),
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return battery
}

/**
 * The battery on the parent door (SPEC §8.2): a drawn cell filled to its level,
 * the percent, and whether it charges. Vermilion when low; nothing on a device
 * with no battery.
 */
@Composable
fun BatteryLine(modifier: Modifier = Modifier) {
    val battery = rememberBattery() ?: return
    val words = when (battery.power) {
        Battery.Power.BATTERY -> stringResource(R.string.battery_level, battery.percent)
        Battery.Power.CHARGING -> stringResource(R.string.battery_charging, battery.percent)
        Battery.Power.FULL -> stringResource(R.string.battery_full, battery.percent)
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val fill = if (battery.low) MaterialTheme.colorScheme.primary else ink
    val ground = MaterialTheme.colorScheme.background
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = words },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(width = 30.dp, height = 15.dp)) {
            val line = 1.5.dp.toPx()
            val nub = 2.5.dp.toPx()
            val body = Size(size.width - nub - line / 2, size.height - line)
            val corner = CornerRadius(3.dp.toPx())
            drawRoundRect(ink, Offset(line / 2, line / 2), body, corner, style = Stroke(line))
            drawRoundRect(
                ink, Offset(size.width - nub, size.height * 0.3f), Size(nub, size.height * 0.4f),
                CornerRadius(1.dp.toPx()),
            )
            val gap = line + 1.5.dp.toPx()
            val inner = Size(body.width + line - 2 * gap, size.height - 2 * gap)
            if (battery.percent > 0) {
                drawRoundRect(
                    fill, Offset(gap, gap), Size(inner.width * battery.percent / 100f, inner.height),
                    CornerRadius(1.5.dp.toPx()),
                )
            }
            if (battery.power == Battery.Power.CHARGING) {
                // A bolt over the fill, cut out in the ground's colour so it reads on any level.
                val w = size.width - nub
                val h = size.height
                val bolt = Path().apply {
                    moveTo(w * 0.56f, h * 0.08f)
                    lineTo(w * 0.30f, h * 0.56f)
                    lineTo(w * 0.48f, h * 0.56f)
                    lineTo(w * 0.42f, h * 0.92f)
                    lineTo(w * 0.70f, h * 0.42f)
                    lineTo(w * 0.52f, h * 0.42f)
                    close()
                }
                drawPath(bolt, ground)
                drawPath(bolt, ink, style = Stroke(1.dp.toPx()))
            }
        }
        Text(words, color = if (battery.low) fill else MaterialTheme.colorScheme.onSurface)
    }
}
