package pl.dubba.share.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.dubba.share.R
import pl.dubba.share.net.version.ContactFetcher
import pl.dubba.share.settings.Settings

/**
 * Full privacy policy. Opened from the About screen's tappable privacy row.
 *
 * All static content lives in string resources so the whole document
 * translates with the rest of the app (see values/strings.xml and
 * values-pl/strings.xml `privacy_*` keys). Sections render with a small
 * header + body block pattern to keep the read scannable.
 *
 * The Contact section is the one piece of dynamic content: the operator
 * contact address comes from the server (`/gpdr-email`) on screen open
 * rather than being baked into the APK. That way self-hosters can publish
 * their own contact without rebuilding, and the upstream APK doesn't have
 * to expose anyone's email address by default.
 */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val persisted by Settings.observe(context).collectAsState(initial = null)

    // Three-state contact section. Starts as Loading; LaunchedEffect kicks
    // off the fetch once we have a urlPrefix to point at. Re-runs on
    // urlPrefix change so a Settings edit during the same Activity lifetime
    // is picked up if the user comes back to this screen.
    var contactState by remember { mutableStateOf<ContactState>(ContactState.Loading) }
    val urlPrefix = persisted?.urlPrefix.orEmpty()
    val trustAllCerts = persisted?.ignoreSslErrors == true
    LaunchedEffect(urlPrefix, trustAllCerts) {
        if (urlPrefix.isBlank()) {
            // No server configured yet -> stay in Loading silently; once
            // they set a urlPrefix the LaunchedEffect re-fires.
            return@LaunchedEffect
        }
        contactState = ContactState.Loading
        contactState = withContext(Dispatchers.IO) {
            when (val result = ContactFetcher.fetch(
                context = context,
                urlPrefix = urlPrefix,
                trustAllCerts = trustAllCerts,
            )) {
                is ContactFetcher.Result.Success -> ContactState.Loaded(result.contact)
                is ContactFetcher.Result.Unavailable -> ContactState.Unavailable(result.message)
            }
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
            Text(stringResource(R.string.about_privacy_section), style = MaterialTheme.typography.titleMedium)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            stringResource(R.string.privacy_last_updated),
            style = MaterialTheme.typography.bodySmall,
            color = HoloColors.OnSurfaceMuted,
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            stringResource(R.string.privacy_intro),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp),
        )

        PolicySection(
            title = stringResource(R.string.privacy_section_location_title),
            body = stringResource(R.string.privacy_section_location_body),
        )
        PolicySection(
            title = stringResource(R.string.privacy_section_logs_title),
            body = stringResource(R.string.privacy_section_logs_body),
        )
        PolicySection(
            title = stringResource(R.string.privacy_section_why_title),
            body = stringResource(R.string.privacy_section_why_body),
        )
        PolicySection(
            title = stringResource(R.string.privacy_section_sharing_title),
            body = stringResource(R.string.privacy_section_sharing_body),
        )
        PolicySection(
            title = stringResource(R.string.privacy_section_selfhost_title),
            body = stringResource(R.string.privacy_section_selfhost_body),
        )

        // Contact section: title + intro + dynamic email / state row.
        Text(
            stringResource(R.string.privacy_section_contact_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
        )
        Text(
            stringResource(R.string.privacy_section_contact_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        ContactStateRow(state = contactState)

        // Bottom padding so the contact line doesn't sit flush against the nav bar.
        Text("", modifier = Modifier.padding(bottom = 24.dp))
    }
}

/**
 * Rendering of the contact-fetch state. Email is rendered monospace so it's
 * unambiguous to read aloud or hand-copy; the loading / error states use
 * regular body text with a muted color.
 */
@Composable
private fun ContactStateRow(state: ContactState) {
    when (state) {
        is ContactState.Loading ->
            Text(
                stringResource(R.string.privacy_section_contact_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = HoloColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        is ContactState.Loaded ->
            Text(
                state.contact,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        is ContactState.Unavailable ->
            Text(
                stringResource(R.string.privacy_section_contact_unavailable, state.detail),
                style = MaterialTheme.typography.bodyMedium,
                color = HoloColors.OnSurfaceMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
    }
}

private sealed interface ContactState {
    object Loading : ContactState
    data class Loaded(val contact: String) : ContactState
    data class Unavailable(val detail: String) : ContactState
}

@Composable
private fun PolicySection(title: String, body: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
    )
}
