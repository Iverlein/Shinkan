package shinkan.app.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import shinkan.app.admin.PolicyStore
import shinkan.app.plan.DeviceVolume
import shinkan.core.Door

/**
 * The volume ceiling as a door opens and closes (SPEC §4.6): the keys steer the
 * media stream while a door is in front, and a stream above the door's ceiling
 * is brought down to it at once. The keys themselves are taken by
 * [shinkan.app.MainActivity.dispatchKeyEvent]; every other change while the door
 * is in front (the slider of the volume panel the keys show, above all) is pulled
 * back to the ceiling by a receiver of the platform's volume broadcast.
 */
object DoorVolume {
    private var watch: BroadcastReceiver? = null

    fun open(context: Context, door: Door) {
        context.activity()?.volumeControlStream = AudioManager.STREAM_MUSIC
        unwatch(context)
        val ceiling = PolicyStore(context).policy.volumes[door] ?: return
        DeviceVolume.clamp(context, ceiling)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (DeviceVolume.isMusic(intent)) DeviceVolume.clamp(context, ceiling)
            }
        }
        context.applicationContext.registerReceiver(receiver, DeviceVolume.filter, Context.RECEIVER_NOT_EXPORTED)
        watch = receiver
    }

    fun close(context: Context) {
        context.activity()?.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        unwatch(context)
    }

    private fun unwatch(context: Context) {
        watch?.let { context.applicationContext.unregisterReceiver(it) }
        watch = null
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
