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
import io.github.iverlein.shinkan.BuildConfig
import io.github.iverlein.shinkan.R
import shinkan.app.MainActivity
import shinkan.app.plan.DeviceVolume
import shinkan.app.plan.PlanClock
import shinkan.core.net.Blocklist
import shinkan.core.net.DnsQuestion
import shinkan.core.net.SeenHosts
import shinkan.core.net.dropsDatagram
import shinkan.core.net.TcpSegment
import shinkan.core.net.UdpDatagram
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
 * The filter (SPEC §5): a VPN that takes all the device's traffic, IPv4 and IPv6. Every query
 * for a blocked host is answered NXDOMAIN; every other query goes to the
 * network's own resolver through a protected socket and comes back as is. TCP
 * and other UDP go on through the [Relay], but QUIC to a blocked host.
 */
class FilterService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val upstreams = Executors.newCachedThreadPool()
    private val blocklist = AtomicReference(Blocklist())
    private val screen = PlanClock.Screen()
    private val volume = DeviceVolume()
    private var relay: Relay? = null
    private val seen = SeenHosts()
    private var output: FileOutputStream? = null

    /** The screen's broadcasts for the day plan's counter (SPEC §4.7) and the volume's for the device ceiling (§4.6), for as long as the filter runs. */
    override fun onCreate() {
        super.onCreate()
        registerReceiver(screen, PlanClock.Screen.filter, RECEIVER_NOT_EXPORTED)
        registerReceiver(volume, DeviceVolume.filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        blocklist.set(BlocklistStore(this).blocklist)
        // Every start comes through startForegroundService, which must end in startForeground:
        // a reload of a filter that is not up yet starts it.
        if (tun == null) start()
        return START_STICKY
    }

    private fun start() {
        startForeground(NOTICE_ID, notice())
        val fd = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(TUN_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addAddress(TUN_ADDRESS6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(MTU)
            // Shinkan's own traffic stays out of its tunnel: inside it, the DNS-only route leaves
            // Shinkan nothing but DNS (lockdown exempts the owner from its drop, not from the route).
            // Shinkan's pages apply the block list themselves (SPEC §5.5).
            .addDisallowedApplication(packageName)
            .also { if (android.os.Build.VERSION.SDK_INT >= 29) it.setMetered(false) }
            .setBlocking(true)
            .establish() ?: run { Log.e(TAG, "establish refused"); stopSelf(); return }
        tun = fd
        worker = Thread({ loop(fd) }, "shinkan-filter").also { it.start() }
        Log.i(TAG, "filter up, ${blocklist.get().sites.size} sites blocked")
    }

    private fun loop(fd: ParcelFileDescriptor) {
        // The built-in list is read here, off the main thread, once per process.
        val builtin = BuiltinLists.get(this)
        Log.i(TAG, "built-in list: ${builtin.ads.size} names of ads and trackers")
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor).also { this.output = it }
        val tcp = Relay(this, { blocklist.get().blocksHost(it, builtin) }, { blocklist.get().refusesPort(it) }, ::toTunnel).also { relay = it }
        Thread(tcp, "shinkan-relay").start()
        val buffer = ByteArray(32767)
        while (!Thread.interrupted()) {
            val n = try { input.read(buffer) } catch (e: Exception) { break }
            if (n <= 0) continue
            TcpSegment.parse(buffer, n)?.let {
                tcp.accept(it)
                continue
            }
            val datagram = UdpDatagram.parse(buffer, n) ?: continue
            if (datagram.dstPort != 53) {
                val blocked = blocklist.get().dropsDatagram(datagram.dstPort, seen.nameOf(datagram.dst), builtin)
                if (blocked && BuildConfig.DEBUG) Log.d(TAG, "dropped quic to ${seen.nameOf(datagram.dst)}")
                if (!blocked) tcp.accept(datagram)
                continue
            }
            val question = DnsQuestion.parse(datagram.payload)
            if (question != null && question.isAddressQuery && blocklist.get().blocksHost(question.name, builtin)) {
                // Only the beta names what it blocked: nothing about the child is logged (SPEC §2.5).
                if (BuildConfig.DEBUG) Log.d(TAG, "blocked ${question.name}")
                reply(output, datagram, DnsQuestion.refuse(datagram.payload))
                continue
            }
            upstreams.execute { forward(output, datagram, question?.name) }
        }
    }

    /** Asks the network's resolver and writes its answer back into the tunnel. */
    private fun forward(output: FileOutputStream, datagram: UdpDatagram, name: String?) {
        val resolver = upstream() ?: return
        try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = 5000
                socket.send(DatagramPacket(datagram.payload, datagram.payload.size, InetSocketAddress(resolver, 53)))
                val answer = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(answer)
                val payload = answer.data.copyOf(answer.length)
                if (name != null) seen.learn(payload, name)
                reply(output, datagram, payload)
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "resolver $resolver timed out")
        } catch (e: Exception) {
            Log.w(TAG, "forward failed: $e")
        }
    }

    private fun reply(output: FileOutputStream, query: UdpDatagram, payload: ByteArray) {
        toTunnel(UdpDatagram.build(query.dst, query.src, query.dstPort, query.srcPort, payload))
    }

    /** One packet into the tun device; the relay and the resolver's replies share it. A closed device drops it. */
    @Synchronized
    private fun toTunnel(packet: ByteArray) {
        try {
            output?.write(packet)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "tunnel closed: $e")
        }
    }

    /**
     * The underlying network's first IPv4 resolver; the tunnel's own is never it.
     * Reading the networks needs ACCESS_NETWORK_STATE; a failure here drops the
     * one query instead of the whole process.
     */
    private fun upstream(): InetAddress? = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.asSequence()
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false }
            .flatMap { cm.getLinkProperties(it)?.dnsServers.orEmpty().asSequence() }
            .firstOrNull { it.address.size == 4 }
    } catch (e: Exception) {
        Log.e(TAG, "no upstream resolver: $e")
        null
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
        unregisterReceiver(screen)
        unregisterReceiver(volume)
        relay?.stop()
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
        private const val TUN_ADDRESS6 = "fd11:1::1"
        private const val MTU = 1500
        const val ACTION_RELOAD = "shinkan.filter.RELOAD"

        /** Starts the filter, or tells a running one to read the block list again. */
        fun start(context: Context, reload: Boolean = false) {
            val intent = Intent(context, FilterService::class.java)
            if (reload) intent.action = ACTION_RELOAD
            context.startForegroundService(intent)
        }
    }
}
