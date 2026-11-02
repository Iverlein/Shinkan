package shinkan.app.admin

import android.content.Context
import shinkan.core.PlanState
import shinkan.core.Policy
import shinkan.core.PolicyText

/**
 * The policy text, the PIN hash with its wrong attempts, and the chosen icon, in device-protected storage so they are
 * readable before the user unlocks after a reboot (SPEC §9.2). Only Shinkan,
 * as device owner, can read them.
 */
class PolicyStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("shinkan", Context.MODE_PRIVATE)

    var policy: Policy
        get() = PolicyText.parse(prefs.getString(KEY_POLICY, "") ?: "")
        set(value) = prefs.edit().putString(KEY_POLICY, PolicyText.render(value)).apply()

    /** Where the day plan stands (SPEC §4.7): the stage, the seconds spent, a rest's end. */
    var planState: PlanState
        get() = PolicyText.parseState(prefs.getString(KEY_PLAN, "") ?: "")
        set(value) { prefs.edit().putString(KEY_PLAN, PolicyText.renderState(value)).commit() }

    var pinHash: String?
        get() = prefs.getString(KEY_PIN, null)
        set(value) = prefs.edit().putString(KEY_PIN, value).apply()

    /** Wrong PIN attempts in a row (SPEC §9.1); written at once, before the PIN is checked, so a kill cannot skip it. */
    var pinWrong: Int
        get() = prefs.getInt(KEY_PIN_WRONG, 0)
        set(value) { prefs.edit().putInt(KEY_PIN_WRONG, value).commit() }

    /** The wall-clock time of the last PIN attempt, in epoch milliseconds; the clock is the hold's (`no_config_date_time`). */
    var pinLastAttempt: Long
        get() = prefs.getLong(KEY_PIN_LAST, 0L)
        set(value) { prefs.edit().putLong(KEY_PIN_LAST, value).commit() }

    /** The launcher icon the parent chose (SPEC §8.5), by its `LauncherIcon.key`; null until chosen. */
    var icon: String?
        get() = prefs.getString(KEY_ICON, null)
        set(value) = prefs.edit().putString(KEY_ICON, value).apply()

    /** The theme the parent pinned (SPEC §8.6), by `ThemePin.key`; null follows the system. */
    var theme: String?
        get() = prefs.getString(KEY_THEME, null)
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_POLICY = "policy"
        const val KEY_PIN = "pin"
        const val KEY_ICON = "icon"
        const val KEY_PLAN = "plan"
        const val KEY_PIN_WRONG = "pin_wrong"
        const val KEY_PIN_LAST = "pin_last"
    }
}
