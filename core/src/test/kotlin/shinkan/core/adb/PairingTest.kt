package shinkan.core.adb

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.CompletableFuture

class PairingTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val public = keys.public as RSAPublicKey

    /** Runs the client against [Pairing.serve] over pipes; the server's result and the client's. */
    private fun run(clientCode: String, serverCode: String): Pair<ByteArray?, Result<ByteArray>> {
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer, 65536)
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, 65536)
        val exported = ByteArray(64) { it.toByte() }
        val deviceInfo = ByteArray(Pairing.PEER_INFO_SIZE).also { it[0] = 1 }
        val server = CompletableFuture.supplyAsync {
            try {
                Pairing.serve(serverIn, toClient, serverCode, exported, deviceInfo).also { if (it == null) toClient.close() }
            } catch (e: Exception) {
                toClient.close()
                null
            }
        }
        val client = runCatching { Pairing.pair(clientIn, toServer, clientCode, exported, AdbKey.publicKey(public, "Shinkan")) }
        return server.get() to client
    }

    @Test fun `the right code pairs and each side gets the other's peer info`() {
        val (server, client) = run("482913", "482913")
        assertEquals(0, server!![0].toInt())
        assertTrue(server.decodeToString(1).startsWith(AdbKey.publicKey(public, "Shinkan")))
        assertEquals(1, client.getOrThrow()[0].toInt())
    }

    @Test fun `a wrong code is told as one`() {
        val (server, client) = run("482913", "482914")
        assertNull(server)
        assertThrows(Pairing.WrongCode::class.java) { client.getOrThrow() }
    }

    @Test fun `adb's key struct holds the modulus, its inverse word and R squared`() {
        val text = AdbKey.publicKey(public, "Shinkan")
        assertTrue(text.endsWith(" Shinkan"))
        val raw = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, raw.size)
        val buf = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buf.getInt())
        val n0inv = buf.getInt().toLong() and 0xffffffffL
        val n = Ed25519.fromLittleEndian(raw.copyOfRange(8, 264))
        val rr = Ed25519.fromLittleEndian(raw.copyOfRange(264, 520))
        assertEquals(public.modulus, n)
        val r32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(r32 - BigInteger.ONE, (n.mod(r32) * BigInteger.valueOf(n0inv)).mod(r32))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), rr)
        assertEquals(65537, java.nio.ByteBuffer.wrap(raw, 520, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt())
    }

    @Test fun `the certificate parses and its signature is its key's`() {
        val der = AdbKey.certificate(public, keys.private, "Shinkan")
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.verify(public)
        assertEquals(public, cert.publicKey)
        assertEquals("CN=Shinkan", cert.subjectX500Principal.name)
    }

    @Test fun `a message is read back as written`() {
        val m = AdbMessage(AdbMessage.WRTE, 7, 9, "abc".toByteArray())
        val back = AdbMessage.read(DataInputStream(ByteArrayInputStream(m.encode())))
        assertEquals(AdbMessage.WRTE, back.command)
        assertEquals(7, back.arg0)
        assertEquals(9, back.arg1)
        assertArrayEquals("abc".toByteArray(), back.data)
    }

    @Test fun `a shell command runs through STLS, CNXN and OPEN to its close`() {
        val toDevice = PipedOutputStream()
        val deviceIn = DataInputStream(PipedInputStream(toDevice, 65536))
        val toHost = PipedOutputStream()
        val hostIn = PipedInputStream(toHost, 65536)
        val seen = mutableListOf<String>()
        val device = CompletableFuture.runAsync {
            fun send(m: AdbMessage) = toHost.write(m.encode())
            val cnxn = AdbMessage.read(deviceIn)
            seen += "cnxn ${cnxn.arg0.toString(16)}"
            send(AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0))
            val stls = AdbMessage.read(deviceIn)
            seen += "stls ${stls.command == AdbMessage.STLS}"
            send(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, 4096, "device::\u0000".toByteArray()))
            val open = AdbMessage.read(deviceIn)
            seen += open.data.decodeToString()
            send(AdbMessage(AdbMessage.OKAY, 42, open.arg0))
            send(AdbMessage(AdbMessage.WRTE, 42, open.arg0, "Success: ".toByteArray()))
            seen += "ack ${AdbMessage.read(deviceIn).command == AdbMessage.OKAY}"
            send(AdbMessage(AdbMessage.WRTE, 42, open.arg0, "owner set\n".toByteArray()))
            AdbMessage.read(deviceIn)
            send(AdbMessage(AdbMessage.CLSE, 42, open.arg0))
            val close = AdbMessage.read(deviceIn)
            seen += "close ${close.arg0} ${close.arg1}"
        }
        var upgraded = false
        val client = AdbClient(hostIn, toDevice)
        client.connect { upgraded = true; hostIn to toDevice }
        val out = client.shell("dpm set-device-owner x/.Y")
        device.get()
        assertTrue(upgraded)
        assertEquals("Success: owner set\n", out)
        assertEquals(listOf("cnxn 1000001", "stls true", "shell:dpm set-device-owner x/.Y\u0000", "ack true", "close 1 42"), seen)
    }
}
