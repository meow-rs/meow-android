package io.github.madeye.meow

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.madeye.meow.bg.MeowInstance
import io.github.madeye.meow.ui.MeowApp
import io.github.madeye.meow.ui.theme.MeowTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The app's only Activity.
 *
 * Was a `FlutterActivity` hosting a MethodChannel bridge; the Compose UI runs
 * in this process and calls the same Kotlin directly, so the channels are gone.
 *
 * Two things here are load-bearing for `test-e2e.sh` and must not be renamed:
 * the `.MainActivity` class name, and the `auto_connect` boolean extra.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AppGraph.vpn.bind(this)

        // Seed the GeoIP databases and register the engine home directory up
        // front, so config validation works before the first VPN start.
        // Idempotent; off the main thread to avoid first-run jank.
        scope.launch(Dispatchers.IO) {
            try {
                MeowInstance.prepareEngineHome(applicationContext)
            } catch (e: Exception) {
                Timber.w(e, "prepareEngineHome failed")
            }
        }

        // MainActivity is exported, so any app could send this extra. Only the
        // e2e harness needs it, and it installs the debug APK: ignore it in
        // non-debuggable builds so third-party apps cannot force-start the VPN.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val autoConnect = debuggable &&
            intent?.getBooleanExtra("auto_connect", false) == true

        setContent {
            MeowTheme {
                MeowApp(autoConnect = autoConnect)
            }
        }
    }

    override fun onDestroy() {
        AppGraph.vpn.unbind(this)
        scope.cancel()
        super.onDestroy()
    }
}
