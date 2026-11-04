package shinkan.app.ui.parent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import shinkan.app.ui.BudgetLine
import shinkan.app.ui.SpanLine
import shinkan.app.ui.doorLabel
import shinkan.app.ui.doorMark
import shinkan.core.Budget
import shinkan.core.Door
import shinkan.core.Mode
import shinkan.core.Plan
import shinkan.core.Policy
import shinkan.core.PolicyText
import shinkan.core.Rest
import shinkan.core.Span
import java.time.DayOfWeek
import java.time.LocalTime

/** What the open dialog edits: a span of plan [plan] at [span], or a new one when null. */
private data class EditSpan(val plan: Int, val span: Int?, val initial: Span)

/** A budget of the span at [span] of plan [plan], at [budget], or a new one when null. */
private data class EditBudget(val plan: Int, val span: Int, val budget: Int?, val initial: Budget)

/**
 * The day plans (SPEC §4.7, §8.2): one card per plan with its weekdays and its
 * spans, each moved up or down, edited or removed, and under each span its
 * budgets, added, edited and removed there. A weekday in another plan is greyed,
 * so no day is in two.
 */
@Composable
fun PlansPage(policy: Policy, onChange: (Policy) -> Unit) {
    val doors = if (policy.mode == Mode.CLOSED) listOf(Door.BROWSER, Door.PLAYER) else listOf(Door.DEVICE)
    var span by remember { mutableStateOf<EditSpan?>(null) }
    var budget by remember { mutableStateOf<EditBudget?>(null) }
    fun withPlan(i: Int, plan: Plan) = onChange(policy.copy(plans = policy.plans.toMutableList().also { it[i] = plan }))
    fun withSpan(pi: Int, si: Int, changed: Span) {
        val plan = policy.plans[pi]
        withPlan(pi, plan.copy(spans = plan.spans.toMutableList().also { it[si] = changed }))
    }

    Text(stringResource(R.string.plans_intro))
    Spacer(Modifier.height(16.dp))
    policy.plans.forEachIndexed { pi, plan ->
        OutlinedCard(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(12.dp)) {
                val taken = policy.plans.filterIndexed { j, _ -> j != pi }.flatMap { it.days }.toSet()
                DayChips(plan.days, taken) { day ->
                    val days = if (day in plan.days) plan.days - day else plan.days + day
                    if (days.isNotEmpty()) withPlan(pi, plan.copy(days = days))
                }
                Spacer(Modifier.height(8.dp))
                plan.spans.forEachIndexed { si, s ->
                    if (si > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SpanLine(s, Modifier.padding(top = 8.dp), budgets = false)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        val move: (Int) -> Unit = { to ->
                            withPlan(pi, plan.copy(spans = plan.spans.toMutableList().also { it.add(to, it.removeAt(si)) }))
                        }
                        Mini("↑", stringResource(R.string.stage_up), si > 0) { move(si - 1) }
                        Mini("↓", stringResource(R.string.stage_down), si < plan.spans.lastIndex) { move(si + 1) }
                        Mini("✎", stringResource(R.string.stage_edit)) { span = EditSpan(pi, si, s) }
                        Mini("✕", stringResource(R.string.stage_remove)) {
                            withPlan(pi, plan.copy(spans = plan.spans.filterIndexed { j, _ -> j != si }))
                        }
                    }
                    s.budgets.forEachIndexed { bi, b ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            BudgetLine(b, Modifier.weight(1f))
                            Mini("✎", stringResource(R.string.stage_edit)) { budget = EditBudget(pi, si, bi, b) }
                            Mini("✕", stringResource(R.string.stage_remove)) {
                                withSpan(pi, si, s.copy(budgets = s.budgets.filterIndexed { j, _ -> j != bi }))
                            }
                        }
                    }
                    val uncounted = Door.entries.filter { it in s.doors && s.budgets.none { b -> it in b.doors } }
                    if (uncounted.isNotEmpty()) {
                        TextButton(modifier = Modifier.padding(start = 16.dp), onClick = {
                            budget = EditBudget(pi, si, null, Budget(setOf(uncounted.last()), 30))
                        }) { Text(stringResource(R.string.plans_add_budget)) }
                    }
                }
                if (plan.spans.isEmpty()) Text(stringResource(R.string.plans_no_stage), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    span = EditSpan(pi, null, Span(LocalTime.of(8, 0), LocalTime.of(12, 0), doors.toSet()))
                }) { Text(stringResource(R.string.plans_add_span)) }
                TextButton(onClick = {
                    onChange(policy.copy(plans = policy.plans.filterIndexed { j, _ -> j != pi }))
                }) { Text(stringResource(R.string.plans_remove), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    val free = DayOfWeek.entries.filter { day -> policy.plans.none { day in it.days } }
    Button(enabled = free.isNotEmpty(), onClick = {
        onChange(policy.copy(plans = policy.plans + Plan(free.toSet(), emptyList())))
    }) { Text(stringResource(R.string.plans_add)) }

    span?.let { e ->
        SpanDialog(e.initial, (doors + e.initial.doors).distinct(), onDismiss = { span = null }) { changed ->
            val plan = policy.plans[e.plan]
            val at = e.span
            withPlan(e.plan, plan.copy(spans = if (at == null) plan.spans + changed else plan.spans.toMutableList().also { it[at] = changed }))
            span = null
        }
    }
    budget?.let { e ->
        val owner = policy.plans[e.plan].spans[e.span]
        // A budget is offered the span's doors no other budget of it counts.
        val others = owner.budgets.filterIndexed { j, _ -> j != e.budget }.flatMap { it.doors }
        BudgetDialog(e.initial, Door.entries.filter { it in owner.doors && it !in others }, onDismiss = { budget = null }) { changed ->
            val at = e.budget
            withSpan(e.plan, e.span, owner.copy(budgets = if (at == null) owner.budgets + changed else owner.budgets.toMutableList().also { it[at] = changed }))
            budget = null
        }
    }
}

/** A small button of one sign, named for the screen reader and for `emulator.py tap --desc`. */
@Composable
private fun Mini(sign: String, name: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = name }) { Text(sign) }
}

