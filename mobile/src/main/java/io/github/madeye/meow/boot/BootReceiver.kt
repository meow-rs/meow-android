package io.github.madeye.meow.boot

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.madeye.meow.Core
import io.github.madeye.meow.R
import io.github.madeye.meow.bg.VpnService
import io.github.madeye.meow.tile.StartRoute
import io.github.madeye.meow.tile.TileConnectActivity
import io.github.madeye.meow.tile.startRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import io.github.madeye.meow.core.R as CoreR

/**
 * Settings' "Start on boot": connects the VPN once the device has booted.
 * The manifest declares this receiver disabled and the switch enables it
 * ([StartOnBoot]), so with the switch off a boot never wakes the app.
 *
 * The start is the Home switch's ([VpnService.start]), so the engine comes up
 * the way the user left it: the selected profile, the route mode, the node
 * picks the engine restores from its selector cache, per-app routing and
 * Built-in DNS. With meow set as Android's always-on VPN the system starts it
 * too; whichever start arrives second finds the service running and is
 * ignored.
 *
 * Only BOOT_COMPLETED, which Android sends after the first unlock: the
 * profiles are in credential-encrypted storage, unreadable before that.
 *
 * Declared in `:vpn`, as the Quick Settings tile is, so a boot only starts the
 * lean process the service runs in. `App` skips the UI graph there, so
 * nothing here may touch `AppGraph`.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Not exported, so only the system can reach it; the check keeps the
        // start to boot should another action join the filter.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                start(app)
            } catch (e: Exception) {
                // Nothing reaches the user from here but the log; a crash in
                // :vpn at boot would not either.
                Timber.w(e, "BootReceiver: start on boot failed")
            } finally {
                pending.finish()
            }
        }
    }

    private fun start(context: Context) {
        // Room, so off the main thread.
        val hasProfile = Core.currentProfile != null
        val hasConsent = android.net.VpnService.prepare(context) == null
        when (bootStart(hasProfile, hasConsent)) {
            BootStart.Connect -> if (!startVpn(context)) notifyConnect(context)
            BootStart.AskToConnect -> notifyConnect(context)
            BootStart.Skip -> Timber.i("BootReceiver: no profile selected; not connecting")
        }
    }

    /**
     * [VpnService.start] from the background. Android exempts a
     * BOOT_COMPLETED receiver from the foreground-service start limit, and
     * `systemExempted` is not among the types Android 15 keeps from starting
     * at boot, so a refusal is not expected; if one comes, the notification
     * finishes the start in an Activity.
     */
    private fun startVpn(context: Context): Boolean = try {
        VpnService.start(context)
    } catch (e: IllegalStateException) {
        Timber.w(e, "BootReceiver: start refused")
        false
    }

    /**
     * Tells the user the VPN did not come up and offers the start
     * [TileConnectActivity] finishes in front: the consent dialog when
     * another VPN app has taken it (Android keeps one consent at a time), or
     * a start the system would not take from the background.
     */
    private fun notifyConnect(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        // Covers a POST_NOTIFICATIONS refusal on Android 13+ too.
        if (!manager.areNotificationsEnabled()) {
            Timber.i("BootReceiver: could not connect; notifications are off")
            return
        }
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.settings_start_on_boot))
                .setShowBadge(false)
                .build(),
        )
        val connect = PendingIntent.getActivity(
            context,
            0,
            Intent(context, TileConnectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(CoreR.drawable.ic_stat_meow)
            .setContentTitle(context.getString(R.string.boot_connect_title))
            .setContentText(context.getString(R.string.boot_connect_text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(connect)
            .setAutoCancel(true)
            .build()
        // The platform notify, as ServiceNotification's: the compat one wants
        // a POST_NOTIFICATIONS check lint can see, and areNotificationsEnabled
        // above already is that check.
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "start-on-boot"

        /** `ServiceNotification` (the VPN's own) uses 1. */
        const val NOTIFICATION_ID = 2

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** What a boot does with the VPN. */
internal enum class BootStart {
    /** Start it in the background, as the tile does. */
    Connect,

    /** Consent needs the user: post a notification that leads to it. */
    AskToConnect,

    /** Nothing to connect with; only picking a profile in the app helps. */
    Skip,
}

/** The tile's order of checks ([startRoute]): a missing profile wins over missing consent. */
internal fun bootStart(hasProfile: Boolean, hasConsent: Boolean): BootStart =
    when (startRoute(hasProfile, hasConsent)) {
        StartRoute.Direct -> BootStart.Connect
        StartRoute.Consent -> BootStart.AskToConnect
        StartRoute.OpenApp -> BootStart.Skip
    }
