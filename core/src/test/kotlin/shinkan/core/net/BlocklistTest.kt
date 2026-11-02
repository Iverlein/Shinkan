package shinkan.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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

class BuiltinListTest {
    private fun list(vararg names: String) = BuiltinList(("# a header\n" + names.joinToString("\n") + "\n").toByteArray())

    private val ads = list("ads.example.net", "googlesyndication.com", "tracker.io")
    private val gambling = list("bet365.com", "casino.example")
    private val builtin = Builtin(ads, mapOf(Category.GAMBLING to gambling))

    @Test fun `the suffix walk finds a name above the host, never beside it`() {
        assertTrue(ads.blocks("pagead2.googlesyndication.com"))
        assertTrue(ads.blocks("a.b.Tracker.io."))
        assertTrue(ads.blocks("ads.example.net"))
        assertFalse(ads.blocks("example.net"))
        assertFalse(ads.blocks("notgooglesyndication.com"))
        assertFalse(ads.blocks("googlesyndication.com.evil.org"))
        assertEquals(3, ads.size)
    }

    @Test fun `names out of byte order are refused`() {
        assertThrows(IllegalArgumentException::class.java) { list("b.com", "a.com") }
    }

    @Test fun `ads are always on, a category only when switched on`() {
        val none = Blocklist()
        assertTrue(none.blocksHost("www.tracker.io", builtin))
        assertFalse(none.blocksHost("www.bet365.com", builtin))
        assertTrue(none.copy(categories = setOf(Category.GAMBLING)).blocksHost("www.bet365.com", builtin))
        assertFalse(none.blocksHost("example.org", builtin))
    }

    @Test fun `a pass lets a built-in name through, never a parent's site`() {
        val passed = Blocklist(passes = setOf("tracker.io"))
        assertFalse(passed.blocksHost("cdn.tracker.io", builtin))
        assertTrue(passed.blocksHost("pagead2.googlesyndication.com", builtin))
        val both = passed + "tracker.io"
        assertTrue(both.blocksHost("cdn.tracker.io", builtin))
    }

    @Test fun `categories and passes in the text`() {
        val list = Blocklist(categories = setOf(Category.PORN, Category.GAMBLING), passes = setOf("example.org")) + "tiktok.com"
        val text = BlocklistText.render(list)
        assertEquals("site tiktok.com\ncategory gambling\ncategory porn\npass example.org\n", text)
        assertEquals(list, BlocklistText.parse(text))
        assertThrows(BlocklistText.Malformed::class.java) { BlocklistText.parse("category knitting") }
    }
}

class ShippedListsTest {
    private val dir = java.io.File("../app/src/main/assets/blocklist")

    @Test fun `the lists refresh_py wrote load in byte order and block what they list`() {
        val ads = BuiltinList(java.io.File(dir, "ads.txt").readBytes())
        assertTrue(ads.size > 50_000)
        assertTrue(ads.blocks("pagead2.googlesyndication.com"))
        assertFalse(ads.blocks("example.org"))
        for (c in Category.entries) {
            assertTrue(BuiltinList(java.io.File(dir, "${c.key}.txt").readBytes()).size > 100, c.key)
        }
        assertTrue(BuiltinList(java.io.File(dir, "gambling.txt").readBytes()).blocks("www.bet365.com"))
        // Shinkan's own list of Russian state-controlled media is in the fake news category.
        val fakenews = BuiltinList(java.io.File(dir, "fakenews.txt").readBytes())
        for (host in listOf("www.gazeta.ru", "lenta.ru", "russian.rt.com", "tass.com", "news.rambler.ru")) assertTrue(fakenews.blocks(host), host)
        for (host in listOf("rambler.ru", "www.rbc.ru", "rt.ru")) assertFalse(fakenews.blocks(host), host)
    }
}

class CreatorTest {
    private val id = "UCsXVk37bltHxD1rDPwtNM8Q"

    @Test fun `an uploader's address reads as the reference a shared link gives`() {
        assertEquals(id, Link.creatorRef("https://www.youtube.com/channel/$id"))
        assertEquals("@SomeHandle", Link.creatorRef("https://m.youtube.com/@SomeHandle/videos"))
        assertEquals("c/Name", Link.creatorRef("https://youtube.com/c/Name"))
        assertEquals("user/Old", Link.creatorRef("https://www.youtube.com/user/Old"))
        assertEquals(null, Link.creatorRef("https://www.youtube.com/watch?v=abc"))
        assertEquals(null, Link.creatorRef("https://example.org/channel/$id"))
        assertEquals(null, Link.creatorRef(null))
    }

