package shinkan.app.admin

import android.content.Context
import shinkan.core.Policy
import shinkan.core.PolicyText

/**
 * The policy text, the PIN hash and the chosen icon, in device-protected storage so they are
 * readable before the user unlocks after a reboot (SPEC §9.2). Only Shinkan,
 * as device owner, can read them.
 */
class PolicyStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("shinkan", Context.MODE_PRIVATE)

    var policy: Policy
        get() = PolicyText.parse(prefs.getString(KEY_POLICY, "") ?: "")
        set(value) = prefs.edit().putString(KEY_POLICY, PolicyText.render(value)).apply()

    var pinHash: String?
        get() = prefs.getString(KEY_PIN, null)
        set(value) = prefs.edit().putString(KEY_PIN, value).apply()

    /** The launcher icon the parent chose (SPEC §8.5), by its `LauncherIcon.key`; null until chosen. */
    var icon: String?
        get() = prefs.getString(KEY_ICON, null)
        set(value) = prefs.edit().putString(KEY_ICON, value).apply()

    private companion object {
        const val KEY_POLICY = "policy"
        const val KEY_PIN = "pin"
        const val KEY_ICON = "icon"
    }
}
