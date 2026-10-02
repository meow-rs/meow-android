package io.github.madeye.meow.tile

import android.service.quicksettings.Tile
import androidx.annotation.StringRes
import io.github.madeye.meow.R
import io.github.madeye.meow.bg.BaseService

/** How the tile renders one service state. */
internal data class TileLook(val tileState: Int, @param:StringRes val subtitle: Int)

/**
 * The status line is the Home screen's, so the two never disagree.
 *
 * The tile reads "on" from the moment a start is under way, not only once it
 * is up: it shows what the user asked for, and a second tap cancels the
 * connect. For the same reason it reads "off" as soon as a stop begins.
 */
internal fun tileLook(state: BaseService.State): TileLook = when (state) {
    BaseService.State.Connecting -> TileLook(Tile.STATE_ACTIVE, R.string.home_connecting)
    BaseService.State.Connected -> TileLook(Tile.STATE_ACTIVE, R.string.home_connected)
    BaseService.State.Stopping -> TileLook(Tile.STATE_INACTIVE, R.string.home_disconnecting)
    BaseService.State.Stopped -> TileLook(Tile.STATE_INACTIVE, R.string.home_disconnected)
    BaseService.State.Idle -> TileLook(Tile.STATE_INACTIVE, R.string.home_not_connected)
}

internal enum class TileTap { Stop, Start, Ignore }

internal fun tileTap(state: BaseService.State): TileTap = when {
    // Connecting included: the service cancels a connect in progress.
    state.canStop -> TileTap.Stop
    // The service only starts from Stopped, and the stop finishes with
    // stopSelf(), so a start sent now would be silently lost.
    state == BaseService.State.Stopping -> TileTap.Ignore
    else -> TileTap.Start
}

/** How a tap on a stopped tile gets the VPN up. */
internal enum class StartRoute {
    /** Start the service in place; the shade stays open. */
    Direct,

    /** The consent dialog needs an Activity: [TileConnectActivity]. */
    Consent,

    /** Nothing to connect with: the user has to pick a profile in the app. */
    OpenApp,
}

internal fun startRoute(hasProfile: Boolean, hasConsent: Boolean): StartRoute = when {
    // Checked first: asking for consent and then failing with "No profile
    // selected" would waste the user's trip through the dialog.
    !hasProfile -> StartRoute.OpenApp
    !hasConsent -> StartRoute.Consent
    else -> StartRoute.Direct
}
