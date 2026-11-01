package shinkan.app.ui

import android.content.Intent
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import shinkan.app.LauncherIcon
import shinkan.app.admin.DeviceHold
import shinkan.app.admin.PolicyStore
import java.time.LocalDateTime

/**
 * The three pages (SPEC §8): setup until Shinkan is device owner; the child's
 * home with the apps usable now; the parent door. The PIN gate (§9.1) and the
 * policy editor are ROADMAP blocks 2 and 3; until then the door opens on a tap.
 */
@Composable
fun ShinkanApp(hold: DeviceHold, store: PolicyStore) {
    var parent by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(24.dp)) {
            when {
                !hold.isOwner -> SetupPage(hold.provisionCommand)
                parent -> ParentPage(store, onBack = { parent = false })
                else -> HomePage(hold, store, onParent = { parent = true })
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
private fun ParentPage(store: PolicyStore, onBack: () -> Unit) {
    Text(stringResource(R.string.parent_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.parent_status_held))
    Text(stringResource(R.string.parent_allowed, store.policy.allowed.size))
    Spacer(Modifier.height(24.dp))
    IconChoice(store)
    Spacer(Modifier.height(24.dp))
    Button(onClick = onBack) { Text(stringResource(R.string.parent_back)) }
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
