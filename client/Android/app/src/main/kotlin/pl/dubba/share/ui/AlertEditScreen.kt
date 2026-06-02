package pl.dubba.share.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.dubba.share.R
import pl.dubba.share.notify.AlertClass
import pl.dubba.share.notify.AlertConfig
import pl.dubba.share.notify.ExtraParam
import pl.dubba.share.notify.VibrationPattern
import pl.dubba.share.settings.Settings

/**
 * Per-[AlertClass] editor. Uniform layout:
 *
 *   - Master "Enabled" switch (gates everything else)
 *   - "Push notification" switch
 *   - Vibration picker
 *   - "Auto-clean when condition resolves" switch
 *   - Class-specific extras (rendered from [AlertClass.extraParams])
 *
 * Auto-save on every change, same pattern as SettingsScreen. No Save button.
 */
@Composable
fun AlertEditScreen(
    alertClass: AlertClass,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val persisted by Settings.observe(context).collectAsState(initial = null)
    val current = persisted ?: return

    val initialCfg = remember { current.alertOf(alertClass) }
    var enabled by remember { mutableStateOf(initialCfg.enabled) }
    var notify by remember { mutableStateOf(initialCfg.notify) }
    var vibration by remember { mutableStateOf(initialCfg.vibration) }
    var autoCancel by remember { mutableStateOf(initialCfg.autoCancelOnResolve) }

    // Extras are typed at the alertClass level - keep them as a Map<String, String>
    // of raw text-field values during editing, parsed/validated on persist.
    val extras = alertClass.extraParams()
    val extraFieldValues = remember {
        mutableStateOf<Map<String, String>>(
            extras.associate { p ->
                p.storageKey to when (p) {
                    is ExtraParam.IntSeconds -> readIntExtra(alertClass, p, current).toString()
                }
            }
        )
    }

    var vibrationPickerOpen by remember { mutableStateOf(false) }

    fun persist() {
        // Build the updated AlertConfig from local UI state and patch the
        // snapshot's `alerts` map.
        val newCfg = AlertConfig(
            enabled = enabled,
            notify = notify,
            vibration = vibration,
            autoCancelOnResolve = autoCancel,
        )
        val newAlerts = current.alerts.toMutableMap()
        newAlerts[alertClass] = newCfg

        // Extras → bake into the snapshot's class-specific fields.
        var netUnstableThreshold = current.netUnstableThresholdSec
        for (p in extras) when (p) {
            is ExtraParam.IntSeconds -> {
                val raw = extraFieldValues.value[p.storageKey] ?: ""
                val parsed = raw.toIntOrNull()?.takeIf { it in p.range } ?: p.defaultValue
                // Only NetUnstable has IntSeconds extras today; route by class.
                if (alertClass is AlertClass.NetUnstable) netUnstableThreshold = parsed
            }
        }

        val updated = current.copy(
            alerts = newAlerts,
            netUnstableThresholdSec = netUnstableThreshold,
        )
        scope.launch { Settings.save(context, updated) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HoloButton(onClick = onBack, text = stringResource(R.string.common_back))
            Text(alertClass.displayName(context), style = MaterialTheme.typography.titleMedium)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Master "Enabled" - when off, the rest of the controls visually
        // remain but firing is suppressed in the service. Keep them visible
        // so the user can prep their preferred config before flipping on.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HoloSwitch(checked = enabled, onCheckedChange = { enabled = it; persist() })
            Column {
                Text(stringResource(R.string.alert_edit_enabled_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.alert_edit_enabled_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HoloSwitch(checked = notify, onCheckedChange = { notify = it; persist() })
            Column {
                Text(stringResource(R.string.alert_edit_push_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.alert_edit_push_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 56.dp)
                .clickable { vibrationPickerOpen = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.alert_edit_vibration_label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                vibration.displayName(context),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HoloSwitch(checked = autoCancel, onCheckedChange = { autoCancel = it; persist() })
            Column {
                Text(stringResource(R.string.alert_edit_auto_cancel_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.alert_edit_auto_cancel_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Class-specific extras
        for (p in extras) when (p) {
            is ExtraParam.IntSeconds -> {
                Text(
                    stringResource(p.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(p.helpTextRes),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = extraFieldValues.value[p.storageKey] ?: "",
                    onValueChange = { newRaw ->
                        extraFieldValues.value = extraFieldValues.value + (p.storageKey to newRaw)
                        persist()
                    },
                    label = { Text(stringResource(R.string.alert_edit_int_seconds_label, p.range.first, p.range.last)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }

    // Vibration picker - same as the standalone VibrationPatternDialog from
    // SettingsScreen but inlined here to keep this screen self-contained.
    if (vibrationPickerOpen) {
        AlertDialog(
            onDismissRequest = { vibrationPickerOpen = false },
            title = { Text(stringResource(R.string.alert_edit_vibration_picker_title)) },
            text = {
                Column {
                    VibrationPattern.values().forEach { pat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vibration = pat
                                    persist()
                                    vibrationPickerOpen = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = pat == vibration,
                                onClick = {
                                    vibration = pat
                                    persist()
                                    vibrationPickerOpen = false
                                },
                            )
                            Text(pat.displayName(context), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vibrationPickerOpen = false }) { Text(stringResource(R.string.common_close)) } },
        )
    }
}

/**
 * Pulls the current value of a class-specific int extra from the snapshot.
 * Right now only [AlertClass.NetUnstable] uses one, but the dispatch is
 * future-proof - when other classes gain extras, add their snapshot field
 * here (or refactor to a typed map).
 */
private fun readIntExtra(
    alertClass: AlertClass,
    p: ExtraParam.IntSeconds,
    snap: Settings.Snapshot,
): Int = when (alertClass) {
    is AlertClass.NetUnstable -> snap.netUnstableThresholdSec
    else -> p.defaultValue
}
