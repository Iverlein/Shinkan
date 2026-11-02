package shinkan.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import shinkan.app.admin.PolicyStore
import shinkan.core.Door
import shinkan.core.Volume

/**
 * The volume ceiling as a door opens and closes (SPEC §4.6): the keys steer the
 * media stream while a door is in front, and a stream above the door's ceiling
 * is brought down to it at once. The keys themselves are taken by
 * [shinkan.app.MainActivity.dispatchKeyEvent].
 */
object DoorVolume {
    fun open(context: Context, door: Door) {
        context.activity()?.volumeControlStream = AudioManager.STREAM_MUSIC
        val ceiling = PolicyStore(context).policy.volumes[door] ?: return
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val opening = Volume.opening(current, max, ceiling)
        if (opening != current) audio.setStreamVolume(AudioManager.STREAM_MUSIC, opening, 0)
    }

    fun close(context: Context) {
        context.activity()?.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
