package shinkan.core.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * The key Shinkan pairs with: an RSA-2048 key in the forms adbd reads.
 *
 * [publicKey] is adb's text form, `android_pubkey_encode`'s struct in base64
 * and a name; it goes to the device in the pairing's peer info and is listed
 * under *Paired devices* by that name. [certificate] is the self-signed X.509
 * certificate the TLS connection presents; adbd matches its key against the
 * paired ones and checks nothing else in it.
 */
object AdbKey {
    private const val WORDS = 64

    fun publicKey(key: RSAPublicKey, name: String): String {
        val n = key.modulus
        require(n.bitLength() == 2048) { "adb takes RSA-2048" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(r32).modInverse(r32).negate().mod(r32)
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val buf = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WORDS)
        buf.putInt(n0inv.toInt())
        buf.put(Ed25519.littleEndian(n, 256))
        buf.put(Ed25519.littleEndian(rr, 256))
        buf.putInt(key.publicExponent.toInt())
        return Base64.getEncoder().encodeToString(buf.array()) + " " + name
    }

    /** A self-signed X.509 v3 certificate of [key] with the common name [name], SHA-256 with RSA. */
    fun certificate(key: RSAPublicKey, private: PrivateKey, name: String): ByteArray {
        val algorithm = Der.seq(Der.oid("1.2.840.113549.1.1.11"), Der.NULL)
        val subject = Der.seq(Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(name))))
        val tbs = Der.seq(
            Der.explicit(0, Der.int(BigInteger.TWO)),
            Der.int(BigInteger.ONE),
            algorithm,
            subject,
            Der.seq(Der.utcTime("200101000000Z"), Der.utcTime("491231235959Z")),
            subject,
            key.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(private)
            update(tbs)
            sign()
        }
        return Der.seq(tbs, algorithm, Der.bitString(signature))
    }
}

/** Just enough DER for [AdbKey.certificate]. */
internal object Der {
    val NULL = byteArrayOf(0x05, 0x00)

    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })

    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })

    fun int(n: BigInteger) = tlv(0x02, n.toByteArray())

    fun utf8(s: String) = tlv(0x0c, s.toByteArray())

    fun utcTime(s: String) = tlv(0x17, s.toByteArray(Charsets.US_ASCII))

    fun bitString(bytes: ByteArray) = tlv(0x03, byteArrayOf(0) + bytes)

    fun explicit(tag: Int, content: ByteArray) = tlv(0xa0 or tag, content)

    fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = mutableListOf((arcs[0] * 40 + arcs[1]).toByte())
        for (arc in arcs.drop(2)) {
            val groups = mutableListOf<Int>()
            var v = arc
            do {
                groups.add((v and 0x7f).toInt())
                v = v shr 7
            } while (v > 0)
            groups.reversed().forEachIndexed { i, g -> out.add((if (i < groups.size - 1) g or 0x80 else g).toByte()) }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val n = content.size
        val length = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
            else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }
}
