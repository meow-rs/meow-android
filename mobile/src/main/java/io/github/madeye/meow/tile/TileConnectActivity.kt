package io.github.madeye.meow.tile

import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.madeye.meow.R
import timber.log.Timber

/**
 * Finishes a start that needs an Activity in front: the VPN consent dialog,
 * or a start the system would not take from the background. A tile tap
 * lands here, and so does a tap on the notification a failed start on boot
 * posts (`boot.BootReceiver`). It is translucent and draws nothing; it
 * finishes once the service is asked to start (or consent is declined).
 *
 * Not exported, so only the app's own PendingIntents can launch it. An
 * exported way to switch the VPN on is exactly what MainActivity's
 * debug-only `auto_connect` avoids.
 */
class TileConnectActivity : ComponentActivity() {

    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) start()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated under the consent dialog: its result is still on the way.
        if (savedInstanceState != null) return
        val request = VpnService.prepare(this)
        if (request == null) {
            // Being in front lifts the background-start limit.
            start()
            finish()
        } else {
            consent.launch(request)
        }
    }

    private fun start() {
        val started = try {
            startVpnService()
        } catch (e: IllegalStateException) {
            Timber.w(e, "startVpnService refused")
            false
        }
        // The application context: this activity finishes right away.
        if (!started) {
            Toast.makeText(applicationContext, R.string.home_start_refused, Toast.LENGTH_LONG).show()
        }
    }
}
