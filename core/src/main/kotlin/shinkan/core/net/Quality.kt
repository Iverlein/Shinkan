package shinkan.core.net

/**
 * The player's video quality (SPEC §7.2): which of a video's streams to play
 * at a height the child or the default asks for. The platform serves 360p with
 * sound in one file and higher qualities as picture alone, joined with a sound
 * stream by the player.
 */
object Quality {
    /** One stream of picture: its [height], its [codec] (`avc1…`, `vp9`, `av01…`) and whether it carries sound. */
    data class Option(val id: Int, val height: Int, val codec: String, val withSound: Boolean)

    /** The default height: the screen's shorter side, never below 1080 (the owner, 2026-11-02). */
    fun default(screenShortSide: Int): Int = maxOf(1080, screenShortSide)

    /** The heights on offer, highest first, AV1 aside. */
    fun heights(options: List<Option>): List<Int> =
        usable(options).map { it.height }.distinct().sortedDescending()

    /**
     * The stream to play for [height]: the highest at or below it, or the lowest
     * when all are above; at one height a stream with sound first, then H.264,
     * then VP9. AV1 is never chosen: many devices decode it in software only.
     */
    fun choose(options: List<Option>, height: Int): Option? {
        val usable = usable(options)
        val at = usable.filter { it.height <= height }.maxOfOrNull { it.height }
            ?: usable.minOfOrNull { it.height } ?: return null
        return usable.filter { it.height == at }
            .sortedWith(compareBy<Option>({ if (it.withSound) 0 else 1 }, { codecRank(it.codec) }))
            .first()
    }

    private fun usable(options: List<Option>) = options.filter { it.height > 0 && !it.codec.lowercase().startsWith("av01") }

    private fun codecRank(codec: String): Int = when {
        codec.lowercase().startsWith("avc") -> 0
        codec.lowercase().startsWith("vp9") || codec.lowercase().startsWith("vp09") -> 1
        else -> 2
    }
}
