package shinkan.app.admin

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import shinkan.app.filter.FilterService
import shinkan.core.Mode
import shinkan.core.Policy
import shinkan.core.Restriction
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Turns the [Policy] into the device's state through DevicePolicyManager
 * (SPEC §4). Every call here is one documented platform policy; the decisions
 * are in the core module.
 */
class DeviceHold(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ShinkanAdmin.component(context)
    private val store = PolicyStore(context)

    val isOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    /** The adb command that makes Shinkan the device owner (SPEC §3.1). */
    val provisionCommand: String
        get() = "adb shell dpm set-device-owner ${admin.flattenToShortString()}"

    /** Brings the device to the stored policy at this moment and sets the alarm for the next change. */
    fun apply(now: LocalDateTime = LocalDateTime.now()) {
        if (!isOwner) return
        val policy = store.policy
        applyRestrictions(policy)
        applyPackages(policy, now)
        applyFilter()
        scheduleNext(policy, now)
    }

    /**
     * Locks the device to Shinkan's own VPN (SPEC §5.1): always on, lockdown, so
     * nothing reaches the network while the filter is down; plain DNS only, so
     * the system's own DNS-over-TLS cannot bypass it.
     */
    private fun applyFilter() {
        try {
            dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            dpm.setAlwaysOnVpnPackage(admin, context.packageName, true)
        } catch (e: Exception) {
            Log.e(TAG, "always-on VPN not set: $e")
        }
        FilterService.start(context)
    }

    private fun applyRestrictions(policy: Policy) {
        for (r in Restriction.entries) {
            if (r in policy.restrictions) dpm.addUserRestriction(admin, r.key) else dpm.clearUserRestriction(admin, r.key)
        }
    }

    /**
     * Suspends the packages the form holds (SPEC §4.2): on the closed device
     * every launchable package but Shinkan, on the open device the closed ones
     * outside their windows; the rest are resumed.
     */
    private fun applyPackages(policy: Policy, now: LocalDateTime) {
        val launchable = launchablePackages()
        val held = when (policy.mode) {
            Mode.CLOSED -> launchable.toSet()
            Mode.OPEN -> policy.closedPackagesAt(now)
        }
        val suspend = launchable.filter { it in held }.toTypedArray()
        val resume = launchable.filter { it !in held }.toTypedArray()
        if (suspend.isNotEmpty()) {
            val refused = dpm.setPackagesSuspended(admin, suspend, true)
            if (refused.isNotEmpty()) Log.w(TAG, "not suspended: ${refused.joinToString()}")
        }
        if (resume.isNotEmpty()) dpm.setPackagesSuspended(admin, resume, false)
        // Lock task mode pins Shinkan alone (SPEC §4.1).
        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
    }

    /** The packages with a launcher entry, Shinkan excluded. */
    fun launchablePackages(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
    }

    private fun scheduleNext(policy: Policy, now: LocalDateTime) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReapplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = policy.nextChange(now)
        if (next == null) {
            alarms.cancel(pending)
            return
        }
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Exact alarms need no permission from a device owner; the edge is the whole point of a window.
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        Log.i(TAG, "next change at $next")
    }

    /** Hands the device back (SPEC §9.3): every restriction and suspension lifted, then the ownership itself. */
    fun release() {
        if (!isOwner) return
        for (r in Restriction.entries) dpm.clearUserRestriction(admin, r.key)
        val all = launchablePackages().toTypedArray()
        if (all.isNotEmpty()) dpm.setPackagesSuspended(admin, all, false)
        dpm.setLockTaskPackages(admin, emptyArray())
        runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
        context.stopService(Intent(context, FilterService::class.java))
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    private companion object {
        const val TAG = "DeviceHold"
    }
}
