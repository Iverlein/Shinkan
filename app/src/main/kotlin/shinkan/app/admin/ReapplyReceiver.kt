package shinkan.app.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Applies the hold again when the device or the clock changed under it, and at every window edge (SPEC §5.2). */
class ReapplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DeviceHold(context).apply()
    }
}
