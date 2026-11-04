package shinkan.app.plan

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import shinkan.app.admin.DeviceHold
import shinkan.app.admin.PolicyStore
import shinkan.core.Door
import shinkan.core.Mode
import shinkan.core.PlanState
import java.time.LocalDateTime

/**
 * The counter of the day plan (SPEC §4.7). Use is time the screen is on and a
 * door in front, over the lock screen or not (the closed home shows over it); on
 * the open device, time the screen is on and unlocked, as [Door.DEVICE]. The door pages report when they are
 * shown and hidden, [Screen] (registered by the filter's foreground service,
 * which always runs) reports the screen, and the count is the elapsed real
 * time between, in whole seconds, added to [PlanState] through
 * [shinkan.core.Policy.count] and kept in [PolicyStore] (device-protected,
 * so a reboot neither resets nor extends it).
 *
 * When the plan changes what is open (a budget spent, a rest started or
 * ended), every door page is told to pause ([pauses]) and the hold is applied
 * again, which sets the alarm for the next edge. The pages read [state].
 * Every call comes on the main thread.
 */
object PlanClock {
    private val current = MutableStateFlow(PlanState())
    private val pausing = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The plan state as last counted or applied. */
    val state: StateFlow<PlanState> = current

    /** Fires when the doors must stop playing: a budget is spent or a door closed. */
    val pauses: SharedFlow<Unit> = pausing

    private var door: Door? = null

    /** The door page in front now, or null; the volume keys read it (SPEC §4.6). */
    val front: Door? get() = door
    private var since: Long? = null

    /** What the seconds since [since] are use of. Kept, so a flush as the screen goes off still adds them. */
    private var counting: Door? = null
    private var context: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { context?.let { flush(it); resume(it) } }

    /** A door page came to the front. */
    fun doorShown(context: Context, door: Door) {
        flush(context)
        this.door = door
        resume(context)
    }

    /** The door page left the front: closed, covered, or the screen went off. */
    fun doorHidden(context: Context) {
        flush(context)
        door = null
        resume(context)
    }

    /**
     * The screen went on or off, or the device was unlocked. When it comes on, the
     * hold is applied again: an edge passed while the device slept (an inexact
     * alarm, a stopped timer) is caught before the child sees the screen.
     */
    fun screenChanged(context: Context, on: Boolean = false) {
        if (on) DeviceHold(context.applicationContext).apply() else flush(context)
        resume(context)
    }

    private val edge = Runnable { context?.let { DeviceHold(it).apply() } }

    /**
     * Times the plan's next edge in this process (SPEC §4.2): exact while the device
     * is awake, which the alarm is not without a permission Shinkan does not ask
     * for; null cancels it. The process lives as long as the filter, which always runs.
     */
    fun edgeAt(context: Context, at: LocalDateTime?) {
        this.context = context.applicationContext
        handler.removeCallbacks(edge)
        if (at == null) return
        val delay = java.time.Duration.between(LocalDateTime.now(), at).toMillis()
        handler.postDelayed(edge, delay.coerceAtLeast(0) + 500)
    }

    /** Reads the stored state again, carried to now, and tells the pages. */
    fun refresh(context: Context) {
        val store = PolicyStore(context.applicationContext)
        current.value = store.policy.resolve(LocalDateTime.now(), store.planState)
    }

    /**
     * Adds the seconds counted since the last flush to the plan state and stores
     * it. When that changes what is open, the doors pause and the hold is applied
     * again (which flushes once more and finds nothing to add).
     */
    fun flush(context: Context) {
        val app = context.applicationContext
        this.context = app
        val store = PolicyStore(app)
        val policy = store.policy
        val now = LocalDateTime.now()
        val before = policy.resolve(now, store.planState)
        var after = before
        val start = since
        val front = counting
        if (start != null && front != null) {
            val seconds = (SystemClock.elapsedRealtime() - start) / 1000
            if (seconds > 0) {
                after = policy.count(now, before, front, seconds)
                since = start + seconds * 1000
            }
        }
        if (after != store.planState) store.planState = after
        current.value = after
        val changed = policy.openAt(now, before) != policy.openAt(now, after) || before.resting != after.resting
        if (changed) {
            pausing.tryEmit(Unit)
            DeviceHold(app).apply(now)
        }
    }

    /** Counts from now as things stand: for the hold just applied, with no screen or door event to start it. */
    fun watch(context: Context) = resume(context)

    /** What is in use now: on the open device the unlocked screen, on the closed one the door in front. */
    private fun inUse(app: Context, store: PolicyStore): Door? {
        if (!app.getSystemService(PowerManager::class.java).isInteractive) return null
        val policy = store.policy
        if (policy.mode == Mode.CLOSED) return door // over the lock screen too (SPEC §4.1): the screen on is enough
        // The rest page does not count (SPEC §4.7), nor would its last seconds go to the stage after it.
        if (policy.resolve(LocalDateTime.now(), current.value).resting) return null
        return Door.DEVICE.takeUnless { app.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
    }

    /** Starts or stops counting as the door, the screen and the lock now stand, and sets the next tick. */
    private fun resume(context: Context) {
        val app = context.applicationContext
        handler.removeCallbacks(tick)
        val store = PolicyStore(app)
        val use = inUse(app, store)
        if (use != counting) since = null
        counting = use
        if (use == null) return
        if (since == null) since = SystemClock.elapsedRealtime()
        // Flush at least every fifteen seconds, and just after the budget runs out.
        val left = store.policy.budgetLeft(LocalDateTime.now(), current.value, use)
        val delay = minOf(TICK_MS, left?.let { it * 1000 + 200 } ?: TICK_MS)
        handler.postDelayed(tick, delay.coerceAtLeast(200))
    }

    private const val TICK_MS = 15_000L

    /**
     * The screen's broadcasts, which only a registered receiver gets: the filter
     * registers it for as long as it runs (SPEC §4.7).
     */
    class Screen : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) =
            screenChanged(context, on = intent.action != Intent.ACTION_SCREEN_OFF)

        companion object {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        }
    }
}
