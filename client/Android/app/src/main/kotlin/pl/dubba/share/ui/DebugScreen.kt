package pl.dubba.share.ui

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import pl.dubba.share.gps.GpsManager
import pl.dubba.share.log.DebugLog
import pl.dubba.share.net.ConnectionState
import pl.dubba.share.settings.Settings

/**
 * Debug-mode screen - protocol log + manual subsystem toggles. Reached from
 * Settings → "Debug view." Same direct-control surface we used before the
 * skeuomorphic main screen existed; kept for troubleshooting and as a place
 * to read the log without scrolling through the dial UI.
 */
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val logs by ConnectionState.logs.collectAsState()
    val logsPaused by ConnectionState.logsPaused.collectAsState()
    val connectionActive by ConnectionState.connectionActive.collectAsState()
    val settings by Settings.observe(context).collectAsState(initial = Settings.Snapshot.Default)

    val gpsRunning by GpsManager.running.collectAsState()
    val gpsFix by GpsManager.fix.collectAsState()
    val satStatus by GpsManager.satStatus.collectAsState()

    var pendingConnectStart by remember { mutableStateOf(false) }
    var pendingGpsStart by remember { mutableStateOf(false) }

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
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        when {
            fineGranted -> startGpsViaService(context)
            coarseGranted -> ConnectionState.appendLog(
                "✗ approximate location only - share requires PRECISE.",
            )
            else -> ConnectionState.appendLog("✗ location permission denied")
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HoloButton(onClick = onBack, text = "← Back")
            Text(
                "share - protocol debug",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = "target: ${settings.host}:${settings.port}  •  ping ${settings.pingIntervalMs / 1000}s" +
                if (settings.keepAwake) "  •  awake" else "",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            text = "GPS: " + when {
                !gpsRunning -> "off"
                gpsFix == null -> "scanning..."
                else -> {
                    val f = gpsFix!!
                    "${"%.6f".format(f.lat)}, ${"%.6f".format(f.lon)}  ±${f.accuracyMeters.toInt()}m"
                }
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = "GNSS: " + when {
                !gpsRunning -> "-"
                satStatus == null -> "-"
                else -> {
                    val s = satStatus!!
                    "${s.usedInFix}/${s.total} sats in fix  •  top SNR ${s.topSnrDbHz.toInt()} dBHz"
                }
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
            fontFamily = FontFamily.Monospace,
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HoloButton(
                onClick = {
                    if (connectionActive) {
                        stopConnect(context)
                    } else {
                        ConnectionState.clearLogs()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingConnectStart = true
                            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            scope.launch { startConnect(context) }
                        }
                    }
                },
                text = if (connectionActive) "Disconnect" else "Connect",
            )
            HoloButton(
                onClick = {
                    if (gpsRunning) {
                        stopGpsViaService(context)
                    } else if (GpsManager.hasFineLocationPermission(context)) {
                        startGpsViaService(context)
                    } else {
                        pendingGpsStart = true
                        locationPermLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
                text = if (gpsRunning) "Stop GPS" else "Start GPS",
            )
            HoloButton(
                onClick = { ConnectionState.clearLogs() },
                text = "Clear",
            )
            HoloButton(
                onClick = {
                    // Hand the rolled-up log file to the system share sheet.
                    // User picks Gmail / Drive / Save-to-Files / Signal / etc.
                    DebugLog.makeShareIntent(context)?.let { intent ->
                        context.startActivity(Intent.createChooser(intent, "Export debug log"))
                    } ?: ConnectionState.appendLog("⚠ export failed - no log file on disk yet")
                },
                text = "Export",
            )
        }

        // Pause switch. Tap before alt-tabbing to a parking spot to lock in
        // the buffer you care about - new lines won't push the relevant ones
        // out of the rotation. Bookended "paused" / "resumed" entries get
        // logged automatically.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HoloSwitch(
                checked = !logsPaused,   // visually: switch "on" = logging on
                onCheckedChange = { ConnectionState.setLogsPaused(!it) },
            )
            Text(
                if (logsPaused) "Logger PAUSED - tap to resume"
                else "Logger active - tap to pause",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
        ) {
            items(logs) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}
