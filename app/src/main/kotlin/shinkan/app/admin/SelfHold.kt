package shinkan.app.admin

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.ssl.SSLSockets
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.iverlein.shinkan.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import shinkan.app.MainActivity
import shinkan.core.adb.AdbClient
import shinkan.core.adb.AdbKey
import shinkan.core.adb.Pairing
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The hold taken from the device itself (SPEC §3.3): Shinkan pairs with the
 * device's own Wireless debugging by the code Settings shows, connects to it on
 * the loopback, and runs the command of §3.1, `dpm set-device-owner`, as the
 * shell. Then it turns Wireless debugging off and forgets its key.
 *
 * Every connection is to 127.0.0.1. adbd's two ports are found there by
 * trying ([LoopbackPorts]): the system's service discovery, where adbd
 * announces them, is closed from Android 17 to an app without
 * `ACCESS_LOCAL_NETWORK`, and the loopback is not.
 */
@RequiresApi(Build.VERSION_CODES.R)
object SelfHold {
    sealed interface Stage {
        data object Idle : Stage
        data object Working : Stage
        data object Done : Stage
        data class Failed(val why: Why, val detail: String? = null) : Stage
    }

    /** What went wrong, worded on the page. */
    enum class Why { NO_PAIRING, WRONG_CODE, NO_DEBUGGING, REFUSED, BROKEN }

    private const val TAG = "SelfHold"
    private const val KEY_NAME = "Shinkan"
    const val CHANNEL = "pairing"
    private const val NOTICE = 7
    const val REPLY = "code"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _stage = MutableStateFlow<Stage>(Stage.Idle)
    val stage: StateFlow<Stage> = _stage

