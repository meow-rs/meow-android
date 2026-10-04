package io.github.madeye.meow.ui.screens.subscribe

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.zxing.common.BitMatrix
import io.github.madeye.meow.R
import io.github.madeye.meow.ui.qr.QrCodes
import io.github.madeye.meow.ui.qr.toBitmap
import io.github.madeye.meow.ui.theme.meow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Side of the shared PNG, roughly: big enough to stay sharp once a chat app recompresses it. */
private const val SHARE_SIDE_PX = 1024

/**
 * [profile] as a QR code another phone can scan to add the same subscription.
 *
 * The code holds an install-config link rather than the bare URL, so a stock
 * camera app opens it straight in meow (or another Clash client) and the
 * subscription keeps its name.
 */
@Composable
fun SubscriptionQrDialog(profile: ProfileUi, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val code = remember(profile.url, profile.name) {
        QrCodes.encode(InstallConfigLink.of(profile.url, profile.name))
    }
    val image = remember(code) { code?.toBitmap()?.asImageBitmap() }
    val close = @Composable {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = stringResource(R.string.subs_share_qr),
                        // One pixel per module, scaled up: keep the edges hard.
                        filterQuality = FilterQuality.None,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Text(stringResource(R.string.subs_qr_too_long))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.subs_qr_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.meow.mutedText,
                )
            }
        },
        confirmButton = {
            if (code != null) {
                TextButton(onClick = { scope.launch { context.shareQrImage(code) } }) {
                    Text(stringResource(R.string.subs_qr_share_image))
                }
            } else {
                close()
            }
        },
        dismissButton = close.takeIf { code != null },
    )
}

/** Hands [code] to the system share sheet as a PNG. */
private suspend fun Context.shareQrImage(code: BitMatrix) {
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "subscription-qr.png")
            val bitmap = code.toBitmap(scale = maxOf(1, SHARE_SIDE_PX / code.width))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            // Authority declared on the FileProvider in AndroidManifest.xml.
            FileProvider.getUriForFile(this@shareQrImage, "$packageName.files", file)
        }.onFailure { Timber.w(it, "QR share: writing the image failed") }.getOrNull()
    } ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        // The share sheet previews, and passes on the read grant, through
        // ClipData rather than EXTRA_STREAM.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(send, null))
}
