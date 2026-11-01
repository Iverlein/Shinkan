package shinkan.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlocklistTest {
    @Test fun `a site blocks itself and every subdomain, nothing beside`() {
        val list = Blocklist() + "example.org"
        assertTrue(list.blocksHost("example.org"))
        assertTrue(list.blocksHost("WWW.Example.org."))
        assertTrue(list.blocksHost("a.b.example.org:443"))
        assertFalse(list.blocksHost("notexample.org"))
        assertFalse(list.blocksHost("example.org.evil.net"))
    }

    @Test fun `the site of a host drops the mobile prefixes only`() {
        assertEquals("youtube.com", Host.site("m.youtube.com"))
        assertEquals("youtube.com", Host.site("www.youtube.com"))
        assertEquals("example.org", Host.site("example.org"))
        assertEquals("m.org", Host.site("m.org"))
    }

    @Test fun `families cover the platform's other hosts`() {
        assertTrue("googlevideo.com" in Families.of("m.youtube.com"))
        assertTrue("youtube.com" in Families.of("youtu.be"))
        assertEquals(setOf("example.org"), Families.of("example.org"))
    }

    @Test fun `text round-trip`() {
        val list = Blocklist() + "tiktok.com" + Creator(Platform.YOUTUBE, "@someone", "Some One")
        val text = BlocklistText.render(list)
        assertEquals("site tiktok.com\ncreator youtube @someone Some One\n", text)
        assertEquals(list, BlocklistText.parse(text))
        assertEquals(Blocklist(), BlocklistText.parse("# nothing\n"))
    }
}
