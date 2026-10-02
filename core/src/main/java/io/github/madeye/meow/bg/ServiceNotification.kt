package io.github.madeye.meow.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.madeye.meow.core.MeowCore
import io.github.madeye.meow.core.R
import io.github.madeye.meow.utils.Action
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The ongoing notification that makes the VPN a foreground service: profile
 * name, live ↑/↓ speed, a Stop action, and a tap that opens the app.
 *
 * Owned by [BaseService] and only touched from the main thread: created and
 * put in the foreground at the top of the first start, told about state
 * changes, and destroyed at the end of every stop except a restart's — a
 * restart (reload, profile switch) keeps it, and the service with it in the
 * foreground, across the gap where no VPN runs.
 */
class ServiceNotification(
    private val service: Service,
    channelId: String,
) {
    private companion object {
        const val NOTIFICATION_ID = 1
        const val REFRESH_MILLIS = 1000L
    }

    private val notificationManager = service.getSystemService(NotificationManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var ticker: Job? = null
    private var connected = false

    private val builder = NotificationCompat.Builder(service, channelId)
        .setSmallIcon(R.drawable.ic_stat_meow)
        .setContentTitle(service.getText(R.string.app_name))
        .setContentText(service.getText(R.string.notification_connecting))
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        // The pre-O stand-in for the channel's LOW importance.
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        // A once-a-second ticker has no business buzzing a paired watch.
        .setLocalOnly(true)
        // Android 12+ may hold an FGS notification back for ~10 s; whether
        // traffic is being tunnelled should be visible the moment it is.
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        // :core cannot name :mobile's MainActivity. The launcher intent finds
        // it and, like the launcher, brings an existing task forward.
        .setContentIntent(
            service.packageManager.getLaunchIntentForPackage(service.packageName)?.let {
                PendingIntent.getActivity(service, 0, it, PendingIntent.FLAG_IMMUTABLE)
            },
        )
        // Package-scoped because BaseService registers its close receiver
        // RECEIVER_NOT_EXPORTED; the PendingIntent sends as this app.
        .addAction(
            0,
            service.getText(R.string.notification_stop),
            PendingIntent.getBroadcast(
                service,
                0,
                Intent(Action.CLOSE).setPackage(service.packageName),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )

    // Nobody reads the speed with the screen off, so the ticker sleeps rather
    // than wake :vpn and the notification service every second.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateTicker()
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The id predates this notification, so existing installs keep the
            // LOW importance they were created with — an app cannot change
            // importance afterwards. Re-creating only refreshes the name.
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    service.getString(R.string.notification_channel_vpn),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
        ContextCompat.registerReceiver(
            service,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Posts the notification and promotes the service to the foreground, or
     * only replaces the notification if a restart left it there.
     *
     * Every start must reach this: callers use startForegroundService, whose
     * watchdog kills `:vpn` unless startForeground() follows within seconds.
     * It never throws. The platform answers the watchdog once the
     * FOREGROUND_SERVICE permission and the manifest's type check out — both
     * static — and only then applies the type and background-start policies,
     * so a refusal here costs the foreground priority, never the VPN.
     *
     * A repeat while still in the foreground is harmless: if the
     * background-start policy refuses it, the platform throws (caught below)
     * but leaves the service where it was. It is not skipped on the belief
     * that the service is still there, because that belief can be stale — a
     * "Restricted" battery setting demotes the service without telling it,
     * and a start that then finds it in the background arms the watchdog.
     */
    fun startForeground() {
        // systemExempted is the type Android documents for VPN apps (it holds
        // while the user's VPN consent stands) and, unlike specialUse, needs
        // no Play Console justification. API 29-33 do not check the type.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(service, NOTIFICATION_ID, builder.build(), type)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException, or a
            // SecurityException once another VPN app has taken the consent. A
            // first start then runs without foreground priority; a repeat
            // keeps the foreground it had.
            Timber.w(e, "startForeground refused")
        }
    }

    /** The profile is only looked up after the start has been answered. */
    fun setProfileName(name: String) {
        builder.setContentTitle(name.ifEmpty { service.getString(R.string.app_name) })
        post()
    }

    /** A restart is underway: the speed would be stale until it connects. */
    fun showConnecting() {
        builder.setContentText(service.getText(R.string.notification_connecting))
        post()
    }

    fun onStateChanged(state: BaseService.State) {
        val nowConnected = state == BaseService.State.Connected
        if (nowConnected == connected) return
        connected = nowConnected
        // Replace "Connecting…" even if the screen is off and the ticker will
        // not run until it comes back on.
        if (connected) show(Speed.IDLE)
        updateTicker()
    }

    fun destroy() {
        scope.cancel()
        service.unregisterReceiver(screenReceiver)
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        // stopForeground() does nothing if startForeground() was refused, and
        // the updates were then posted as an ordinary notification.
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun updateTicker() {
        val run = connected && power.isInteractive
        if (run == (ticker != null)) return
        if (run) {
            ticker = scope.launch { tick() }
        } else {
            ticker?.cancel()
            ticker = null
        }
    }

    private suspend fun tick() {
        val meter = SpeedMeter()
        while (true) {
            meter.sample(
                tx = MeowCore.nativeGetUploadTraffic(),
                rx = MeowCore.nativeGetDownloadTraffic(),
                nowMillis = SystemClock.elapsedRealtime(),
            )?.let(::show)
            delay(REFRESH_MILLIS)
        }
    }

    private fun show(speed: Speed) {
        builder.setContentText(speed.text)
        post()
    }

    private fun post() {
        // Without POST_NOTIFICATIONS (Android 13+) the notification only shows
        // in the task manager; don't rebuild and re-post it every second for
        // nobody.
        if (!NotificationManagerCompat.from(service).areNotificationsEnabled()) return
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }
}
