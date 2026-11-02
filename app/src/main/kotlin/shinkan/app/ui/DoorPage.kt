package shinkan.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import shinkan.app.plan.PlanClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import shinkan.core.Door

/**
 * A door of the closed device (SPEC §8.1): the browser of block 5 or the player
 * of block 6a. Until those blocks it is a stub that names the block that fills
 * it; Back and *Close* return to the home.
 *
 * While the page is in front its time counts against the plan's budget
 * (SPEC §4.7): it reports itself to [PlanClock] on resume and on pause, and
 * stops what it plays when [PlanClock.pauses] fires ([onPause]; the stub
 * plays nothing).
 */
@Composable
fun DoorPage(door: Door, onClose: () -> Unit, onPause: () -> Unit = {}) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)
    LifecycleResumeEffect(door) {
        PlanClock.doorShown(context, door)
        onPauseOrDispose { PlanClock.doorHidden(context) }
    }
    LaunchedEffect(door) { PlanClock.pauses.collect { onPause() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(doorLabel(door)), style = MaterialTheme.typography.headlineMedium)
        val stub = if (door == Door.BROWSER) R.string.door_stub_browser else R.string.door_stub_player
        Text(stringResource(stub), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onClose) { Text(stringResource(R.string.door_close)) }
    }
}