    @Test fun `each form of a blocked creator hides that uploader and no other`() {
        val list = Blocklist() + Creator(Platform.YOUTUBE, id) + Creator(Platform.YOUTUBE, "@SomeHandle") +
            Creator(Platform.YOUTUBE, "c/Name") + Creator(Platform.YOUTUBE, "user/Old")
        assertTrue(list.hidesUploader("https://www.youtube.com/channel/$id"))
        assertFalse(list.hidesUploader("https://www.youtube.com/channel/${id.lowercase()}"))
        assertTrue(list.hidesUploader("https://www.youtube.com/@somehandle"))
        assertTrue(list.hidesUploader("https://www.youtube.com/c/NAME"))
        assertTrue(list.hidesUploader("https://www.youtube.com/user/old/videos"))
        assertFalse(list.hidesUploader("https://www.youtube.com/@someoneelse"))
        assertFalse(list.hidesUploader("https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx"))
    }

    @Test fun `a handle blocked reaches the channel id through the player's aliases`() {
        val list = Blocklist() + Creator(Platform.YOUTUBE, "@SomeHandle")
        assertEquals(listOf("@SomeHandle"), list.unresolvedCreators)
        val uploader = "https://www.youtube.com/channel/$id"
        assertFalse(list.hidesUploader(uploader))
        assertTrue(list.hidesUploader(uploader, mapOf(id to "@somehandle")))
        assertEquals(emptyList<String>(), (Blocklist() + Creator(Platform.YOUTUBE, id)).unresolvedCreators)
    }
}

class PlayerModeTest {
    private val id = "UCsXVk37bltHxD1rDPwtNM8Q"
    private val kurz = "https://www.youtube.com/channel/$id"
    private val other = "https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx"

    @Test fun `the allow mode hides every uploader but the allowed`() {
        val list = Blocklist(player = PlayerMode.ALLOW, allowed = setOf(Creator(Platform.YOUTUBE, "@kurzgesagt")))
        assertFalse(list.hidesUploader(kurz, mapOf(id to "@kurzgesagt")))
        assertTrue(list.hidesUploader(other))
        assertTrue(list.hidesUploader(null))
        assertEquals(listOf("@kurzgesagt"), list.unresolvedCreators)
    }

    @Test fun `the block mode ignores the allowed, the allow mode the blocked`() {
        val both = Blocklist(creators = setOf(Creator(Platform.YOUTUBE, id)), allowed = setOf(Creator(Platform.YOUTUBE, id)))
        assertTrue(both.hidesUploader(kurz))
        assertFalse(both.copy(player = PlayerMode.ALLOW).hidesUploader(kurz))
    }

    @Test fun `mode and allowed creators in the text`() {
        val list = Blocklist(player = PlayerMode.ALLOW, allowed = setOf(Creator(Platform.YOUTUBE, "@kurzgesagt", "Kurzgesagt")))
        val text = BlocklistText.render(list)
        assertEquals("player allow\nallow youtube @kurzgesagt Kurzgesagt\n", text)
        assertEquals(list, BlocklistText.parse(text))
        assertEquals(PlayerMode.BLOCK, BlocklistText.parse("player block").player)
        assertThrows(BlocklistText.Malformed::class.java) { BlocklistText.parse("player maybe") }
    }
}

class HistoryTest {
    private fun e(url: String, pos: Long, secs: Long = 600, at: Long = 0) =
        History.Entry(url, "Title\twith tab", "Up", "https://www.youtube.com/channel/U$url", "https://i/$url.jpg", secs, pos, at)

    @Test fun `a video played comes first, once, and the list keeps its cap`() {
        var h = History()
        for (i in 1..105) h = h.played(e("v$i", 1000))
        assertEquals(History.MAX, h.entries.size)
        assertEquals("v105", h.entries.first().url)
        h = h.played(e("v50", 30_000))
        assertEquals(listOf("v50", "v105"), h.entries.take(2).map { it.url })
        assertEquals(1, h.entries.count { it.url == "v50" })
    }

    @Test fun `a video resumes where it stopped, from the start when it was finished`() {
        val h = History().played(e("a", 125_000)).played(e("b", 595_000))
        assertEquals(125_000, h.positionOf("a"))
        assertEquals(0, h.positionOf("b"))
        assertEquals(0, h.positionOf("none"))
        assertEquals(125f / 600f, h.entries.last().progress, 0.001f)
    }

    @Test fun `the text reads back, a bad line is left out`() {
        val h = History().played(e("a", 1)).played(e("b", 2))
        val back = History.parse(History.render(h) + "\nnot an entry")
        assertEquals(listOf("b", "a"), back.entries.map { it.url })
        assertEquals("Title with tab", back.entries.first().title)
        assertEquals(listOf("Up" to "https://www.youtube.com/channel/Ub", "Up" to "https://www.youtube.com/channel/Ua"), back.creators())
    }
}
