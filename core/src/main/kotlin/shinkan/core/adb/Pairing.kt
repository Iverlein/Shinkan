package shinkan.core.adb

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The pairing of Wireless debugging (adb's `pairing_connection`), the client's
 * side, over a TLS 1.3 stream already open to the pairing port. The password is
 * the code the device shows followed by 64 bytes exported from that TLS session
 * (label `adb-label\0`), so the code is bound to the connection.
 *
 * Each side sends its SPAKE2 message in a packet, derives the key, and sends its
 * peer info sealed with AES-128-GCM; a wrong code shows as a peer info that does
 * not open, or as the server closing the stream.
 */
object Pairing {
    const val EXPORT_LABEL = "adb-label\u0000"
    const val EXPORT_SIZE = 64

    private const val VERSION = 1
    private const val SPAKE2_MSG = 0
    private const val PEER_INFO = 1
    private const val MAX_PAYLOAD = 16384
    const val PEER_INFO_SIZE = 8192
    private const val RSA_PUB_KEY = 0
    private val CLIENT = "adb pair client\u0000".toByteArray()
    private val SERVER = "adb pair server\u0000".toByteArray()
    private val INFO = "adb pairing_auth aes-128-gcm key".toByteArray()

    class WrongCode : IOException("the pairing code was not accepted")

    /** Pairs: our adb public key (as [AdbKey.publicKey] writes it) goes to the device; returns the device's peer info. */
    fun pair(
        input: InputStream,
        output: OutputStream,
        code: String,
        exported: ByteArray,
        publicKey: String,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val spake = Spake2(Spake2.Role.ALICE, CLIENT, SERVER, code.toByteArray() + exported, random)
        val inp = DataInputStream(input)
        writePacket(output, SPAKE2_MSG, spake.message)
        val theirs = readPacket(inp, SPAKE2_MSG)
        val key = spake.finish(theirs) ?: throw WrongCode()
        val cipher = PairingCipher(key)
        writePacket(output, PEER_INFO, cipher.seal(peerInfo(publicKey)))
        val sealed = try {
            readPacket(inp, PEER_INFO)
        } catch (_: EOFException) {
            throw WrongCode()
        }
        return cipher.open(sealed) ?: throw WrongCode()
    }

    /** The server's half, for tests: answers one pairing; the client's peer info, or null on a wrong code. */
    fun serve(input: InputStream, output: OutputStream, code: String, exported: ByteArray, info: ByteArray): ByteArray? {
        val spake = Spake2(Spake2.Role.BOB, SERVER, CLIENT, code.toByteArray() + exported)
        val inp = DataInputStream(input)
        val theirs = readPacket(inp, SPAKE2_MSG)
        writePacket(output, SPAKE2_MSG, spake.message)
        val cipher = PairingCipher(spake.finish(theirs) ?: return null)
        val peer = cipher.open(readPacket(inp, PEER_INFO)) ?: return null
        writePacket(output, PEER_INFO, cipher.seal(info))
        return peer
    }

    /** A peer info: its type, then the key's text, zero-padded to [PEER_INFO_SIZE]. */
    fun peerInfo(publicKey: String): ByteArray {
        val text = publicKey.toByteArray()
        require(text.size < PEER_INFO_SIZE - 1) { "key too long" }
        return ByteArray(PEER_INFO_SIZE).also {
            it[0] = RSA_PUB_KEY.toByte()
            text.copyInto(it, 1)
        }
    }

    private fun writePacket(output: OutputStream, type: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION.toByte()).put(type.toByte()).putInt(payload.size).array()
        output.write(header + payload)
        output.flush()
    }

    private fun readPacket(input: DataInputStream, type: Int): ByteArray {
        val header = ByteArray(6)
        input.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toInt()
        val got = buf.get().toInt()
        val size = buf.getInt()
        if (version != VERSION || got != type || size <= 0 || size > MAX_PAYLOAD) {
            throw IOException("unexpected pairing packet: version $version, type $got, size $size")
        }
        return ByteArray(size).also(input::readFully)
    }
}

/**
 * AES-128-GCM keyed by HKDF-SHA256 of the SPAKE2 key (no salt), with each
 * direction's nonce its own counter, little-endian, from zero.
 */
class PairingCipher(spakeKey: ByteArray) {
    private val key = SecretKeySpec(hkdf(spakeKey, "adb pairing_auth aes-128-gcm key".toByteArray(), 16), "AES")
    private var sealed = 0L
    private var opened = 0L

    fun seal(plain: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, sealed++, plain)!!

    fun open(data: ByteArray): ByteArray? = try {
        run(Cipher.DECRYPT_MODE, opened++, data)
    } catch (_: AEADBadTagException) {
        null
    }

    private fun run(mode: Int, sequence: Long, data: ByteArray): ByteArray? {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(128, nonce))
        return cipher.doFinal(data)
    }

    private companion object {
        fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(info)
            mac.update(1)
            return mac.doFinal().copyOf(length)
        }
    }
}
