package shinkan.core.adb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class Spake2Test {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test fun `the base point times a clamped key is RFC 8032's public key`() {
        val h = MessageDigest.getInstance("SHA-512")
            .digest(Ed25519.fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
        val a = h.copyOf(32)
        a[0] = (a[0].toInt() and 248).toByte()
        a[31] = ((a[31].toInt() and 127) or 64).toByte()
        val public = Ed25519.BASE.times(Ed25519.fromLittleEndian(a)).encode()
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", hex(public))
    }

    @Test fun `M and N are BoringSSL's points`() {
        assertEquals("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e", hex(Spake2.M.encode()))
        assertEquals("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778", hex(Spake2.N.encode()))
    }

    @Test fun `an encoding decodes to the same point`() {
        val p = Ed25519.BASE.times(java.math.BigInteger.valueOf(123456789))
        assertEquals(p, Ed25519.decode(p.encode()))
        assertEquals(Ed25519.IDENTITY, Ed25519.BASE.times(Ed25519.L))
    }

    private fun pair(a: String, b: String): Pair<ByteArray?, ByteArray?> {
        val client = "adb pair client\u0000".toByteArray()
        val server = "adb pair server\u0000".toByteArray()
        val alice = Spake2(Spake2.Role.ALICE, client, server, a.toByteArray())
        val bob = Spake2(Spake2.Role.BOB, server, client, b.toByteArray())
        return alice.finish(bob.message) to bob.finish(alice.message)
    }

    @Test fun `the same password gives both sides one key`() {
        val (a, b) = pair("123456", "123456")
        assertEquals(64, a!!.size)
        assertArrayEquals(a, b)
    }

    @Test fun `another password gives another key`() {
        val (a, b) = pair("123456", "123457")
        assertFalse(a!!.contentEquals(b))
    }

    @Test fun `a message that is no point is refused`() {
        val alice = Spake2(Spake2.Role.ALICE, ByteArray(1), ByteArray(1), ByteArray(1))
        // y = 2 has no x on the curve.
        val bad = ByteArray(32).also { it[0] = 2 }
        assertNull(alice.finish(bad))
        assertNull(alice.finish(ByteArray(31)))
    }
}
