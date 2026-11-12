package app.pitstop.video.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pitstop.video.AppGraph
import app.pitstop.video.bilibili.BiliApi
import app.pitstop.video.player.DrivingMonitor
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AccountScreen(graph: AppGraph) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        YouTubeSection(graph)
        HorizontalDivider()
        BilibiliSection(graph)
        HorizontalDivider()
        SafetySection(graph)
        HorizontalDivider()
        Text("Using it in the car", style = MaterialTheme.typography.titleMedium)
        Text(
            "1. Android Auto settings → tap Version 10× → ⋮ → Developer settings → enable “Unknown sources”.\n" +
                "2. Android Auto settings → Customize launcher → make sure Pitstop Video is shown.\n" +
                "3. Connect to the car and open Pitstop Video from the Android Auto launcher.\n" +
                "Video only plays while the car is parked.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun YouTubeSection(graph: AppGraph) {
    val account = graph.youTubeAccount
    val signedIn by account.signedIn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        runCatching { account.completeSignIn(res.data) }.onFailure { message = it.message }
    }

    Text("YouTube", style = MaterialTheme.typography.titleMedium)
    Text(
        if (signedIn) "Signed in – Subscriptions and Liked videos are available."
        else "Works without signing in (Popular + search). Sign in to add your Subscriptions and Liked videos.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (signedIn) {
        OutlinedButton(onClick = account::signOut) { Text("Sign out of YouTube") }
    } else {
        Button(onClick = {
            scope.launch {
                runCatching { account.beginSignIn() }
                    .onSuccess { pi -> pi?.let { launcher.launch(IntentSenderRequest.Builder(it.intentSender).build()) } }
                    .onFailure { message = "Google sign-in failed: ${it.message}. See README → YouTube sign-in setup." }
            }
        }) { Text("Sign in with Google") }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun BilibiliSection(graph: AppGraph) {
    val context = LocalContext.current
    val loggedIn by graph.biliCookies.loggedIn.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    val webLogin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    LaunchedEffect(loggedIn) {
        name = if (loggedIn) runCatching { graph.biliApi.profile().name }.getOrNull() else null
    }

    Text("Bilibili", style = MaterialTheme.typography.titleMedium)
    Text(
        if (loggedIn) "Signed in${name?.let { " as $it" } ?: ""} – Following feed and higher quality (1080p) are available."
        else "Works without signing in (up to 480p/720p). Sign in for 1080p and your Following feed.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (loggedIn) {
        OutlinedButton(onClick = { graph.biliCookies.logout() }) { Text("Sign out of Bilibili") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { webLogin.launch(Intent(context, BiliLoginActivity::class.java)) }) { Text("Sign in (web)") }
            OutlinedButton(onClick = { showQr = true }) { Text("Sign in (QR code)") }
        }
    }
    if (showQr) BiliQrDialog(graph.biliApi) { showQr = false }
}

@Composable
private fun BiliQrDialog(api: BiliApi, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var qr by remember { mutableStateOf<BiliApi.QrCode?>(null) }
    var status by remember { mutableStateOf("Generating QR code…") }

    LaunchedEffect(Unit) {
        val code = runCatching { api.qrGenerate() }.getOrElse { status = "Failed: ${it.message}"; return@LaunchedEffect }
        qr = code
        status = "Scan with the Bilibili app on another device, or tap “Open in Bilibili app”."
        repeat(90) {
            delay(2_000)
            when (runCatching { api.qrPoll(code.key) }.getOrNull()) {
                BiliApi.QrState.DONE -> { onDismiss(); return@LaunchedEffect }
                BiliApi.QrState.SCANNED -> status = "Scanned – confirm the login in the Bilibili app."
                BiliApi.QrState.EXPIRED -> { status = "QR code expired. Close and try again."; return@LaunchedEffect }
                else -> Unit
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bilibili QR login") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                qr?.let { code ->
                    val bmp = remember(code.url) { qrBitmap(code.url) }
                    Image(bmp.asImageBitmap(), "QR code", Modifier.size(220.dp))
                }
                Text(status)
            }
        },
        confirmButton = {
            qr?.let { code ->
                TextButton(onClick = {
                    val deepLink = Uri.parse("bilibili://browser?url=" + Uri.encode(code.url))
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, deepLink)) }
                }) { Text("Open in Bilibili app") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
private fun SafetySection(graph: AppGraph) {
    val signal by graph.driving.signal.collectAsStateWithLifecycle()
    val moving by graph.driving.moving.collectAsStateWithLifecycle()
    var hasLocation by remember { mutableStateOf(graph.driving.hasLocationPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = graph.driving.hasLocationPermission()
    }

    Text("Parked-only safety lock", style = MaterialTheme.typography.titleMedium)
    Text(
        "Video on the car screen pauses and hides whenever the car moves. Speed comes from the car " +
            "when Android Auto provides it, otherwise from the phone's GPS.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "Speed source: " + when (signal) {
            DrivingMonitor.Signal.CAR -> "car"
            DrivingMonitor.Signal.PHONE_GPS -> "phone GPS"
            DrivingMonitor.Signal.NONE -> "none yet"
        } + if (moving) " · moving" else " · stopped",
        style = MaterialTheme.typography.bodySmall,
    )
    if (!hasLocation) {
        Button(onClick = {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }) { Text("Allow location (GPS speed fallback)") }
    }
}
