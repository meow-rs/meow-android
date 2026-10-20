package io.github.madeye.meow.bg

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import io.github.madeye.meow.Core
import io.github.madeye.meow.preference.DataStore
import io.github.madeye.meow.preference.PerAppConfigStore
import io.github.madeye.meow.repo.PerAppMode
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import timber.log.Timber
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(), BaseService.Interface {
    companion object {
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        private const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        private const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        private const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"

        /** `android.os.Process.INVALID_UID`, a constant that only exists from API 29. */
        private const val INVALID_UID = -1

        /**
         * Starts the VPN. The caller must already hold VPN consent
         * ([android.net.VpnService.prepare] returned null); without it the
         * service just stops itself again.
         *
         * startForegroundService rather than startService, so callers that
         * are not on screen (a Quick Settings tile, the service restarting
         * itself) work too: an app holding VPN consent is exempt from the
         * background foreground-service start restriction, and the service
         * answers the call with startForeground() on every path. A background
         * caller with no such exemption gets an IllegalStateException
         * (ForegroundServiceStartNotAllowedException on Android 12+).
         *
         * False when the system could not launch the `:vpn` process for the
         * service: AMS reports any such failure as "Unable to start service
         * …: process is bad", thrown in the caller as a SecurityException.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, VpnService::class.java))
            true
        } catch (e: SecurityException) {
            // Uncaught it took down the caller, the UI process (#119). The VPN
            // just stays Stopped; the caller tells the user. IllegalState keeps
            // propagating: the QS tile's activity fallback depends on it.
            Timber.w(e, "VpnService: start refused")
            false
        }
    }

    inner class NullConnectionException : NullPointerException(), BaseService.ExpectedException {
        override fun getLocalizedMessage() = "Reboot required"
    }

    override val data = BaseService.Data(this)
    override val tag: String get() = "MeowVpnService"
    override fun createNotification(): ServiceNotification =
        ServiceNotification(this, "service-vpn")

    private var conn: ParcelFileDescriptor? = null
    private var active = false
    private var metered = false
    private var networkChanges: NetworkChangeRecorder? = null
    /** Keeps the declared underlying network current, below API 29 only. */
    private var underlying: DefaultNetwork? = null

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override fun onRevoke() = stopRunner()

    override fun killProcesses(scope: CoroutineScope) {
        underlying?.stop()
        underlying = null
        networkChanges?.stop()
        networkChanges = null
        super.killProcesses(scope)
        active = false
        conn?.close()
        conn = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)

    override suspend fun preInit() {
        if (prepare(this) != null) throw NullConnectionException()
    }

    override suspend fun startProcesses() {
        val configDir = File(Core.deviceStorage.noBackupFilesDir, "meow")
        configDir.mkdirs()
        data.meowInstance!!.start(configDir, this)
        startVpn()
    }

    override val isVpnService get() = true

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    /**
     * The uid of the app that owns one tunnelled flow, or
     * [INVALID_UID] when the system cannot tell (Android 9 and older
     * have no such query). Called over JNI from the FFI's owner.rs, on a
     * worker thread, for each new TCP connection and UDP session;
     * [localAddr] / [remoteAddr] are the app socket's raw IPv4 or IPv6
     * address bytes.
     */
    fun connectionOwnerUid(
        protocol: Int,
        localAddr: ByteArray,
        localPort: Int,
        remoteAddr: ByteArray,
        remotePort: Int,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return INVALID_UID
        return try {
            connectivity.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddress.getByAddress(localAddr), localPort),
                InetSocketAddress(InetAddress.getByAddress(remoteAddr), remotePort),
            )
        } catch (_: Exception) {
            // Must not reach the native caller: SecurityException once this
            // service is no longer the active VPN, IllegalArgumentException for
            // a protocol the platform rejects.
            INVALID_UID
        }
    }

    /**
     * The first package installed under [uid], or null for uids no package
     * owns (root, system daemons). Called over JNI from the FFI's owner.rs,
     * which caches the answer per uid.
     */
    fun packageForUid(uid: Int): String? = packageManager.getPackagesForUid(uid)?.firstOrNull()

    private fun startVpn() {
        val builder = Builder()
            .setSession("Meow VPN")
            .setMtu(VPN_MTU)
            .addAddress(PRIVATE_VLAN4_CLIENT, 30)
            .addDnsServer(PRIVATE_VLAN4_ROUTER)
            .addRoute("0.0.0.0", 0)
        // `ipv6: false` keeps IPv6 off the VPN, as mihomo drops its TUN's IPv6
        // address. With no IPv6 address or route, Android blocks the family
        // for the apps the VPN covers, so they fall back to IPv4 at once
        // instead of reaching DIRECT over IPv6 with an address from their own
        // DNS.
        if (data.meowInstance!!.tunIpv6()) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
                .addRoute("::", 0)
        }

        // Per-app VPN routing — the file store is authoritative: this process
        // caches SharedPreferences for its whole lifetime, so edits saved
        // after the first establish would never reach the next one. A file
        // holding a mode this build does not know is treated like a missing
        // one: the prefs keys are written alongside every save and stand in.
        val perApp = PerAppConfigStore.default.load()
            ?.takeIf { PerAppMode.from(it.mode) != null }
        val perAppPackages: Set<String> = perApp?.packages ?: try {
            JSONArray(DataStore.perAppPackages).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
        } catch (_: Exception) { emptySet() }
        val perAppMode = perApp?.mode ?: DataStore.perAppMode

        // Note: we deliberately do NOT add the meow package to
        // `addDisallowedApplication` here. The engine and tun2socks run in
        // the `:vpn` process and rely on `VpnService.protect(fd)` (called
        // from the patched meow-proxy connect hook and the meow-dns
        // SocketFactory) to bypass the TUN on a per-socket basis. Excluding
        // the whole app's uid would also exempt traffic users may want to
        // intercept (e.g. a built-in browser preview) and would shadow the
        // protect path the rest of the stack is designed around.
        if (perAppPackages.isNotEmpty()) when (PerAppMode.from(perAppMode)) {
            PerAppMode.Bypass -> {
                // All apps except selected go through VPN.
                for (pkg in perAppPackages) {
                    try { builder.addDisallowedApplication(pkg) }
                    // Vanished packages plus OEM RuntimeExceptions — one bad
                    // entry must not abort the whole establish.
                    catch (_: Exception) { }
                }
            }
            PerAppMode.Proxy -> {
                // Only selected apps go through VPN.
                for (pkg in perAppPackages) {
                    try { builder.addAllowedApplication(pkg) }
                    catch (_: Exception) { }
                }
            }
            null ->
                // Only reachable when the legacy prefs key itself is unknown
                // (the file path is filtered above). Apply no per-app routing
                // rather than guess: the tunnel covers everything.
                Timber.w("VpnService: unknown per-app mode '$perAppMode'; routing all apps")
        }

        active = true
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)

        val conn = builder.establish() ?: throw NullConnectionException()
        this.conn = conn
        // Records in the logbook which network the VPN sits on, and what the
        // apps on it are told, as the networks change.
        networkChanges?.stop()
        val networkChanges = NetworkChangeRecorder(this).also { it.start() }
        this.networkChanges = networkChanges
        // The underlying network. From API 29 a VPN that declares none sits on
        // the default network and follows it, its capabilities included; one
        // it declares is what Android answers the apps on it with
        // (getActiveNetworkInfo), even once that network is gone. A VPN that
        // declared the Wi-Fi it then left had the apps believe they were
        // offline until it restarted. Below API 29 nothing is followed, so
        // the declaration follows the default network instead, as
        // Clash Meta for Android does.
        underlying?.stop()
        underlying = if (Build.VERSION.SDK_INT < 29) {
            DefaultNetwork(this) { network, _ ->
                setUnderlyingNetworks(network?.let { arrayOf(it) })
                networkChanges.declare(network)
                Timber.d("VpnService: setUnderlyingNetworks=$network")
            }.also { it.start() }
        } else {
            null
        }
        data.meowInstance!!.startTun2Socks(this, conn.fd)
    }

    override fun onDestroy() {
        super.onDestroy()
        data.binder.close()
    }
}
