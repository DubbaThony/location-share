package pl.dubba.share

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import pl.dubba.share.log.DebugLog
import pl.dubba.share.net.ConnectionState
import pl.dubba.share.notify.AlertClass
import pl.dubba.share.settings.LanguagePref
import pl.dubba.share.settings.LocaleHelper
import pl.dubba.share.ui.AboutScreen
import pl.dubba.share.ui.AlertEditScreen
import pl.dubba.share.ui.DebugScreen
import pl.dubba.share.ui.HoloTheme
import pl.dubba.share.ui.IdentityScreen
import pl.dubba.share.ui.MainScreen
import pl.dubba.share.ui.NotificationsScreen
import pl.dubba.share.ui.PrivacyScreen
import pl.dubba.share.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    /**
     * Apply the persisted language preference before any UI inflates.
     * runBlocking on DataStore is safe here - attachBaseContext fires once
     * per Activity lifetime, before window attach, and we need the locale
     * resolved synchronously so the resource framework picks the right
     * values directory on the very first inflate. When the user changes
     * the language in Settings, the picker calls Activity.recreate() so
     * this runs again with the fresh value.
     */
    override fun attachBaseContext(newBase: Context) {
        val language = LanguagePref.read(newBase)
        super.attachBaseContext(LocaleHelper.applyLocale(newBase, language.resolveLocale()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wire the persistent log store first so any subsequent ConnectionState
        // appendLog calls (including from a service that's about to come up)
        // immediately write to disk, and prior-session logs are visible in
        // the Debug screen.
        DebugLog.init(this)
        ConnectionState.bootstrapLogsFromDisk()
        setContent {
            HoloTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                ) {
                    AppNav()
                }
            }
        }
    }
}

/**
 * Sealed nav so the AlertEdit destination can carry which class is being
 * edited. System back walks the natural parent chain.
 */
private sealed class Screen {
    object Main : Screen()
    object Settings : Screen()
    object Notifications : Screen()
    data class AlertEdit(val alertClass: AlertClass) : Screen()
    object Identity : Screen()
    object About : Screen()
    object Privacy : Screen()
    object Debug : Screen()
}

/**
 * Saver for the Screen sealed hierarchy. Persists via a short string tag
 * so the navigation survives Activity.recreate() - specifically the
 * recreate triggered by the language picker, otherwise the user gets
 * snapped back to Main right when they were trying to confirm the change.
 *
 * AlertEdit carries the chosen AlertClass via its storageKey, which is
 * stable across builds. Unknown tags fall back to Main (cold start
 * behaviour) so a deleted alert class doesn't crash the restore.
 */
private val ScreenSaver: Saver<Screen, String> = Saver(
    save = { screen ->
        when (screen) {
            Screen.Main -> "main"
            Screen.Settings -> "settings"
            Screen.Notifications -> "notifications"
            is Screen.AlertEdit -> "alertedit:${screen.alertClass.storageKey}"
            Screen.Identity -> "identity"
            Screen.About -> "about"
            Screen.Privacy -> "privacy"
            Screen.Debug -> "debug"
        }
    },
    restore = { tag ->
        when {
            tag == "main" -> Screen.Main
            tag == "settings" -> Screen.Settings
            tag == "notifications" -> Screen.Notifications
            tag.startsWith("alertedit:") -> {
                val storageKey = tag.removePrefix("alertedit:")
                AlertClass.all.firstOrNull { it.storageKey == storageKey }
                    ?.let { Screen.AlertEdit(it) }
                    ?: Screen.Main
            }
            tag == "identity" -> Screen.Identity
            tag == "about" -> Screen.About
            tag == "privacy" -> Screen.Privacy
            tag == "debug" -> Screen.Debug
            else -> Screen.Main
        }
    },
)

@Composable
private fun AppNav() {
    // rememberSaveable so the language-picker's activity.recreate() leaves
    // the user on the screen they were on (Settings), not back at Main.
    var current by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(Screen.Main)
    }

    // System back maps each screen to its natural parent.
    BackHandler(enabled = current !is Screen.Main) {
        current = when (val c = current) {
            Screen.Main -> Screen.Main
            Screen.Settings -> Screen.Main
            Screen.Notifications -> Screen.Settings
            is Screen.AlertEdit -> Screen.Notifications
            Screen.Identity -> Screen.Settings
            Screen.About -> Screen.Settings
            Screen.Privacy -> Screen.About
            Screen.Debug -> Screen.Settings
        }
    }

    when (val c = current) {
        Screen.Main -> MainScreen(
            onOpenSettings = { current = Screen.Settings },
        )
        Screen.Settings -> SettingsScreen(
            onBack = { current = Screen.Main },
            onOpenDebug = { current = Screen.Debug },
            onOpenNotifications = { current = Screen.Notifications },
            onOpenIdentity = { current = Screen.Identity },
            onOpenAbout = { current = Screen.About },
        )
        Screen.Notifications -> NotificationsScreen(
            onBack = { current = Screen.Settings },
            onPickClass = { ac -> current = Screen.AlertEdit(ac) },
        )
        is Screen.AlertEdit -> AlertEditScreen(
            alertClass = c.alertClass,
            onBack = { current = Screen.Notifications },
        )
        Screen.Identity -> IdentityScreen(
            onBack = { current = Screen.Settings },
        )
        Screen.About -> AboutScreen(
            onBack = { current = Screen.Settings },
            onOpenPrivacy = { current = Screen.Privacy },
        )
        Screen.Privacy -> PrivacyScreen(
            onBack = { current = Screen.About },
        )
        Screen.Debug -> DebugScreen(
            onBack = { current = Screen.Settings },
        )
    }
}
