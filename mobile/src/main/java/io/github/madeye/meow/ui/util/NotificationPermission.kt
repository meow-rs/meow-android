package io.github.madeye.meow.ui.util

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.madeye.meow.preference.DataStore

/**
 * Asks for POST_NOTIFICATIONS the first time it is invoked on Android 13+,
 * and never again.
 *
 * Without the permission the VPN's notification (live speed, Stop) stays out
 * of the shade, but the VPN itself works the same — so the answer is ignored,
 * nothing waits on it, and a denial stands: asking on every connect would nag,
 * and the user can still allow it from system settings.
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    return remember(context, launcher) {
        request@{
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@request
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted || DataStore.notificationPermissionAsked) return@request
            DataStore.notificationPermissionAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
