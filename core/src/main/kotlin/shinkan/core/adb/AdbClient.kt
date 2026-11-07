package shinkan.core.adb

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One message of the adb protocol: a 24-byte little-endian header, then its data. */
class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = ByteArray(0)) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
        buf.putInt(data.sumOf { it.toInt() and 0xff })
        buf.putInt(command.inv())
        buf.put(data)
        return buf.array()
    }

    companion object {
        const val CNXN = 0x4e584e43
        const val STLS = 0x534c5453
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43

        const val VERSION = 0x01000001
        const val STLS_VERSION = 0x01000000
        const val MAX_DATA = 1024 * 1024

        fun read(input: DataInputStream): AdbMessage {
            val header = ByteArray(24)
            input.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.getInt()
            val arg0 = buf.getInt()
            val arg1 = buf.getInt()
            val length = buf.getInt()
            buf.getInt()
            val magic = buf.getInt()
            if (magic != command.inv() || length < 0 || length > MAX_DATA) throw IOException("not an adb message")
            return AdbMessage(command, arg0, arg1, ByteArray(length).also(input::readFully))
        }
    }
}

/**
 * The host's side of adb over Wireless debugging, enough to run one shell
 * command: CNXN, the device's STLS answered and TLS started by [upgrade] (which
 * returns the streams of the TLS session on the same socket), the device's
 * CNXN, then `shell:` opened, its output read to its close.
 */
class AdbClient(input: InputStream, private var output: OutputStream) {
    private var input = DataInputStream(input)
    private var nextId = 1

    fun connect(upgrade: () -> Pair<InputStream, OutputStream>) {
        send(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_DATA, "host::\u0000".toByteArray()))
        var m = AdbMessage.read(input)
        if (m.command == AdbMessage.STLS) {
            send(AdbMessage(AdbMessage.STLS, AdbMessage.STLS_VERSION, 0))
            val (i, o) = upgrade()
            input = DataInputStream(i)
            output = o
            m = AdbMessage.read(input)
        }
        when (m.command) {
            AdbMessage.CNXN -> return
            AdbMessage.AUTH -> throw IOException("the device asks for a key: not paired over TLS")
            else -> throw IOException("unexpected adb message %08x".format(m.command))
        }
    }

    /** Runs [command] in the device's shell and returns what it wrote, stdout and stderr together. */
    fun shell(command: String): String {
        val local = nextId++
        send(AdbMessage(AdbMessage.OPEN, local, 0, "shell:$command\u0000".toByteArray()))
        val out = StringBuilder()
        var remote = 0
        while (true) {
            val m = AdbMessage.read(input)
            if (m.arg1 != local) continue
            when (m.command) {
                AdbMessage.OKAY -> remote = m.arg0
                AdbMessage.WRTE -> {
                    out.append(m.data.decodeToString())
                    send(AdbMessage(AdbMessage.OKAY, local, m.arg0))
                }
                AdbMessage.CLSE -> {
                    if (remote != 0) send(AdbMessage(AdbMessage.CLSE, local, remote))
                    return out.toString()
                }
            }
        }
    }

    private fun send(m: AdbMessage) {
        output.write(m.encode())
        output.flush()
    }
}
