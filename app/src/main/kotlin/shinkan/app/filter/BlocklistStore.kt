package shinkan.app.filter

import android.content.Context
import shinkan.core.net.Blocklist
import shinkan.core.net.BlocklistText

/** The block list in device-protected storage, beside the policy (SPEC §9.2). */
class BlocklistStore(context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("shinkan", Context.MODE_PRIVATE)

    var blocklist: Blocklist
        get() = BlocklistText.parse(prefs.getString(KEY, "") ?: "")
        set(value) = prefs.edit().putString(KEY, BlocklistText.render(value)).apply()

    private companion object {
        const val KEY = "blocklist"
    }
}
