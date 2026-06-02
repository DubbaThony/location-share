package pl.dubba.share.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.compose.material3.RadioButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.dubba.share.Config
import pl.dubba.share.R
import pl.dubba.share.settings.Language
import pl.dubba.share.settings.LanguagePref
import pl.dubba.share.settings.Settings

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenIdentity: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val persisted by Settings.observe(context).collectAsState(initial = null)
    val current = persisted ?: return

    // Snapshot the settings as they were when the screen opened - Revert
    // restores these exact values. Captured once via no-key remember so a
    // subsequent auto-save (which re-emits through DataStore and changes
    // `current`) doesn't clobber the "original" reference point.
    val originalSnapshot = remember { current }

    // Field state intentionally NOT keyed on `current`: with auto-save, every
    // keystroke writes to DataStore which re-emits, which would re-key the
    // state and overwrite the user's mid-typing input. Reset / Revert
    // explicitly update these instead.
    var host by remember { mutableStateOf(originalSnapshot.host) }
    var port by remember { mutableStateOf(originalSnapshot.port.toString()) }
    var pingSec by remember { mutableStateOf((originalSnapshot.pingIntervalMs / 1000).toString()) }
    var keepAwake by remember { mutableStateOf(originalSnapshot.keepAwake) }
    var useCompassOnly by remember { mutableStateOf(originalSnapshot.useCompassOnly) }
    var urlPrefix by remember { mutableStateOf(originalSnapshot.urlPrefix) }
    var viewerDebug by remember { mutableStateOf(originalSnapshot.viewerDebug) }
    var showAdvanced by remember { mutableStateOf(originalSnapshot.showAdvancedSettings) }
    var allowHttpServer by remember { mutableStateOf(originalSnapshot.allowHttpServer) }
    var ignoreSslErrors by remember { mutableStateOf(originalSnapshot.ignoreSslErrors) }
    // Language lives outside the DataStore-backed Settings (see LanguagePref);
    // we still mirror the persisted value into a Compose state for the picker
    // UI, but writes go through LanguagePref, not through persist().
    val originalLanguage = remember { LanguagePref.read(context) }
    var language by remember { mutableStateOf(originalLanguage) }
    var languagePickerOpen by remember { mutableStateOf(false) }

    // --- Save-time HTTP guard state ---
    // Dialog opens on a debounced effect when urlPrefix settles as
    // http://... while Advanced → "Allow HTTP servers" is off. Tracking
    // the last URL we already prompted for so dismissing the dialog
    // doesn't re-fire on every recomposition; we only re-prompt when the
    // URL actually changes again.
    var httpDialogOpen by remember { mutableStateOf(false) }
    var lastHttpPromptedFor by remember { mutableStateOf<String?>(null) }

    // Debounced: 1.5 s after the user stops typing OR toggles allow-HTTP,
    // we check whether the saved urlPrefix needs a confirmation prompt.
    // LaunchedEffect cancels and restarts whenever any of the keys change,
    // so a rapid sequence of keystrokes coalesces into a single check.
    LaunchedEffect(urlPrefix, allowHttpServer) {
        delay(1_500)
        val trimmed = urlPrefix.trim()
        if (isHttpScheme(trimmed) && !allowHttpServer && lastHttpPromptedFor != trimmed) {
            httpDialogOpen = true
            lastHttpPromptedFor = trimmed
        }
    }

    // Reading `current` via rememberUpdatedState makes persist() see the
    // latest DataStore snapshot at call time without recreating the lambda.
    // We need this so fields NOT managed by this screen (shareTimeMin,
    // pollingIntervalMs, alerts/extras edited in the Notifications sub-screen)
    // survive any save we do from settings.
    val currentLatest by rememberUpdatedState(current)
    // persist() returns the Job that owns the actual DataStore write, so
    // call sites that need to act AFTER the write (the language picker
    // chains an activity.recreate()) can await it. Fire-and-forget callers
    // (every onValueChange) just discard the Job - Kotlin lets the return
    // value drop silently.
    val persist: () -> kotlinx.coroutines.Job = {
        val parsedPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: originalSnapshot.port
        val parsedPingMs = (pingSec.toLongOrNull()?.takeIf { it in 1..3600 }
            ?: (originalSnapshot.pingIntervalMs / 1000)) * 1000L
        val updated = currentLatest.copy(
            host = host.trim(),
            port = parsedPort,
            pingIntervalMs = parsedPingMs.coerceAtLeast(1_000),
            keepAwake = keepAwake,
            useCompassOnly = useCompassOnly,
            urlPrefix = urlPrefix.trim(),
            viewerDebug = viewerDebug,
            showAdvancedSettings = showAdvanced,
            allowHttpServer = allowHttpServer,
            ignoreSslErrors = ignoreSslErrors,
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
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(stringResource(R.string.settings_server_section), style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; persist() },
                label = { Text(stringResource(R.string.settings_host_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it; persist() },
                label = { Text(stringResource(R.string.settings_port_label)) },
                singleLine = true,
                modifier = Modifier.width(110.dp),
            )
        }

        Text(
            stringResource(R.string.settings_viewer_url_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        OutlinedTextField(
            value = urlPrefix,
            onValueChange = { urlPrefix = it; persist() },
            label = { Text(stringResource(R.string.settings_url_prefix_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Text(
            stringResource(R.string.settings_power_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HoloSwitch(checked = keepAwake, onCheckedChange = { keepAwake = it; persist() })
            Column {
                Text(stringResource(R.string.settings_keep_awake_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_keep_awake_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text(
            stringResource(R.string.settings_direction_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HoloSwitch(checked = useCompassOnly, onCheckedChange = { useCompassOnly = it; persist() })
            Column {
                Text(stringResource(R.string.settings_compass_only_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_compass_only_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Language picker - top-level so Polish-only users can find it
        // without diving into Advanced. Opens a radio-list dialog; on
        // selection we persist + recreate the activity so the resource
        // framework re-resolves strings into the new locale.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .clickable { languagePickerOpen = true }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_language_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                languageLabel(language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Single row that drills into the per-class editor screens.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .clickable(onClick = onOpenNotifications)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_notifications_row_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_notifications_row_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = HoloColors.OnSurfaceMuted)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenIdentity)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_identity_row_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_identity_row_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("›", style = MaterialTheme.typography.titleMedium, color = HoloColors.OnSurfaceMuted)
        }

        // --- Advanced gate ---
        // Last row of the "main" settings. Flipping this on reveals the
        // Advanced section below - dev-y knobs that we don't want a
        // first-time user to encounter by accident.
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HoloSwitch(checked = showAdvanced, onCheckedChange = { showAdvanced = it; persist() })
            Column {
                Text(stringResource(R.string.settings_show_advanced_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_show_advanced_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (showAdvanced) {
            Text(
                stringResource(R.string.settings_advanced_section),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 16.dp),
            )

            // Allow http:// URL prefixes. When off (default), the connect
            // path refuses any http:// URL with a dialog that points the
            // user here. The justification lives at that dialog - we keep
            // the toggle label terse on this dense screen.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HoloSwitch(
                    checked = allowHttpServer,
                    onCheckedChange = { allowHttpServer = it; persist() },
                )
                Column {
                    Text(stringResource(R.string.settings_allow_http_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_allow_http_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // SSL cert validation bypass - companion to allow-HTTP. Same
            // attack surface (MITM forges /identity) but takes a slightly
            // different path: cert validation off rather than no cert.
            // Useful for LAN self-signed setups, dangerous everywhere else.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HoloSwitch(
                    checked = ignoreSslErrors,
                    onCheckedChange = { ignoreSslErrors = it; persist() },
                )
                Column {
                    Text(stringResource(R.string.settings_ignore_ssl_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_ignore_ssl_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Append ?debug=1 to viewer URLs - moved from the main section.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HoloSwitch(checked = viewerDebug, onCheckedChange = { viewerDebug = it; persist() })
                Column {
                    Text(stringResource(R.string.settings_viewer_debug_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_viewer_debug_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Ping interval - moved from the main "Timing" section.
            OutlinedTextField(
                value = pingSec,
                onValueChange = { pingSec = it; persist() },
                label = { Text(stringResource(R.string.settings_ping_interval_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HoloButton(
                onClick = {
                    // Restore every field to the values we cached at screen
                    // open, then push that snapshot through persist() so the
                    // DataStore matches what's visible. Alerts/extras live in
                    // their own sub-screen and have their own revert path.
                    host = originalSnapshot.host
                    port = originalSnapshot.port.toString()
                    pingSec = (originalSnapshot.pingIntervalMs / 1000).toString()
                    keepAwake = originalSnapshot.keepAwake
                    useCompassOnly = originalSnapshot.useCompassOnly
                    urlPrefix = originalSnapshot.urlPrefix
                    viewerDebug = originalSnapshot.viewerDebug
                    showAdvanced = originalSnapshot.showAdvancedSettings
                    allowHttpServer = originalSnapshot.allowHttpServer
                    ignoreSslErrors = originalSnapshot.ignoreSslErrors
                    // Language lives outside DataStore; restore via LanguagePref.
                    // Skip activity.recreate() - the picker is the deliberate
                    // path for language changes; Revert just snaps the picker
                    // label back without surprising the user with a relaunch.
                    if (language != originalLanguage) {
                        LanguagePref.write(context, originalLanguage)
                        language = originalLanguage
                    }
                    persist()
                },
                text = stringResource(R.string.settings_btn_revert),
            )

            HoloButton(
                onClick = {
                    // Snap fields to Snapshot.Default and wipe DataStore; the
                    // observe Flow will re-emit defaults shortly. Setting
                    // fields manually keeps the UI in sync immediately rather
                    // than waiting for the round-trip.
                    val def = Settings.Snapshot.Default
                    host = def.host
                    port = def.port.toString()
                    pingSec = (def.pingIntervalMs / 1000).toString()
                    keepAwake = def.keepAwake
                    useCompassOnly = def.useCompassOnly
                    urlPrefix = def.urlPrefix
                    viewerDebug = def.viewerDebug
                    showAdvanced = def.showAdvancedSettings
                    allowHttpServer = def.allowHttpServer
                    ignoreSslErrors = def.ignoreSslErrors
                    // Language lives outside DataStore - reset directly.
                    if (language != Config.DEFAULT_SETTING_LANGUAGE) {
                        LanguagePref.write(context, Config.DEFAULT_SETTING_LANGUAGE)
                        language = Config.DEFAULT_SETTING_LANGUAGE
                    }
                    scope.launch { Settings.reset(context) }
                },
                text = stringResource(R.string.settings_btn_reset),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HoloButton(onClick = onOpenAbout, text = stringResource(R.string.settings_btn_about))
            // Debug view only appears in Advanced mode - it's mostly useful
            // when something's gone wrong, and "something has gone wrong"
            // is downstream of the dev-y toggles in the Advanced section.
            if (showAdvanced) {
                HoloButton(onClick = onOpenDebug, text = stringResource(R.string.settings_btn_debug_view))
            }
        }
    }

    // --- Save-time HTTP guard dialog ---
    // Cancel just dismisses - the urlPrefix stays as the user typed it
    // (we don't auto-revert; they own their input). Confirm flips
    // allowHttpServer on, which both makes the connect path stop blocking
    // AND prevents this dialog from re-firing on subsequent saves.
    if (httpDialogOpen) {
        AlertDialog(
            onDismissRequest = { httpDialogOpen = false },
            title = { Text(stringResource(R.string.http_dialog_title)) },
            text = { Text(stringResource(R.string.http_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    allowHttpServer = true
                    persist()
                    httpDialogOpen = false
                }) { Text(stringResource(R.string.http_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { httpDialogOpen = false }) { Text(stringResource(R.string.common_dismiss)) }
            },
        )
    }

    // --- Language picker dialog ---
    // Persist immediately, then ask the host activity to recreate() - the
    // resource framework re-resolves all stringResource() reads into the
    // new locale on the fresh activity instance. attachBaseContext in
    // MainActivity does the locale wrap before any UI inflates.
    if (languagePickerOpen) {
        val activity = context as? ComponentActivity
        AlertDialog(
            onDismissRequest = { languagePickerOpen = false },
            title = { Text(stringResource(R.string.settings_language_title)) },
            text = {
                Column {
                    Language.values().forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val changed = lang != language
                                    language = lang
                                    languagePickerOpen = false
                                    if (changed) {
                                        // LanguagePref.write uses commit() so the next
                                        // Activity.attachBaseContext sees it synchronously.
                                        // No race with DataStore - language lives outside
                                        // it specifically to dodge this class of bug.
                                        LanguagePref.write(context, lang)
                                        activity?.recreate()
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = lang == language,
                                onClick = {
                                    val changed = lang != language
                                    language = lang
                                    languagePickerOpen = false
                                    if (changed) {
                                        // LanguagePref.write uses commit() so the next
                                        // Activity.attachBaseContext sees it synchronously.
                                        // No race with DataStore - language lives outside
                                        // it specifically to dodge this class of bug.
                                        LanguagePref.write(context, lang)
                                        activity?.recreate()
                                    }
                                },
                            )
                            Text(languageLabel(lang), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { languagePickerOpen = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
}

/**
 * Display label for a [Language] value. AUTO gets a localized "Auto"
 * string so it tracks the current language; the other two use their
 * autonyms (English / Polski), which are intentionally NOT translated -
 * a Polish speaker should be able to recognize "English" as a choice, and
 * vice versa.
 */
@Composable
private fun languageLabel(lang: Language): String = when (lang) {
    Language.AUTO -> stringResource(R.string.settings_language_auto)
    Language.ENGLISH -> lang.autonym
    Language.POLISH -> lang.autonym
}
