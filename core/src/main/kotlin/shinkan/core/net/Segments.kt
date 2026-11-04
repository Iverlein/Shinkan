package shinkan.core.net

import java.net.URLEncoder
import java.security.MessageDigest

/** The kinds of segment SponsorBlock marks, by the API's own names (SPEC §7.5). */
enum class SegmentKind(val key: String) {
    SPONSOR("sponsor"),
    SELFPROMO("selfpromo"),
    INTERACTION("interaction"),
    INTRO("intro"),
    OUTRO("outro"),
    PREVIEW("preview"),
    MUSIC_OFFTOPIC("music_offtopic"),
    FILLER("filler"),
    ;

    companion object {
        val DEFAULT = setOf(SPONSOR, SELFPROMO, INTERACTION)

        fun byKey(key: String): SegmentKind? = entries.firstOrNull { it.key == key }
    }
}

/** A part of a video to skip, in milliseconds. */
data class Segment(val startMs: Long, val endMs: Long, val kind: SegmentKind)

/**
 * The segment lookup (SPEC §7.5), the second named request of §2.1: the video is
 * asked for by the first four hex characters of its id's SHA-256, so the server
 * sees a bucket of videos, and the answer is narrowed to the video here.
 */
object Segments {
    const val API = "https://sponsor.ajay.app"

    /** The first four hex characters of the SHA-256 of [videoId]. */
    fun prefix(videoId: String): String =
        MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray()).take(2).joinToString("") { "%02x".format(it) }

    /** The address to ask at [base] for the bucket of [videoId], for [kinds]; the id itself is never in it. */
    fun address(base: String, videoId: String, kinds: Set<SegmentKind>): String {
        val names = SegmentKind.entries.filter { it in kinds }.joinToString(",", "[", "]") { "\"${it.key}\"" }
        return "${base.trimEnd('/')}/api/skipSegments/${prefix(videoId)}?categories=${URLEncoder.encode(names, "UTF-8")}"
    }

    /**
     * A mirror's address as the parent typed it, without its trailing slash; null
     * for anything but a plain `http` or `https` address.
     */
    fun mirror(typed: String): String? {
        val t = typed.trim().trimEnd('/')
        return t.takeIf { Regex("""https?://[^\s/?#]+(/[^\s?#]*)?""", RegexOption.IGNORE_CASE).matches(it) }
    }

    /**
     * The segments of [videoId] in the answer [json]: only its own entry of the
     * bucket, only [kinds], only those to skip, sorted by start. Fails with an
     * [IllegalArgumentException] on a text that is not such an answer.
     */
    fun parse(json: String, videoId: String, kinds: Set<SegmentKind>): List<Segment> {
        val bucket = Json.parse(json) as? List<*> ?: throw IllegalArgumentException("not a list of videos")
        val video = bucket.filterIsInstance<Map<*, *>>().firstOrNull { it["videoID"] == videoId } ?: return emptyList()
        val all = video["segments"] as? List<*> ?: return emptyList()
        return all.filterIsInstance<Map<*, *>>().mapNotNull { one(it) }
            .filter { it.kind in kinds }
            .sortedBy { it.startMs }
    }

    private fun one(entry: Map<*, *>): Segment? {
        if ((entry["actionType"] ?: "skip") != "skip") return null
        val kind = (entry["category"] as? String)?.let { SegmentKind.byKey(it) } ?: return null
        val times = entry["segment"] as? List<*> ?: return null
        val start = times.getOrNull(0) as? Double ?: return null
        val end = times.getOrNull(1) as? Double ?: return null
        if (end <= start) return null
        return Segment((start * 1000).toLong(), (end * 1000).toLong(), kind)
    }

    /**
     * Where to jump from [positionMs], or null to play on: the end of the segment the
     * position is in, carried on through any segment that overlaps or touches it.
     * [segments] are sorted by start.
     */
    fun skipTo(positionMs: Long, segments: List<Segment>): Long? {
        var to: Long? = null
        for (s in segments) {
            val at = to ?: positionMs
            if (s.startMs > at) break
            if (at < s.endMs) to = s.endMs
        }
        return to
    }
}
