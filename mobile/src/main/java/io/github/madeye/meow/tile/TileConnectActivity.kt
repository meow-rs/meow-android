package io.github.madeye.meow.tile

import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Finishes a tile tap that needs an Activity in front: the VPN consent
 * dialog, or a start the system would not take from the background. It is
 * translucent and draws nothing; it finishes once the service is asked to
 * start (or consent is declined).
 *
 * Not exported, so only the tile's PendingIntent can launch it. An exported
 * way to switch the VPN on is exactly what MainActivity's debug-only
 * `auto_connect` avoids.
 */
class TileConnectActivity : ComponentActivity() {

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpnService()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated under the consent dialog: its result is still on the way.
        if (savedInstanceState != null) return
        val request = VpnService.prepare(this)
        if (request == null) {
            // Being in front lifts the background-start limit.
            startVpnService()
            finish()
        } else {
            consent.launch(request)
        }
    }
}
