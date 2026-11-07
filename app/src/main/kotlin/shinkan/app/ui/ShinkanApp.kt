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
 * [onRelease] hands the device back; [onApplied] follows the editor's
 * *Apply now* (the form may have changed); [onRest] follows the open device's
 * rest starting or ending; [onTheme] pins the theme.
 */
@Composable
fun ShinkanApp(
    hold: DeviceHold,
    store: PolicyStore,
    theme: ThemePin,
    onTheme: (ThemePin) -> Unit,
    onApplied: () -> Unit,
    onRest: () -> Unit,
    onSetUp: () -> Unit,
    onRelease: () -> Unit,
) {
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
        if (door !in store.policy.doorsAt(LocalDateTime.now(), counted)) page = Page.HOME
    }
    // The open device's rest (SPEC §4.7): pinned while it runs; at its end the task steps back,
    // and whatever was in front comes back, unless a parent is on the door.
    val resting = store.policy.mode == Mode.OPEN && counted.resting
    var rested by remember { mutableStateOf(false) }
    LaunchedEffect(resting) {
        onRest()
        if (rested && !resting && page == Page.HOME) (context as? android.app.Activity)?.moveTaskToBack(true)
        rested = resting
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
    // The hold taken from the device itself (SPEC §3.3) lands while Shinkan may stay on screen.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val taken by shinkan.app.admin.SelfHold.stage.collectAsState()
        LaunchedEffect(taken) { owner = hold.isOwner }
    }
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
        // The doors fill the screen; every other page keeps a margin.
        val door = owner && pinSet && (page == Page.BROWSER || page == Page.PLAYER)
        Column(Modifier.safeDrawingPadding().padding(if (door) 0.dp else 24.dp)) {
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
                    hold, store, counted, theme, onTheme, onApplied,
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
                resting -> RestPage(store.policy, counted, onParent = { page = Page.DOOR_PIN })
                else -> HomePage(
                    store, counted,
                    onDoor = { page = if (it == Door.BROWSER) Page.BROWSER else Page.PLAYER },
                    onParent = { page = Page.DOOR_PIN },
                )
            }
        }
    }
}
