package shinkan.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QualityTest {
    private val options = listOf(
        Quality.Option(0, 360, "avc1.42001E", withSound = true),
        Quality.Option(1, 720, "avc1.4d401f", withSound = false),
        Quality.Option(2, 720, "vp9", withSound = false),
        Quality.Option(3, 1080, "avc1.640028", withSound = false),
        Quality.Option(4, 1080, "vp9", withSound = false),
        Quality.Option(5, 1440, "vp9", withSound = false),
        Quality.Option(6, 2160, "av01.0.12M.08", withSound = false),
    )

    @Test fun `the default is the screen's short side, never below 1080`() {
        assertEquals(1080, Quality.default(720))
        assertEquals(1080, Quality.default(1080))
        assertEquals(1440, Quality.default(1440))
    }

    @Test fun `the highest at or below the height, H264 before VP9, AV1 never`() {
        assertEquals(5, Quality.choose(options, 1440)!!.id)
        assertEquals(5, Quality.choose(options, 2160)!!.id)
        assertEquals(3, Quality.choose(options, 1080)!!.id)
        assertEquals(1, Quality.choose(options, 1000)!!.id)
        assertEquals(0, Quality.choose(options, 360)!!.id)
        assertEquals(0, Quality.choose(options, 144)!!.id)
        assertEquals(listOf(1440, 1080, 720, 360), Quality.heights(options))
    }

    @Test fun `a stream with sound first at one height, nothing to play gives null`() {
        val two = listOf(Quality.Option(0, 360, "vp9", withSound = false), Quality.Option(1, 360, "avc1", withSound = true))
        assertEquals(1, Quality.choose(two, 360)!!.id)
        assertNull(Quality.choose(emptyList(), 1080))
        assertNull(Quality.choose(listOf(Quality.Option(0, 2160, "av01", withSound = false)), 1080))
    }
}
