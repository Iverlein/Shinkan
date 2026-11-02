package shinkan.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
 * A door of the closed device (SPEC §8.1): the browser ([BrowserPage]) or the
 * player of block 6a, until then a stub that names its block; Back and *Close*
 * return to the home. A door fills the screen, without the pages' margin.
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
        DoorVolume.open(context, door)
        onPauseOrDispose {
            PlanClock.doorHidden(context)
            DoorVolume.close(context)
        }
    }
    LaunchedEffect(door) { PlanClock.pauses.collect { onPause() } }
    // Before the first unlock after a boot the credential storage the doors need (the WebView's
    // data, the player's cache) is shut: the door says so instead of opening (SPEC §8.1).
    if (!context.getSystemService(android.os.UserManager::class.java).isUserUnlocked) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(doorLabel(door)), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.door_locked), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onClose) { Text(stringResource(R.string.door_close)) }
        }
        return
    }
    if (door == Door.BROWSER) {
        BrowserPage(onClose)
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(doorLabel(door)), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.door_stub_player), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onClose) { Text(stringResource(R.string.door_close)) }
    }
}
