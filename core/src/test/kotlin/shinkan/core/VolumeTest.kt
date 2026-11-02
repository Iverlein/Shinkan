package shinkan.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VolumeTest {
    @Test fun `a ceiling in percent is the index below it`() {
        assertEquals(5, Volume.ceiling(20, 25))
        assertEquals(3, Volume.ceiling(20, 15))
        assertEquals(0, Volume.ceiling(0, 25))
        assertEquals(25, Volume.ceiling(100, 25))
    }

    @Test fun `up stops at the ceiling, down goes to zero`() {
        assertEquals(5, Volume.step(4, 25, 20, up = true))
        assertEquals(5, Volume.step(5, 25, 20, up = true))
        assertEquals(4, Volume.step(5, 25, 20, up = false))
        assertEquals(0, Volume.step(0, 25, 20, up = false))
    }

    @Test fun `a stream above the ceiling is brought down by either key`() {
        assertEquals(5, Volume.step(12, 25, 20, up = true))
        assertEquals(5, Volume.step(12, 25, 20, up = false))
    }

    @Test fun `no ceiling is the stream's own range`() {
        assertEquals(25, Volume.step(25, 25, null, up = true))
        assertEquals(13, Volume.step(12, 25, null, up = true))
    }

    @Test fun `a door opens at the ceiling when the stream is above it`() {
        assertEquals(5, Volume.opening(12, 25, 20))
        assertEquals(3, Volume.opening(3, 25, 20))
        assertEquals(12, Volume.opening(12, 25, null))
    }
}
