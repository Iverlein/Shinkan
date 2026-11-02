package shinkan.app.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import kotlinx.coroutines.delay
import shinkan.app.plan.PlanClock
import shinkan.core.PlanState
import shinkan.core.Policy
import shinkan.core.Rest
import shinkan.core.Stage
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.sin

/**
 * The rest (SPEC §8.7): an ensō in vermilion that closes clockwise as the rest
 * passes, the minutes left under it in large figures; whole and still when the
 * rest is the parent's to end. Nothing else but the *Parent* link. Back does
 * nothing: on the closed device this page is the home while a rest runs.
 */
@Composable
fun RestPage(policy: Policy, state: PlanState, onParent: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val end = state.restEnd
    val minutes = state.day?.let { policy.planFor(it) }?.stages?.getOrNull(state.stage)
        ?.let { (it as? Stage.Budget)?.rest as? Rest.Minutes }?.minutes
    LaunchedEffect(end) {
        while (end != null) {
            delay(1000)
            now = LocalDateTime.now()
            // The alarm applies the plan at the end; this is the page's own look at it.
            if (now >= end) {
                PlanClock.refresh(context)
                break
            }
        }
    }
    BackHandler {}

    val left = end?.let { Duration.between(now, it).seconds.coerceAtLeast(0) }
    val total = (minutes ?: 1) * 60L
    val passed = if (left == null) 1f else (1f - left.toFloat() / total).coerceIn(0f, 1f)
    val ring: @Composable (Modifier) -> Unit = { m -> Enso(passed, MaterialTheme.colorScheme.primary, m) }
    val words: @Composable () -> Unit = {
        if (left == null) {
            Text(stringResource(R.string.rest_parent), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        } else {
            val shown = (left + 59) / 60
            Text(shown.toString(), style = MaterialTheme.typography.displayLarge)
            Text(stringResource(R.string.rest_minutes_left), style = MaterialTheme.typography.titleMedium)
        }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize()) {
        if (landscape) {
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ring(Modifier.fillMaxSize().aspectRatio(1f)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { words() }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                ring(Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(24.dp))
                words()
            }
        }
        TextButton(onClick = onParent) { Text(stringResource(R.string.home_parent)) }
    }
}

/**
 * The ensō: a faint whole ring, the circle to be completed, and over it one
 * brush stroke from the top, closing clockwise to [passed] of the circle. The
 * stroke is wide where the brush lands and thins as it travels, with a slight
 * swell, and a dry thin trail runs beside it; its width depends on the place on
 * the circle, not on the time, so the stroke grows without changing shape.
 */
@Composable
private fun Enso(passed: Float, colour: Color, modifier: Modifier) {
    Canvas(modifier) {
        val base = size.minDimension
        val radius = base * 0.40f
        drawCircle(colour.copy(alpha = 0.10f), radius, center, style = Stroke(width = base * 0.012f))
        val sweep = 360f * passed
        if (sweep <= 0f) return@Canvas
        fun width(angle: Float): Float {
            val p = angle / 360f
            return base * (0.085f - 0.045f * p + 0.006f * sin(p * 13f))
        }
        fun point(angle: Float, r: Float = radius): Offset {
            val a = Math.toRadians((angle - 90f).toDouble())
            return Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
        }
        val steps = (sweep / 1.5f).toInt().coerceAtLeast(1)
        val step = sweep / steps
        for (i in 0 until steps) {
            val angle = i * step
            arc(colour, width(angle + step / 2), radius, angle - 90f, step + 0.6f, StrokeCap.Butt)
        }
        // The brush's landing and its lift.
        drawCircle(colour, width(0f) / 2, point(0f))
        drawCircle(colour, width(sweep) / 2, point(sweep))
        // The dry trail along the outer edge.
        arc(colour.copy(alpha = 0.35f), base * 0.010f, radius + width(0f) * 0.55f, -88f, sweep * 0.94f, StrokeCap.Round)
    }
}

private fun DrawScope.arc(colour: Color, width: Float, radius: Float, start: Float, sweep: Float, cap: StrokeCap) {
    if (sweep <= 0f) return
    drawArc(
        color = colour,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = width, cap = cap),
    )
}
