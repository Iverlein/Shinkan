package shinkan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import shinkan.core.Door
import shinkan.core.PolicyText
import shinkan.core.Rest
import shinkan.core.Stage

/**
 * One stage of a day plan drawn, not spelled (SPEC §4.7, §8.2): a span as its
 * hours and the marks of the doors open in it; a budget as the marks of its
 * doors, the hourglass and its minutes, then the ensō and the rest's minutes, or
 * the ensō and the key for a rest a parent ends. Each mark is named for the
 * screen reader.
 */
@Composable
fun StageLine(stage: Stage, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (stage) {
            is Stage.Span -> {
                Text("${PolicyText.time(stage.from)}–${PolicyText.time(stage.to)}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(4.dp))
                if (stage.doors.isEmpty()) Text(stringResource(R.string.stage_closed))
                Doors(stage.doors)
            }
            is Stage.Budget -> {
                Doors(stage.doors)
                Spacer(Modifier.width(4.dp))
                Mark(R.drawable.mark_budget, stringResource(R.string.stage_minutes))
                Text(stringResource(R.string.minutes_short, stage.minutes), style = MaterialTheme.typography.titleMedium)
                when (val rest = stage.rest) {
                    null -> Unit
                    is Rest.Minutes -> {
                        Spacer(Modifier.width(8.dp))
                        Mark(R.drawable.mark_rest, stringResource(R.string.stage_rest_length))
                        Text(stringResource(R.string.minutes_short, rest.minutes), style = MaterialTheme.typography.titleMedium)
                    }
                    Rest.Parent -> {
                        Spacer(Modifier.width(8.dp))
                        Mark(R.drawable.mark_rest, stringResource(R.string.stage_rest_parent))
                        Mark(R.drawable.mark_parent, null)
                    }
                }
            }
        }
    }
}

@Composable
private fun Doors(doors: Set<Door>) {
    Door.entries.filter { it in doors }.forEach { Mark(doorMark(it), stringResource(doorLabel(it)), 26) }
}

@Composable
private fun Mark(icon: Int, name: String?, size: Int = 22) {
    Icon(painterResource(icon), contentDescription = name, Modifier.size(size.dp))
}
