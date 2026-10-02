package io.github.madeye.meow.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import androidx.core.content.ContextCompat
import io.github.madeye.meow.Core
import io.github.madeye.meow.aidl.IMeowService
import io.github.madeye.meow.aidl.IMeowServiceCallback
import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.utils.Action
import kotlinx.coroutines.*
import timber.log.Timber

object BaseService {
    enum class State(val canStop: Boolean = false) {
        Idle,
        Connecting(true),
        Connected(true),
        Stopping,
        Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        @Volatile var state = State.Stopped
        @Volatile var meowInstance: MeowInstance? = null
        var notification: ServiceNotification? = null
        var closeReceiverRegistered = false
        val binder = Binder(this)
        var connectingJob: Job? = null

        /**
         * A reload arrived mid-start; [Interface.reloadIfChanged] runs again
         * once connected. A stale flag from a start that failed is harmless:
         * the check compares against what the next start actually read.
         */
        var reloadPending = false

        val closeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SHUTDOWN -> {}
                    Action.RELOAD -> service.reloadIfChanged()
                    else -> service.stopRunner()
                }
            }
        }

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            binder.stateChanged(s, msg)
            state = s
            notification?.onStateChanged(s)
        }
    }

    class Binder(@Volatile private var data: Data? = null) : IMeowService.Stub(), CoroutineScope, AutoCloseable {
        // RemoteCallbackList drops dead binders automatically; the parallel
        // bandwidthListeners map must mirror that. If a binder that died with
        // the app process stayed in the map, the 1 Hz traffic looper would
        // keep ticking for it and a later (re)registering listener would be
        // silently skipped by the isEmpty() guard — freezing traffic stats
        // until the service itself is destroyed.
        private val callbacks = object : RemoteCallbackList<IMeowServiceCallback>() {
            override fun onCallbackDied(callback: IMeowServiceCallback) {
                launch {
                    if (bandwidthListeners.remove(callback.asBinder()) != null &&
                        bandwidthListeners.isEmpty()
                    ) {
                        looper?.cancel()
                        looper = null
                    }
                }
            }
        }
        private val bandwidthListeners = mutableMapOf<IBinder, Long>()
        override val coroutineContext = Dispatchers.Main.immediate + Job()
        private var looper: Job? = null

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.meowInstance?.profileName ?: "Idle"

        override fun registerCallback(cb: IMeowServiceCallback) { callbacks.register(cb) }

        private fun broadcast(work: (IMeowServiceCallback) -> Unit) {
            val count = callbacks.beginBroadcast()
            try {
                repeat(count) {
                    try { work(callbacks.getBroadcastItem(it)) }
                    catch (_: RemoteException) {}
                    catch (e: Exception) { Timber.w(e) }
                }
            } finally { callbacks.finishBroadcast() }
        }

        private suspend fun loop() {
            while (true) {
                delay(bandwidthListeners.values.minOrNull() ?: return)
                val instance = data?.meowInstance ?: continue
                if (data?.state != State.Connected || bandwidthListeners.isEmpty()) continue
                val stats = instance.requestTrafficUpdate()
                broadcast { item ->
                    if (bandwidthListeners.contains(item.asBinder())) {
                        item.trafficUpdated(0, stats)
                    }
                }
            }
        }

        override fun startListeningForBandwidth(cb: IMeowServiceCallback, timeout: Long) {
            launch {
                // Always (re)register the listener — the previous guard only
                // added it when the map was empty, so after an app-process
                // restart the new binder was silently ignored while the stale
                // dead entry kept the looper running for nobody.
                bandwidthListeners[cb.asBinder()] = timeout
                if (looper?.isActive != true) {
                    looper = launch { loop() }
                }
            }
        }

        override fun stopListeningForBandwidth(cb: IMeowServiceCallback) {
            launch {
                if (bandwidthListeners.remove(cb.asBinder()) != null && bandwidthListeners.isEmpty()) {
                    looper?.cancel()
                    looper = null
                }
            }
        }

        override fun unregisterCallback(cb: IMeowServiceCallback) {
            stopListeningForBandwidth(cb)
            callbacks.unregister(cb)
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun forceLoad() {
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> Timber.w("Illegal state $s when invoking use")
            }
        }

        /**
         * Handles [Action.RELOAD], which the UI sends after committing a
         * change to the selected profile (`vpn.ConfigReloader`). Unlike
         * [forceLoad] it never starts a stopped service, and it restarts only
         * if the selected profile now differs from the one this run started
         * with, so duplicate or cancelled-out requests cost nothing.
         *
         * The restart is [stopRunner]'s: the TUN goes down and comes back,
         * [onStartCommand] reads the selected profile from Room afresh,
         * [MeowInstance.start] replays the route mode from `RouteModeStore`,
         * and the engine restores selector picks from its own cache file
         * (`selector-cache.json` in the engine home dir, keyed by group name).
         */
        fun reloadIfChanged() {
            val data = data
            val running = ActiveConfig.of(data.meowInstance?.profile)
            when (ReloadPolicy.onRequest(data.state, running) { ActiveConfig.of(Core.currentProfile) }) {
                ReloadPolicy.Decision.Restart -> {
                    Timber.i("Reloading: the selected profile changed")
                    stopRunner(true)
                }
                ReloadPolicy.Decision.Defer -> data.reloadPending = true
                ReloadPolicy.Decision.Ignore -> {}
            }
        }

        val isVpnService get() = false

        suspend fun startProcesses()

        /** Starts this service again, e.g. on reload; see [VpnService.start]. */
        fun startRunner() {
            this as Context
            try {
                ContextCompat.startForegroundService(this, Intent(this, javaClass))
            } catch (e: IllegalStateException) {
                // A refused restart leaves the VPN stopped; crashing :vpn over
                // it would not bring it back either. Nothing will start behind
                // the notification stopRunner kept for the restart, so drop it.
                Timber.w(e, "restart refused")
                data.notification?.destroy()
                data.notification = null
            }
        }

        fun killProcesses(scope: CoroutineScope) {
            data.meowInstance?.stop()
            data.meowInstance = null
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            if (data.state == State.Stopping) return
            data.changeState(State.Stopping)
            if (restart) data.notification?.showConnecting()
            GlobalScope.launch(Dispatchers.Main.immediate) {
                data.connectingJob?.cancelAndJoin()
                this@Interface as Service
                coroutineScope {
                    killProcesses(this)
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.closeReceiver)
                        data.closeReceiverRegistered = false
                    }
                }
                // A restart keeps the notification, and so the foreground:
                // dropping it would flash it away and leave the next start to
                // promote the service anew, which Android 12+ may refuse while
                // the app is in the background. Otherwise it is torn down here
                // rather than in the block above, which can suspend: a start
                // arriving meanwhile is answered with this notification (see
                // onStartCommand) and then ignored, so it must still be
                // removed. Nothing suspends from here to Stopped.
                if (!restart) {
                    data.notification?.destroy()
                    data.notification = null
                }
                data.changeState(State.Stopped, msg)
                if (restart) startRunner() else stopSelf()
            }
        }

        suspend fun preInit() {}

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val data = data
            // Starts arrive through startForegroundService (see
            // VpnService.start), whose watchdog kills :vpn unless
            // startForeground() follows within seconds — so answer it before
            // anything can bail out, including the redundant-start and
            // no-profile returns below and the Room lookup in between. After a
            // restart the notification is still up and this only refreshes it.
            val notification = data.notification
                ?: createNotification().also { data.notification = it }
            notification.startForeground()
            if (data.state != State.Stopped) return Service.START_NOT_STICKY

            val profile = Core.currentProfile
            this as Context
            if (profile == null) {
                stopRunner(false, "No profile selected")
                return Service.START_NOT_STICKY
            }
            notification.setProfileName(profile.name)

            data.meowInstance = MeowInstance(profile)

            if (!data.closeReceiverRegistered) {
                ContextCompat.registerReceiver(this, data.closeReceiver, IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            data.connectingJob = GlobalScope.launch(Dispatchers.Main.immediate) {
                try {
                    preInit()
                    startProcesses()
                    data.changeState(State.Connected)
                    if (data.reloadPending) {
                        data.reloadPending = false
                        reloadIfChanged()
                    }
                } catch (_: CancellationException) {
                } catch (exc: Throwable) {
                    Timber.w(exc)
                    stopRunner(false, "Service failed: ${exc.localizedMessage}")
                } finally {
                    data.connectingJob = null
                }
            }
            return Service.START_NOT_STICKY
        }
    }
}
