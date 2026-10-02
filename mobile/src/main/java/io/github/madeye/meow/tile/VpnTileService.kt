package io.github.madeye.meow.tile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.service.quicksettings.PendingIntentActivityWrapper
import androidx.core.service.quicksettings.TileServiceCompat
import io.github.madeye.meow.Core
import io.github.madeye.meow.MainActivity
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.bg.VpnService
import io.github.madeye.meow.utils.Action
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Quick Settings tile that toggles the VPN.
 *
 * Declared in `:vpn`, next to the service it toggles, so it follows
 * [BaseService.localState] in-process: no binding whose callbacks can lag or
 * go stale, and pulling down the shade boots only that lean process, not the
 * UI one. The flip side: `App` skips the UI graph in `:vpn`, so nothing here
 * may touch `AppGraph` (analytics included).
 *
 * Deliberately not an ACTIVE_TILE. The system then rebinds the tile every time
 * the shade opens, so if `:vpn` died with the VPN up the next pull-down shows
 * "off" instead of a stale "on" — and `requestListeningState` (a no-op for
 * non-active tiles) is not needed to keep it current.
 */
class VpnTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        // Main.immediate renders a transition before the service goes on to
        // block this thread starting or stopping the engine.
        listening = scope.launch { BaseService.localState.collect { render(it) } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when (tileTap(BaseService.localState.value)) {
            // From the lock screen, anyone holding the phone could otherwise
            // drop the tunnel; starting it is harmless, so only stop asks.
            TileTap.Stop -> if (isSecure) unlockAndRun { stop() } else stop()
            TileTap.Start -> scope.launch { start() }
            TileTap.Ignore -> Unit
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(state: BaseService.State) {
        val tile = qsTile ?: return
        val look = tileLook(state)
        tile.state = look.tileState
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = getString(look.subtitle)
        tile.updateTile()
    }

    /** The Home switch's stop path, see `VpnStateRepository.requestStop`. */
    private fun stop() {
        sendBroadcast(Intent(Action.CLOSE).setPackage(packageName))
    }

    private suspend fun start() {
        val hasProfile = withContext(Dispatchers.IO) { Core.currentProfile != null }
        val hasConsent = android.net.VpnService.prepare(this) == null
        when (startRoute(hasProfile, hasConsent)) {
            StartRoute.Direct -> if (!startInPlace()) open(Intent(this, TileConnectActivity::class.java))
            StartRoute.Consent -> open(Intent(this, TileConnectActivity::class.java))
            StartRoute.OpenApp -> open(
                // The launcher's own intent, so an open app task is brought
                // forward instead of gaining a second MainActivity.
                Intent.makeMainActivity(ComponentName(this, MainActivity::class.java))
                    .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }
    }

    /**
     * False when the system refuses the start. The start is a foreground one
     * ([VpnService.start]); on API 31+ starting a foreground service from the
     * background needs an exemption, and the shade's binding to a tile is not
     * guaranteed to provide one (ForegroundServiceStartNotAllowedException is
     * an IllegalStateException).
     */
    private fun startInPlace(): Boolean = try {
        startVpnService()
        true
    } catch (e: IllegalStateException) {
        Timber.i(e, "background start refused; finishing the start in an activity")
        false
    }

    /**
     * Collapses the shade into [intent]. On API 34+ this has to be a
     * PendingIntent (the Intent overload throws); the compat picks for us.
     */
    private fun open(intent: Intent) {
        TileServiceCompat.startActivityAndCollapse(
            this,
            PendingIntentActivityWrapper(
                this,
                0,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                0,
                false,
            ),
        )
    }
}

/**
 * The Home switch's start (`startVpnService` in MeowApp.kt), shared by the
 * tile and [TileConnectActivity]: [VpnService.start], whose
 * startForegroundService the service answers with startForeground().
 */
internal fun Context.startVpnService() {
    VpnService.start(this)
}
