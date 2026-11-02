package shinkan.app.player

import android.content.Context
import shinkan.core.net.History
import java.io.File

/**
 * The player's history on the device (SPEC §7.1): a file in the app's own
 * storage, newest first, with the position each video stopped at. Nothing of
 * it leaves the device; the parent clears it on the door.
 */
object PlayerHistory {
    private fun file(context: Context) = File(context.applicationContext.filesDir, "history.txt")

    @Synchronized
    fun load(context: Context): History =
        runCatching { History.parse(file(context).readText()) }.getOrDefault(History())

    @Synchronized
    fun played(context: Context, entry: History.Entry) {
        file(context).writeText(History.render(load(context).played(entry)))
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }
}
