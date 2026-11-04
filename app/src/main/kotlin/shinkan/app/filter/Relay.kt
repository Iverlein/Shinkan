package shinkan.app.filter

import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import shinkan.core.net.Tcp4
import shinkan.core.net.TcpFlow
import shinkan.core.net.Udp4
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The device's TCP through the tunnel (SPEC §5.2): each connection an app opens ends
 * here, in a [TcpFlow], and goes on over a protected socket of the service's; each
 * sender's datagrams to one address go on over a socket of their own until they fall
 * silent. One thread and a selector; the tun device's reader hands packets in by
 * [accept]. The sockets carry the device's traffic and nothing of Shinkan's own (§2.1).
 */
class Relay(private val service: VpnService, private val toTunnel: (ByteArray) -> Unit) : Runnable {
    private class Connection(val flow: TcpFlow, val channel: SocketChannel) {
        val pending = ArrayDeque<ByteBuffer>()
        var pendingBytes = 0
        var shutdownAfterWrites = false
        var serverEnded = false
    }

    private data class Key(val address: Int, val srcPort: Int, val dstPort: Int)

    /** The datagrams of one sender to one address and port. */
    private class Exchange(val first: Udp4, val channel: DatagramChannel, var last: Long)

    private val selector = Selector.open()
    private val inbox = ConcurrentLinkedQueue<Any>()
    private val exchanges = HashMap<Key, Exchange>()
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

    /** A datagram from the tun device, from any thread. */
    fun accept(datagram: Udp4) {
        inbox.add(datagram)
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
                while (true) {
                    when (val packet = inbox.poll() ?: break) {
                        is Tcp4 -> handle(packet)
                        is Udp4 -> handle(packet)
                    }
                }
                val keys = selector.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (key.isValid) (key.attachment() as? Exchange)?.let(::answer) ?: ready(key)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastTick >= TICK_MS) {
                    lastTick = now
                    connections.entries.toList().forEach { (k, c) -> apply(k, c, c.flow.tick(now)) }
                    exchanges.entries.removeAll { (_, e) ->
                        (now - e.last > UDP_IDLE_MS).also { if (it) runCatching { e.channel.close() } }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "relay stopped: $e")
        } finally {
            connections.values.forEach { runCatching { it.channel.close() } }
            connections.clear()
            exchanges.values.forEach { runCatching { it.channel.close() } }
            exchanges.clear()
            runCatching { selector.close() }
        }
    }

    private fun keyOf(t: Tcp4) = Key(t.dst.fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xff) }, t.srcPort, t.dstPort)

    private fun handle(d: Udp4) {
        val key = Key(d.dst.fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xff) }, d.srcPort, d.dstPort)
        val exchange = exchanges[key] ?: open(key, d) ?: return
        exchange.last = SystemClock.elapsedRealtime()
        // A datagram that cannot go is lost, as any may be; the app asks again or gives up.
        runCatching { exchange.channel.write(ByteBuffer.wrap(d.payload)) }
    }

    private fun open(key: Key, d: Udp4): Exchange? {
        if (exchanges.size >= MAX_EXCHANGES) return null
        val channel = DatagramChannel.open()
        return try {
            channel.configureBlocking(false)
            if (!service.protect(channel.socket())) throw IllegalStateException("not protected")
            channel.connect(InetSocketAddress(InetAddress.getByAddress(d.dst), d.dstPort))
            Exchange(d, channel, SystemClock.elapsedRealtime()).also {
                channel.register(selector, SelectionKey.OP_READ, it)
                exchanges[key] = it
            }
        } catch (e: Exception) {
            runCatching { channel.close() }
            null
        }
    }

    /** What the far side sent back, to the sender, as from where it was sent. */
    private fun answer(exchange: Exchange) {
        val datagrams = ByteBuffer.allocate(MAX_DATAGRAM)
        val first = exchange.first
        while (true) {
            datagrams.clear()
            val n = runCatching { exchange.channel.read(datagrams) }.getOrDefault(0)
            if (n <= 0) return
            exchange.last = SystemClock.elapsedRealtime()
            toTunnel(Udp4.build(first.dst, first.src, first.dstPort, first.srcPort, datagrams.array().copyOf(n)))
        }
    }

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
        val id = key.attachment() as? Key ?: return
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
        const val MAX_EXCHANGES = 512
        const val UDP_IDLE_MS = 60_000L

        /** The largest datagram that fits the tunnel's MTU. */
        const val MAX_DATAGRAM = 1472
    }
}
