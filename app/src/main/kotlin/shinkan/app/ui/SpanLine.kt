package shinkan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
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
import shinkan.core.Budget
import shinkan.core.Door
import shinkan.core.PolicyText
import shinkan.core.Rest
import shinkan.core.Span

/**
 * One span of a day plan drawn, not spelled (SPEC §4.7, §8.2): its hours and the
 * marks of the doors open in it, with its [budgets] under it unless the caller
 * draws them. Each mark is named for the screen reader.
 */
@Composable
fun SpanLine(span: Span, modifier: Modifier = Modifier, budgets: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${PolicyText.time(span.from)}–${PolicyText.time(span.to)}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(4.dp))
            if (span.doors.isEmpty()) Text(stringResource(R.string.stage_closed))
            DoorMarks(span.doors)
        }
        if (budgets) span.budgets.forEach { BudgetLine(it, Modifier.padding(start = 16.dp)) }
    }
}

/**
 * A budget drawn: the marks of its doors, the hourglass and its minutes, then the
 * ensō and the rest's minutes, or the ensō and the key for a rest a parent ends.
 */
@Composable
fun BudgetLine(budget: Budget, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DoorMarks(budget.doors)
        Spacer(Modifier.width(4.dp))
        Mark(R.drawable.mark_budget, stringResource(R.string.stage_minutes))
        Text(stringResource(R.string.minutes_short, budget.minutes), style = MaterialTheme.typography.titleMedium)
        when (val rest = budget.rest) {
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

/** The marks of [doors], in the doors' order. */
@Composable
fun DoorMarks(doors: Set<Door>) {
    Door.entries.filter { it in doors }.forEach { Mark(doorMark(it), stringResource(doorLabel(it)), 26) }
}

@Composable
private fun Mark(icon: Int, name: String?, size: Int = 22) {
    Icon(painterResource(icon), contentDescription = name, Modifier.size(size.dp))
}
