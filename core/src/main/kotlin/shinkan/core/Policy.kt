package shinkan.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * What the device may do, as the parent set it (SPEC §4). Pure data: the app
 * module turns it into DevicePolicyManager calls, this module only decides.
 *
 * A package named in [allowed] with no [Window] is usable at any time; one
 * with windows is usable only inside one of them. Every other package is held.
 */
data class Policy(
    val allowed: Set<String> = emptySet(),
    val windows: List<Window> = emptyList(),
    val restrictions: Set<Restriction> = Restriction.DEFAULT,
) {
    /** The packages the child may use at [now]. */
    fun usableAt(now: LocalDateTime): Set<String> {
        val windowed = windows.map { it.pkg }.toSet()
        val open = windows.filter { it.contains(now) }.map { it.pkg }.toSet()
        return allowed.filter { it !in windowed || it in open }.toSet()
    }

    /** The next moment at which [usableAt] can change after [now], or null when nothing is timed. */
    fun nextChange(now: LocalDateTime): LocalDateTime? =
        windows.flatMap { it.edgesAfter(now) }.minOrNull()
}

/** One span of hours on given weekdays in which [pkg] is usable. A span may cross midnight. */
data class Window(
    val pkg: String,
    val days: Set<DayOfWeek>,
    val from: LocalTime,
    val to: LocalTime,
) {
    init {
        require(pkg.isNotBlank()) { "window without a package" }
        require(days.isNotEmpty()) { "window without days" }
        require(from != to) { "window of zero length" }
    }

    private val overnight get() = to < from

    fun contains(now: LocalDateTime): Boolean {
        val t = now.toLocalTime()
        return if (!overnight) {
            now.dayOfWeek in days && t >= from && t < to
        } else {
            (now.dayOfWeek in days && t >= from) || (now.dayOfWeek.minus(1) in days && t < to)
        }
    }

    /** The window's opening and closing moments in the week after [now]. */
    internal fun edgesAfter(now: LocalDateTime): List<LocalDateTime> {
        val edges = mutableListOf<LocalDateTime>()
        for (offset in 0L..7L) {
            val day = now.toLocalDate().plusDays(offset)
            if (day.dayOfWeek !in days) continue
            edges += day.atTime(from)
            edges += if (overnight) day.plusDays(1).atTime(to) else day.atTime(to)
        }
        return edges.filter { it > now }
    }
}

/**
 * A user restriction the device owner sets (SPEC §4.3), by the constant's
 * name in android.os.UserManager so the app module needs no table of its own.
 */
enum class Restriction(val key: String) {
    FACTORY_RESET("no_factory_reset"),
    SAFE_BOOT("no_safe_boot"),
    ADD_USER("no_add_user"),
    INSTALL_APPS("no_install_apps"),
    INSTALL_UNKNOWN_SOURCES("no_install_unknown_sources"),
    UNINSTALL_APPS("no_uninstall_apps"),
    MODIFY_ACCOUNTS("no_modify_accounts"),
    CONFIG_DATE_TIME("no_config_date_time"),
    DEBUGGING_FEATURES("no_debugging_features"),
    USB_FILE_TRANSFER("no_usb_file_transfer"),
    CONFIG_CREDENTIALS("no_config_credentials"),
    CONFIG_PRIVATE_DNS("disallow_config_private_dns"),
    CONFIG_VPN("no_config_vpn"),
    ;

    companion object {
        /** What a freshly held device gets: everything a child could use to slip the hold, nothing a parent needs for daily use. */
        val DEFAULT: Set<Restriction> = setOf(
            FACTORY_RESET, SAFE_BOOT, ADD_USER, INSTALL_APPS, INSTALL_UNKNOWN_SOURCES,
            UNINSTALL_APPS, MODIFY_ACCOUNTS, CONFIG_DATE_TIME, DEBUGGING_FEATURES, CONFIG_PRIVATE_DNS, CONFIG_VPN,
        )

        fun byName(name: String): Restriction? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
