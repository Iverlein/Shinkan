package shinkan.app.ui.parent

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import shinkan.app.filter.BlocklistStore
import shinkan.core.net.SegmentKind
import shinkan.core.net.Segments

/**
 * Which segments the player skips and who is asked for them (SPEC §7.5, §8.2). Kept
 * with the block list, and like it stored at once rather than drafted.
 */
@Composable
fun SegmentsPage() {
    val context = LocalContext.current
    val store = remember { BlocklistStore(context) }
    var list by remember { mutableStateOf(store.blocklist) }
    var mirror by remember { mutableStateOf(list.mirror.orEmpty()) }
    var wrong by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Text(stringResource(R.string.segments_intro))
    Spacer(Modifier.height(12.dp))
    SegmentKind.entries.forEach { kind ->
        CheckRow(stringResource(segmentLabel(kind)), kind in list.segments) { on ->
            list = store.change(context, list.copy(segments = if (on) list.segments + kind else list.segments - kind))
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.segments_mirror), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = mirror,
        onValueChange = {
            mirror = it
            wrong = false
            saved = false
        },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(Segments.API) },
        singleLine = true,
        isError = wrong,
    )
    if (wrong) Text(stringResource(R.string.segments_mirror_wrong), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    if (saved) Text(stringResource(R.string.segments_mirror_saved), modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(8.dp))
    Button(onClick = {
        val typed = mirror.trim()
        val next = if (typed.isEmpty()) null else Segments.mirror(typed)
        if (typed.isNotEmpty() && next == null) {
            wrong = true
        } else {
            list = store.change(context, list.copy(mirror = next))
            mirror = next.orEmpty()
            saved = true
        }
    }) { Text(stringResource(R.string.segments_mirror_save)) }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.segments_credit), style = MaterialTheme.typography.bodySmall)
}

private fun segmentLabel(kind: SegmentKind): Int = when (kind) {
    SegmentKind.SPONSOR -> R.string.segment_sponsor
    SegmentKind.SELFPROMO -> R.string.segment_selfpromo
    SegmentKind.INTERACTION -> R.string.segment_interaction
    SegmentKind.INTRO -> R.string.segment_intro
    SegmentKind.OUTRO -> R.string.segment_outro
    SegmentKind.PREVIEW -> R.string.segment_preview
    SegmentKind.MUSIC_OFFTOPIC -> R.string.segment_music_offtopic
    SegmentKind.FILLER -> R.string.segment_filler
}

/** One line under the section's name on the door. */
@Composable
fun segmentsSummary(): String {
    val context = LocalContext.current
    val list = remember { BlocklistStore(context).blocklist }
    if (list.segments.isEmpty()) return stringResource(R.string.summary_segments_off)
    return SegmentKind.entries.filter { it in list.segments }.map { stringResource(segmentLabel(it)) }.joinToString(", ")
}
