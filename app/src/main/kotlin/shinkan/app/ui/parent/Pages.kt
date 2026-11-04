package shinkan.app.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import shinkan.app.ui.ThemePin
import shinkan.app.ui.doorLabel
import shinkan.core.Door
import shinkan.core.Mode
import shinkan.core.Policy
import shinkan.core.PolicyText
import shinkan.core.Restriction
import kotlin.math.roundToInt

/** The form of the hold (SPEC §4.1): the closed device or the open device. */
@Composable
fun FormPage(policy: Policy, onChange: (Policy) -> Unit) {
    listOf(
        Triple(Mode.CLOSED, R.string.form_closed, R.string.form_closed_body),
        Triple(Mode.OPEN, R.string.form_open, R.string.form_open_body),
    ).forEach { (mode, title, body) ->
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(policy.mode == mode, role = Role.RadioButton) { onChange(policy.copy(mode = mode)) }
                .padding(vertical = 8.dp),
        ) {
            RadioButton(selected = policy.mode == mode, onClick = null, modifier = Modifier.padding(end = 12.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The volume ceilings (SPEC §4.6): per door on the closed device, one for the
 * device on the open one, each switched on or off and set in steps of five.
 */
@Composable
fun VolumesPage(policy: Policy, onChange: (Policy) -> Unit) {
    val doors = (if (policy.mode == Mode.CLOSED) listOf(Door.BROWSER, Door.PLAYER) else listOf(Door.DEVICE)) +
        policy.volumes.keys
    Text(stringResource(R.string.volumes_intro))
    Spacer(Modifier.height(12.dp))
    doors.distinct().forEach { door ->
        val ceiling = policy.volumes[door]
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(ceiling != null, role = Role.Switch) { on ->
                    onChange(policy.copy(volumes = if (on) policy.volumes + (door to 50) else policy.volumes - door))
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(doorLabel(door)), style = MaterialTheme.typography.titleMedium)
                Text(if (ceiling == null) stringResource(R.string.volumes_none) else stringResource(R.string.volumes_ceiling, ceiling))
            }
            Switch(checked = ceiling != null, onCheckedChange = null)
        }
        if (ceiling != null) {
            Slider(
                value = ceiling.toFloat(),
                onValueChange = { v -> onChange(policy.copy(volumes = policy.volumes + (door to (v / 5).roundToInt() * 5))) },
                valueRange = 0f..100f,
                steps = 19,
            )
        }
    }
}

/**
 * The restrictions (SPEC §4.3), each a switch, with the two warnings: a way out
 * of the hold open (factory reset, safe boot, a new user, USB debugging) and a
 * way around the filter open (another VPN, a private DNS server).
 */
@Composable
fun RestrictionsPage(policy: Policy, onChange: (Policy) -> Unit) {
    val set = policy.restrictions
    Text(stringResource(R.string.restrictions_intro))
    Spacer(Modifier.height(12.dp))
    if (!set.containsAll(HOLD)) Warning(stringResource(R.string.restrictions_warn_hold))
    if (!set.containsAll(FILTER)) Warning(stringResource(R.string.restrictions_warn_filter))
    Restriction.entries.forEach { r ->
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(r in set, role = Role.Switch) { on -> onChange(policy.copy(restrictions = if (on) set + r else set - r)) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(restrictionLabel(r)))
                Text(r.key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = r in set, onCheckedChange = null)
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(enabled = set != Restriction.DEFAULT, onClick = { onChange(policy.copy(restrictions = Restriction.DEFAULT)) }) {
        Text(stringResource(R.string.restrictions_default))
    }
}

/** The restrictions whose absence opens a way out of the hold. */
private val HOLD = setOf(Restriction.FACTORY_RESET, Restriction.SAFE_BOOT, Restriction.ADD_USER, Restriction.DEBUGGING_FEATURES)

/** The restrictions whose absence opens a way around the filter. */
private val FILTER = setOf(Restriction.CONFIG_VPN, Restriction.CONFIG_PRIVATE_DNS)

@Composable
private fun Warning(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

private fun restrictionLabel(r: Restriction): Int = when (r) {
    Restriction.FACTORY_RESET -> R.string.restriction_factory_reset
    Restriction.SAFE_BOOT -> R.string.restriction_safe_boot
    Restriction.ADD_USER -> R.string.restriction_add_user
    Restriction.INSTALL_APPS -> R.string.restriction_install_apps
    Restriction.INSTALL_UNKNOWN_SOURCES -> R.string.restriction_install_unknown_sources
    Restriction.UNINSTALL_APPS -> R.string.restriction_uninstall_apps
    Restriction.MODIFY_ACCOUNTS -> R.string.restriction_modify_accounts
    Restriction.CONFIG_DATE_TIME -> R.string.restriction_config_date_time
    Restriction.DEBUGGING_FEATURES -> R.string.restriction_debugging_features
    Restriction.USB_FILE_TRANSFER -> R.string.restriction_usb_file_transfer
    Restriction.CONFIG_CREDENTIALS -> R.string.restriction_config_credentials
    Restriction.CONFIG_PRIVATE_DNS -> R.string.restriction_config_private_dns
    Restriction.CONFIG_VPN -> R.string.restriction_config_vpn
}

/** The theme pin (SPEC §8.6): the system's setting, rice paper or sumi; it holds at once. */
@Composable
fun ThemePage(theme: ThemePin, onTheme: (ThemePin) -> Unit) {
    ThemePin.entries.forEach { pin -> RadioRow(stringResource(pin.label), pin == theme) { onTheme(pin) } }
}

/**
 * The policy as its text (SPEC §4.4): rendered, editable, read back by the
 * parser on *Use this text*; a malformed line is named by its number and the
 * draft stays as it was.
 */
@Composable
fun TextPage(policy: Policy, onChange: (Policy) -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var text by remember(policy) { mutableStateOf(PolicyText.render(policy).trimEnd()) }
    var note by remember { mutableStateOf<String?>(null) }
    var wrong by remember { mutableStateOf(false) }
    Text(stringResource(R.string.text_intro))
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            note = null
        },
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        minLines = 8,
        isError = wrong,
    )
    note?.let {
        Text(it, color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = {
            try {
                onChange(PolicyText.parse(text))
                wrong = false
                note = resources.getString(R.string.text_used)
            } catch (e: PolicyText.Malformed) {
                wrong = true
                note = resources.getString(R.string.text_malformed, e.line, "${resources.problem(e.problem, e.detail)}: ${e.text}")
            } catch (e: IllegalArgumentException) {
                wrong = true
                note = resources.getString(R.string.text_invalid)
            }
        }) { Text(stringResource(R.string.text_use)) }
        OutlinedButton(onClick = {
            text = PolicyText.render(policy).trimEnd()
            wrong = false
            note = null
        }) { Text(stringResource(R.string.text_reset)) }
    }
}

/**
 * The browser door's start page (SPEC §7.6): an address, or a host made one,
 * or nothing, which opens the door on its address bar alone.
 */
@Composable
fun StartPage(policy: Policy, onChange: (Policy) -> Unit) {
    var text by remember(policy) { mutableStateOf(policy.start ?: "") }
    Text(stringResource(R.string.start_intro))
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.start_label)) },
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = { onChange(policy.copy(start = shinkan.core.net.Address.of(text))) }) { Text(stringResource(R.string.start_use)) }
        OutlinedButton(onClick = { onChange(policy.copy(start = null)) }) { Text(stringResource(R.string.start_none)) }
    }
}
