package shinkan.app.admin

import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * The pairing code typed into Shinkan's notification (SPEC §3.3), while Settings
 * keeps the code and its pairing open on screen. A foreground service for as long
 * as the hold is being taken: finding adbd's ports on the loopback takes seconds,
 * longer than a broadcast may run. Started only by that notification's reply.
 */
class PairingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val working = SelfHold.notification(this, SelfHold.Stage.Working)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SelfHold.NOTICE_ID, working, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(SelfHold.NOTICE_ID, working)
        }
        val code = intent?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(SelfHold.REPLY)?.toString()
        if (code == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        SelfHold.start(this, code, fromNotice = true) {
            // The notification stays, now saying how it went.
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
