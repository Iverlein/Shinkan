package shinkan.app.filter

import android.content.Context
import shinkan.app.admin.DeviceHold
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

    /**
     * Stores [next], an edit of the list, with what follows from it (SPEC §6.3): the
     * family of a first creator, the official app held, the filter reloaded. Returns
     * the list as stored.
     */
    fun change(context: Context, next: Blocklist): Blocklist {
        val held = next.holdingFamily(blocklist)
        blocklist = held
        FilterService.start(context, reload = true)
        DeviceHold(context).applyBlocklist()
        return held
    }

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

/**
 * The block list as Shinkan's own pages apply it (SPEC §5.5): Shinkan's traffic
 * is outside the tunnel, so the browser asks this for every host, as the filter
 * answers every other app. The stored list is read again at each page, the
 * built-in lists once per process.
 */
object HostCheck {
    @Volatile private var list: Blocklist = Blocklist()

    /** Reads the stored block list again; called as a page starts. */
    fun refresh(context: Context) {
        list = BlocklistStore(context).blocklist
    }

    fun blocks(context: Context, host: String): Boolean = list.blocksHost(host, BuiltinLists.get(context))
}
