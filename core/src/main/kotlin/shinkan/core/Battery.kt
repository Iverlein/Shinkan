package shinkan.core

/**
 * The battery as the parent door shows it (SPEC §8.2): a percent and whether
 * power is in. [low] is drawn in vermilion.
 */
data class Battery(val percent: Int, val power: Power) {
    /** On power, charging or already full; or on the battery alone. */
    enum class Power { BATTERY, CHARGING, FULL }

    /** At or under [LOW] percent with no power in. */
    val low: Boolean get() = power == Power.BATTERY && percent <= LOW

    companion object {
        const val LOW = 15

        /**
         * From the system's `EXTRA_LEVEL`, `EXTRA_SCALE` and whether a charger is
         * plugged and the status is full; rounded to the nearest percent. Null when
         * the system gives no level, as on a device with no battery.
         */
        fun of(level: Int, scale: Int, plugged: Boolean, full: Boolean): Battery? {
            if (level < 0 || scale <= 0) return null
            val percent = ((level * 100 + scale / 2) / scale).coerceIn(0, 100)
            val power = when {
                !plugged -> Power.BATTERY
                full || percent == 100 -> Power.FULL
                else -> Power.CHARGING
            }
            return Battery(percent, power)
        }
    }
}
