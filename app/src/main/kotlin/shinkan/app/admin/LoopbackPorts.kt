package shinkan.app.admin

import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors

/**
 * The ports open on 127.0.0.1, found by connecting to each, many at a time:
 * on the loopback a closed port is refused at once. An app can read neither
 * `/proc/net/tcp` nor the ephemeral range, so every unprivileged port is tried.
 */
object LoopbackPorts {
    private const val BATCH = 512
    private const val WAIT_MS = 300L

    private const val THREADS = 8

    /** The open ports of [range], tried in [THREADS] slices at once. */
    fun open(range: IntRange = 1024..65535): List<Int> {
        val size = (range.last - range.first + THREADS) / THREADS
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val parts = (0 until THREADS).map { i ->
                val from = range.first + i * size
                pool.submit<List<Int>> { slice(from..minOf(from + size - 1, range.last)) }
            }
            return parts.flatMap { it.get() }.sorted()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun slice(range: IntRange): List<Int> {
        if (range.isEmpty()) return emptyList()
        val found = mutableListOf<Int>()
        Selector.open().use { selector ->
            for (batch in range.chunked(BATCH)) {
                val channels = mutableListOf<SocketChannel>()
                try {
                    for (port in batch) {
                        val ch = SocketChannel.open()
                        channels += ch
                        ch.configureBlocking(false)
                        if (ch.connect(InetSocketAddress("127.0.0.1", port))) {
                            found += port
                        } else {
                            ch.register(selector, SelectionKey.OP_CONNECT, port)
                        }
                    }
                    val deadline = System.currentTimeMillis() + WAIT_MS
                    var pending = selector.keys().size
                    while (pending > 0) {
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) break
                        selector.select(left)
                        for (key in selector.selectedKeys()) {
                            val ch = key.channel() as SocketChannel
                            if (runCatching { ch.finishConnect() }.getOrDefault(false)) found += key.attachment() as Int
                            key.cancel()
                            pending--
                        }
                        selector.selectedKeys().clear()
                    }
                } finally {
                    channels.forEach { runCatching { it.close() } }
                    selector.selectNow()
                }
            }
        }
        return found
    }
}
