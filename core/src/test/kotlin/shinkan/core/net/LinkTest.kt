package shinkan.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LinkTest {
    @Test fun `a shared page is its site with the family`() {
        val l = Link.parse("look at this https://www.tiktok.com/@someone/video/123?lang=en ok") as Link.Site
        assertEquals("tiktok.com", l.domain)
        assertEquals(Families.of("tiktok.com"), l.family)
        assertEquals("example.org", (Link.parse("m.example.org/page") as Link.Site).domain)
        assertEquals(setOf("example.org"), (Link.parse("example.org") as Link.Site).family)
    }

    @Test fun `youtube channel urls name the creator`() {
        assertEquals(Creator(Platform.YOUTUBE, "@handle", "@handle"), (Link.parse("https://youtube.com/@handle/videos") as Link.CreatorLink).creator)
        assertEquals("UCabc", (Link.parse("https://m.youtube.com/channel/UCabc") as Link.CreatorLink).creator.ref)
        assertEquals("c/Name", (Link.parse("https://www.youtube.com/c/Name") as Link.CreatorLink).creator.ref)
    }

    @Test fun `a short link with no video is the site`() {
        assertEquals(Link.Site("youtu.be", Families.of("youtu.be")), Link.parse("https://youtu.be/"))
    }

    @Test fun `youtube videos wait for a lookup of their creator`() {
        assertEquals(Link.Item(Platform.YOUTUBE, "dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ"), Link.parse("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", (Link.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10") as Link.Item).id)
        assertEquals("abc", (Link.parse("https://youtube.com/shorts/abc") as Link.Item).id)
    }

    @Test fun `no url, no link`() {
        assertNull(Link.parse("just words"))
        assertNull(Link.parse(""))
    }
}
