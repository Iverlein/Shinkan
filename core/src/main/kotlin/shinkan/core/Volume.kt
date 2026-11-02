package shinkan.core

/**
 * The volume ceiling of a door (SPEC §4.6), as stream indices: the platform
 * counts the media stream in steps from 0 to a maximum that differs by
 * device, the parent in percent of that maximum.
 */
object Volume {
    /** The highest index a ceiling of [percent] allows on a stream of [max] steps: never above it, rounded down. */
    fun ceiling(percent: Int, max: Int): Int = (max * percent.coerceIn(0, 100)) / 100

    /**
     * The index after one press of a volume key at [current], with a ceiling of
     * [percent] (null: none): one step up, never above the ceiling, and a stream
     * already above it is brought down to it; one step down, never below zero.
     */
    fun step(current: Int, max: Int, percent: Int?, up: Boolean): Int {
        val top = percent?.let { ceiling(it, max) } ?: max
        return if (up) minOf(current + 1, top) else minOf(maxOf(current - 1, 0), top)
    }

    /** The index a door opens at: the current one, or the ceiling when the current is above it. */
    fun opening(current: Int, max: Int, percent: Int?): Int =
        percent?.let { minOf(current, ceiling(it, max)) } ?: current
}
