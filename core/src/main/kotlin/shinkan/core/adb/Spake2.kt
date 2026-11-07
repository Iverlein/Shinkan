package shinkan.core.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over edwards25519 as BoringSSL's `spake25519.c` does it, which adbd's
 * pairing server uses (`adb/pairing_auth`): a 32-byte message each way, then a
 * 64-byte key that both sides hold only when they started from the same
 * password. Alice, the pairing client, masks with M and unmasks with N.
 */
class Spake2(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    password: ByteArray,
    random: SecureRandom = SecureRandom(),
) {
    enum class Role { ALICE, BOB }

    private val passwordHash: ByteArray = MessageDigest.getInstance("SHA-512").digest(password)

    /**
     * The hash reduced by the group's order, then raised by a multiple of it to a
     * multiple of eight: BoringSSL's unilateral fix of a missing cofactor shift,
     * which changes the masks by points of small order and must be matched.
     */
    private val passwordScalar: BigInteger = run {
        val s = Ed25519.fromLittleEndian(passwordHash).mod(Ed25519.L)
        (0..7).map { s + Ed25519.L * BigInteger.valueOf(it.toLong()) }.first { !it.testBit(0) && !it.testBit(1) && !it.testBit(2) }
    }

    /** A random scalar times the cofactor, so the peer's point is cleared of small order. */
    private val privateKey: BigInteger = run {
        val bytes = ByteArray(64).also(random::nextBytes)
        Ed25519.fromLittleEndian(bytes).mod(Ed25519.L).shiftLeft(3)
    }

    /** This side's message: the private point plus the password's mask. */
    val message: ByteArray = run {
        val mask = (if (role == Role.ALICE) M else N).times(passwordScalar)
        (Ed25519.BASE.times(privateKey) + mask).encode()
    }

    /** The shared key from the other side's message; null when it is no point. */
    fun finish(theirMessage: ByteArray): ByteArray? {
        if (theirMessage.size != 32) return null
        val masked = Ed25519.decode(theirMessage) ?: return null
        val peer = masked - (if (role == Role.ALICE) N else M).times(passwordScalar)
        val shared = peer.times(privateKey).encode()
        val sha = MessageDigest.getInstance("SHA-512")
        fun put(bytes: ByteArray) {
            sha.update(Ed25519.littleEndian(BigInteger.valueOf(bytes.size.toLong()), 8))
            sha.update(bytes)
        }
        if (role == Role.ALICE) {
            put(myName); put(theirName); put(message); put(theirMessage)
        } else {
            put(theirName); put(myName); put(theirMessage); put(message)
        }
        put(shared)
        put(passwordHash)
        return sha.digest()
    }

    companion object {
        internal val M: Ed25519.Point = Ed25519.fromSeed("edwards25519 point generation seed (M)")
        internal val N: Ed25519.Point = Ed25519.fromSeed("edwards25519 point generation seed (N)")
    }
}
