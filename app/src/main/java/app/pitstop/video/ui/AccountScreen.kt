package app.pitstop.video.ui

import app.pitstop.video.core.S
import app.pitstop.video.core.I18n
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
import androidx.compose.ui.Alignment
import androidx.compose.material3.Switch
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
        LanguageSection()
        HorizontalDivider()
        SubtitleSettingsSection()
        HorizontalDivider()
        Text(S.accounts, style = MaterialTheme.typography.titleLarge)
        YouTubeSection(graph)
        HorizontalDivider()
        BilibiliSection(graph)
        HorizontalDivider()
        SafetySection(graph)
        DrivingAudioSection()
        HorizontalDivider()
        Text(S.usingInCar, style = MaterialTheme.typography.titleMedium)
        Text(
            if (graph.isAutomotiveOs) S.aaosHelp else S.aaHelp,
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

    Text(S.youtube, style = MaterialTheme.typography.titleMedium)
    Text(
        if (signedIn) S.youtubeSignedIn else S.youtubeSignedOut,
        style = MaterialTheme.typography.bodyMedium,
    )
    if (signedIn) {
        OutlinedButton(onClick = account::signOut) { Text(S.signOutYoutube) }
    } else {
        Button(onClick = {
            scope.launch {
                runCatching { account.beginSignIn() }
                    .onSuccess { pi -> pi?.let { launcher.launch(IntentSenderRequest.Builder(it.intentSender).build()) } }
                    .onFailure { message = I18n.s.googleFailed(it.message) }
            }
        }) { Text(S.signInGoogle) }
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

    Text(S.bilibili, style = MaterialTheme.typography.titleMedium)
    Text(
        if (loggedIn) S.biliSignedIn(name) else S.biliSignedOut,
        style = MaterialTheme.typography.bodyMedium,
    )
    if (loggedIn) {
        OutlinedButton(onClick = { graph.biliCookies.logout() }) { Text(S.signOutBili) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { webLogin.launch(Intent(context, BiliLoginActivity::class.java)) }) { Text(S.signInWeb) }
            OutlinedButton(onClick = { showQr = true }) { Text(S.signInQr) }
        }
    }
    if (showQr) BiliQrDialog(graph.biliApi) { showQr = false }
}

@Composable
private fun BiliQrDialog(api: BiliApi, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var qr by remember { mutableStateOf<BiliApi.QrCode?>(null) }
    var status by remember { mutableStateOf(I18n.s.qrGenerating) }

    LaunchedEffect(Unit) {
        val code = runCatching { api.qrGenerate() }.getOrElse { status = I18n.s.failed(it.message); return@LaunchedEffect }
        qr = code
        status = I18n.s.qrScanHint
        repeat(90) {
            delay(2_000)
            when (runCatching { api.qrPoll(code.key) }.getOrNull()) {
                BiliApi.QrState.DONE -> { onDismiss(); return@LaunchedEffect }
                BiliApi.QrState.SCANNED -> status = I18n.s.qrScanned
                BiliApi.QrState.EXPIRED -> { status = I18n.s.qrExpired; return@LaunchedEffect }
                else -> Unit
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.qrTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                qr?.let { code ->
                    val bmp = remember(code.url) { qrBitmap(code.url) }
                    Image(bmp.asImageBitmap(), S.qrCode, Modifier.size(220.dp))
                }
                Text(status)
            }
        },
        confirmButton = {
            qr?.let { code ->
                TextButton(onClick = {
                    val deepLink = Uri.parse("bilibili://browser?url=" + Uri.encode(code.url))
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, deepLink)) }
                }) { Text(S.openInBili) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.close) } },
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

    Text(S.safetyTitle, style = MaterialTheme.typography.titleMedium)
    Text(
        S.safetyText,
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        S.speedSource(
            when (signal) {
                DrivingMonitor.Signal.CAR -> S.speedCar
                DrivingMonitor.Signal.PHONE_GPS -> S.speedGps
                DrivingMonitor.Signal.NONE -> S.speedNone
            },
            moving,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    if (!hasLocation) {
        Button(onClick = {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }) { Text(S.allowLocation) }
    }

    // Head units / car tablets: this screen itself is in the car, so the lock applies here too.
    var inCar by remember { mutableStateOf(graph.inCarDevice) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(S.inCarTitle, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (graph.isAutomotiveOs) S.inCarAaos else S.inCarText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = inCar,
            enabled = !graph.isAutomotiveOs,
            onCheckedChange = { on ->
                graph.inCarDevice = on
                inCar = graph.inCarDevice
                if (on) graph.driving.startPhoneGps("device") else graph.driving.stopPhoneGps("device")
            },
        )
    }
}
