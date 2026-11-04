package shinkan.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.iverlein.shinkan.R
import shinkan.app.LauncherIcon
import shinkan.app.admin.DeviceHold
import shinkan.app.admin.PolicyStore
import shinkan.app.plan.PlanClock
import shinkan.app.ui.parent.AppsPage
import shinkan.app.ui.parent.BlocklistPage
import shinkan.app.ui.parent.SegmentsPage
import shinkan.app.ui.parent.problem
import shinkan.app.ui.parent.segmentsSummary
import shinkan.app.ui.parent.blocklistSummary
import shinkan.app.ui.parent.FormPage
import shinkan.app.ui.parent.PlansPage
import shinkan.app.ui.parent.RestrictionsPage
import shinkan.app.ui.parent.StartPage
import shinkan.app.ui.parent.TextPage
import shinkan.app.ui.parent.ThemePage
import shinkan.app.ui.parent.VolumesPage
import shinkan.core.Mode
import shinkan.core.PlanState
import shinkan.core.Policy
import shinkan.core.PolicyText
import shinkan.core.Restriction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The sections of the parent's editor (SPEC §8.2), each a page of its own. */
private enum class Section(val title: Int) {
    FORM(R.string.section_form),
    PLANS(R.string.section_plans),
    APPS(R.string.section_apps),
    VOLUMES(R.string.section_volumes),
    BROWSER(R.string.section_browser),
    RESTRICTIONS(R.string.section_restrictions),
    BLOCKLIST(R.string.section_blocklist),
    SEGMENTS(R.string.section_segments),
    THEME(R.string.section_theme),
    TEXT(R.string.section_text),
}

/**
 * The parent door (SPEC §8.2): the hold's status, the day plan now, the policy's
 * sections, *Apply now*, the icon, *Release this device*.
 *
 * The sections edit a draft of the policy; *Apply now* checks it by its own text
 * ([PolicyText.problem], so the editor and a pasted text meet one parser), stores
 * it and applies the hold. A changed day plan starts the day afresh: the stored
 * plan state names a stage of the old plan. The theme is not part of the policy
 * and is pinned at once.
 */
@Composable
fun ParentPage(
    hold: DeviceHold,
    store: PolicyStore,
    counted: PlanState,
    theme: ThemePin,
    onTheme: (ThemePin) -> Unit,
    onApplied: () -> Unit,
    onBack: () -> Unit,
    onRelease: () -> Unit,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var stored by remember { mutableStateOf(store.policy) }
    var draft by remember { mutableStateOf(stored) }
    var section by remember { mutableStateOf<Section?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    var leaving by remember { mutableStateOf(false) }

    /** Checks the draft by its text, stores it and applies the hold; false when it cannot be stored. */
    fun applyDraft(): Boolean {
        val problem = PolicyText.problem(draft)
        if (problem != null) {
            message = resources.getString(R.string.edit_problem, problem.line, resources.problem(problem.problem, problem.detail))
            return false
        }
        val clean = PolicyText.parse(PolicyText.render(draft))
        if (clean.plans != store.policy.plans) store.planState = PlanState()
        store.policy = clean
        stored = clean
        draft = clean
        hold.apply()
        PlanClock.refresh(context)
        onApplied()
        message = resources.getString(R.string.edit_applied)
        return true
    }

    // Leaving the door with changes not applied asks first; the door closing by itself drops them.
    val leave = { if (draft != stored) leaving = true else onBack() }
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.edit_leave_title)) },
            text = { Text(stringResource(R.string.edit_leave_body)) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        leaving = false
                        onBack()
                    }) { Text(stringResource(R.string.edit_discard)) }
                    TextButton(onClick = {
                        leaving = false
                        if (applyDraft()) onBack()
                    }) { Text(stringResource(R.string.edit_apply)) }
                }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.edit_keep)) } },
        )
    }

    val open = section
    if (open != null) {
        BackHandler { section = null }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton(onClick = { section = null }) { Text(stringResource(R.string.edit_back)) }
            Text(stringResource(open.title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            val edit: (Policy) -> Unit = { draft = it }
            when (open) {
                Section.FORM -> FormPage(draft, edit)
                Section.PLANS -> PlansPage(draft, edit)
                Section.APPS -> AppsPage(draft, hold, edit)
                Section.VOLUMES -> VolumesPage(draft, edit)
                Section.BROWSER -> StartPage(draft, edit)
                Section.RESTRICTIONS -> RestrictionsPage(draft, edit)
                Section.BLOCKLIST -> BlocklistPage()
                Section.SEGMENTS -> SegmentsPage()
                Section.THEME -> ThemePage(theme, onTheme)
                Section.TEXT -> TextPage(draft, edit)
            }
            Spacer(Modifier.height(24.dp))
        }
        return
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        BackHandler(onBack = leave)
        Text(stringResource(R.string.parent_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.parent_status_held))
        val form = if (stored.mode == Mode.CLOSED) R.string.parent_mode_closed else R.string.parent_mode_open
        Text(stringResource(form))
        Spacer(Modifier.height(16.dp))
        PlanNow(stored, counted) {
            // *Open now* (SPEC §8.2): the rest ends, the next stage begins, the hold follows.
            val now = LocalDateTime.now()
            store.planState = store.policy.openNow(now, store.policy.resolve(now, store.planState))
            hold.apply(now)
            PlanClock.refresh(context)
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.edit_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Section.entries.forEach { s ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        section = s
                        message = null
                    }
                    .padding(vertical = 12.dp),
            ) {
                Text(stringResource(s.title), style = MaterialTheme.typography.titleSmall)
                Text(summary(s, draft, theme), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        if (draft != stored) Text(stringResource(R.string.edit_unsaved), color = MaterialTheme.colorScheme.primary)
        message?.let { Text(it) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { applyDraft() }) { Text(stringResource(R.string.edit_apply)) }
            if (draft != stored) {
                OutlinedButton(onClick = {
                    draft = stored
                    message = null
                }) { Text(stringResource(R.string.edit_discard)) }
            }
        }
        Spacer(Modifier.height(24.dp))
        IconChoice(store)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = leave) { Text(stringResource(R.string.parent_back)) }
            OutlinedButton(onClick = onRelease) { Text(stringResource(R.string.release_button)) }
        }
    }
}

