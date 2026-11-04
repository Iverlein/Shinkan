package shinkan.app.filter

import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import shinkan.core.net.Tcp4
import shinkan.core.net.TcpFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The device's TCP through the tunnel (SPEC §5.2): each connection an app opens ends
 * here, in a [TcpFlow], and goes on over a protected socket of the service's. One
 * thread and a selector; the tun device's reader hands segments in by [accept]. The
 * sockets carry the device's traffic and nothing of Shinkan's own (§2.1).
 */
class Relay(private val service: VpnService, private val toTunnel: (ByteArray) -> Unit) : Runnable {
    private class Connection(val flow: TcpFlow, val channel: SocketChannel) {
        val pending = ArrayDeque<ByteBuffer>()
        var pendingBytes = 0
        var shutdownAfterWrites = false
        var serverEnded = false
    }

    private data class Key(val address: Int, val srcPort: Int, val dstPort: Int)

    private val selector = Selector.open()
    private val inbox = ConcurrentLinkedQueue<Tcp4>()
    private val connections = HashMap<Key, Connection>()
    private val random = SecureRandom()
    private val buffer = ByteBuffer.allocate(READ_SIZE)
    private var lastTick = 0L

    @Volatile private var running = true

    /** A segment from the tun device, from any thread. */
    fun accept(segment: Tcp4) {
        inbox.add(segment)
        selector.wakeup()
    }

    fun stop() {
        running = false
        selector.wakeup()
    }

    override fun run() {
        try {
            while (running) {
                selector.select(TICK_MS)
                while (true) handle(inbox.poll() ?: break)
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (key.isValid) ready(key)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastTick >= TICK_MS) {
                    lastTick = now
                    connections.entries.toList().forEach { (k, c) -> apply(k, c, c.flow.tick(now)) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "relay stopped: $e")
        } finally {
            connections.values.forEach { runCatching { it.channel.close() } }
            connections.clear()
            runCatching { selector.close() }
        }
    }

    private fun keyOf(t: Tcp4) = Key(t.dst.fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xff) }, t.srcPort, t.dstPort)

    private fun handle(t: Tcp4) {
        val key = keyOf(t)
        val connection = connections[key]
        if (connection == null) {
            if (t.has(Tcp4.SYN) && !t.has(Tcp4.ACK)) open(key, t) else if (!t.has(Tcp4.RST)) refuse(t)
            return
        }
        val now = SystemClock.elapsedRealtime()
        apply(key, connection, connection.flow.segment(t, connection.pendingBytes < MAX_PENDING, now))
    }

    /** A segment of no connection known: the device is told, and stops. */
    private fun refuse(t: Tcp4) = toTunnel(
        Tcp4.build(t.dst, t.src, t.dstPort, t.srcPort, t.ack, (t.seq + t.payload.size) and 0xffffffffL, Tcp4.RST or Tcp4.ACK, 0),
    )

    private fun open(key: Key, syn: Tcp4) {
        val flow = TcpFlow(syn, random.nextInt().toLong() and 0xffffffffL, SystemClock.elapsedRealtime())
        if (connections.size >= MAX_CONNECTIONS) {
            flow.failed().packets.forEach(toTunnel)
            return
        }
        val channel = SocketChannel.open()
        try {
            channel.configureBlocking(false)
            // Every socket of the service's is protected, or it loops into the tunnel (app rule).
            if (!service.protect(channel.socket())) throw IllegalStateException("not protected")
            val connection = Connection(flow, channel)
            connections[key] = connection
            channel.register(selector, SelectionKey.OP_CONNECT, key)
            channel.connect(InetSocketAddress(InetAddress.getByAddress(syn.dst), syn.dstPort))
        } catch (e: Exception) {
            connections.remove(key)
            runCatching { channel.close() }
            flow.failed().packets.forEach(toTunnel)
        }
    }

    private fun ready(key: SelectionKey) {
        val id = key.attachment() as Key
        val connection = connections[id] ?: return
        try {
            if (key.isConnectable) {
                connection.channel.finishConnect()
                apply(id, connection, connection.flow.connected())
            }
            if (key.isValid && key.isWritable) drain(connection)
            if (key.isValid && key.isReadable) read(id, connection)
        } catch (e: Exception) {
            apply(id, connection, connection.flow.failed())
        }
    }

    private fun read(id: Key, connection: Connection) {
        buffer.clear()
        buffer.limit(minOf(READ_SIZE, connection.flow.room))
        if (!buffer.hasRemaining()) return
        val n = connection.channel.read(buffer)
        val now = SystemClock.elapsedRealtime()
        when {
            n > 0 -> apply(id, connection, connection.flow.fromServer(buffer.array().copyOf(n), now))
            n < 0 -> {
                connection.serverEnded = true
                apply(id, connection, connection.flow.serverDone(now))
            }
        }
    }

    private fun drain(connection: Connection) {
        while (connection.pending.isNotEmpty()) {
            val first = connection.pending.first()
            connection.pendingBytes -= connection.channel.write(first)
            if (first.hasRemaining()) return
            connection.pending.removeFirst()
        }
        if (connection.shutdownAfterWrites) {
            connection.shutdownAfterWrites = false
            runCatching { connection.channel.shutdownOutput() }
        }
    }

    private fun apply(id: Key, connection: Connection, step: TcpFlow.Step) {
        step.packets.forEach(toTunnel)
        if (step.close) {
            connections.remove(id)
            runCatching { connection.channel.close() }
            return
        }
        try {
            step.write?.let {
                connection.pending.addLast(ByteBuffer.wrap(it))
                connection.pendingBytes += it.size
            }
            if (step.shutdown) connection.shutdownAfterWrites = true
            if (connection.channel.isConnected) drain(connection)
            val registered = connection.channel.keyFor(selector) ?: return
            if (!connection.channel.isConnected) return
            var interest = 0
            if (connection.pending.isNotEmpty()) interest = interest or SelectionKey.OP_WRITE
            if (!connection.serverEnded && connection.flow.room > 0) interest = interest or SelectionKey.OP_READ
            if (registered.isValid) registered.interestOps(interest)
        } catch (e: Exception) {
            connections.remove(id)
            connection.flow.failed().packets.forEach(toTunnel)
            runCatching { connection.channel.close() }
        }
    }

    private companion object {
        const val TAG = "Relay"
        const val TICK_MS = 100L
        const val READ_SIZE = 32 * 1024
        const val MAX_PENDING = 256 * 1024
        const val MAX_CONNECTIONS = 1024
    }
}
