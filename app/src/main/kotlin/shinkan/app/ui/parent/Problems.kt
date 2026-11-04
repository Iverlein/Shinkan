package shinkan.app.ui.parent

import android.content.res.Resources
import io.github.iverlein.shinkan.R
import shinkan.core.TextProblem

/** The words of a [TextProblem] in the interface's language; [detail] is what was typed, or a day. */
fun Resources.problem(problem: TextProblem, detail: String): String = getString(
    when (problem) {
        TextProblem.AFTER_MINUTES -> R.string.problem_after_minutes
        TextProblem.ALLOW -> R.string.problem_allow
        TextProblem.BUDGET_MINUTES -> R.string.problem_budget_minutes
        TextProblem.CATEGORY -> R.string.problem_category
        TextProblem.CLOSE -> R.string.problem_close
        TextProblem.CREATOR -> R.string.problem_creator
        TextProblem.HISTORY -> R.string.problem_history
        TextProblem.HOURS -> R.string.problem_hours
        TextProblem.MIRROR -> R.string.problem_mirror
        TextProblem.MODE -> R.string.problem_mode
        TextProblem.PASS -> R.string.problem_pass
        TextProblem.PERMISSION_KIND -> R.string.problem_permission_kind
        TextProblem.PERMISSION_SHAPE -> R.string.problem_permission_shape
        TextProblem.PLAN_DAYS -> R.string.problem_plan_days
        TextProblem.PLAN_WITHOUT_STAGES -> R.string.problem_plan_without_stages
        TextProblem.PLAYER -> R.string.problem_player
        TextProblem.REST -> R.string.problem_rest
        TextProblem.RESTRICT -> R.string.problem_restrict
        TextProblem.SITE -> R.string.problem_site
        TextProblem.STAGE_OUTSIDE_PLAN -> R.string.problem_stage_outside_plan
        TextProblem.STAGE_SHAPE -> R.string.problem_stage_shape
        TextProblem.START -> R.string.problem_start
        TextProblem.UNKNOWN_CATEGORY -> R.string.problem_unknown_category
        TextProblem.UNKNOWN_PLATFORM -> R.string.problem_unknown_platform
        TextProblem.UNKNOWN_RESTRICTION -> R.string.problem_unknown_restriction
        TextProblem.UNKNOWN_SEGMENT -> R.string.problem_unknown_segment
        TextProblem.UNKNOWN_STATEMENT -> R.string.problem_unknown_statement
        TextProblem.VOLUME_RANGE -> R.string.problem_volume_range
        TextProblem.VOLUME_SHAPE -> R.string.problem_volume_shape
        TextProblem.WINDOW -> R.string.problem_window
        TextProblem.ZERO_HOURS -> R.string.problem_zero_hours
        TextProblem.UNKNOWN_DOOR -> R.string.problem_unknown_door
        TextProblem.UNKNOWN_DAY -> R.string.problem_unknown_day
        TextProblem.DAY_REPEATED -> R.string.problem_day_repeated
    },
    detail,
)
