package pl.dubba.share.ui

import android.widget.Toast
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.dubba.share.R
import pl.dubba.share.net.identity.IdentityFetcher
import pl.dubba.share.net.identity.IdentityRefreshNotificationMode
import pl.dubba.share.net.identity.IdentityRefreshNotifier
import pl.dubba.share.net.identity.IdentityStore
import pl.dubba.share.settings.Settings

/**
 * Settings sub-screen for the server-identity / MITM-mitigation layer.
 * Three persisted toggles + the manual refresh / clear actions.
 *
 * The manual Refresh button always toasts its result regardless of the
 * notification-mode setting - the mode controls background-driven
 * (service-initiated) firings only; if the user explicitly tapped a
 * button, suppressing feedback would be worse than ignoring their pref.
 */
@Composable
fun IdentityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val persisted by Settings.observe(context).collectAsState(initial = null)
    val current = persisted ?: return

    // Local UI state mirrors the persisted Snapshot. Each control writes
    // back through persist() on change - auto-save, no Save button.
    var verify by remember { mutableStateOf(current.verifyServerIdentity) }
    var autoRefresh by remember { mutableStateOf(current.autoRefreshServerIdentity) }
    var refreshMode by remember { mutableStateOf(current.identityRefreshNotificationMode) }
    var refreshModePickerOpen by remember { mutableStateOf(false) }
    var clearConfirmOpen by remember { mutableStateOf(false) }
    var refreshInProgress by remember { mutableStateOf(false) }

    // Live-observe the stored identity for the currently-configured host so
    // the displayed fingerprint updates immediately after a successful
    // Refresh or Clear.
    val storedPubkey by IdentityStore.observe(context, current.urlPrefix).collectAsState(initial = null)

    fun persist() {
        scope.launch {
            Settings.save(
                context,
                current.copy(
                    verifyServerIdentity = verify,
                    autoRefreshServerIdentity = autoRefresh,
                    identityRefreshNotificationMode = refreshMode,
                ),
            )
        }
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
            Text(stringResource(R.string.identity_title), style = MaterialTheme.typography.titleMedium)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // --- Verify toggle ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HoloSwitch(checked = verify, onCheckedChange = { verify = it; persist() })
            Column {
                Text(stringResource(R.string.identity_verify_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.identity_verify_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // --- Auto-refresh toggle ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HoloSwitch(checked = autoRefresh, onCheckedChange = { autoRefresh = it; persist() })
            Column {
                Text(stringResource(R.string.identity_auto_refresh_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.identity_auto_refresh_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // --- Notification mode (picker row) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clickable { refreshModePickerOpen = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.identity_refresh_mode_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.identity_refresh_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                refreshMode.displayName(context),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        // --- Current stored identity display ---
        // The identity is bound to the Viewer URL prefix (HTTPS trust
        // anchor), so that's what we surface here - not the UDP host. The
        // two are usually the same authority anyway, but when they differ
        // the URL is the truthful label.
        Text(stringResource(R.string.identity_server_url_section), style = MaterialTheme.typography.titleSmall)
        Text(
            current.urlPrefix.ifBlank { stringResource(R.string.identity_server_url_unset) },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            stringResource(R.string.identity_stored_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        val pubkeyText = storedPubkey?.let { "0x" + pl.dubba.share.util.Hex.encode(it, uppercase = true) }
            ?: stringResource(R.string.identity_stored_none)
        Text(
            pubkeyText,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp),
        )

        // --- Action buttons ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HoloButton(
                onClick = {
                    if (refreshInProgress) return@HoloButton
                    refreshInProgress = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            IdentityFetcher.fetch(
                                context = context,
                                urlPrefix = current.urlPrefix,
                                trustAllCerts = current.ignoreSslErrors,
                            )
                        }
                        when (result) {
                            is IdentityFetcher.Result.Success -> {
                                IdentityStore.put(context, current.urlPrefix, result.pubkey)
                                // Route through the shared notifier so MUTE / TOAST
                                // / POPUP in Settings actually governs the manual
                                // Refresh button feedback, not just background fetches.
                                IdentityRefreshNotifier.notify(
                                    context,
                                    current.identityRefreshNotificationMode,
                                    result.pubkey,
                                )
                            }
                            is IdentityFetcher.Result.HttpFailure -> {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.identity_refresh_toast_http_fail, result.message),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            is IdentityFetcher.Result.BadResponse -> {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.identity_refresh_toast_bad_response, result.message),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                        refreshInProgress = false
                    }
                },
                text = stringResource(if (refreshInProgress) R.string.identity_btn_refreshing else R.string.identity_btn_refresh),
            )

            HoloButton(
                onClick = { clearConfirmOpen = true },
                text = stringResource(R.string.identity_btn_clear),
            )
        }
    }

    // --- Notification-mode picker dialog ---
    if (refreshModePickerOpen) {
        AlertDialog(
            onDismissRequest = { refreshModePickerOpen = false },
            title = { Text(stringResource(R.string.identity_refresh_mode_title)) },
            text = {
                Column {
                    IdentityRefreshNotificationMode.values().forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    refreshMode = mode
                                    persist()
                                    refreshModePickerOpen = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = mode == refreshMode,
                                onClick = {
                                    refreshMode = mode
                                    persist()
                                    refreshModePickerOpen = false
                                },
                            )
                            Text(mode.displayName(context), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { refreshModePickerOpen = false }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }

    // --- Clear-confirmation dialog ---
    if (clearConfirmOpen) {
        AlertDialog(
            onDismissRequest = { clearConfirmOpen = false },
            title = { Text(stringResource(R.string.identity_clear_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.identity_clear_dialog_body,
                        current.urlPrefix.ifBlank { stringResource(R.string.identity_clear_dialog_default_label) },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        IdentityStore.clear(context, current.urlPrefix)
                        Toast.makeText(context, context.getString(R.string.identity_refresh_toast_clear), Toast.LENGTH_SHORT).show()
                    }
                    clearConfirmOpen = false
                }) { Text(stringResource(R.string.identity_btn_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmOpen = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
