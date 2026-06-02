package pl.dubba.share.ui

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.dubba.share.R
import pl.dubba.share.net.version.AppFingerprint
import pl.dubba.share.net.version.ConfigFetcher
import pl.dubba.share.protocol.PROTO_VERSION
import pl.dubba.share.settings.Settings

/**
 * About screen - version info + tagline + privacy policy + license +
 * library attributions. All static text comes from string resources, so
 * the entire screen translates with the rest of the app.
 *
 * Scrollable on purpose: privacy policy alone runs to multiple paragraphs.
 * No interactive controls - back navigation is the only exit.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenPrivacy: () -> Unit) {
    val context = LocalContext.current

    // Read versionName / versionCode once at compose time. Use the modern
    // longVersionCode on API 28+ (which our minSdk guarantees) and truncate
    // to Int for display since our version scheme fits in 32 bits.
    val packageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionName = packageInfo.versionName ?: "?"
    val versionCode = packageInfo.longVersionCodeCompat()

    // Whether to show the version-check section. Advanced-only because the
    // SHAs are noise for normal users (they can't action a mismatch). The
    // *silent* signer-mismatch alert on first connect is a separate concern
    // that lives elsewhere; this screen is the manual / power-user surface.
    val persisted by Settings.observe(context).collectAsState(initial = null)
    val showVersionCheck = persisted?.showAdvancedSettings == true

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
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // App identity block - name, version, protocol, "built with love".
        Text(
            stringResource(R.string.about_app_label),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.about_built_with_love),
            style = MaterialTheme.typography.bodyMedium,
            color = HoloColors.OnSurfaceMuted,
            modifier = Modifier.padding(top = 4.dp),
        )

        Column(modifier = Modifier.padding(top = 16.dp)) {
            LabelValueRow(
                stringResource(R.string.about_version_label),
                stringResource(R.string.about_version_value, versionName, versionCode),
            )
            LabelValueRow(
                stringResource(R.string.about_proto_version_label),
                PROTO_VERSION.toString(),
            )
            LabelValueRow(
                stringResource(R.string.about_min_sdk_label),
                stringResource(R.string.about_min_sdk_value, Build.VERSION_CODES.P),
            )
        }

        // --- Privacy policy - tap to open full text in its own screen ---
        // The privacy body is long enough that inlining it pushes the
        // license + library blocks too far down. Using the same divider
        // styling as other sections plus a chevron makes the affordance
        // legible at a glance.
        HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPrivacy)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.9f)) {
                Text(
                    stringResource(R.string.about_privacy_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.about_privacy_row_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = HoloColors.OnSurfaceMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.titleMedium,
                color = HoloColors.OnSurfaceMuted,
            )
        }

        // --- Build fingerprint / version check (Advanced only) ---
        if (showVersionCheck) {
            VersionCheckSection(
                urlPrefix = persisted?.urlPrefix.orEmpty(),
                trustAllCerts = persisted?.ignoreSslErrors == true,
            )
        }

        // --- License ---
        SectionHeader(stringResource(R.string.about_license_section))
        Text(
            stringResource(R.string.about_license_intro),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.about_license_body),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 8.dp),
        )

        // --- Library attributions ---
        SectionHeader(stringResource(R.string.about_libraries_section))
        Text(
            stringResource(R.string.about_libraries_intro),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        AttributionBlock(
            header = stringResource(R.string.about_libraries_server_header),
            body = stringResource(R.string.about_libraries_server_body),
        )
        AttributionBlock(
            header = stringResource(R.string.about_libraries_android_header),
            body = stringResource(R.string.about_libraries_android_body),
        )
        AttributionBlock(
            header = stringResource(R.string.about_libraries_web_header),
            body = stringResource(R.string.about_libraries_web_body),
        )
        AttributionBlock(
            header = stringResource(R.string.about_libraries_data_header),
            body = stringResource(R.string.about_libraries_data_body),
        )

        // Padding at bottom so the last text doesn't sit flush against the nav bar.
        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }
}

/**
 * Small two-column row: label on the left, value on the right. Used for the
 * version / protocol / min-SDK lines, and for the long hex fingerprints in
 * the build-fingerprint section ([valueMonospace] flips to bodySmall + mono
 * so a 128-char SHA-512 doesn't look like prose).
 */
