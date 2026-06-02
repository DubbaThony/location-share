package pl.dubba.share.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.widget.Toast
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import pl.dubba.share.R
import pl.dubba.share.net.UiErrorAction
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import pl.dubba.share.Config
import pl.dubba.share.gps.GpsManager
import pl.dubba.share.net.ConnectionService
import pl.dubba.share.net.ConnectionState
import pl.dubba.share.settings.Settings

private enum class DialKind { ShareTime, PollingRate }

@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val connectionActive by ConnectionState.connectionActive.collectAsState()
    val pongReceived by ConnectionState.pongReceived.collectAsState()
    val accessKey by ConnectionState.accessKey.collectAsState()
    val innerKey  by ConnectionState.innerKey.collectAsState()
    val lastError by ConnectionState.lastError.collectAsState()
    val shareEndMs by ConnectionState.shareEndTimeMs.collectAsState()
    val subCount by ConnectionState.subCount.collectAsState()
    val gpsRunning by GpsManager.running.collectAsState()
    val gpsFix by GpsManager.fix.collectAsState()
    val settings by Settings.observe(context).collectAsState(initial = Settings.Snapshot.Default)

    // Tick once per second while a share timer is running so the displayed
    // "time left" updates without needing a new emission on shareEndMs.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(shareEndMs) {
        val end = shareEndMs ?: return@LaunchedEffect
        while (System.currentTimeMillis() < end) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
        nowMs = System.currentTimeMillis()
    }
    val timeLeftText = shareEndMs?.let {
        val rem = (it - nowMs).coerceAtLeast(0L)
        val sec = rem / 1000L
        "%d:%02d".format(sec / 60L, sec % 60L)
    }

    var pendingConnectStart by remember { mutableStateOf(false) }
    var pendingGpsStart by remember { mutableStateOf(false) }
    // Opens when the user taps the Server toggle ON while the URL prefix is
    // http:// and Advanced → "Allow HTTP servers" is off. The dialog
    // explains the trade-off and lets the user either grant the allow-HTTP
    // setting (which both persists and proceeds with this connect) or
    // cancel. Toggle visual snaps back automatically - connectionActive
    // hasn't moved, so there's no bounce-state to manage.
    var httpDialogOpen by remember { mutableStateOf(false) }
    // Forces the GPS toggle visual ON for a brief moment when start fails because
    // system location is off. `checked` OR's this with [gpsRunning] so the toggle
    // springs up under the finger, then snaps back when this flips false a
    // moment later - the "bounce" feedback we owe the user.
    var gpsBounce by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf<DialKind?>(null) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        if (pendingConnectStart) {
            pendingConnectStart = false
            scope.launch { startConnect(context) }
        }
    }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (!pendingGpsStart) return@rememberLauncherForActivityResult
        pendingGpsStart = false
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        when {
            fine -> tryStartGps(context, onSystemLocationOff = {
                gpsBounce = true
                Toast.makeText(
                    context,
                    "Location is disabled in system settings",
                    Toast.LENGTH_SHORT,
                ).show()
                scope.launch {
                    delay(450)
                    gpsBounce = false
                }
            })
            coarse -> ConnectionState.appendLog("✗ approximate location only - share requires PRECISE")
            else -> ConnectionState.appendLog("✗ location permission denied")
        }
    }

    val shareValues = Config.SHARE_TIME_DETENTS_MIN
    val pollingValues = Config.POLLING_INTERVAL_DETENTS_MS
    val shareLabels = remember { shareValues.map { formatShareTime(it) } }
    val pollingLabels = remember { pollingValues.map { formatPollingRate(it) } }

    val shareIndex = shareValues.indexOfFirst { it == settings.shareTimeMin }.let { if (it >= 0) it else 1 }
    val pollingIndex = pollingValues.indexOfFirst { it == settings.pollingIntervalMs }.let { if (it >= 0) it else 5 }

    // LED states reflect what each subsystem is actually doing, not just the
    // user's toggle position.
    val gpsLed = when {
        !gpsRunning -> LedState.Off
        gpsFix == null -> LedState.Pending
        else -> LedState.Ok
    }
    val connectionLed = when {
        !connectionActive -> LedState.Off
        !pongReceived -> LedState.Pending
        else -> LedState.Ok
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- Top row: GPS toggle | Server toggle ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            PanelToggle(
                checked = gpsRunning || gpsBounce,
                ledState = gpsLed,
                onCheckedChange = { on ->
                    if (on) {
                        if (GpsManager.hasFineLocationPermission(context)) {
                            tryStartGps(context, onSystemLocationOff = {
                                gpsBounce = true
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_location_disabled),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                scope.launch {
                                    delay(450)
                                    gpsBounce = false
                                }
                            })
                        } else {
                            pendingGpsStart = true
                            locationPermLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }
                    } else {
                        stopGpsViaService(context)
                    }
                },
                label = stringResource(R.string.main_toggle_gps),
            )
            PanelToggle(
                checked = connectionActive,
                ledState = connectionLed,
                onCheckedChange = { on ->
                    if (on) {
                        // Pre-flight check: refuse http:// URL prefixes unless
                        // the user explicitly enabled "Allow HTTP servers" in
                        // Advanced. Identity verification over HTTP is forgeable
                        // by anyone on the network, so we want a deliberate
                        // tap rather than a silent gotcha.
                        val isHttp = isHttpScheme(settings.urlPrefix)
                        ConnectionState.appendLog(
                            "Server toggle ON - urlPrefix='${settings.urlPrefix}' " +
                                "isHttp=$isHttp allowHttpServer=${settings.allowHttpServer}",
                        )
                        if (isHttp && !settings.allowHttpServer) {
                            httpDialogOpen = true
                        } else if (needsNotifPermission(context)) {
                            pendingConnectStart = true
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            scope.launch { startConnect(context) }
                        }
                    } else {
                        stopConnect(context)
                    }
                },
                label = stringResource(R.string.main_toggle_server),
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- Dials stack - claims all leftover vertical space; each dial gets half ---
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            HandwheelDial(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                detentIndex = shareIndex,
                detentCount = shareValues.size,
                onDetentChange = { idx ->
                    scope.launch { Settings.saveShareTime(context, shareValues[idx]) }
                },
                onDoubleTap = { pickerOpen = DialKind.ShareTime },
                label = stringResource(R.string.main_dial_label_share_time),
                valueLabel = { idx -> shareLabels[idx] },
                valueColor = { idx ->
                    if (shareValues[idx] >= Config.SHARE_TIME_INFINITE_SENTINEL) HoloColors.Red
                    else HoloColors.OnSurface
                },
            )
            Spacer(Modifier.height(8.dp))
            HandwheelDial(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                detentIndex = pollingIndex,
                detentCount = pollingValues.size,
                onDetentChange = { idx ->
                    scope.launch { Settings.savePollingInterval(context, pollingValues[idx]) }
                },
                onDoubleTap = { pickerOpen = DialKind.PollingRate },
                label = stringResource(R.string.main_dial_label_polling_rate),
                valueLabel = { idx -> pollingLabels[idx] },
            )
        }

        Spacer(Modifier.height(16.dp))

        // --- Status: current link + consumer count ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.main_label_current_link),
                fontSize = 13.sp,
                color = HoloColors.OnSurfaceMuted,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.width(8.dp))
            val shareUrl = accessKey?.let { ak ->
                innerKey?.let { ek ->
                    buildShareUrl(settings.urlPrefix, ak, ek, settings.viewerDebug)
                }
            }
            val unavailable = stringResource(R.string.main_value_link_unavailable)
            Text(
                text = shareUrl ?: unavailable,
                fontSize = 12.sp,
                color = if (shareUrl != null) HoloColors.Blue else HoloColors.OnSurfaceMuted,
                fontFamily = FontFamily.Monospace,
                // Hex inner key inflated the URL - without these the long URL
                // wrapped to a second line, taking space from the dial row and
                // making the encoders visibly resize on connect. Single-line
                // with ellipsis keeps the status block a fixed height; the
                // whole URL still goes to the clipboard on tap.
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                softWrap = false,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (shareUrl != null) Modifier.clickable {
                            clipboard.setText(AnnotatedString(shareUrl))
                        } else Modifier,
                    ),
            )
            ClipboardGlyph(
                enabled = shareUrl != null,
                onClick = { shareUrl?.let { clipboard.setText(AnnotatedString(it)) } },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(
                stringResource(R.string.main_label_stream_consumers),
                fontSize = 13.sp,
                color = HoloColors.OnSurfaceMuted,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatSubCount(subCount),
                fontSize = 13.sp,
                color = if (subCount == null) HoloColors.OnSurfaceMuted else HoloColors.OnSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(
                stringResource(R.string.main_label_time_left),
                fontSize = 13.sp,
                color = HoloColors.OnSurfaceMuted,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = timeLeftText ?: "-",
                fontSize = 13.sp,
                color = if (timeLeftText != null) HoloColors.OnSurface else HoloColors.OnSurfaceMuted,
                fontFamily = FontFamily.Monospace,
            )
        }

        // --- Bottom: gear in the corner ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            GearButton(onClick = onOpenSettings)
        }
    }

    // --- Last-error dialog (handshake failure, port unreachable, etc.) ---
    // Shown over the main screen so the user can't miss it; cleared on dismiss
    // or on the next successful connection attempt. If the error carries
    // any [UiErrorAction]s, they render as additional buttons (typically a
    // "download APK from the configured server" jump-out for proto-version
    // mismatches). The default OK still dismisses; actions are
    // additive and don't auto-dismiss so the user can decide which to use.
    lastError?.let { err ->
        AlertDialog(
            onDismissRequest = { ConnectionState.setLastError(null) },
            title = { Text(err.title) },
            text = err.detail?.let { d -> { Text(d) } },
            confirmButton = {
                Column {
                    err.actions.forEach { action ->
                        when (action) {
                            is UiErrorAction.OpenUrl -> TextButton(onClick = {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, action.url.toUri())
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                                ConnectionState.setLastError(null)
                            }) { Text(action.label) }
                        }
                    }
                    TextButton(onClick = { ConnectionState.setLastError(null) }) {
                        Text("OK")
                    }
                }
            },
        )
    }

    // --- HTTP-server confirmation dialog ---
    // Raised by the Server toggle's pre-flight check. "Use HTTP anyway"
    // persists allowHttpServer=true and continues the connect (going
    // through the notif-perm flow if needed). Cancel does nothing - the
    // toggle visual is driven by connectionActive, which never moved.
    if (httpDialogOpen) {
        AlertDialog(
            onDismissRequest = { httpDialogOpen = false },
            title = { Text("HTTP server URL") },
            text = {
                Text(
                    "Your Viewer URL prefix is http://, not https://. The /identity " +
                        "endpoint we use to anchor server-identity verification is " +
                        "forgeable by anyone on the same network - verification becomes " +
                        "cosmetic.\n\n" +
                        "To proceed anyway, we'll flip Settings → Advanced → " +
                        "\"Allow HTTP server URLs\" on for you, so this dialog doesn't " +
                        "pop up every time. Untick it later if you want it back.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    httpDialogOpen = false
                    scope.launch {
                        Settings.save(
                            context,
                            Settings.snapshot(context).copy(allowHttpServer = true),
                        )
                        if (needsNotifPermission(context)) {
                            pendingConnectStart = true
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            startConnect(context)
                        }
                    }
                }) { Text("Use HTTP anyway") }
            },
            dismissButton = {
                TextButton(onClick = { httpDialogOpen = false }) { Text("Cancel") }
            },
        )
    }

    // --- Value picker dialogs (opened on dial double-tap) ---
    pickerOpen?.let { kind ->
        val (title, labels, currentIdx, onPick) = when (kind) {
            DialKind.ShareTime -> Quad(
                stringResource(R.string.main_picker_share_time), shareLabels, shareIndex,
                { idx: Int -> scope.launch { Settings.saveShareTime(context, shareValues[idx]) } },
            )
            DialKind.PollingRate -> Quad(
                stringResource(R.string.main_picker_polling_rate), pollingLabels, pollingIndex,
                { idx: Int -> scope.launch { Settings.savePollingInterval(context, pollingValues[idx]) } },
            )
        }
        DetentPickerDialog(
            title = title,
            labels = labels,
            selectedIndex = currentIdx,
            onSelect = { idx ->
                onPick(idx)
                pickerOpen = null
            },
            onDismiss = { pickerOpen = null },
        )
    }
}

