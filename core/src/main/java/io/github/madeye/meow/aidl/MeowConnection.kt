package io.github.madeye.meow.aidl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import io.github.madeye.meow.bg.BaseService
import java.io.IOException
import timber.log.Timber

class MeowConnection(private var listenForBandwidth: Boolean = false) : ServiceConnection,
    IMeowServiceCallback.Stub() {

    interface Callback {
        fun stateChanged(state: BaseService.State, profileName: String, msg: String?)
        fun trafficUpdated(profileId: Long, stats: TrafficStats)
        fun trafficPersisted(profileId: Long)
    }

    private var callback: Callback? = null
    // Volatile: proxy probes read it from IO threads.
    @Volatile private var service: IMeowService? = null
    private var callbackRegistered = false
    // Remembered so onServiceDisconnected can rebind and resume the callback
    // registration (state + traffic) when the :vpn process restarts.
    private var bound = false
    private var bindContext: Context? = null

    val serviceState: BaseService.State
        get() = try {
            BaseService.State.entries[service?.state ?: 0]
        } catch (_: Exception) {
            BaseService.State.Idle
        }

    @Synchronized
    fun connect(context: Context, callback: Callback) {
        this.callback = callback
        if (bound) return
        bindContext = context
        val intent = Intent(context, io.github.madeye.meow.bg.VpnService::class.java)
            .setAction(io.github.madeye.meow.utils.Action.SERVICE)
        bound = try {
            context.bindService(intent, this, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            // A :vpn launch failure only returns false; the bind throws
            // when it is denied outright. Either way nothing is bound.
            Timber.w(e, "bindService refused")
            false
        }
    }

    @Synchronized
    fun disconnect(context: Context) {
        // Drop the rebind context first so a racing onServiceDisconnected
        // cannot rebind after a clean disconnect.
        bindContext = null
        if (bound) {
            unregisterCallback()
            context.unbindService(this)
            bound = false
        }
        callback = null
        service = null
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val service = IMeowService.Stub.asInterface(binder) ?: return
        this.service = service
        try {
            service.registerCallback(this)
            callbackRegistered = true
            if (listenForBandwidth) service.startListeningForBandwidth(this, 1000)
        } catch (e: RemoteException) {
            Timber.w(e)
        }
        callback?.stateChanged(serviceState, service.profileName ?: "", null)
    }

    @Synchronized
    override fun onServiceDisconnected(name: ComponentName?) {
        callbackRegistered = false
        service = null
        bound = false
        // VPN runs in a separate :vpn process; if it dies (system kill, crash),
        // the UI would otherwise keep showing the last-known state.
        callback?.stateChanged(BaseService.State.Stopped, "", null)
        // Rebind immediately so registerCallback()/startListeningForBandwidth()
        // run against the new process instance as soon as it is up. Without
        // this, the Activity never re-registered, and state + traffic
        // callbacks stayed dead until the Activity was recreated.
        bindContext?.let { ctx ->
            val intent = Intent(ctx, io.github.madeye.meow.bg.VpnService::class.java)
                .setAction(io.github.madeye.meow.utils.Action.SERVICE)
            bound = try {
                ctx.bindService(intent, this, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                Timber.w(e, "bindService refused")
                false
            }
        }
    }

    /**
     * Runs one proxy-detail probe in `:vpn` and returns its JSON answer,
     * blocking until it ends. Throws [IOException] when the service is not
     * bound or its process died mid-probe.
     */
    fun probeProxy(id: Long, proxy: String, kind: String, arg: String): String {
        val service = service ?: throw IOException("the VPN service is not bound")
        return try {
            service.probeProxy(id, proxy, kind, arg)
        } catch (e: RemoteException) {
            throw IOException("the VPN service went away", e)
        } ?: throw IOException("the VPN service gave no answer")
    }

    fun cancelProxyProbe(id: Long) {
        try {
            service?.cancelProxyProbe(id)
        } catch (_: RemoteException) {
            // Gone with its process, and its probes with it.
        }
    }

    /**
     * Puts the running VPN on Settings' "Built-in DNS" position, blocking
     * until it is applied. Null then, or when no VPN runs: its next start
     * reads the store. Else why the VPN kept the DNS it had.
     */
    fun setBuiltinDns(enabled: Boolean): String? {
        // Not bound: the :vpn process is restarting, and no VPN runs in it.
        val service = service ?: return null
        return try {
            service.setBuiltinDns(enabled)
        } catch (_: RemoteException) {
            // Gone with its process, and the VPN with it.
            null
        }
    }

    private fun unregisterCallback() {
        val service = service ?: return
        if (callbackRegistered) try {
            service.unregisterCallback(this)
        } catch (_: RemoteException) { }
        callbackRegistered = false
    }

    override fun stateChanged(state: Int, profileName: String?, msg: String?) {
        callback?.stateChanged(BaseService.State.entries[state], profileName ?: "", msg)
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats?) {
        if (stats != null) callback?.trafficUpdated(profileId, stats)
    }

    override fun trafficPersisted(profileId: Long) {
        callback?.trafficPersisted(profileId)
    }
}
