package shinkan.core

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The policy as text, one statement per line (SPEC §4.4): what the app stores
 * and what a parent may read or paste. No library, so the format is this file.
 *
 *     allow org.fdroid.fdroid
 *     window com.example.game mon,tue,wed,thu,fri 16:00-18:00
 *     restrict factory_reset
 *
 * Blank lines and lines starting with # are skipped. Unknown statements and
 * malformed lines raise [PolicyText.Malformed] with the line number.
 */
object PolicyText {
    class Malformed(line: Int, text: String, why: String) : IllegalArgumentException("line $line: $why: $text")

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    private val dayNames = DayOfWeek.entries.associateBy { it.name.take(3).lowercase() }

    fun parse(text: String): Policy {
        val allowed = linkedSetOf<String>()
        val windows = mutableListOf<Window>()
        val restrictions = linkedSetOf<Restriction>()
        var restrictionsGiven = false
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val n = i + 1
            val parts = line.split(Regex("\\s+"))
            when (parts[0]) {
                "allow" -> {
                    if (parts.size != 2) throw Malformed(n, line, "allow takes one package")
                    allowed += parts[1]
                }
                "window" -> {
                    if (parts.size != 4) throw Malformed(n, line, "window takes package, days and hours")
                    val days = parts[2].split(",").map {
                        dayNames[it.lowercase()] ?: throw Malformed(n, line, "unknown day '$it'")
                    }.toSet()
                    val span = parts[3].split("-")
                    if (span.size != 2) throw Malformed(n, line, "hours are HH:MM-HH:MM")
                    val (from, to) = try {
                        LocalTime.parse(span[0], hhmm) to LocalTime.parse(span[1], hhmm)
                    } catch (e: Exception) {
                        throw Malformed(n, line, "hours are HH:MM-HH:MM")
                    }
                    if (from == to) throw Malformed(n, line, "window of zero length")
                    windows += Window(parts[1], days, from, to)
                }
                "restrict" -> {
                    if (parts.size != 2) throw Malformed(n, line, "restrict takes one name")
                    restrictionsGiven = true
                    restrictions += Restriction.byName(parts[1]) ?: throw Malformed(n, line, "unknown restriction")
                }
                else -> throw Malformed(n, line, "unknown statement")
            }
        }
        return Policy(allowed, windows, if (restrictionsGiven) restrictions else Restriction.DEFAULT)
    }

    fun render(policy: Policy): String = buildString {
        policy.allowed.sorted().forEach { appendLine("allow $it") }
        policy.windows.forEach { w ->
            val days = DayOfWeek.entries.filter { it in w.days }.joinToString(",") { it.name.take(3).lowercase() }
            appendLine("window ${w.pkg} $days ${w.from.format(hhmm)}-${w.to.format(hhmm)}")
        }
        policy.restrictions.sortedBy { it.ordinal }.forEach { appendLine("restrict ${it.name.lowercase()}") }
    }
}
