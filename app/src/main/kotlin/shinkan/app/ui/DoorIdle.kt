package shinkan.app.ui

import io.github.iverlein.shinkan.BuildConfig

/**
 * How long the parent door stays open without a touch (SPEC §8.2): two minutes.
 * The beta alone reads a shorter time from the system property
 * `debug.shinkan.door_idle` (seconds), so the emulator check need not wait
 * (`adb shell setprop debug.shinkan.door_idle 15`); the release build has no
 * such setting and R8 drops the read.
 */
internal object DoorIdle {
    const val SECONDS = 120L

    fun millis(): Long {
        if (BuildConfig.DEBUG) debugSeconds()?.let { return it * 1000 }
        return SECONDS * 1000
    }

    private fun debugSeconds(): Long? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("getprop", "debug.shinkan.door_idle"))
        process.inputStream.bufferedReader().use { it.readText() }.trim().toLongOrNull()
    }.getOrNull()?.takeIf { it > 0 }
}