@Composable
private fun LabelValueRow(label: String, value: String, valueMonospace: Boolean = false) {
    val labelFraction = if (valueMonospace) 0.4f else 0.5f
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = if (valueMonospace) Alignment.Top else Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = HoloColors.OnSurfaceMuted,
            modifier = Modifier.fillMaxWidth(labelFraction),
        )
        Text(
            value,
            style = if (valueMonospace) MaterialTheme.typography.bodySmall
                    else MaterialTheme.typography.bodyMedium,
            fontFamily = if (valueMonospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun AttributionBlock(header: String, body: String) {
    Text(
        header,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        body,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * Read the version code via [PackageInfo.getLongVersionCode] on API 28+,
 * falling back to the deprecated `versionCode` on older. minSdk is 28 so
 * the fallback branch is technically dead, but kept so a future minSdk
 * drop doesn't silently break.
 */
@Suppress("DEPRECATION")
private fun PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode
    else versionCode.toLong()

/**
 * Outcomes of the "this APK vs the server's APK" comparison. Five states
 * because there's a real semantic split between "fingerprint missing on
 * server" (server admin's build is broken - they can fix it) and "no
 * match" (the install on this phone genuinely is something else).
 */
private sealed interface VersionCheckResult {
    object Match : VersionCheckResult
    object SignerOnly : VersionCheckResult
    object SignerMismatch : VersionCheckResult
    object NoPublisherData : VersionCheckResult
    data class FetchFailed(val message: String) : VersionCheckResult
}

/**
 * Build-fingerprint comparison section. Shows the running APK's SHA-512 and
 * signing-cert SHA-256, plus a button that fetches the server's `/config`,
 * computes the comparable fingerprint from the server's `local_signer`
 * PKCS#7 blob, and surfaces a three-state (plus two error states) result.
 *
 * Always called inside `if (showAdvancedSettings)` - the rendering itself
 * doesn't re-check that flag.
 */
@Composable
private fun VersionCheckSection(
    urlPrefix: String,
    trustAllCerts: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Local hashes are heavy (~150-200 ms for SHA-512 on a 25 MB APK), so
    // compute them off the main thread on first composition and cache. The
    // signer-cert SHA is fast but consistency wins - same effect block.
    var localApkSha by remember { mutableStateOf<String?>(null) }
    var localSignerSha by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // SHA-512 over the 25 MB APK is ~150-200 ms; signer SHA-256 is fast
        // but does I/O too. Fan out so we eat one wall-clock cost, not two.
        withContext(Dispatchers.IO) {
            coroutineScope {
                val apkDeferred = async { AppFingerprint.selfApkSha512(context) }
                val signerDeferred = async { AppFingerprint.selfSignerSha256(context) }
                localApkSha = apkDeferred.await()
                localSignerSha = signerDeferred.await()
            }
        }
    }

    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<VersionCheckResult?>(null) }

    SectionHeader(stringResource(R.string.about_version_check_section))
    Text(
        stringResource(R.string.about_version_check_intro),
        style = MaterialTheme.typography.bodySmall,
        color = HoloColors.OnSurfaceMuted,
        modifier = Modifier.padding(top = 4.dp),
    )

    LabelValueRow(
        stringResource(R.string.about_version_check_apk_hash_label),
        localApkSha ?: stringResource(R.string.about_version_check_unavailable),
        valueMonospace = true,
    )
    LabelValueRow(
        stringResource(R.string.about_version_check_signer_label),
        localSignerSha ?: stringResource(R.string.about_version_check_unavailable),
        valueMonospace = true,
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HoloButton(
            onClick = {
                if (checking) return@HoloButton
                checking = true
                result = null
                scope.launch {
                    val outcome = runVersionCheck(
                        context = context,
                        urlPrefix = urlPrefix,
                        trustAllCerts = trustAllCerts,
                        localApkSha = localApkSha,
                        localSignerSha = localSignerSha,
                    )
                    result = outcome
                    checking = false
                }
            },
            text = stringResource(
                if (checking) R.string.about_version_check_button_busy
                else R.string.about_version_check_button,
            ),
        )
    }

    result?.let { r ->
        Text(
            text = r.toMessage(context),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/**
 * Off-main-thread `/config` fetch + comparison. Returns the bucket the UI
 * should render.
 */
private suspend fun runVersionCheck(
    context: Context,
    urlPrefix: String,
    trustAllCerts: Boolean,
    localApkSha: String?,
    localSignerSha: String?,
): VersionCheckResult = withContext(Dispatchers.IO) {
    val fetch = ConfigFetcher.fetch(
        context = context,
        urlPrefix = urlPrefix,
        trustAllCerts = trustAllCerts,
    )
    when (fetch) {
        is ConfigFetcher.Result.HttpFailure -> VersionCheckResult.FetchFailed(fetch.message)
        is ConfigFetcher.Result.BadResponse -> VersionCheckResult.FetchFailed(fetch.message)
        is ConfigFetcher.Result.Success -> {
            val serverSignerHex = fetch.signerBlobHex
            if (serverSignerHex == null) {
                VersionCheckResult.NoPublisherData
            } else {
                val serverSignerSha = AppFingerprint.parseServerSignerSha256(serverSignerHex)
                if (serverSignerSha == null || localSignerSha == null) {
                    // Parsing the server's cert blob failed, or we couldn't
                    // read our own signer earlier. Either way we can't render
                    // a confident result, so fall back to NoPublisherData
                    // rather than asserting a mismatch we can't actually prove.
                    VersionCheckResult.NoPublisherData
                } else if (!serverSignerSha.equals(localSignerSha, ignoreCase = true)) {
                    VersionCheckResult.SignerMismatch
                } else {
                    // Signer matches. Now compare APK content hash if the
                    // server published one; otherwise we can only confirm the
                    // signer.
                    val serverApkSha = fetch.apkSha512Hex
                    if (serverApkSha != null &&
                        localApkSha != null &&
                        serverApkSha.equals(localApkSha, ignoreCase = true)
                    ) {
                        VersionCheckResult.Match
                    } else {
                        VersionCheckResult.SignerOnly
                    }
                }
            }
        }
    }
}

private fun VersionCheckResult.toMessage(context: Context): String = when (this) {
    is VersionCheckResult.Match ->
        context.getString(R.string.about_version_check_result_match)
    is VersionCheckResult.SignerOnly ->
        context.getString(R.string.about_version_check_result_signer_only)
    is VersionCheckResult.SignerMismatch ->
        context.getString(R.string.about_version_check_result_mismatch)
    is VersionCheckResult.NoPublisherData ->
        context.getString(R.string.about_version_check_result_no_publisher_data)
    is VersionCheckResult.FetchFailed ->
        context.getString(R.string.about_version_check_result_fetch_failed, message)
}
