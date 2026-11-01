package shinkan.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.iverlein.shinkan.R
import shinkan.app.admin.PolicyStore

/**
 * The launcher icon the parent chose (SPEC §8.5). Each icon is an `activity-alias`
 * of [MainActivity] in the manifest carrying the LAUNCHER filter and its own icon;
 * exactly one alias is enabled, through `PackageManager.setComponentEnabledSetting`,
 * and the package manager keeps that state across reboots and updates. The home
 * filter stays on the activity itself, so the default home and lock task are
 * untouched by the choice.
 *
 * The choice is stored and applied by [apply] when the activity stops, not at the
 * tap: the system finishes every task whose root is the alias just disabled, even
 * with `DONT_KILL_APP`, and a parent who opened Shinkan from the launcher would see
 * the door close under their finger. Once Shinkan has left the screen, that
 * finish is harmless.
 */
enum class LauncherIcon(val key: String, val alias: String, val label: Int, val foreground: Int) {
    CREST("crest", "shinkan.app.IconCrest", R.string.icon_crest, R.drawable.icon_crest),
    GATE("gate", "shinkan.app.IconGate", R.string.icon_gate, R.drawable.icon_gate),
    GATE_DRAWN("gate_drawn", "shinkan.app.IconGateDrawn", R.string.icon_gate_drawn, R.drawable.icon_gate_drawn),
    SEAL("seal", "shinkan.app.IconSeal", R.string.icon_seal, R.drawable.icon_seal);

    fun component(context: Context) = ComponentName(context, alias)

    companion object {
        /** The alias enabled in the manifest. */
        val DEFAULT = CREST

        /** The parent's choice, the default until one is made. */
        fun chosen(store: PolicyStore): LauncherIcon = entries.firstOrNull { it.key == store.icon } ?: DEFAULT

        /** The alias the package manager has enabled now. */
        fun enabled(context: Context): LauncherIcon {
            val pm = context.packageManager
            return entries.firstOrNull {
                pm.getComponentEnabledSetting(it.component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } ?: DEFAULT
        }

        /** Enables the chosen alias and disables the others, when they differ; the app keeps running. */
        fun apply(context: Context, store: PolicyStore) {
            val wanted = chosen(store)
            if (enabled(context) == wanted) return
            val pm = context.packageManager
            for (each in entries) {
                val state = if (each == wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                pm.setComponentEnabledSetting(each.component(context), state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
