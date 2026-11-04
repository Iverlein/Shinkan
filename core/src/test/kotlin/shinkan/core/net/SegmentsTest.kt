package shinkan.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SegmentsTest {
    // Two entries of a real answer of 2026-11-04, the third made up for the other action.
    private val answer = """
        [{"videoID":"7_oiJmbrwLI","segments":[
          {"category":"sponsor","actionType":"skip","segment":[0,58.258],"UUID":"68a4","videoDuration":594.101,"locked":0,"votes":0,"description":""},
          {"category":"filler","actionType":"skip","segment":[100,110.5],"UUID":"68a5","videoDuration":594.101,"locked":0,"votes":0,"description":""},
          {"category":"sponsor","actionType":"mute","segment":[200,210],"UUID":"68a6","videoDuration":594.101,"locked":0,"votes":0,"description":"a \"quoted\" é"}
        ]},
        {"videoID":"wxYn7lEXvSo","segments":[{"category":"selfpromo","actionType":"skip","segment":[113.92,120],"UUID":"c25c","videoDuration":120,"locked":0,"votes":0,"description":""}]}]
    """

    @Test fun `the prefix is the start of the id's sha-256`() {
        assertEquals("ba78", Segments.prefix("abc"))
    }

    @Test fun `the address names the bucket and never the video`() {
        val url = Segments.address("https://sponsor.ajay.app/", "abc", setOf(SegmentKind.SELFPROMO, SegmentKind.SPONSOR))
        assertEquals("https://sponsor.ajay.app/api/skipSegments/ba78?categories=%5B%22sponsor%22%2C%22selfpromo%22%5D", url)
        assertFalse("abc" in url)
    }

    @Test fun `the answer is narrowed to the video and the kinds asked for`() {
        assertEquals(
            listOf(Segment(0, 58258, SegmentKind.SPONSOR)),
            Segments.parse(answer, "7_oiJmbrwLI", SegmentKind.DEFAULT),
        )
        assertEquals(
            listOf(Segment(113920, 120000, SegmentKind.SELFPROMO)),
            Segments.parse(answer, "wxYn7lEXvSo", SegmentKind.DEFAULT),
        )
        assertEquals(2, Segments.parse(answer, "7_oiJmbrwLI", SegmentKind.DEFAULT + SegmentKind.FILLER).size)
    }

    @Test fun `a bucket without the video gives nothing`() {
        assertEquals(emptyList<Segment>(), Segments.parse(answer, "other", SegmentKind.DEFAULT))
        assertEquals(emptyList<Segment>(), Segments.parse("[]", "other", SegmentKind.DEFAULT))
    }

    @Test fun `an unknown kind or an empty span is left out`() {
        val odd = """[{"videoID":"v","segments":[
            {"category":"chapter","actionType":"skip","segment":[1,2]},
            {"category":"sponsor","segment":[5,5]},
            {"category":"sponsor","segment":[6,7]}]}]"""
        assertEquals(listOf(Segment(6000, 7000, SegmentKind.SPONSOR)), Segments.parse(odd, "v", SegmentKind.DEFAULT))
    }

    @Test fun `what is not an answer fails`() {
        assertThrows(IllegalArgumentException::class.java) { Segments.parse("<html>", "v", SegmentKind.DEFAULT) }
        assertThrows(IllegalArgumentException::class.java) { Segments.parse("{}", "v", SegmentKind.DEFAULT) }
        assertThrows(IllegalArgumentException::class.java) { Segments.parse("[1", "v", SegmentKind.DEFAULT) }
    }

    private val one = Segment(10_000, 20_000, SegmentKind.SPONSOR)

    @Test fun `a position in a segment jumps to its end`() {
        assertNull(Segments.skipTo(9_999, listOf(one)))
        assertEquals(20_000L, Segments.skipTo(10_000, listOf(one)))
        assertEquals(20_000L, Segments.skipTo(15_000, listOf(one)))
        assertNull(Segments.skipTo(20_000, listOf(one)))
    }

    @Test fun `overlapping and touching segments are one jump`() {
        val list = listOf(one, Segment(15_000, 30_000, SegmentKind.SELFPROMO), Segment(30_000, 40_000, SegmentKind.INTERACTION), Segment(50_000, 60_000, SegmentKind.SPONSOR))
        assertEquals(40_000L, Segments.skipTo(12_000, list))
        assertEquals(40_000L, Segments.skipTo(25_000, list))
        assertNull(Segments.skipTo(45_000, list))
    }

    @Test fun `a short segment inside a long one does not cut the jump`() {
        val list = listOf(Segment(0, 100_000, SegmentKind.SPONSOR), Segment(10_000, 20_000, SegmentKind.SELFPROMO))
        assertEquals(100_000L, Segments.skipTo(5_000, list))
    }

    @Test fun `a mirror is a plain web address`() {
        assertEquals("https://sb.example.org", Segments.mirror(" https://sb.example.org/ "))
        assertEquals("https://example.org/sb", Segments.mirror("https://example.org/sb/"))
        assertEquals("http://127.0.0.1:8000", Segments.mirror("http://127.0.0.1:8000"))
        assertNull(Segments.mirror("sb.example.org"))
        assertNull(Segments.mirror("file:///etc/passwd"))
        assertNull(Segments.mirror("https://a b"))
        assertNull(Segments.mirror("https://example.org/?x=1"))
    }

    @Test fun `the block list keeps the kinds and the mirror`() {
        assertEquals(SegmentKind.DEFAULT, Blocklist().segments)
        assertEquals("", BlocklistText.render(Blocklist()))
        val list = Blocklist(segments = setOf(SegmentKind.SPONSOR, SegmentKind.FILLER), mirror = "https://sb.example.org")
        val text = BlocklistText.render(list)
        assertEquals("segments sponsor filler\nmirror https://sb.example.org\n", text)
        assertEquals(list, BlocklistText.parse(text))
    }

    @Test fun `segments alone means none`() {
        val none = Blocklist(segments = emptySet())
        assertEquals("segments\n", BlocklistText.render(none))
        assertEquals(none, BlocklistText.parse("segments\n"))
        assertTrue(BlocklistText.parse("segments sponsor selfpromo interaction intro outro").segments.size == 5)
    }

    @Test fun `a wrong kind or mirror names its line`() {
        assertEquals(2, assertThrows(BlocklistText.Malformed::class.java) { BlocklistText.parse("site a.org\nsegments ads") }.line)
        assertEquals(1, assertThrows(BlocklistText.Malformed::class.java) { BlocklistText.parse("mirror sb.example.org") }.line)
    }
}
