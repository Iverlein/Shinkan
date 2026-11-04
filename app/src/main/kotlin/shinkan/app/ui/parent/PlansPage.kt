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
import shinkan.app.ui.StageLine
import shinkan.app.ui.doorLabel
import shinkan.app.ui.doorMark
import shinkan.core.Door
import shinkan.core.Mode
import shinkan.core.Plan
import shinkan.core.Policy
import shinkan.core.PolicyText
import shinkan.core.Rest
import shinkan.core.Stage
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * What the dialog edits: of plan [plan], the stage at [stage], or a new one when
 * null; with [inSpan], a budget of that span, at [budget], or a new one when null.
 */
private data class Editing(val plan: Int, val stage: Int?, val initial: Stage, val inSpan: Boolean = false, val budget: Int? = null)

/**
 * The day plans (SPEC §4.7, §8.2): one card per plan with its weekdays and its
 * spans, each moved up or down, edited or removed, and under each span its
 * budgets, added, edited and removed there. A budget standing as a stage, as 1.0
 * wrote it, is shown and edited but no longer added. A weekday in another plan is
 * greyed, so no day is in two.
 */
@Composable
fun PlansPage(policy: Policy, onChange: (Policy) -> Unit) {
    val doors = if (policy.mode == Mode.CLOSED) listOf(Door.BROWSER, Door.PLAYER) else listOf(Door.DEVICE)
    var editing by remember { mutableStateOf<Editing?>(null) }
    fun withPlan(i: Int, plan: Plan) = onChange(policy.copy(plans = policy.plans.toMutableList().also { it[i] = plan }))

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
                plan.stages.forEachIndexed { si, stage ->
                    if (si > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    StageLine(stage, Modifier.padding(top = 8.dp), budgets = false)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        val move: (Int) -> Unit = { to ->
                            withPlan(pi, plan.copy(stages = plan.stages.toMutableList().also { it.add(to, it.removeAt(si)) }))
                        }
                        Mini("↑", stringResource(R.string.stage_up), si > 0) { move(si - 1) }
                        Mini("↓", stringResource(R.string.stage_down), si < plan.stages.lastIndex) { move(si + 1) }
                        Mini("✎", stringResource(R.string.stage_edit)) { editing = Editing(pi, si, stage) }
                        Mini("✕", stringResource(R.string.stage_remove)) {
                            withPlan(pi, plan.copy(stages = plan.stages.filterIndexed { j, _ -> j != si }))
                        }
                    }
                    if (stage is Stage.Span) {
                        stage.budgets.forEachIndexed { bi, budget ->
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                StageLine(budget, Modifier.weight(1f))
                                Mini("✎", stringResource(R.string.stage_edit)) { editing = Editing(pi, si, budget, inSpan = true, budget = bi) }
                                Mini("✕", stringResource(R.string.stage_remove)) {
                                    val kept = stage.copy(budgets = stage.budgets.filterIndexed { j, _ -> j != bi })
                                    withPlan(pi, plan.copy(stages = plan.stages.toMutableList().also { it[si] = kept }))
                                }
                            }
                        }
                        val uncounted = Door.entries.filter { it in stage.doors && stage.budgets.none { b -> it in b.doors } }
                        if (uncounted.isNotEmpty()) {
                            TextButton(modifier = Modifier.padding(start = 16.dp), onClick = {
                                editing = Editing(pi, si, Stage.Budget(setOf(uncounted.last()), 30), inSpan = true)
                            }) { Text(stringResource(R.string.plans_add_budget)) }
                        }
                    }
                }
                if (plan.stages.isEmpty()) Text(stringResource(R.string.plans_no_stage), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    editing = Editing(pi, null, Stage.Span(LocalTime.of(8, 0), LocalTime.of(12, 0), doors.toSet()))
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

    editing?.let { e ->
        val plan = policy.plans[e.plan]
        val span = e.stage?.takeIf { e.inSpan }?.let { plan.stages[it] as? Stage.Span }
        // A budget of a span is offered the span's doors no other budget of it counts.
        val offered = if (span == null) (doors + e.initial.doors).distinct() else {
            val others = span.budgets.filterIndexed { j, _ -> j != e.budget }.flatMap { it.doors }
            Door.entries.filter { it in span.doors && it !in others }
        }
        StageDialog(e.initial, offered, inSpan = span != null, onDismiss = { editing = null }) { edited ->
            val stage = if (span == null) edited else {
                val budget = edited as Stage.Budget
                val at = e.budget
                span.copy(budgets = if (at == null) span.budgets + budget else span.budgets.toMutableList().also { it[at] = budget })
            }
            val stages = if (e.stage == null) plan.stages + stage else plan.stages.toMutableList().also { it[e.stage] = stage }
            withPlan(e.plan, plan.copy(stages = stages))
            editing = null
        }
    }
}

