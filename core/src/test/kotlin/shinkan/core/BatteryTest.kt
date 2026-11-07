package shinkan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import shinkan.core.Battery.Power

class BatteryTest {
    @Test fun `the level is a percent of the scale, rounded`() {
        assertEquals(87, Battery.of(87, 100, plugged = false, full = false)!!.percent)
        assertEquals(50, Battery.of(127, 255, plugged = false, full = false)!!.percent)
        assertEquals(100, Battery.of(255, 255, plugged = false, full = false)!!.percent)
        assertEquals(0, Battery.of(0, 100, plugged = false, full = false)!!.percent)
    }

    @Test fun `no level or no scale is no battery`() {
        assertNull(Battery.of(-1, 100, plugged = false, full = false))
        assertNull(Battery.of(50, 0, plugged = true, full = false))
    }

    @Test fun `power in is charging until full`() {
        assertEquals(Power.BATTERY, Battery.of(40, 100, plugged = false, full = false)!!.power)
        assertEquals(Power.CHARGING, Battery.of(40, 100, plugged = true, full = false)!!.power)
        assertEquals(Power.FULL, Battery.of(98, 100, plugged = true, full = true)!!.power)
        assertEquals(Power.FULL, Battery.of(100, 100, plugged = true, full = false)!!.power)
        // Full without a charger is the battery alone.
        assertEquals(Power.BATTERY, Battery.of(100, 100, plugged = false, full = true)!!.power)
    }

    @Test fun `low is at or under fifteen with no power in`() {
        assertTrue(Battery.of(15, 100, plugged = false, full = false)!!.low)
        assertFalse(Battery.of(16, 100, plugged = false, full = false)!!.low)
        assertFalse(Battery.of(5, 100, plugged = true, full = false)!!.low)
    }
}
