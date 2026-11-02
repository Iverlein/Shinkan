package shinkan.app.player

import android.content.Context
import shinkan.app.filter.BlocklistStore
import shinkan.core.net.History
import shinkan.core.net.HistoryKeep
import java.io.File

/**
 * The player's history on the device (SPEC §7.1.1): a file in the app's own
 * storage, newest first, with the position each video stopped at, kept for the
 * period the parent chose (`history` in the block list) and pruned to it each
 * time it is read or written. Nothing of it leaves the device; the parent
 * clears it on the door.
 */
object PlayerHistory {
    private fun file(context: Context) = File(context.applicationContext.filesDir, "history.txt")

    private fun keep(context: Context): HistoryKeep = BlocklistStore(context).blocklist.history

    @Synchronized
    fun load(context: Context): History {
        val keep = keep(context)
        if (keep == HistoryKeep.OFF) {
            file(context).delete()
            return History()
        }
        val all = runCatching { History.parse(file(context).readText()) }.getOrDefault(History())
        return all.kept(System.currentTimeMillis(), keep)
    }

    @Synchronized
    fun played(context: Context, entry: History.Entry) {
        if (keep(context) == HistoryKeep.OFF) return
        file(context).writeText(History.render(load(context).played(entry)))
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }
}