/** A small button of one sign, named for the screen reader and for `emulator.py tap --desc`. */
@Composable
private fun Mini(sign: String, name: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = name }) { Text(sign) }
}

/**
 * One stage, a span or a budget as [initial] is: the span's times typed as
 * HH:MM and the doors open in it, a door taken out of the span leaving its
 * budgets; the budget's doors, minutes and rest, and with [inSpan] what they mean
 * there. Done stays off until the stage is whole, and says why.
 */
@Composable
private fun StageDialog(initial: Stage, doors: List<Door>, inSpan: Boolean = false, onDismiss: () -> Unit, onDone: (Stage) -> Unit) {
    var chosen by remember { mutableStateOf(initial.doors) }
    var from by remember { mutableStateOf((initial as? Stage.Span)?.from?.let(PolicyText::time) ?: "") }
    var to by remember { mutableStateOf((initial as? Stage.Span)?.to?.let(PolicyText::time) ?: "") }
    val budget = initial as? Stage.Budget
    var minutes by remember { mutableStateOf(budget?.minutes?.toString() ?: "") }
    var rest by remember { mutableStateOf(budget?.rest) }
    var restMinutes by remember { mutableStateOf(((budget?.rest as? Rest.Minutes)?.minutes ?: 30).toString()) }

    val badTime = stringResource(R.string.stage_bad_time)
    val sameTime = stringResource(R.string.stage_same_time)
    val noDoor = stringResource(R.string.stage_no_door)
    val badMinutes = stringResource(R.string.stage_bad_minutes)
    val (stage, why) = if (budget == null) {
        val f = PolicyText.time(from)
        val t = PolicyText.time(to)
        when {
            f == null || t == null -> null to badTime
            f == t -> null to sameTime
            else -> {
                val kept = (initial as? Stage.Span)?.budgets.orEmpty().mapNotNull { b ->
                    (b.doors intersect chosen).takeIf { it.isNotEmpty() }?.let { b.copy(doors = it) }
                }
                Stage.Span(f, t, chosen, kept) to null
            }
        }
    } else {
        val m = minutes.toIntOrNull()?.takeIf { it > 0 }
        val r = when (rest) {
            is Rest.Minutes -> restMinutes.toIntOrNull()?.takeIf { it > 0 }?.let { Rest.Minutes(it) }
            else -> rest
        }
        when {
            chosen.isEmpty() -> null to noDoor
            m == null || (rest is Rest.Minutes && r == null) -> null to badMinutes
            else -> Stage.Budget(chosen, m, r) to null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (budget == null) R.string.stage_span else R.string.stage_budget)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (budget == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(stringResource(R.string.stage_from), from, Modifier.weight(1f), time = true) { from = it }
                        NumberField(stringResource(R.string.stage_to), to, Modifier.weight(1f), time = true) { to = it }
                    }
                }
                Text(stringResource(if (budget == null) R.string.stage_doors else R.string.stage_budget_doors), style = MaterialTheme.typography.titleSmall)
                doors.forEach { door ->
                    CheckRow(stringResource(doorLabel(door)), door in chosen, doorMark(door)) { on -> chosen = if (on) chosen + door else chosen - door }
                }
                if (budget != null) {
                    NumberField(stringResource(R.string.stage_minutes), minutes, Modifier.fillMaxWidth()) { minutes = it }
                    RadioRow(stringResource(R.string.stage_rest_none), rest == null) { rest = null }
                    RadioRow(stringResource(R.string.stage_rest_minutes), rest is Rest.Minutes) { rest = Rest.Minutes(1) }
                    if (rest is Rest.Minutes) {
                        NumberField(stringResource(R.string.stage_rest_length), restMinutes, Modifier.fillMaxWidth()) { restMinutes = it }
                    }
                    RadioRow(stringResource(R.string.stage_rest_parent), rest == Rest.Parent) { rest = Rest.Parent }
                    if (inSpan) Text(stringResource(R.string.stage_budget_in_span), style = MaterialTheme.typography.bodySmall)
                }
                if (why != null) Text(why, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = stage != null, onClick = { stage?.let(onDone) }) { Text(stringResource(R.string.edit_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
}
