package shinkan.app.browser

import android.content.Context
import shinkan.core.net.FilterList

/**
 * EasyList and EasyPrivacy as the browser door applies them (SPEC §7.6): read
 * once per process from the APK's assets (`assets/filters/`, written by
 * `tools/blocklist/refresh.py`), never downloaded. The first request of the
 * browser waits for the reading; the main thread never does it.
 */
object Filters {
    @Volatile private var list: FilterList? = null

    fun get(context: Context): FilterList = list ?: synchronized(this) {
        list ?: load(context.applicationContext).also { list = it }
    }

    private fun load(context: Context): FilterList {
        fun lines(name: String) = context.assets.open("filters/$name.txt").bufferedReader().use { it.readLines() }
        return FilterList.parse((lines("easylist") + lines("easyprivacy")).asSequence())
    }
}
