package io.github.madeye.meow.ui.util

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads plain text off the clipboard, or null when there is none. */
@Composable
fun rememberClipboardText(): () -> String? {
    val context = LocalContext.current
    return remember(context) {
        {
            context.getSystemService<ClipboardManager>()
                ?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        }
    }
}

/** SAF read. Null when the document could not be opened. */
suspend fun Context.readText(uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()
}

/** SAF write. False when the document could not be written. */
suspend fun Context.writeText(uri: Uri, content: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
        true
    }.getOrDefault(false)
}

/** Opens [url] in whichever app handles it, or only in [inPackage]. False when no app does. */
fun Context.openUrl(url: String, inPackage: String? = null): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).setPackage(inPackage))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** This app's Google Play listing: in the Play Store app, or failing that on the web. */
fun Context.openPlayListing(): Boolean =
    openUrl("market://details?id=$packageName", inPackage = "com.android.vending") ||
        openUrl("https://play.google.com/store/apps/details?id=$packageName")
