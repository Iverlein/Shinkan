package shinkan.app.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.iverlein.shinkan.R
import kotlinx.coroutines.delay
import shinkan.app.LauncherIcon
import shinkan.app.admin.DeviceHold
import shinkan.app.admin.PolicyStore
import shinkan.core.Door
import shinkan.core.Mode
import shinkan.core.PlanState
import shinkan.core.Policy
import shinkan.core.PolicyText
import shinkan.app.plan.PlanClock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where the app stands: the child's home, a door, the PIN before the parent door, that door, the PIN before release. */
private enum class Page { HOME, BROWSER, PLAYER, DOOR_PIN, DOOR, RELEASE_PIN }

/**
 * The pages (SPEC §8): setup until Shinkan is device owner; the PIN set at the
 * first opening after that (§8.3); the child's home; the parent door behind
 * the PIN (§8.2, §9.1), which closes to the home after [DoorIdle] without a
 * touch and whenever Shinkan leaves the screen; release behind the PIN once
 * more (§9.3). [onSetUp] runs once the PIN is set and applies the hold;
 * [onRelease] hands the device back. The policy editor is ROADMAP block 3e.
 */
@Composable
fun ShinkanApp(hold: DeviceHold, store: PolicyStore, onSetUp: () -> Unit, onRelease: () -> Unit) {
    var page by remember { mutableStateOf(Page.HOME) }
    var owner by remember { mutableStateOf(hold.isOwner) }
    var pinSet by remember { mutableStateOf(store.pinHash != null) }
    var lastTouch by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val context = LocalContext.current
    val counted by PlanClock.state.collectAsState()
    // A door the plan has closed (a budget spent, a span ended) gives way to the home (SPEC §4.7).
    LaunchedEffect(page, counted) {
        val door = when (page) {
            Page.BROWSER -> Door.BROWSER
            Page.PLAYER -> Door.PLAYER
            else -> return@LaunchedEffect
        }
        if (door !in store.policy.openAt(LocalDateTime.now(), counted)) page = Page.HOME
    }
    val behindPin = page == Page.DOOR || page == Page.DOOR_PIN || page == Page.RELEASE_PIN

    // The door closes after two minutes without a touch (SPEC §8.2)...
    LaunchedEffect(behindPin) {
        if (!behindPin) return@LaunchedEffect
        lastTouch = SystemClock.uptimeMillis()
        val idle = DoorIdle.millis()
        while (true) {
            delay(1000)
            if (SystemClock.uptimeMillis() - lastTouch >= idle) {
                page = Page.HOME
                break
            }
        }
    }
    // ...and as soon as Shinkan leaves the screen; the owner is read again on return.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (behindPin) page = Page.HOME }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { owner = hold.isOwner }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { PlanClock.refresh(context) }

    Surface(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastTouch = SystemClock.uptimeMillis()
                    }
                }
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.safeDrawingPadding().padding(24.dp)) {
            when {
                !owner -> SetupPage(hold.provisionCommand)
                !pinSet -> PinPage(
                    store, PinPurpose.SET, stringResource(R.string.pin_set_title),
                    onDone = {
                        pinSet = true
                        page = Page.HOME
                        onSetUp()
                    },
                )
                page == Page.DOOR_PIN -> PinPage(
                    store, PinPurpose.ENTER, stringResource(R.string.parent_title),
                    onDone = { page = Page.DOOR }, onCancel = { page = Page.HOME },
                )
                page == Page.DOOR -> ParentPage(
                    hold, store, counted,
                    onBack = { page = Page.HOME }, onRelease = { page = Page.RELEASE_PIN },
                )
                page == Page.RELEASE_PIN -> PinPage(
                    store, PinPurpose.ENTER, stringResource(R.string.release_title),
                    onDone = {
                        onRelease()
                        owner = hold.isOwner
                        page = Page.HOME
                    },
                    onCancel = { page = Page.DOOR },
                )
                page == Page.BROWSER -> DoorPage(Door.BROWSER, onClose = { page = Page.HOME })
                page == Page.PLAYER -> DoorPage(Door.PLAYER, onClose = { page = Page.HOME })
                else -> HomePage(
                    store, counted,
                    onDoor = { page = if (it == Door.BROWSER) Page.BROWSER else Page.PLAYER },
                    onParent = { page = Page.DOOR_PIN },
                )
            }
        }
    }
}

@Composable
private fun SetupPage(command: String) = Column(Modifier.verticalScroll(rememberScrollState())) {
    Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.setup_body))
    Spacer(Modifier.height(16.dp))
    Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.setup_after))
}

@Composable
private fun ParentPage(
    hold: DeviceHold,
    store: PolicyStore,
    counted: PlanState,
    onBack: () -> Unit,
    onRelease: () -> Unit,
) = Column(Modifier.verticalScroll(rememberScrollState())) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    Text(stringResource(R.string.parent_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.parent_status_held))
    val form = if (store.policy.mode == Mode.CLOSED) R.string.parent_mode_closed else R.string.parent_mode_open
    Text(stringResource(form))
    Spacer(Modifier.height(16.dp))
    PlanNow(store.policy, counted) {
        // *Open now* (SPEC §8.2): the rest ends, the next stage begins, the hold follows.
        val now = LocalDateTime.now()
        store.planState = store.policy.openNow(now, store.policy.resolve(now, store.planState))
        hold.apply(now)
        PlanClock.refresh(context)
    }
    Spacer(Modifier.height(24.dp))
    IconChoice(store)
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = onBack) { Text(stringResource(R.string.parent_back)) }
        OutlinedButton(onClick = onRelease) { Text(stringResource(R.string.release_button)) }
    }
}

/** The day plan's current stage, what is left of it, and *Open now* during a rest (SPEC §8.2, §4.7). */
@Composable
private fun PlanNow(policy: Policy, counted: PlanState, onOpenNow: () -> Unit) {
    val now = LocalDateTime.now()
    val state = policy.resolve(now, counted)
    val stage = state.day?.let { policy.planFor(it) }?.stages?.getOrNull(state.stage)
    val line = when {
        policy.plans.isEmpty() -> stringResource(R.string.plan_none)
        stage == null -> stringResource(R.string.plan_done)
        else -> stringResource(R.string.plan_stage, PolicyText.render(stage))
    }
    Text(line)
    when {
        state.restParent -> Text(stringResource(R.string.plan_rest_parent))
        state.restEnd != null -> Text(stringResource(R.string.plan_rest_until, state.restEnd!!.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))))
        else -> policy.budgetLeft(now, state)?.let { Text(stringResource(R.string.plan_left, (it + 59) / 60)) }
    }
    if (state.resting) {
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenNow) { Text(stringResource(R.string.plan_open_now)) }
    }
}

/** The launcher icon (SPEC §8.5): each shown as the launcher's round mask shows it. */
@Composable
private fun IconChoice(store: PolicyStore) {
    var chosen by remember { mutableStateOf(LauncherIcon.chosen(store)) }
    Text(stringResource(R.string.parent_icon), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LauncherIcon.entries.forEach { icon ->
            val selected = icon == chosen
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = selected, role = Role.RadioButton) {
                        store.icon = icon.key
                        chosen = icon
                    }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(colorResource(R.color.paper))
                        .border(if (selected) 2.dp else 1.dp, ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // An adaptive icon shows the middle 66 of its 108 units: draw the foreground larger than the circle.
                    Image(painterResource(icon.foreground), contentDescription = null, Modifier.requiredSize(92.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(icon.label), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.parent_icon_hint), style = MaterialTheme.typography.bodySmall)
}
