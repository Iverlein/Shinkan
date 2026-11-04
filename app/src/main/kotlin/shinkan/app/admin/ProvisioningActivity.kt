package shinkan.app.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/**
 * What the system's provisioning asks of the app taking the device (SPEC §3.2): from
 * Android 12 a QR code provisions nothing unless the app answers these two. Which
 * mode: the whole device, the only one Shinkan knows. Compliance: the hold is applied,
 * and the PIN is asked for at the first opening. No page of its own.
 */
class ProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> setResult(
                RESULT_OK,
                Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE),
            )
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                DeviceHold(this).apply()
                setResult(RESULT_OK)
            }
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}