/**
 * One span: its times typed as HH:MM and the doors open in it, a door taken out of
 * the span leaving its budgets. Done stays off until the span is whole, and says why.
 */
@Composable
private fun SpanDialog(initial: Span, doors: List<Door>, onDismiss: () -> Unit, onDone: (Span) -> Unit) {
    var chosen by remember { mutableStateOf(initial.doors) }
    var from by remember { mutableStateOf(PolicyText.time(initial.from)) }
    var to by remember { mutableStateOf(PolicyText.time(initial.to)) }

    val badTime = stringResource(R.string.stage_bad_time)
    val sameTime = stringResource(R.string.stage_same_time)
    val f = PolicyText.time(from)
    val t = PolicyText.time(to)
    val (span, why) = when {
        f == null || t == null -> null to badTime
        f == t -> null to sameTime
        else -> {
            val kept = initial.budgets.mapNotNull { b ->
                (b.doors intersect chosen).takeIf { it.isNotEmpty() }?.let { b.copy(doors = it) }
            }
            Span(f, t, chosen, kept) to null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stage_span)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.stage_from), from, Modifier.weight(1f), time = true) { from = it }
                    NumberField(stringResource(R.string.stage_to), to, Modifier.weight(1f), time = true) { to = it }
                }
                Text(stringResource(R.string.stage_doors), style = MaterialTheme.typography.titleSmall)
                doors.forEach { door ->
                    CheckRow(stringResource(doorLabel(door)), door in chosen, doorMark(door)) { on -> chosen = if (on) chosen + door else chosen - door }
                }
                if (why != null) Text(why, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = span != null, onClick = { span?.let(onDone) }) { Text(stringResource(R.string.edit_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
}

/**
 * One budget of a span: its doors, its minutes and its rest, and what they mean
 * there. Done stays off until the budget is whole, and says why.
 */
@Composable
private fun BudgetDialog(initial: Budget, doors: List<Door>, onDismiss: () -> Unit, onDone: (Budget) -> Unit) {
    var chosen by remember { mutableStateOf(initial.doors) }
    var minutes by remember { mutableStateOf(initial.minutes.toString()) }
    var rest by remember { mutableStateOf(initial.rest) }
    var restMinutes by remember { mutableStateOf(((initial.rest as? Rest.Minutes)?.minutes ?: 30).toString()) }

    val noDoor = stringResource(R.string.stage_no_door)
    val badMinutes = stringResource(R.string.stage_bad_minutes)
    val m = minutes.toIntOrNull()?.takeIf { it > 0 }
    val r = when (rest) {
        is Rest.Minutes -> restMinutes.toIntOrNull()?.takeIf { it > 0 }?.let { Rest.Minutes(it) }
        else -> rest
    }
    val (budget, why) = when {
        chosen.isEmpty() -> null to noDoor
        m == null || (rest is Rest.Minutes && r == null) -> null to badMinutes
        else -> Budget(chosen, m, r) to null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stage_budget)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.stage_budget_doors), style = MaterialTheme.typography.titleSmall)
                doors.forEach { door ->
                    CheckRow(stringResource(doorLabel(door)), door in chosen, doorMark(door)) { on -> chosen = if (on) chosen + door else chosen - door }
                }
                NumberField(stringResource(R.string.stage_minutes), minutes, Modifier.fillMaxWidth()) { minutes = it }
                RadioRow(stringResource(R.string.stage_rest_none), rest == null) { rest = null }
                RadioRow(stringResource(R.string.stage_rest_minutes), rest is Rest.Minutes) { rest = Rest.Minutes(1) }
                if (rest is Rest.Minutes) {
                    NumberField(stringResource(R.string.stage_rest_length), restMinutes, Modifier.fillMaxWidth()) { restMinutes = it }
                }
                RadioRow(stringResource(R.string.stage_rest_parent), rest == Rest.Parent) { rest = Rest.Parent }
                Text(stringResource(R.string.stage_budget_in_span), style = MaterialTheme.typography.bodySmall)
                if (why != null) Text(why, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = budget != null, onClick = { budget?.let(onDone) }) { Text(stringResource(R.string.edit_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
}
