package shinkan.app.ui.parent

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import shinkan.app.admin.DeviceHold
import shinkan.core.Mode
import shinkan.core.Policy
import shinkan.core.PolicyText
import shinkan.core.Window
import java.time.DayOfWeek

/**
 * The closed apps of the open device (SPEC §4.2, §8.2): every launchable
 * package by its name, with a check to close it and its windows, each added by
 * a dialog of weekdays and hours or removed. On the closed device the page says
 * that the form suspends every app anyway.
 */
@Composable
fun AppsPage(policy: Policy, hold: DeviceHold, onChange: (Policy) -> Unit) {
    val pm = LocalContext.current.packageManager
    val apps = remember {
        val launchable = hold.launchablePackages()
        (launchable + policy.closed.filter { it !in launchable })
            .map { it to label(pm, it) }
            .sortedBy { it.second.lowercase() }
    }
    var windowFor by remember { mutableStateOf<Pair<String, String>?>(null) }

    Text(stringResource(if (policy.mode == Mode.CLOSED) R.string.apps_closed_form else R.string.apps_intro))
    Spacer(Modifier.height(12.dp))
    apps.forEach { (pkg, name) ->
        val closed = pkg in policy.closed
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(closed, role = Role.Checkbox) { on ->
                    onChange(
                        if (on) policy.copy(closed = policy.closed + pkg)
                        else policy.copy(closed = policy.closed - pkg, windows = policy.windows.filter { it.pkg != pkg }),
                    )
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = closed, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
            Column {
                Text(name)
                Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (closed) {
            Column(Modifier.padding(start = 40.dp)) {
                val windows = policy.windows.filter { it.pkg == pkg }
                if (windows.isEmpty()) Text(stringResource(R.string.apps_always), style = MaterialTheme.typography.bodySmall)
                windows.forEach { w ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(windowText(w), Modifier.weight(1f))
                        TextButton(onClick = { onChange(policy.copy(windows = policy.windows - w)) }) {
                            Text(stringResource(R.string.apps_window_remove))
                        }
                    }
                }
                TextButton(onClick = { windowFor = pkg to name }) { Text(stringResource(R.string.apps_window_add)) }
            }
        }
    }

    windowFor?.let { (pkg, name) ->
        WindowDialog(pkg, name, onDismiss = { windowFor = null }) { w ->
            onChange(policy.copy(windows = policy.windows + w))
            windowFor = null
        }
    }
}

private fun label(pm: PackageManager, pkg: String): String =
    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

@Composable
private fun windowText(w: Window): String {
    val days = mutableListOf<String>()
    DayOfWeek.entries.forEach { if (it in w.days) days += stringResource(dayLabel(it)) }
    return "${days.joinToString(", ")}  ${PolicyText.time(w.from)}–${PolicyText.time(w.to)}"
}

@Composable
private fun WindowDialog(pkg: String, name: String, onDismiss: () -> Unit, onDone: (Window) -> Unit) {
    var days by remember { mutableStateOf(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) }
    var from by remember { mutableStateOf("10:00") }
    var to by remember { mutableStateOf("12:00") }
    val f = PolicyText.time(from)
    val t = PolicyText.time(to)
    val why = when {
        days.isEmpty() -> stringResource(R.string.apps_no_day)
        f == null || t == null -> stringResource(R.string.stage_bad_time)
        f == t -> stringResource(R.string.stage_same_time)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_window_title, name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayChips(days) { d -> days = if (d in days) days - d else days + d }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.stage_from), from, Modifier.weight(1f), time = true) { from = it }
                    NumberField(stringResource(R.string.stage_to), to, Modifier.weight(1f), time = true) { to = it }
                }
                if (why != null) Text(why, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = why == null, onClick = { onDone(Window(pkg, days, f!!, t!!)) }) { Text(stringResource(R.string.edit_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
}
