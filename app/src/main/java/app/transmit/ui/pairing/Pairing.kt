package app.transmit.ui.pairing

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhonelinkErase
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.transmit.gm.BridgeState
import app.transmit.gm.GmBridge
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

private enum class PairMode { GOOGLE, QR }

/**
 * Links Transmit to Google Messages, like Messages for Web. The default works on one phone: sign in
 * to Google in the app, then confirm an emoji in Google Messages. A QR code is the fallback when
 * there's a second screen to show it on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(bridge: GmBridge, onDone: () -> Unit, onBack: () -> Unit) {
    val state by bridge.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(PairMode.GOOGLE) }
    var signingIn by rememberSaveable { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var cookies by remember { mutableStateOf<Map<String, String>?>(null) }
    val pairing = state as? BridgeState.Pairing
    LaunchedEffect(state) {
        if (started && (state is BridgeState.Connecting || state is BridgeState.Connected)) onDone()
    }
    DisposableEffect(Unit) {
        onDispose { bridge.cancelPairing() }
    }
    if (signingIn) {
        GoogleSignIn(
            onSignedIn = { c ->
                signingIn = false
                cookies = c
                started = true
                bridge.startGooglePairing(c)
            },
            onBack = { signingIn = false },
        )
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair with Google Messages") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (mode) {
                PairMode.GOOGLE -> GoogleStep(
                    pairing = pairing?.takeIf { it.google },
                    onSignIn = { signingIn = true },
                    onRetry = { cookies?.let { started = true; bridge.startGooglePairing(it) } ?: run { signingIn = true } },
                    onOtherAccount = {
                        bridge.cancelPairing()
                        GoogleSignInCookies.clear()
                        cookies = null
                        signingIn = true
                    },
                    onUseQr = {
                        bridge.cancelPairing()
                        mode = PairMode.QR
                        started = true
                        bridge.startPairing()
                    },
                )
                PairMode.QR -> QrStep(
                    pairing = pairing?.takeIf { !it.google },
                    onRetry = { bridge.startPairing() },
                    onUseGoogle = {
                        bridge.cancelPairing()
                        mode = PairMode.GOOGLE
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GoogleStep(
    pairing: BridgeState.Pairing?,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onOtherAccount: () -> Unit,
    onUseQr: () -> Unit,
) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        pairing == null -> {
            Text(
                "Transmit sends and receives through Google Messages on this phone, the same way Messages for Web does, " +
                    "so RCS chats keep working.",
                style = MaterialTheme.typography.bodyMedium, color = muted,
            )
            Spacer(Modifier.height(24.dp))
            Numbered(
                listOf(
                    "Sign in with the Google account Google Messages uses",
                    "Google Messages asks you to confirm: tap the emoji Transmit shows",
                ),
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) { Text("Sign in with Google") }
        }
        pairing.error != null -> {
            Icon(Icons.Default.CloudOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Text(pairing.error, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOtherAccount, modifier = Modifier.fillMaxWidth()) { Text("Sign in again") }
        }
        pairing.emoji == null -> {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Finding your phone…", style = MaterialTheme.typography.bodyLarge)
        }
        else -> {
            Spacer(Modifier.height(16.dp))
            Text("Open Google Messages and tap", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(pairing.emoji, fontSize = 88.sp, modifier = Modifier.padding(horizontal = 36.dp, vertical = 20.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Google Messages shows a pairing request with a few emoji. Pick this one to link Transmit.",
                style = MaterialTheme.typography.bodyMedium, color = muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = { openGoogleMessages(context) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open Google Messages")
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Waiting for you to confirm…", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
    if (pairing?.emoji == null || pairing.error != null) {
        Spacer(Modifier.height(32.dp))
        TextButton(onClick = onUseQr) { Text("Pair with a QR code instead") }
        Text("Needs a second screen to show the code on", style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

@Composable
private fun QrStep(pairing: BridgeState.Pairing?, onRetry: () -> Unit, onUseGoogle: () -> Unit) {
    val url = pairing?.qrUrl
    val qr = remember(url) { url?.let { qrBitmap(it, 768) } }
    Text(
        "Show this code on another screen if you're using Transmit on the same phone as Google Messages.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    Box(
        Modifier.size(280.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            qr != null -> Image(qr.asImageBitmap(), "Pairing QR code", Modifier.fillMaxSize())
            pairing?.error != null -> Text(pairing.error, color = Color.Black, textAlign = TextAlign.Center)
            else -> CircularProgressIndicator()
        }
    }
    Spacer(Modifier.height(20.dp))
    if (pairing?.error != null) {
        Button(onClick = onRetry) { Text("Try again") }
        Spacer(Modifier.height(20.dp))
    }
    Numbered(
        listOf(
            "Open Google Messages on your phone",
            "Tap your profile picture, then Device pairing",
            "Tap QR code scanner and point it at this code",
        ),
    )
    Spacer(Modifier.height(16.dp))
    if (qr != null) {
        val context = LocalContext.current
        OutlinedButton(onClick = { shareQr(context, qr) }) {
            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share code")
        }
    }
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onUseGoogle) { Text("Sign in with Google instead") }
}

private fun openGoogleMessages(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(GOOGLE_MESSAGES)
    if (intent != null) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private const val GOOGLE_MESSAGES = "com.google.android.apps.messaging"

@Composable
private fun Numbered(steps: List<String>) {
    Column(Modifier.fillMaxWidth().widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(26.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("${i + 1}", style = MaterialTheme.typography.labelLarge) }
                }
                Spacer(Modifier.width(12.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L),
    )
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

private fun shareQr(context: Context, qr: Bitmap) {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "transmit-pairing.png")
    file.outputStream().use { qr.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, "Show the pairing code on another screen"))
}

/** A strip explaining why messages may not be flowing, with the fix when there is one. */
@Composable
fun BridgeBanner(state: BridgeState, onPair: () -> Unit, onUseHere: () -> Unit, modifier: Modifier = Modifier) {
    data class Content(val icon: ImageVector, val title: String, val body: String?, val action: String?, val onAction: (() -> Unit)?)
    val content = when (state) {
        BridgeState.Unpaired -> Content(Icons.Default.Link, "Pair with Google Messages", "Needed to send and receive", "Pair", onPair)
        is BridgeState.LoggedOut -> Content(Icons.Default.PhonelinkErase, "Disconnected from Google Messages", state.reason, "Pair", onPair)
        is BridgeState.Connected -> when {
            state.inactive -> Content(Icons.Default.Devices, "Messages for Web is open elsewhere", "Google Messages talks to one at a time", "Use here", onUseHere)
            !state.phoneResponding -> Content(Icons.Default.CloudOff, "Your phone isn't responding", "Check that it's on and online", null, null)
            state.trouble != null -> Content(Icons.Default.Sync, state.trouble, null, null, null)
            else -> null
        }
        BridgeState.Connecting, is BridgeState.Pairing -> null
    } ?: return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(content.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(content.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                content.body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            if (content.action != null && content.onAction != null) {
                FilledTonalButton(onClick = content.onAction) { Text(content.action) }
            }
        }
    }
}
