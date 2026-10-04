package io.github.madeye.meow.ui.screens.subscribe

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.madeye.meow.R
import io.github.madeye.meow.ui.qr.QrAnalyzer
import io.github.madeye.meow.ui.qr.decodeQrImage
import io.github.madeye.meow.ui.theme.MeowTheme
import io.github.madeye.meow.ui.theme.meow
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Scans a subscription QR code: through the camera, or from a picked image —
 * a code that arrived as a screenshot, or a phone with no usable camera.
 *
 * An Activity of its own so the camera lives exactly as long as the screen.
 * It finishes with the first code [InstallConfigLink.parseScanned] accepts;
 * anything else (a Wi-Fi code, a proxy share link) is called out and the
 * scan goes on. Nothing is added here: the caller still asks first.
 */
class SubscriptionScanActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light bar icons over the camera, whatever the app theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            MeowTheme {
                ScanScreen(
                    onBack = ::finish,
                    onScanned = { text ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
                        finish()
                    },
                )
            }
        }
    }

    /** The scanned text, or null when the user backed out. */
    class Contract : ActivityResultContract<Unit, String?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(context, SubscriptionScanActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            intent?.takeIf { resultCode == RESULT_OK }?.getStringExtra(EXTRA_TEXT)
    }

    private companion object {
        const val EXTRA_TEXT = "text"
    }
}

/**
 * 720p resolves a URL-sized code from a comfortable distance and keeps each
 * frame's decode short. 16:9, or CameraX's default 4:3 takes the next 4:3
 * size up, which can be far larger (1856×1392 on the emulator).
 */
private val ANALYSIS_RESOLUTION = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(
            Size(1280, 720),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
        ),
    )
    .build()

/** Behind text drawn over the camera image, which can be any colour. */
private val SCRIM = Color.Black.copy(alpha = 0.6f)

/** How long a "that is not a subscription" note outlives the code that caused it. */
private const val NOTE_MILLIS = 3_000L

@Composable
private fun ScanScreen(onBack: () -> Unit, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    // Saved, so a rotation while the permission dialog is up asks only once.
    var asked by rememberSaveable { mutableStateOf(false) }
    var cameraUnavailable by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    var note by remember { mutableStateOf<Int?>(null) }
    // Bumped on every rejected code, so a code that stays in view keeps its
    // note up and the note clears a moment after it leaves.
    var noteSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(noteSeq) {
        if (noteSeq == 0) return@LaunchedEffect
        delay(NOTE_MILLIS)
        note = null
    }
    fun showNote(@StringRes message: Int) {
        note = message
        noteSeq++
    }

    val scanned by rememberUpdatedState(onScanned)
    val onDecoded: (String) -> Unit = remember {
        { text ->
            // Frames already in flight still arrive after the first hit.
            if (!done) {
                when (val link = InstallConfigLink.parseScanned(text)) {
                    is InstallConfigLink.Valid -> {
                        done = true
                        scanned(text)
                    }
                    is InstallConfigLink.Invalid -> showNote(link.reason.scanMessageRes())
                }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (hasCamera && !granted && !asked) {
            asked = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }
    // Picks up a permission granted in system settings.
    LifecycleResumeEffect(context) {
        granted = context.hasCameraPermission()
        onPauseOrDispose { }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = context.decodeQrImage(uri)
            if (text == null) showNote(R.string.subs_scan_no_code) else onDecoded(text)
        }
    }

    val cameraLive = hasCamera && granted && !cameraUnavailable
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            cameraLive -> {
                CameraScanner(
                    onDecoded = onDecoded,
                    onUnavailable = { cameraUnavailable = true },
                    modifier = Modifier.fillMaxSize(),
                )
                // Decoding reads the whole frame; this only shows where to aim.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(240.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
                )
            }
            !hasCamera || cameraUnavailable -> Notice(R.string.subs_scan_no_camera)
            // Still waiting on the first permission answer: show nothing yet.
            !asked -> Unit
            else -> Notice(R.string.subs_scan_camera_denied) {
                TextButton(
                    onClick = {
                        val canAsk = activity != null &&
                            ActivityCompat.shouldShowRequestPermissionRationale(
                                activity,
                                Manifest.permission.CAMERA,
                            )
                        if (canAsk) {
                            permission.launch(Manifest.permission.CAMERA)
                        } else {
                            // "Don't ask again": only settings can grant it now.
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        }
                    },
                ) { Text(stringResource(R.string.subs_scan_allow_camera)) }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // White on whatever the camera sees: darken behind the bar.
                .background(Brush.verticalGradient(listOf(SCRIM, Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = Color.White,
                )
            }
            Text(
                text = stringResource(R.string.subs_scan_qr),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val message = note ?: R.string.subs_scan_hint.takeIf { cameraLive }
            if (message != null) {
                Text(
                    text = stringResource(message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (note != null) MaterialTheme.meow.warning else Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(SCRIM, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
            }
            FilledTonalButton(
                onClick = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.subs_scan_from_image))
            }
        }
    }
}

/**
 * The back camera's preview, with every frame handed to a [QrAnalyzer].
 * Bound to the screen's lifecycle, so the camera stops while it is paused —
 * including while the image picker is open over it.
 */
@Composable
private fun CameraScanner(
    onDecoded: (String) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val decoded by rememberUpdatedState(onDecoded)
    val unavailable by rememberUpdatedState(onUnavailable)
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }

    LaunchedEffect(context, lifecycleOwner) {
        val provider = try {
            ProcessCameraProvider.awaitInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "QR scan: camera init failed")
            unavailable()
            return@LaunchedEffect
        }
        // Some tablets and Chromebooks only have a front camera.
        val selector = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
            .firstOrNull { runCatching { provider.hasCamera(it) }.getOrDefault(false) }
        if (selector == null) {
            unavailable()
            return@LaunchedEffect
        }

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { surfaceRequest = it }
        val main = ContextCompat.getMainExecutor(context)
        val executor = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ANALYSIS_RESOLUTION)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor, QrAnalyzer { text -> main.execute { decoded(text) } })
        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } catch (e: RuntimeException) {
            // The camera refused the use cases. bindToLifecycle does not suspend,
            // so this cannot swallow a cancellation.
            Timber.w(e, "QR scan: binding the camera failed")
            executor.shutdown()
            unavailable()
            return@LaunchedEffect
        }
        try {
            awaitCancellation()
        } finally {
            provider.unbind(preview, analysis)
            executor.shutdown()
        }
    }

    surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = modifier) }
}

@Composable
private fun Notice(@StringRes message: Int, action: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        action()
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** Why a scanned code was turned away, as the scanner says it. */
@StringRes
private fun InstallConfigLink.Reason.scanMessageRes(): Int = when (this) {
    // A Wi-Fi code, a proxy share link, a web page: none is a link at all here.
    InstallConfigLink.Reason.UNSUPPORTED_LINK -> R.string.subs_scan_not_subscription
    else -> messageRes()
}
