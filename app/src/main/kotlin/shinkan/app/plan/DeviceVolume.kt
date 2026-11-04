package shinkan.app.plan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import shinkan.app.admin.PolicyStore
import shinkan.core.Volume

/**
 * The open device's ceiling (SPEC §4.6): one for the whole device, since no policy
 * says which app is in front, and a clamp, since no policy stops the change. The
 * filter's foreground service registers this for as long as it runs. The broadcast
 * is the platform's own, unnamed in its public API, and the only word of a change.
 */
class DeviceVolume : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(EXTRA_STREAM, -1) == AudioManager.STREAM_MUSIC) clamp(context)
    }

    companion object {
        private const val CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"

        val filter = IntentFilter(CHANGED)

        /** Brings the media stream down to the ceiling, when there is one and it is above. */
        fun clamp(context: Context) {
            val ceiling = PolicyStore(context).policy.deviceCeiling ?: return
            val audio = context.getSystemService(AudioManager::class.java)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val allowed = Volume.opening(current, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), ceiling)
            if (allowed != current) audio.setStreamVolume(AudioManager.STREAM_MUSIC, allowed, 0)
        }
    }
}
