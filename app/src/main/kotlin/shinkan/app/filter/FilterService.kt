package shinkan.app.filter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.iverlein.shinkan.R
import shinkan.app.MainActivity
import shinkan.core.net.Blocklist
import shinkan.core.net.DnsQuestion
import shinkan.core.net.Udp4
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The filter (SPEC §5): a VPN that takes only the device's DNS. Every query
 * for a blocked host is answered NXDOMAIN; every other query goes to the
 * network's own resolver through a protected socket and comes back as is.
 * No other traffic enters the tunnel in this block; SNI and QUIC are block 8.
 */
class FilterService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val upstreams = Executors.newCachedThreadPool()
    private val blocklist = AtomicReference(Blocklist())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        blocklist.set(BlocklistStore(this).blocklist)
        if (intent?.action == ACTION_RELOAD) return START_STICKY
        if (tun == null) start()
        return START_STICKY
    }

    private fun start() {
        startForeground(NOTICE_ID, notice())
        val fd = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addRoute(DNS_ADDRESS, 32)
            .setBlocking(true)
            .setMetered(false)
            .establish() ?: run { Log.e(TAG, "establish refused"); stopSelf(); return }
        tun = fd
        worker = Thread({ loop(fd) }, "shinkan-filter").also { it.start() }
        Log.i(TAG, "filter up, ${blocklist.get().sites.size} sites blocked")
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)
        while (!Thread.interrupted()) {
            val n = try { input.read(buffer) } catch (e: Exception) { break }
            if (n <= 0) continue
            val datagram = Udp4.parse(buffer, n) ?: continue
            if (datagram.dstPort != 53) continue
            val question = DnsQuestion.parse(datagram.payload)
            if (question != null && question.isAddressQuery && blocklist.get().blocksHost(question.name)) {
                Log.d(TAG, "blocked ${question.name}")
                reply(output, datagram, DnsQuestion.refuse(datagram.payload))
                continue
            }
            upstreams.execute { forward(output, datagram) }
        }
    }

    /** Asks the network's resolver and writes its answer back into the tunnel. */
    private fun forward(output: FileOutputStream, datagram: Udp4) {
        val resolver = upstream() ?: return
        try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = 5000
                socket.send(DatagramPacket(datagram.payload, datagram.payload.size, InetSocketAddress(resolver, 53)))
                val answer = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(answer)
                reply(output, datagram, answer.data.copyOf(answer.length))
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "resolver $resolver timed out")
        } catch (e: Exception) {
            Log.w(TAG, "forward failed: $e")
        }
    }

    @Synchronized
    private fun reply(output: FileOutputStream, query: Udp4, payload: ByteArray) {
        output.write(Udp4.build(query.dst, query.src, query.dstPort, query.srcPort, payload))
    }

    /** The underlying network's first IPv4 resolver; the tunnel's own is never it. */
    private fun upstream(): InetAddress? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.asSequence()
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false }
            .flatMap { cm.getLinkProperties(it)?.dnsServers.orEmpty().asSequence() }
            .firstOrNull { it.address.size == 4 }
    }

    private fun notice(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.filter_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.icon_crest)
            .setContentTitle(getString(R.string.filter_notice))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        worker?.interrupt()
        tun?.close()
        tun = null
        upstreams.shutdownNow()
        super.onDestroy()
    }

    override fun onRevoke() {
        // The device owner locks the VPN to this package (DeviceHold); a revoke means the hold is gone.
        Log.w(TAG, "revoked")
        stopSelf()
    }

    companion object {
        private const val TAG = "FilterService"
        private const val CHANNEL = "filter"
        private const val NOTICE_ID = 1
        private const val TUN_ADDRESS = "10.111.0.1"
        private const val DNS_ADDRESS = "10.111.0.2"
        const val ACTION_RELOAD = "shinkan.filter.RELOAD"

        /** Starts the filter, or tells a running one to read the block list again. */
        fun start(context: Context, reload: Boolean = false) {
            val intent = Intent(context, FilterService::class.java)
            if (reload) intent.action = ACTION_RELOAD
            context.startForegroundService(intent)
        }
    }
}
