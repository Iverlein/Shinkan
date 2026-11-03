package shinkan.core.net

/**
 * The newest videos of several creators as one list, a page at a time (the
 * allow mode's Home, SPEC §7.1.1). Each creator's videos arrive newest first,
 * a page at a time; the list takes the newest of the creators' next videos,
 * and stops wherever a creator has none left in hand but has a further page,
 * since that page may hold a video newer than every other. [wanted] names
 * those creators; their pages are fetched and [add]ed, and [take] goes on.
 *
 * [time] gives a video's upload time in milliseconds; a video without one
 * counts as the oldest. Two videos of the same time keep their creators'
 * order, and a creator's own videos are never reordered.
 */
class Feed<T>(sources: Int, private val time: (T) -> Long?) {
    private val held = List(sources) { ArrayDeque<T>() }
    private val more = BooleanArray(sources) { true }

    /** The creators whose next page is needed before the list can go on. */
    fun wanted(): List<Int> = held.indices.filter { held[it].isEmpty() && more[it] }

    /** A page of [source]'s videos, newest first; [hasMore] false after its last page or when it failed. */
    fun add(source: Int, items: List<T>, hasMore: Boolean) {
        held[source].addAll(items)
        more[source] = hasMore
    }

    /** Up to [n] videos, newest first; fewer when a creator's page is [wanted] or nothing is left. */
    fun take(n: Int): List<T> {
        val out = ArrayList<T>()
        while (out.size < n && wanted().isEmpty()) {
            val next = held.indices.filter { held[it].isNotEmpty() }
                .maxWithOrNull(compareBy<Int> { time(held[it].first()) ?: Long.MIN_VALUE }.thenByDescending { it })
                ?: break
            out += held[next].removeFirst()
        }
        return out
    }

    /** Whether every creator's videos have all been taken. */
    val done: Boolean get() = held.all { it.isEmpty() } && more.none { it }
}
