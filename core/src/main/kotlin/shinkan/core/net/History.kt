package shinkan.core.net

/**
 * What the player has played, with where each video stopped (SPEC §7.1):
 * kept on the device only, newest first, at most [MAX] videos; the parent may
 * clear it. Nothing of it leaves the device (§2.5).
 */
data class History(val entries: List<Entry> = emptyList()) {
    /** One video: its address, title, uploader, thumbnail, length and the position it stopped at. */
    data class Entry(
        val url: String,
        val title: String,
        val uploader: String,
        val uploaderUrl: String,
        val thumbnail: String,
        val seconds: Long,
        val positionMs: Long,
        val watchedAt: Long,
    ) {
        /** How much has been watched, 0 to 1; a video played to its last ten seconds counts as done. */
        val progress: Float
            get() = if (seconds <= 0) 0f else (positionMs / 1000f / seconds).coerceIn(0f, 1f)

        /** Where to start it again: its position, or the beginning when it was watched to the end. */
        val resumeMs: Long
            get() = if (seconds > 0 && positionMs / 1000 >= seconds - 10) 0 else positionMs
    }

    /** [entry] at the front, any earlier entry of the same video gone, the oldest beyond [MAX] dropped. */
    fun played(entry: Entry): History =
        History((listOf(entry) + entries.filter { it.url != entry.url }).take(MAX))

    fun positionOf(url: String): Long = entries.firstOrNull { it.url == url }?.resumeMs ?: 0

    /** The uploaders of the history, most recent first, each once: the block mode's creators. */
    fun creators(): List<Pair<String, String>> =
        entries.filter { it.uploaderUrl.isNotEmpty() }.distinctBy { it.uploaderUrl }.map { it.uploader to it.uploaderUrl }

    companion object {
        const val MAX = 100

        /** One entry a line, its fields between tabs; what cannot be read is left out. */
        fun parse(text: String): History = History(
            text.lineSequence().mapNotNull { line ->
                val f = line.split('\t')
                if (f.size != 8) return@mapNotNull null
                runCatching { Entry(f[0], f[1], f[2], f[3], f[4], f[5].toLong(), f[6].toLong(), f[7].toLong()) }.getOrNull()
            }.toList(),
        )

        fun render(history: History): String = history.entries.joinToString("\n") { e ->
            listOf(e.url, e.title, e.uploader, e.uploaderUrl, e.thumbnail, e.seconds, e.positionMs, e.watchedAt)
                .joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') }
        }
    }
}
