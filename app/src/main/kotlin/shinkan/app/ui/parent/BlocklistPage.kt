package shinkan.app.ui.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import shinkan.app.filter.BlocklistStore
import shinkan.app.filter.FilterService
import shinkan.core.net.Blocklist
import shinkan.core.net.BlocklistText
import shinkan.core.net.Category

/**
 * The block list (SPEC §6, §8.2): the built-in categories, each a switch, with
 * ads and trackers always on; and the whole list as its text (sites, creators,
 * categories, passes), editable and read back by its parser. Unlike the policy
 * it is not a draft: a change is stored and the filter reads it at once, as a
 * shared link is (§6.2).
 */
@Composable
fun BlocklistPage() {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val store = remember { BlocklistStore(context) }
    var list by remember { mutableStateOf(store.blocklist) }
    var text by remember(list) { mutableStateOf(BlocklistText.render(list).trimEnd()) }
    var note by remember { mutableStateOf<String?>(null) }
    var wrong by remember { mutableStateOf(false) }
    fun save(next: Blocklist) {
        store.blocklist = next
        list = next
        FilterService.start(context, reload = true)
    }

    Text(stringResource(R.string.blocklist_intro))
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.blocklist_ads), style = MaterialTheme.typography.bodyMedium)
    Category.entries.forEach { c ->
        val on = c in list.categories
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(on, role = Role.Switch) { save(list.copy(categories = if (it) list.categories + c else list.categories - c)) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(categoryLabel(c)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = on, onCheckedChange = null)
        }
    }
    Spacer(Modifier.height(16.dp))
    // The player's mode (SPEC §7.1): a block list or an allow list of creators, both kept.
    Text(stringResource(R.string.player_mode), style = MaterialTheme.typography.titleSmall)
    RadioRow(stringResource(R.string.player_mode_block), list.player == shinkan.core.net.PlayerMode.BLOCK) {
        save(list.copy(player = shinkan.core.net.PlayerMode.BLOCK))
    }
    RadioRow(stringResource(R.string.player_mode_allow), list.player == shinkan.core.net.PlayerMode.ALLOW) {
        save(list.copy(player = shinkan.core.net.PlayerMode.ALLOW))
    }
    var cleared by remember { mutableStateOf(false) }
    androidx.compose.material3.TextButton(onClick = {
        shinkan.app.player.PlayerHistory.clear(context)
        cleared = true
    }) { Text(stringResource(R.string.player_history_clear)) }
    if (cleared) Text(stringResource(R.string.player_history_cleared), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.blocklist_text), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            note = null
        },
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
        minLines = 5,
        isError = wrong,
    )
    note?.let {
        Text(it, color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Button(onClick = {
            try {
                save(BlocklistText.parse(text))
                wrong = false
                note = resources.getString(R.string.blocklist_saved)
            } catch (e: BlocklistText.Malformed) {
                wrong = true
                note = resources.getString(R.string.text_malformed, e.line, "${e.why}: ${e.text}")
            }
        }) { Text(stringResource(R.string.blocklist_save)) }
        OutlinedButton(onClick = {
            text = BlocklistText.render(list).trimEnd()
            wrong = false
            note = null
        }) { Text(stringResource(R.string.text_reset_list)) }
    }
}

private fun categoryLabel(c: Category): Int = when (c) {
    Category.GAMBLING -> R.string.category_gambling
    Category.PORN -> R.string.category_porn
    Category.FAKENEWS -> R.string.category_fakenews
    Category.SOCIAL -> R.string.category_social
}

/** One line under the section's name on the door. */
@Composable
fun blocklistSummary(): String {
    val context = LocalContext.current
    val list = remember { BlocklistStore(context).blocklist }
    val on = mutableListOf<String>()
    Category.entries.forEach { if (it in list.categories) on += stringResource(categoryLabel(it)) }
    return stringResource(
        R.string.summary_blocklist, list.sites.size, list.creators.size,
        if (on.isEmpty()) stringResource(R.string.summary_blocklist_ads) else on.joinToString(", "),
    )
}
