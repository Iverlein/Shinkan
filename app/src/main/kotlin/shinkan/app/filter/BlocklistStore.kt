package shinkan.app.filter

import android.content.Context
import shinkan.core.net.Blocklist
import shinkan.core.net.BlocklistText
import shinkan.core.net.Builtin
import shinkan.core.net.BuiltinList
import shinkan.core.net.Category

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

/**
 * The built-in block list (SPEC §6.5), read once from the APK's assets
 * (`assets/blocklist/`, written by `tools/blocklist/refresh.py`) and kept for
 * the process: a few megabytes of names searched in place. Never downloaded.
 */
object BuiltinLists {
    @Volatile private var lists: Builtin? = null

    fun get(context: Context): Builtin = lists ?: synchronized(this) {
        lists ?: load(context).also { lists = it }
    }

    private fun load(context: Context): Builtin {
        fun read(name: String) = BuiltinList(context.assets.open("blocklist/$name.txt").use { it.readBytes() })
        return Builtin(read("ads"), Category.entries.associateWith { read(it.key) })
    }
}