/** One line under a section's name: what the draft holds there. */
@Composable
private fun summary(section: Section, policy: Policy, theme: ThemePin): String = when (section) {
    Section.FORM -> stringResource(if (policy.mode == Mode.CLOSED) R.string.summary_form_closed else R.string.summary_form_open)
    Section.PLANS -> if (policy.plans.isEmpty()) {
        stringResource(R.string.summary_plans_none)
    } else {
        pluralStringResource(R.plurals.summary_plans, policy.plans.size, policy.plans.size)
    }
    Section.APPS -> pluralStringResource(R.plurals.summary_apps, policy.closed.size, policy.closed.size)
    Section.VOLUMES -> if (policy.volumes.isEmpty()) {
        stringResource(R.string.summary_volumes_none)
    } else {
        val parts = mutableListOf<String>()
        policy.volumes.forEach { (door, percent) -> parts += stringResource(R.string.summary_volume, stringResource(doorLabel(door)), percent) }
        parts.joinToString(", ")
    }
    Section.RESTRICTIONS -> if (policy.restrictions == Restriction.DEFAULT) {
        stringResource(R.string.summary_restrictions_default)
    } else {
        stringResource(R.string.summary_restrictions_count, policy.restrictions.size, Restriction.entries.size)
    }
    Section.BLOCKLIST -> blocklistSummary()
    Section.SEGMENTS -> segmentsSummary()
    Section.BROWSER -> policy.start ?: stringResource(R.string.start_summary_none)
    Section.THEME -> stringResource(theme.label)
    Section.TEXT -> stringResource(R.string.summary_text)
}

/** The day plan's current stage, what is left of it, and *Open now* during a rest (SPEC §8.2, §4.7). */
@Composable
private fun PlanNow(policy: Policy, counted: PlanState, onOpenNow: () -> Unit) {
    val now = LocalDateTime.now()
    val state = policy.resolve(now, counted)
    val stage = state.day?.let { policy.planFor(it) }?.stages?.getOrNull(state.stage)
    when {
        policy.plans.isEmpty() -> Text(stringResource(R.string.plan_none))
        stage == null -> Text(stringResource(R.string.plan_done))
        else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.plan_now))
            StageLine(stage)
        }
    }
    policy.budgets(now, state).forEach { b ->
        val end = b.restEnd
        val left = b.left
        val words = when {
            b.restParent -> stringResource(R.string.plan_rest_parent)
            end != null -> stringResource(R.string.plan_rest_until, end.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
            left != null -> stringResource(R.string.plan_left, (left + 59) / 60)
            else -> return@forEach
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DoorMarks(b.budget.doors)
            Text(words)
        }
    }
    if (state.resting) {
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenNow) { Text(stringResource(R.string.plan_open_now)) }
    }
}

/** The launcher icon (SPEC §8.5): each shown as the launcher's round mask shows it. */
@Composable
private fun IconChoice(store: PolicyStore) {
    var chosen by remember { mutableStateOf(LauncherIcon.chosen(store)) }
    Text(stringResource(R.string.parent_icon), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    // Equal columns: a long name in one language must not squeeze another into broken words.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LauncherIcon.entries.forEach { icon ->
            val selected = icon == chosen
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = selected, role = Role.RadioButton) {
                        store.icon = icon.key
                        chosen = icon
                    }
                    .padding(vertical = 8.dp, horizontal = 2.dp),
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
                Text(stringResource(icon.label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.parent_icon_hint), style = MaterialTheme.typography.bodySmall)
}
