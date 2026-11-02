package shinkan.app

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import shinkan.app.admin.DeviceHold
import shinkan.app.admin.PolicyStore
import shinkan.app.ui.ShinkanApp
import shinkan.app.ui.ShinkanTheme
import shinkan.app.plan.PlanClock
import shinkan.core.Mode
import shinkan.core.Volume

/**
 * The one activity: the child's home, the parent door, or the setup page before
 * the device is held (SPEC §8). On the closed device it is the default home and
 * holds itself in lock task (SPEC §4.1): a device owner whose package is in
 * `setLockTaskPackages` starts it from its own activity without a prompt.
 */
class MainActivity : ComponentActivity() {
    private lateinit var store: PolicyStore
    private lateinit var hold: DeviceHold

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hold = DeviceHold(this)
        store = PolicyStore(this)
        setContent {
            ShinkanTheme {
                ShinkanApp(
                    hold, store,
                    onSetUp = {
                        // The setup of SPEC §8.3 is complete: the hold is applied (§3.1).
                        hold.apply()
                        holdLockTask()
                    },
                    onRelease = {
                        if (inLockTask()) stopLockTask()
                        hold.release()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        holdLockTask()
    }

    /** Enters lock task on the held closed device once the PIN is set, and leaves it on the open one. */
    private fun holdLockTask() {
        if (!hold.isOwner || store.pinHash == null) return
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val closed = store.policy.mode == Mode.CLOSED
        if (closed && !inLockTask() && dpm.isLockTaskPermitted(packageName)) startLockTask()
        if (!closed && inLockTask()) stopLockTask()
    }

    /**
     * The volume keys while a door is in front (SPEC §4.6): Shinkan sets the media
     * stream itself, one step a press, never above the door's ceiling. Taken in
     * dispatchKeyEvent, ahead of any view of the door's (a WebView, a player), and
     * left to the system on every other page.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (!up && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.dispatchKeyEvent(event)
        val door = PlanClock.front ?: return super.dispatchKeyEvent(event)
        val ceiling = store.policy.volumes[door] ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val audio = getSystemService(AudioManager::class.java)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val next = Volume.step(audio.getStreamVolume(AudioManager.STREAM_MUSIC), max, ceiling, up)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI)
        }
        return true
    }

    private fun inLockTask(): Boolean =
        getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    /** The icon chosen on the parent door takes effect once Shinkan has left the screen (SPEC §8.5). */
    override fun onStop() {
        super.onStop()
        LauncherIcon.apply(this, store)
    }
}
