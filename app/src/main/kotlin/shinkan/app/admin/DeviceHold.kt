package shinkan.app.admin

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import io.github.iverlein.shinkan.BuildConfig
import shinkan.app.MainActivity
import shinkan.app.filter.BlocklistStore
import shinkan.app.filter.FilterService
import shinkan.app.plan.DeviceVolume
import shinkan.app.plan.PlanClock
import shinkan.core.Capability
import shinkan.core.Mode
import shinkan.core.PlanState
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
        // The count so far first, so the state is the one of this moment (SPEC §4.7).
        PlanClock.flush(context)
        val policy = store.policy
        val state = policy.resolve(now, store.planState)
        if (state != store.planState) store.planState = state
        applyRestrictions(policy)
        applyHome(policy)
        applyPackages(policy, now)
        applyPermissions(policy)
        applyFilter()
        scheduleNext(policy, state, now)
        PlanClock.refresh(context)
        PlanClock.watch(context)
        DeviceVolume.clamp(context)
        // A rest takes the screen, and takes it back on every application: the screen coming on, a boot (SPEC §4.7).
        if (policy.mode == Mode.OPEN && policy.pinnedAt(now, state)) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Locks the device to Shinkan's own VPN (SPEC §5.1): always on, lockdown, so
     * nothing reaches the network while the filter is down; plain DNS only, so
     * the system's own DNS-over-TLS cannot bypass it.
     */
    private fun applyFilter() {
        try {
            // Private DNS is a setting from Android 10 (API 29); Android 9 has none to lock.
            if (android.os.Build.VERSION.SDK_INT >= 29) dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            dpm.setAlwaysOnVpnPackage(admin, context.packageName, true)
        } catch (e: Exception) {
            Log.e(TAG, "always-on VPN not set: $e")
        }
        FilterService.start(context)
    }

    /**
     * The restrictions of SPEC §4.3. The beta never applies `no_debugging_features`,
     * so the emulator can still be driven by adb after the hold (SPEC §10); the
     * release build applies it as the policy says.
     */
    private fun applyRestrictions(policy: Policy) {
        val wanted = if (BuildConfig.DEBUG) policy.restrictions - Restriction.DEBUGGING_FEATURES else policy.restrictions
        for (r in Restriction.entries) {
            if (r in wanted) dpm.addUserRestriction(admin, r.key) else dpm.clearUserRestriction(admin, r.key)
        }
    }

    /**
     * The closed device's home (SPEC §4.1): Shinkan made the default home by the
     * device owner itself, and the only package lock task may pin, with none of
     * the system's lock task features (no home, no recents, no notifications)
     * until block A1 tunes them. [shinkan.app.MainActivity] starts lock task.
     * On the open device the device's launcher is the home, and Shinkan pins
     * itself for a rest alone (§4.7).
     */
    private fun applyHome(policy: Policy) {
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        when (policy.mode) {
            Mode.CLOSED -> {
                otherHomes().forEach { dpm.clearPackagePersistentPreferredActivities(admin, it) }
                dpm.addPersistentPreferredActivity(admin, home, ComponentName(context, MainActivity::class.java))
                dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
                // The keyguard stays, behind the home (which shows over it), so a parent can unlock with the
                // device PIN from a door after a boot (SPEC §4.1); nothing else of the system in lock task.
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD)
            }
            Mode.OPEN -> {
                dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
                // Shinkan is a home too, by its manifest, so with the preference gone the Home key
                // would ask which: the device's own launcher is named instead (SPEC §4.1).
                deviceHome()?.let { dpm.addPersistentPreferredActivity(admin, home, it) }
                // Permitted on this form all the time, which pins nothing by itself: a locked task that
                // loses the permission is terminated by the system, which would throw a parent off the
                // door at *Open now* (found 2026-11-04). MainActivity leaves lock task itself.
                dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
                // The keyguard stays: the rest page is under it, and the lock screen keeps its emergency call.
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD)
            }
        }
    }

    /**
     * Suspends the packages the form holds (SPEC §4.2): on the closed device
     * every launchable package but Shinkan, on the open device the closed ones
     * outside their windows and the official app of a blocked platform (§7.1);
     * the rest are resumed.
     */
    private fun applyPackages(policy: Policy, now: LocalDateTime) {
        // On the closed device the other homes are held too: when Shinkan's process dies, the
        // system resumes the launcher's old task, not the persistent home (found 2026-11-02).
        // They are in this list on both forms, for the open one to resume what the closed one held.
        val launchable = (launchablePackages() + otherHomes()).distinct()
        val held = when (policy.mode) {
            Mode.CLOSED -> launchable.toSet()
            Mode.OPEN -> policy.closedPackagesAt(now) + launchable.filter(BlocklistStore(context).blocklist::holdsPackage)
        }
        val suspend = launchable.filter { it in held }.toTypedArray()
        val resume = launchable.filter { it !in held }.toTypedArray()
        if (suspend.isNotEmpty()) {
            val refused = dpm.setPackagesSuspended(admin, suspend, true)
            if (refused.isNotEmpty()) Log.w(TAG, "not suspended: ${refused.joinToString()}")
        }
        if (resume.isNotEmpty()) dpm.setPackagesSuspended(admin, resume, false)
    }

    /**
     * The parent's denials (SPEC §4.5): `setPermissionGrantState` DENIED for what a
     * package is refused, and back to DEFAULT for what it no longer is, which leaves
     * the choice to the app's user again. Only what the package asks for.
     */
    private fun applyPermissions(policy: Policy) {
        for (pkg in launchablePackages()) {
            val asked = requestedPermissions(pkg)
            val refused = policy.denied[pkg].orEmpty().flatMap { it.permissions }
            for (permission in Capability.entries.flatMap { it.permissions }.filter { it in asked }) {
                // A refusal is the system's policy value from then on, so setting it again changes nothing.
                // Anything but DEFAULT is cleared, whatever it reads: a grant by adb over a denial reads GRANTED.
                val wanted = when {
                    permission in refused -> DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    dpm.getPermissionGrantState(admin, pkg, permission) != DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT ->
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                    else -> continue
                }
                if (!dpm.setPermissionGrantState(admin, pkg, permission, wanted)) Log.w(TAG, "not set: $permission of $pkg")
            }
        }
    }

    /** The permissions [pkg] declares it asks for. */
    fun requestedPermissions(pkg: String): Set<String> = runCatching {
        context.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
    }.getOrDefault(emptySet())

    /** Brings the suspensions to a block list just changed (SPEC §6.3). */
    fun applyBlocklist(now: LocalDateTime = LocalDateTime.now()) {
        if (isOwner) applyPackages(store.policy, now)
    }

    /** The packages with a launcher entry, Shinkan excluded. */
    fun launchablePackages(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
    }

    /** The packages with a home activity, Shinkan excluded: the launchers the closed device replaces. */
    private fun otherHomes(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName && it != "android" }
    }

    /** The device's own launcher: the home of the highest priority that is not Shinkan's, which leaves out Settings' fallback. */
    private fun deviceHome(): ComponentName? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.packageName != "android" }
            .maxByOrNull { it.priority }
            ?.let { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }

    private fun scheduleNext(policy: Policy, state: PlanState, now: LocalDateTime) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, 0, Intent(context, ReapplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = policy.nextChange(now, state)
        if (next == null) {
            alarms.cancel(pending)
            PlanClock.edgeAt(context, null)
            return
        }
        val at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // An exact alarm needs SCHEDULE_EXACT_ALARM even from a device owner (Android 37 refuses
        // it with a SecurityException), and that permission is not among the seven of SPEC §5.1.
        // So the alarm is exact only where the system allows it, inexact otherwise; while the
        // device is awake the process times the edge itself, and the screen coming on applies
        // the hold again (PlanClock), so the child never sees a stale edge.
        // Below Android 12 (API 31) an exact alarm needs no permission.
        if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        PlanClock.edgeAt(context, next)
        Log.i(TAG, "next change at $next")
    }

    /** Hands the device back (SPEC §9.3): every restriction and suspension lifted, then the ownership itself. */
    fun release() {
        if (!isOwner) return
        for (r in Restriction.entries) dpm.clearUserRestriction(admin, r.key)
        val all = (launchablePackages() + otherHomes()).distinct().toTypedArray()
        if (all.isNotEmpty()) dpm.setPackagesSuspended(admin, all, false)
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        otherHomes().forEach { dpm.clearPackagePersistentPreferredActivities(admin, it) }
        dpm.setLockTaskPackages(admin, emptyArray())
        runCatching { dpm.setAlwaysOnVpnPackage(admin, null, false) }
        context.stopService(Intent(context, FilterService::class.java))
        dpm.clearDeviceOwnerApp(context.packageName)
    }

    private companion object {
        const val TAG = "DeviceHold"
    }
}
