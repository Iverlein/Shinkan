package shinkan.app.ui

import android.content.Intent
import android.os.SystemClock
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.time.LocalDateTime

/** Where the app stands: the child's home, the PIN before the door, the door, the PIN before release. */
private enum class Page { HOME, DOOR_PIN, DOOR, RELEASE_PIN }

/**
 * The pages (SPEC §8): setup until Shinkan is device owner; the PIN set at the
 * first opening after that (§8.3); the child's home; the parent door behind
 * the PIN (§8.2, §9.1), which closes to the home after [DoorIdle] without a
 * touch and whenever Shinkan leaves the screen; release behind the PIN once
 * more (§9.3). The policy editor is ROADMAP block 3e.
 */
@Composable
fun ShinkanApp(hold: DeviceHold, store: PolicyStore) {
    var page by remember { mutableStateOf(Page.HOME) }
    var owner by remember { mutableStateOf(hold.isOwner) }
    var pinSet by remember { mutableStateOf(store.pinHash != null) }
    var lastTouch by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
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
                    onDone = { pinSet = true; page = Page.HOME },
                )
                page == Page.DOOR_PIN -> PinPage(
                    store, PinPurpose.ENTER, stringResource(R.string.parent_title),
                    onDone = { page = Page.DOOR }, onCancel = { page = Page.HOME },
                )
                page == Page.DOOR -> ParentPage(store, onBack = { page = Page.HOME }, onRelease = { page = Page.RELEASE_PIN })
                page == Page.RELEASE_PIN -> PinPage(
                    store, PinPurpose.ENTER, stringResource(R.string.release_title),
                    onDone = {
                        hold.release()
                        owner = hold.isOwner
                        page = Page.HOME
                    },
                    onCancel = { page = Page.DOOR },
                )
                else -> HomePage(hold, store, onParent = { page = Page.DOOR_PIN })
            }
        }
    }
}

@Composable
private fun SetupPage(command: String) {
    Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.setup_body))
    Spacer(Modifier.height(16.dp))
    Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.setup_after))
}

@Composable
private fun HomePage(hold: DeviceHold, store: PolicyStore, onParent: () -> Unit) {
    val context = LocalContext.current
    val usable = remember { store.policy.usableAt(LocalDateTime.now()).sorted() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        if (usable.isEmpty()) {
            Text(stringResource(R.string.home_empty), style = MaterialTheme.typography.titleMedium)
        } else {
            LazyColumn {
                items(usable) { pkg ->
                    val label = runCatching {
                        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    TextButton(onClick = {
                        context.packageManager.getLaunchIntentForPackage(pkg)
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ?.let(context::startActivity)
                    }) { Text(label, style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        TextButton(onClick = onParent) { Text(stringResource(R.string.home_parent)) }
    }
}

@Composable
private fun ParentPage(store: PolicyStore, onBack: () -> Unit, onRelease: () -> Unit) {
    Text(stringResource(R.string.parent_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.parent_status_held))
    Text(stringResource(R.string.parent_allowed, store.policy.allowed.size))
    Spacer(Modifier.height(24.dp))
    IconChoice(store)
    Spacer(Modifier.height(24.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = onBack) { Text(stringResource(R.string.parent_back)) }
        OutlinedButton(onClick = onRelease) { Text(stringResource(R.string.release_button)) }
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
