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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.iverlein.shinkan.R
import kotlinx.coroutines.delay
import shinkan.app.admin.PolicyStore
import shinkan.core.Door
import shinkan.core.PlanState
import shinkan.core.Policy
import shinkan.core.Stage
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * The child's home on the closed device (SPEC §8.1): two large doors, the
 * browser and the player, one above the other in portrait and side by side in
 * landscape; a door the plan has closed is greyed and says when it opens next.
 * An open door with a budget says what is left of it. During a rest the
 * home is the rest page (§8.7). Back does nothing here: there is nowhere
 * behind the home. A small *Parent* link leads to the door.
 */
@Composable
fun HomePage(store: PolicyStore, counted: PlanState, onDoor: (Door) -> Unit, onParent: () -> Unit) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val policy = remember(now, counted) { store.policy }
    val state = policy.resolve(now, counted)
    val open = policy.openAt(now, state)

    // Look again at the next edge of the plan, and at least once a minute.
    LaunchedEffect(now) {
        val next = policy.nextChange(now, state)
        val wait = next?.let { Duration.between(LocalDateTime.now(), it).toMillis() } ?: Long.MAX_VALUE
        delay(wait.coerceIn(1_000, 60_000))
        now = LocalDateTime.now()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { now = LocalDateTime.now() }
    BackHandler {}
    if (state.resting) {
        RestPage(policy, state, onParent)
        return
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize()) {
        val doors: @Composable (Modifier) -> Unit = { m ->
            for (door in listOf(Door.BROWSER, Door.PLAYER)) {
                val isOpen = door in open
                val left = policy.budgetLeft(now, state)?.takeIf { isOpen && door in budgetDoors(policy, state) }
                val note = when {
                    left != null -> stringResource(R.string.home_left, (left + 59) / 60)
                    isOpen -> null
                    else -> opensNote(policy.nextOpening(door, now, state), now)
                }
                DoorButton(door, isOpen, note, m) { onDoor(door) }
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
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onParent) { Text(stringResource(R.string.home_parent)) }
    }
}

@Composable
private fun DoorButton(door: Door, open: Boolean, note: String?, modifier: Modifier, onClick: () -> Unit) {
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
            Text(stringResource(doorLabel(door)), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            if (note != null) {
                Text(note, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** The doors the running budget counts, or none when no budget runs. */
private fun budgetDoors(policy: Policy, state: PlanState): Set<Door> =
    (state.day?.let { policy.planFor(it) }?.stages?.getOrNull(state.stage) as? Stage.Budget)?.doors.orEmpty()

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
    val day = at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    return stringResource(R.string.home_opens_on, day, time)
}
