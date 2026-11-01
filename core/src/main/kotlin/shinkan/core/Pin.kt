package shinkan.core

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The parent's PIN, kept only as a salted PBKDF2 hash (SPEC §6.1). The stored
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
