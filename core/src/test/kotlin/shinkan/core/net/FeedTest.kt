package shinkan.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeedTest {
    /** A video named by its creator and upload time. */
    private data class V(val by: String, val at: Long?)

    private fun feed(n: Int) = Feed<V>(n) { it.at }

    @Test fun `nothing is taken before every creator's first page`() {
        val f = feed(2)
        assertEquals(listOf(0, 1), f.wanted())
        f.add(0, listOf(V("a", 10)), hasMore = false)
        assertEquals(emptyList<V>(), f.take(5))
        assertEquals(listOf(1), f.wanted())
    }

    @Test fun `the creators' videos are merged newest first`() {
        val f = feed(2)
        f.add(0, listOf(V("a", 90), V("a", 50), V("a", 10)), hasMore = false)
        f.add(1, listOf(V("b", 70), V("b", 30)), hasMore = false)
        assertEquals(listOf(90L, 70, 50, 30, 10), f.take(10).map { it.at })
        assertTrue(f.done)
    }

    @Test fun `the list stops where a creator's next page may hold a newer video`() {
        val f = feed(2)
        f.add(0, listOf(V("a", 90), V("a", 80)), hasMore = true)
        f.add(1, listOf(V("b", 85), V("b", 20)), hasMore = false)
        // a's page ends at 80; its next page could hold 60, newer than b's 20.
        assertEquals(listOf(90L, 85, 80), f.take(10).map { it.at })
        assertEquals(listOf(0), f.wanted())
        f.add(0, listOf(V("a", 60), V("a", 5)), hasMore = false)
        assertEquals(listOf(60L, 20, 5), f.take(10).map { it.at })
        assertTrue(f.done)
    }

    @Test fun `take stops at n and goes on from there`() {
        val f = feed(1)
        f.add(0, listOf(V("a", 3), V("a", 2), V("a", 1)), hasMore = false)
        assertEquals(listOf(3L, 2), f.take(2).map { it.at })
        assertFalse(f.done)
        assertEquals(listOf(1L), f.take(2).map { it.at })
        assertTrue(f.done)
    }

    @Test fun `a video without a time counts as the oldest, ties keep the creators' order`() {
        val f = feed(2)
        f.add(0, listOf(V("a", null), V("a", 7)), hasMore = false)
        f.add(1, listOf(V("b", 7), V("b", null)), hasMore = false)
        // a's own order is kept: its timeless video stays before its 7.
        assertEquals(listOf(V("b", 7), V("a", null), V("a", 7), V("b", null)), f.take(10))
    }

    @Test fun `a creator that failed is dropped and the rest go on`() {
        val f = feed(2)
        f.add(0, emptyList(), hasMore = false)
        f.add(1, listOf(V("b", 1)), hasMore = false)
        assertEquals(listOf(V("b", 1)), f.take(10))
        assertTrue(f.done)
    }
}
