package shinkan.app.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.iverlein.shinkan.R
import kotlinx.coroutines.delay
import shinkan.app.admin.PolicyStore
import shinkan.core.Door
import shinkan.core.Mode
import shinkan.core.PlanState
import shinkan.core.Policy
import shinkan.core.Stage
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

/**
 * The child's home (SPEC §8.1), on the open device the page Shinkan opens on, with
 * the closed apps under the doors: two large doors, the
 * browser and the player, one above the other in portrait and side by side in
 * landscape; a door the plan has closed is greyed and says when it opens next.
 * An open door with a budget says what is left of it. During a rest the
 * doors of the spent budget are greyed with a small ensō in its current state
 * and the minutes left (§8.1, §8.7). Back does nothing here: there is nowhere
 * behind the home. A small *Parent* link leads to the door.
 */
@Composable
fun HomePage(store: PolicyStore, counted: PlanState, onDoor: (Door) -> Unit, onParent: () -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val policy = remember(now, counted) { store.policy }
    val state = policy.resolve(now, counted)
    val open = policy.doorsAt(now, state)
    val held = policy.mode == Mode.CLOSED

    // Look again at the next edge of the plan, and at least once a minute.
    LaunchedEffect(now) {
        val next = policy.nextChange(now, state)
        val wait = next?.let { Duration.between(LocalDateTime.now(), it).toMillis() } ?: Long.MAX_VALUE
        // During a rest the ensō on the doors closes as it passes: look again every few seconds.
        delay(wait.coerceIn(1_000, if (state.resting) 5_000 else 60_000))
        now = LocalDateTime.now()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { now = LocalDateTime.now() }
    // On the open device Shinkan is an app like any other: Back leaves it for the launcher.
    BackHandler(enabled = held) {}

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize()) {
        val doors: @Composable (Modifier) -> Unit = { m ->
            for (door in listOf(Door.BROWSER, Door.PLAYER)) {
                val isOpen = door in open
                val left = policy.budgetLeft(now, state)?.takeIf { isOpen && door in budgetDoors(policy, state) }
                val rest = if (door in budgetDoors(policy, state)) restMark(policy, state, now) else null
                val note = when {
                    rest != null -> rest.minutes?.let { stringResource(R.string.home_resting, it) }
                        ?: stringResource(R.string.home_resting_parent)
                    left != null -> stringResource(R.string.home_left, (left + 59) / 60)
                    isOpen -> null
                    else -> opensNote(policy.nextOpening(door, now, state), now)
                }
                DoorButton(door, isOpen, note, rest, m) { onDoor(door) }
            }
        }
        if (landscape) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                doors(Modifier.weight(1f).fillMaxSize())
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                doors(Modifier.weight(1f).fillMaxSize())
            }
        }
        if (!held) ClosedApps(policy, now)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onParent) { Text(stringResource(R.string.home_parent)) }
    }
}

/** The open device's closed apps by name, each with when it opens next (SPEC §8.1). */
@Composable
private fun ClosedApps(policy: Policy, now: LocalDateTime) {
    val apps = policy.closedApps(now)
    if (apps.isEmpty()) return
    val pm = LocalContext.current.packageManager
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.home_closed_apps), style = MaterialTheme.typography.titleMedium)
    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
        apps.forEach { app ->
            val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(app.pkg, 0)).toString() }.getOrDefault(app.pkg)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(name, Modifier.weight(1f))
                Text(opensNote(app.opens, now), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DoorButton(door: Door, open: Boolean, note: String?, rest: RestMark?, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = open,
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The door's mark, tinted as the label is, so a greyed door greys it too.
            if (rest != null) {
                // The rest's ensō, small and in the gate's colour, in its current state (SPEC §8.1).
                Enso(rest.passed, MaterialTheme.colorScheme.primary, Modifier.size(96.dp).padding(bottom = 12.dp))
            } else {
                Icon(painterResource(doorMark(door)), contentDescription = null, Modifier.size(96.dp).padding(bottom = 12.dp))
            }
            Text(stringResource(doorLabel(door)), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** A running rest as a door shows it: how much of the ring is drawn, and the minutes left (null: a parent's rest). */
private class RestMark(val passed: Float, val minutes: Long?)

/** The rest of the running budget at [now], or null when no rest runs. */
private fun restMark(policy: Policy, state: PlanState, now: LocalDateTime): RestMark? {
    if (!state.resting) return null
    if (state.restParent) return RestMark(1f, null)
    val end = state.restEnd ?: return null
    val stage = state.day?.let { policy.planFor(it) }?.stages?.getOrNull(state.stage) as? Stage.Budget
    val total = ((stage?.rest as? shinkan.core.Rest.Minutes)?.minutes ?: 1) * 60L
    val left = Duration.between(now, end).seconds.coerceAtLeast(0)
    return RestMark((1f - left.toFloat() / total).coerceIn(0f, 1f), (left + 59) / 60)
}

/** The doors the running budget counts, or none when no budget runs. */
private fun budgetDoors(policy: Policy, state: PlanState): Set<Door> =
    (state.day?.let { policy.planFor(it) }?.stages?.getOrNull(state.stage) as? Stage.Budget)?.doors.orEmpty()

/** The drawn mark of a door: a globe for the browser, a screen with the play sign for the player, a phone for the open device. */
fun doorMark(door: Door): Int = when (door) {
    Door.BROWSER -> R.drawable.door_browser
    Door.PLAYER -> R.drawable.door_player
    Door.DEVICE -> R.drawable.door_device
}

/** The name of a door as the child reads it. */
fun doorLabel(door: Door): Int = when (door) {
    Door.BROWSER -> R.string.door_browser
    Door.PLAYER -> R.string.door_player
    Door.DEVICE -> R.string.door_device
}

/** "Opens at 16:00" today, "Opens Tue 06:00" on another day, "Closed" when not within a week. */
@Composable
private fun opensNote(at: LocalDateTime?, now: LocalDateTime): String {
    if (at == null) return stringResource(R.string.home_closed)
    val time = at.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    if (at.toLocalDate() == now.toLocalDate()) return stringResource(R.string.home_opens_at, time)
    val day = at.dayOfWeek.getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0])
    return stringResource(R.string.home_opens_on, day, time)
}
