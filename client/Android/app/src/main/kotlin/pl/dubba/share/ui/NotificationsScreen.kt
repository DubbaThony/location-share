package pl.dubba.share.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.dubba.share.R
import pl.dubba.share.notify.AlertClass
import pl.dubba.share.settings.Settings

/**
 * Top-level "Notifications & alerts" screen. Shows one row per [AlertClass]
 * child with a TLDR of its current config; tap to drill into
 * [AlertEditScreen] for that class.
 *
 * Settings are observed so the TLDR updates immediately when the edit screen
 * persists changes - no manual refresh needed.
 */
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onPickClass: (AlertClass) -> Unit,
) {
    val context = LocalContext.current
    val persisted by Settings.observe(context).collectAsState(initial = null)
    val snapshot = persisted ?: return

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HoloButton(onClick = onBack, text = stringResource(R.string.common_back))
            Text(stringResource(R.string.notifications_title), style = MaterialTheme.typography.titleMedium)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Render every alert class in a stable order. Reading `snapshot.alertOf(c)`
        // ensures the TLDR reflects what's actually persisted (the live
        // AlertClass.config field is service-side and not always in sync with
        // the UI's snapshot during the brief observer round-trip).
        for (c in AlertClass.all) {
            val cfg = snapshot.alertOf(c)
            // Mirror the AlertClass.tldr logic against THIS snapshot rather
            // than the service singleton, so the row reflects the most
            // recent persist() during the brief observer round-trip.
            val tldr = if (!cfg.enabled) {
                stringResource(R.string.tldr_off)
            } else {
                stringResource(
                    R.string.tldr_on_template,
                    cfg.vibration.displayName(context).lowercase(),
                    stringResource(if (cfg.notify) R.string.tldr_push else R.string.tldr_silent),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPickClass(c) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(c.displayName(context), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        tldr,
                        style = MaterialTheme.typography.bodySmall,
                        color = HoloColors.OnSurfaceMuted,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = HoloColors.OnSurfaceMuted)
            }
            HorizontalDivider()
        }
    }
}