/** Helper to carry 4 values out of the when-expression. Avoids destructuring lambdas. */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun DetentPickerDialog(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                labels.forEachIndexed { idx, lbl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(idx) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = idx == selectedIndex,
                            onClick = { onSelect(idx) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(lbl, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun ClipboardGlyph(enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) HoloColors.Blue else HoloColors.OnSurfaceMuted
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .width(32.dp)
            .height(32.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text("⎘", color = color, fontSize = 22.sp)
    }
}

private fun formatShareTime(min: Int): String = when {
    min >= Config.SHARE_TIME_INFINITE_SENTINEL -> "∞"
    min < 60 -> "$min min"
    min == 60 -> "1 h"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60}h ${min % 60}m"
}

private fun formatPollingRate(ms: Long): String = when {
    ms == 1000L -> "1/s"
    ms > 1000L -> "1/${ms / 1000}s"
    else -> "${1000L / ms}/s"
}

/**
 * Renders the server-reported subscriber count for the UI. `null` = no value
 * known yet (not connected, query in flight, or just connected and waiting for
 * the first pong cycle). `-1L` is the server's error sentinel (uint64::MAX on
 * the wire) and surfaces as a "?" so the user knows the value isn't trustable.
 */
private fun formatSubCount(count: Long?): String = when {
    count == null -> "-"
    count == -1L -> "?"
    else -> count.toString()
}

/**
 * Composes the shareable viewer URL: `<prefix>?id=<accessKey>[&debug=1]#<encKeyHex>`.
 * Access key in the query so swapping URLs triggers a real reload (a
 * fragment-only change doesn't); encryption key (hex-encoded 32 random bytes,
 * 64 chars) stays in the fragment so it never reaches the server. The
 * optional `debug=1` flag toggles the viewer's on-screen state overlay.
 */
private fun buildShareUrl(prefix: String, accessKey: String, encKeyHex: String, viewerDebug: Boolean): String {
    val sep = if (prefix.contains('?')) "&" else "?"
    val maybeDebug = if (viewerDebug) "&debug=1" else ""
    return "$prefix${sep}id=$accessKey$maybeDebug#$encKeyHex"
}

private fun needsNotifPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
}

/**
 * True if [urlPrefix] specifies the unencrypted `http://` scheme. Used by
 * the Server toggle pre-flight to decide whether to surface the
 * "identity verification is cosmetic over HTTP" dialog. Tolerant on
 * whitespace and casing; matches whatever the user could plausibly type.
 */
internal fun isHttpScheme(urlPrefix: String): Boolean =
    urlPrefix.trim().lowercase().startsWith("http://")

internal suspend fun startConnect(context: Context) {
    val s = Settings.snapshot(context)
    val intent = Intent(context, ConnectionService::class.java).apply {
        action = ConnectionService.ACTION_START
        putExtra(ConnectionService.EXTRA_HOST, s.host)
        putExtra(ConnectionService.EXTRA_PORT, s.port)
        putExtra(ConnectionService.EXTRA_KEEP_AWAKE, s.keepAwake)
        putExtra(ConnectionService.EXTRA_PING_INTERVAL_MS, s.pingIntervalMs)
    }
    ContextCompat.startForegroundService(context, intent)
}

internal fun stopConnect(context: Context) {
    val intent = Intent(context, ConnectionService::class.java).apply {
        action = ConnectionService.ACTION_STOP
    }
    context.startService(intent)
}

internal fun startGpsViaService(context: Context) {
    val intent = Intent(context, ConnectionService::class.java).apply {
        action = ConnectionService.ACTION_GPS_START
    }
    ContextCompat.startForegroundService(context, intent)
}

internal fun stopGpsViaService(context: Context) {
    val intent = Intent(context, ConnectionService::class.java).apply {
        action = ConnectionService.ACTION_GPS_STOP
    }
    context.startService(intent)
}

/**
 * Checks system-wide location toggle BEFORE asking the service to start GPS.
 * If off, runs [onSystemLocationOff] (UI bounce + toast) and skips the intent
 * so [GpsManager] never flips to a fake-true state. If on, hands off to
 * [startGpsViaService] - GpsManager still re-checks there as defence-in-depth
 * but the common case stays smooth.
 */
internal fun tryStartGps(
    context: Context,
    onSystemLocationOff: () -> Unit,
) {
    if (!isSystemLocationEnabled(context)) {
        onSystemLocationOff()
        return
    }
    startGpsViaService(context)
}

// minSdk = 29, so isLocationEnabled (API 28+) is safe unconditional. If the
// floor ever drops below 28, fall back to lm.isProviderEnabled(GPS_PROVIDER).
private fun isSystemLocationEnabled(context: Context): Boolean {
    val lm = context.getSystemService(LocationManager::class.java) ?: return false
    return lm.isLocationEnabled
}
