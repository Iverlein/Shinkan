package shinkan.core

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The parent's PIN, kept only as a salted PBKDF2 hash (SPEC §9.1). The stored
 * form is "pbkdf2:<iterations>:<salt hex>:<hash hex>", so a later change of
 * parameters can be told from the old ones.
 */
object Pin {
    private const val ITERATIONS = 120_000
    private const val BITS = 256

    fun hash(pin: CharArray, random: SecureRandom = SecureRandom()): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2:$ITERATIONS:${salt.hex()}:${derive(pin, salt, ITERATIONS).hex()}"
    }

    fun verify(pin: CharArray, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val expected = parts[3].unhex()
        val actual = derive(pin, parts[2].unhex(), iterations)
        // Constant-time comparison: the result is independent of where the bytes first differ.
        if (expected.size != actual.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor actual[i].toInt())
        return diff == 0
    }

    private fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin, salt, iterations, BITS)).encoded

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * The rate limit on PIN entry (SPEC §9.1): after [FREE] wrong attempts in a row
 * each further one doubles the wait before the next, from [FIRST] seconds up to
 * [CAP]. A right PIN clears the count. Pure: the app keeps the count and the
 * time of the last attempt and asks here how long the door stays shut.
 */
object PinGate {
    /** Wrong attempts that cost nothing. */
    const val FREE = 3
    /** The first wait, in seconds, after [FREE] wrong attempts. */
    const val FIRST = 5L
    /** No wait is longer than an hour. */
    const val CAP = 3600L

    /** The shortest PIN accepted when one is set. */
    const val MIN_LENGTH = 4

    /** Seconds to wait before the next attempt after [wrong] wrong attempts in a row: 0, 0, 0, 5, 10, 20, 40, … up to an hour. */
    fun wait(wrong: Int): Long {
        if (wrong < FREE) return 0
        val doublings = wrong - FREE
        // 5 · 2^10 already passes the cap; stop before the shift overflows.
        if (doublings >= 10) return CAP
        return minOf(FIRST shl doublings, CAP)
    }

    /**
     * Seconds left before the next attempt, given [wrong] attempts in a row and the
     * last one at [lastMillis], both as stored, at [nowMillis]. A clock that went
     * backwards never makes the wait longer than [wait] itself.
     */
    fun remaining(wrong: Int, lastMillis: Long, nowMillis: Long): Long {
        val total = wait(wrong)
        if (total == 0L) return 0
        val elapsed = ((nowMillis - lastMillis) / 1000).coerceAtLeast(0)
        return (total - elapsed).coerceIn(0, total)
    }

    /** True when [pin] may be set: digits only, at least [MIN_LENGTH] of them. */
    fun acceptable(pin: CharArray): Boolean = pin.size >= MIN_LENGTH && pin.all { it in '0'..'9' }
}