    /** Takes the hold with [code], the six digits Settings shows. */
    /** [fromNotice]: the code came from the notification, which then says how it went. */
    fun start(context: Context, code: String, fromNotice: Boolean = false, done: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                _stage.value = Stage.Working
                val result = try {
                    take(app, code.trim())
                } catch (e: Pairing.WrongCode) {
                    Stage.Failed(Why.WRONG_CODE)
                } catch (e: Failure) {
                    Stage.Failed(e.why, e.detail)
                } catch (e: Exception) {
                    Log.w(TAG, "taking the hold failed", e)
                    Stage.Failed(Why.BROKEN, e.message ?: e.javaClass.simpleName)
                }
                _stage.value = result
                if (notifying || fromNotice) notice(app, result)
            }
            done()
        }
    }

    private class Failure(val why: Why, val detail: String? = null) : IOException(why.name)

    private fun take(context: Context, code: String): Stage {
        val keys = Keys.load(context)
        val tls = tls(keys)
        val started = System.currentTimeMillis()
        val open = LoopbackPorts.open()
        Log.i(TAG, "open on the loopback after ${System.currentTimeMillis() - started} ms: $open")
        // The pairing port is the one that pairs: anything else fails at TLS or at the first packet.
        val paired = open.firstOrNull { port ->
            try {
                pair(tls, keys, port, code)
                true
            } catch (e: Pairing.WrongCode) {
                throw e
            } catch (e: IOException) {
                false
            }
        } ?: throw Failure(Why.NO_PAIRING)
        Log.i(TAG, "paired on port $paired")
        // adbd's own port is the one that answers CNXN with STLS.
        for (port in open - paired) {
            val out = runCatching { shellOn(context, tls, port) }.getOrElse { continue } ?: continue
            Log.i(TAG, "dpm on port $port: ${out.trim()}")
            if (!DeviceHold(context).isOwner) throw Failure(Why.REFUSED, out.trim())
            Keys.forget(context)
            return Stage.Done
        }
        throw Failure(Why.NO_DEBUGGING)
    }

    private fun pair(tls: SSLContext, keys: Keys, port: Int, code: String) {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
        socket.soTimeout = 5000
        val ssl = tls.socketFactory.createSocket(socket, "127.0.0.1", port, true) as SSLSocket
        ssl.use {
            ssl.useClientMode = true
            ssl.startHandshake()
            val exported = SSLSockets.exportKeyingMaterial(ssl, Pairing.EXPORT_LABEL, null, Pairing.EXPORT_SIZE)
                ?: throw IOException("no keying material")
            Pairing.pair(ssl.inputStream, ssl.outputStream, code, exported, AdbKey.publicKey(keys.public, KEY_NAME))
        }
    }

    /**
     * Runs `dpm set-device-owner` through adbd on [port]; null when the port is not
     * adbd's (no STLS to CNXN). Then turns Wireless debugging off: the door Shinkan
     * came in by is shut behind it, and the hold's restrictions keep it shut.
     */
    private fun shellOn(context: Context, tls: SSLContext, port: Int): String? {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
        socket.soTimeout = 15000
        socket.use {
            val adb = AdbClient(socket.getInputStream(), socket.getOutputStream())
            var upgraded = false
            adb.connect {
                upgraded = true
                val ssl = tls.socketFactory.createSocket(socket, "127.0.0.1", port, true) as SSLSocket
                ssl.useClientMode = true
                ssl.startHandshake()
                ssl.inputStream to ssl.outputStream
            }
            if (!upgraded) return null
            val component = ShinkanAdmin.component(context).flattenToShortString()
            val out = adb.shell("dpm set-device-owner $component")
            if (DeviceHold(context).isOwner) runCatching { adb.shell("settings put global adb_wifi_enabled 0") }
            return out
        }
    }

    /** A TLS 1.3 context that shows Shinkan's certificate and takes adbd's own, which is self-signed. */
    private fun tls(keys: Keys): SSLContext {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(AdbKey.certificate(keys.public, keys.private, KEY_NAME).inputStream()) as X509Certificate
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(KEY_NAME)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = KEY_NAME
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = KEY_NAME
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getCertificateChain(alias: String?) = arrayOf(cert)
            override fun getPrivateKey(alias: String?): PrivateKey = keys.private
        }
        // adbd's certificate is its own, self-signed: the pairing code is what proves it.
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        val tm = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(km), arrayOf(tm), SecureRandom()) }
    }

    /** Shinkan's RSA-2048 pairing key, in its files beyond backup, kept until the hold is taken. */
    private class Keys(val public: RSAPublicKey, val private: PrivateKey) {
        companion object {
            private fun file(context: Context) = File(context.noBackupFilesDir, "adb_key.pk8")

            fun load(context: Context): Keys {
                val f = file(context)
                val factory = KeyFactory.getInstance("RSA")
                if (f.exists()) {
                    val private = factory.generatePrivate(PKCS8EncodedKeySpec(f.readBytes())) as RSAPrivateCrtKey
                    val public = factory.generatePublic(RSAPublicKeySpec(private.modulus, private.publicExponent)) as RSAPublicKey
                    return Keys(public, private)
                }
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                f.writeBytes(pair.private.encoded)
                return Keys(pair.public as RSAPublicKey, pair.private)
            }

            fun forget(context: Context) {
                file(context).delete()
            }
        }
    }

    // The notification the code is typed into, while Settings keeps its code on screen.

    @Volatile private var notifying = false

    /** Shows the notification that asks for the code; its reply runs [start] through [PairingService]. */
    fun ask(context: Context) {
        notifying = true
        _stage.value = Stage.Idle
        notice(context, null)
    }

    fun dismiss(context: Context) {
        notifying = false
        context.getSystemService(NotificationManager::class.java).cancel(NOTICE)
    }

    private fun notice(context: Context, result: Stage?) {
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTICE, notification(context, result)) }
    }

    /** The notification for [stage]: asking for the code (again, with what went wrong), working, or done. */
    fun notification(context: Context, stage: Stage?): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.selfhold_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.icon_crest)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
        when (stage) {
            Stage.Done -> {
                notifying = false
                builder.setContentTitle(context.getString(R.string.selfhold_done))
                    .setContentText(context.getString(R.string.selfhold_done_body))
                    .setAutoCancel(true)
            }
            Stage.Working -> builder.setContentTitle(context.getString(R.string.selfhold_working)).setOngoing(true)
            else -> {
                // The reply starts PairingService: work begun from a notification may run in the
                // foreground while Settings keeps the code on screen, with no broadcast's deadline.
                val reply = PendingIntent.getForegroundService(
                    context, 0, Intent(context, PairingService::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val input = RemoteInput.Builder(REPLY).setLabel(context.getString(R.string.selfhold_code_label)).build()
                val action = Notification.Action.Builder(null, context.getString(R.string.selfhold_reply), reply)
                    .addRemoteInput(input).build()
                val text = (stage as? Stage.Failed)?.let { context.getString(whyText(it.why)) }
                    ?: context.getString(R.string.selfhold_notice_body)
                builder.setContentTitle(context.getString(R.string.selfhold_notice))
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setOngoing(true)
                    .addAction(action)
            }
        }
        return builder.build()
    }

    const val NOTICE_ID = NOTICE

    /** The page's words for [why]. */
    fun whyText(why: Why): Int = when (why) {
        Why.NO_PAIRING -> R.string.selfhold_no_pairing
        Why.WRONG_CODE -> R.string.selfhold_wrong_code
        Why.NO_DEBUGGING -> R.string.selfhold_no_debugging
        Why.REFUSED -> R.string.selfhold_refused
        Why.BROKEN -> R.string.selfhold_broken
    }
}
