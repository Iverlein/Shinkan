package shinkan.app.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The device owner's receiver (SPEC §3). It has no policy of its own: the
 * hold is in [DeviceHold], which this receiver triggers once provisioning
 * is complete.
 */
class ShinkanAdmin : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        DeviceHold(context).apply()
        context.startActivity(
            Intent(context, shinkan.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        fun component(context: Context) = ComponentName(context, ShinkanAdmin::class.java)
    }
}
