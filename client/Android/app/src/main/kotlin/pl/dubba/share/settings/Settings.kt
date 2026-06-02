package pl.dubba.share.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import pl.dubba.share.Config
import pl.dubba.share.net.identity.IdentityRefreshNotificationMode
import pl.dubba.share.notify.AlertClass
import pl.dubba.share.notify.AlertConfig
import pl.dubba.share.notify.VibrationPattern
// Note: language is intentionally NOT in this DataStore-backed Settings - it
// needs to be read in Application.attachBaseContext, which is too early for
// DataStore. See pl.dubba.share.settings.LanguagePref.

/**
 * Persistent app settings backed by DataStore Preferences. File lives at
 * /data/data/pl.dubba.share/files/datastore/settings.preferences_pb (binary).
 *
 * Defaults come from [Config] - that's the "ship-time" layer; this is the
 * "user-tunable runtime" layer. If a key isn't present in the store yet, the
 * Config default fills in.
 *
 * Notification-related keys are flat-prefix: `alert_<storageKey>_<field>` per
 * [AlertClass] child. See [readAlertConfig] / [writeAlertConfig].
 */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

object Settings {
    private val KEY_HOST = stringPreferencesKey("server_host")
    private val KEY_PORT = intPreferencesKey("server_port")
    private val KEY_KEEP_AWAKE = booleanPreferencesKey("keep_awake")
    private val KEY_PING_INTERVAL_MS = longPreferencesKey("ping_interval_ms")
    private val KEY_SHARE_TIME_MIN = intPreferencesKey("share_time_min")
    private val KEY_POLLING_INTERVAL_MS = longPreferencesKey("polling_interval_ms")
    private val KEY_USE_COMPASS_ONLY = booleanPreferencesKey("use_compass_only")
    private val KEY_URL_PREFIX = stringPreferencesKey("url_prefix")
    private val KEY_VIEWER_DEBUG = booleanPreferencesKey("viewer_debug")

    // Class-specific extras (e.g. NetUnstable threshold) get their own keys.
    private val KEY_NET_UNSTABLE_THRESHOLD_SEC = intPreferencesKey("alert_net_unstable_threshold_sec")

    // --- Server identity / MITM mitigation ---
    private val KEY_VERIFY_SERVER_IDENTITY = booleanPreferencesKey("verify_server_identity")
    private val KEY_AUTO_REFRESH_SERVER_IDENTITY = booleanPreferencesKey("auto_refresh_server_identity")
    private val KEY_IDENTITY_REFRESH_NOTIFICATION_MODE = stringPreferencesKey("identity_refresh_notification_mode")

    // --- Advanced section gating ---
    private val KEY_SHOW_ADVANCED = booleanPreferencesKey("show_advanced")
    private val KEY_ALLOW_HTTP_SERVER = booleanPreferencesKey("allow_http_server")
    private val KEY_IGNORE_SSL_ERRORS = booleanPreferencesKey("ignore_ssl_errors")


    // Per-AlertClass key builders, kept here so the storage layer owns the
    // naming and the alert layer doesn't have to import DataStore types.
    private fun keyEnabled(a: AlertClass) = booleanPreferencesKey("alert_${a.storageKey}_enabled")
    private fun keyNotify(a: AlertClass) = booleanPreferencesKey("alert_${a.storageKey}_notify")
    private fun keyVibration(a: AlertClass) = stringPreferencesKey("alert_${a.storageKey}_vibration")
    private fun keyAutoCancel(a: AlertClass) = booleanPreferencesKey("alert_${a.storageKey}_auto_cancel")

    data class Snapshot(
        val host: String,
        val port: Int,
        val keepAwake: Boolean,
        val pingIntervalMs: Long,
        val shareTimeMin: Int,
        val pollingIntervalMs: Long,
        val useCompassOnly: Boolean,
        val urlPrefix: String,
        val viewerDebug: Boolean,
        /**
         * Per-AlertClass configuration. Keyed by [AlertClass] identity so adding
         * a new alert class only requires touching [AlertClass.all], the
         * defaults in [Config], and the per-class extras in [Snapshot] if it
         * has any.
         */
        val alerts: Map<AlertClass, AlertConfig>,
        /** Class-specific extra - silence-seconds threshold for [AlertClass.NetUnstable]. */
        val netUnstableThresholdSec: Int,
        /** Master switch for Ed25519-based server identity verification on connect. */
        val verifyServerIdentity: Boolean,
        /** Auto-fetch /identity over HTTPS on cache miss; manual button otherwise. */
        val autoRefreshServerIdentity: Boolean,
        /** How to surface identity-refresh events to the user. */
        val identityRefreshNotificationMode: IdentityRefreshNotificationMode,
        /** Reveal the dev-y "Advanced" section at the bottom of the Settings screen. */
        val showAdvancedSettings: Boolean,
        /** When false, the connect path refuses http:// URL prefixes with a dialog. */
        val allowHttpServer: Boolean,
        /** When true, [IdentityFetcher] accepts any TLS certificate. Self-signed LAN escape hatch. */
        val ignoreSslErrors: Boolean,
    ) {
        /** Convenience: read the config for a specific class, falling back to its baseline. */
        fun alertOf(c: AlertClass): AlertConfig = alerts[c] ?: c.defaultConfig

        companion object {
            val Default = Snapshot(
                host = Config.DEFAULT_SERVER_HOST,
                port = Config.DEFAULT_SERVER_PORT,
                keepAwake = true,
                pingIntervalMs = Config.PING_INTERVAL_MS,
                shareTimeMin = Config.DEFAULT_SHARE_TIME_MIN,
                pollingIntervalMs = Config.DEFAULT_POLLING_INTERVAL_MS,
                useCompassOnly = false,
                urlPrefix = Config.DEFAULT_URL_PREFIX,
                viewerDebug = false,
                alerts = AlertClass.all.associateWith { it.defaultConfig },
                netUnstableThresholdSec = Config.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_THRESHOLD_SEC,
                verifyServerIdentity = Config.DEFAULT_SETTING_VERIFY_SERVER_IDENTITY,
                autoRefreshServerIdentity = Config.DEFAULT_SETTING_AUTO_REFRESH_SERVER_IDENTITY,
                identityRefreshNotificationMode = Config.DEFAULT_SETTING_IDENTITY_REFRESH_NOTIFICATION_MODE,
                showAdvancedSettings = Config.DEFAULT_SETTING_SHOW_ADVANCED,
                allowHttpServer = Config.DEFAULT_SETTING_ALLOW_HTTP_SERVER,
                ignoreSslErrors = Config.DEFAULT_SETTING_IGNORE_SSL_ERRORS,
            )
        }
    }

    /** Read an [AlertConfig] from prefs for a given class, falling back to its baseline per field. */
    private fun readAlertConfig(p: Preferences, c: AlertClass): AlertConfig = AlertConfig(
        enabled = p[keyEnabled(c)] ?: c.defaultConfig.enabled,
        notify = p[keyNotify(c)] ?: c.defaultConfig.notify,
        vibration = VibrationPattern.parse(p[keyVibration(c)], c.defaultConfig.vibration),
        autoCancelOnResolve = p[keyAutoCancel(c)] ?: c.defaultConfig.autoCancelOnResolve,
    )

    /** Mirror of [readAlertConfig] on the write side. */
    private fun writeAlertConfig(p: androidx.datastore.preferences.core.MutablePreferences, c: AlertClass, cfg: AlertConfig) {
        p[keyEnabled(c)] = cfg.enabled
        p[keyNotify(c)] = cfg.notify
        p[keyVibration(c)] = cfg.vibration.name
        p[keyAutoCancel(c)] = cfg.autoCancelOnResolve
    }

    fun observe(context: Context): Flow<Snapshot> = context.settingsStore.data.map { p ->
        Snapshot(
            host = p[KEY_HOST] ?: Snapshot.Default.host,
            port = p[KEY_PORT] ?: Snapshot.Default.port,
            keepAwake = p[KEY_KEEP_AWAKE] ?: Snapshot.Default.keepAwake,
            pingIntervalMs = p[KEY_PING_INTERVAL_MS] ?: Snapshot.Default.pingIntervalMs,
            shareTimeMin = p[KEY_SHARE_TIME_MIN] ?: Snapshot.Default.shareTimeMin,
            pollingIntervalMs = p[KEY_POLLING_INTERVAL_MS] ?: Snapshot.Default.pollingIntervalMs,
            useCompassOnly = p[KEY_USE_COMPASS_ONLY] ?: Snapshot.Default.useCompassOnly,
            urlPrefix = p[KEY_URL_PREFIX] ?: Snapshot.Default.urlPrefix,
            viewerDebug = p[KEY_VIEWER_DEBUG] ?: Snapshot.Default.viewerDebug,
            alerts = AlertClass.all.associateWith { readAlertConfig(p, it) },
            netUnstableThresholdSec = (p[KEY_NET_UNSTABLE_THRESHOLD_SEC]?.takeIf { it in 5..3600 })
                ?: Snapshot.Default.netUnstableThresholdSec,
            verifyServerIdentity = p[KEY_VERIFY_SERVER_IDENTITY] ?: Snapshot.Default.verifyServerIdentity,
            autoRefreshServerIdentity = p[KEY_AUTO_REFRESH_SERVER_IDENTITY]
                ?: Snapshot.Default.autoRefreshServerIdentity,
            identityRefreshNotificationMode = IdentityRefreshNotificationMode.parse(
                p[KEY_IDENTITY_REFRESH_NOTIFICATION_MODE],
                Snapshot.Default.identityRefreshNotificationMode,
            ),
            showAdvancedSettings = p[KEY_SHOW_ADVANCED] ?: Snapshot.Default.showAdvancedSettings,
            allowHttpServer = p[KEY_ALLOW_HTTP_SERVER] ?: Snapshot.Default.allowHttpServer,
            ignoreSslErrors = p[KEY_IGNORE_SSL_ERRORS] ?: Snapshot.Default.ignoreSslErrors,
        )
    }

    suspend fun snapshot(context: Context): Snapshot = observe(context).first()

    suspend fun save(context: Context, s: Snapshot) {
        context.settingsStore.edit { p ->
            p[KEY_HOST] = s.host
            p[KEY_PORT] = s.port
            p[KEY_KEEP_AWAKE] = s.keepAwake
            p[KEY_PING_INTERVAL_MS] = s.pingIntervalMs
            p[KEY_SHARE_TIME_MIN] = s.shareTimeMin
            p[KEY_POLLING_INTERVAL_MS] = s.pollingIntervalMs
            p[KEY_USE_COMPASS_ONLY] = s.useCompassOnly
            p[KEY_URL_PREFIX] = s.urlPrefix
            p[KEY_VIEWER_DEBUG] = s.viewerDebug
            for (c in AlertClass.all) {
                writeAlertConfig(p, c, s.alertOf(c))
            }
            p[KEY_NET_UNSTABLE_THRESHOLD_SEC] = s.netUnstableThresholdSec.coerceIn(5, 3600)
            p[KEY_VERIFY_SERVER_IDENTITY] = s.verifyServerIdentity
            p[KEY_AUTO_REFRESH_SERVER_IDENTITY] = s.autoRefreshServerIdentity
            p[KEY_IDENTITY_REFRESH_NOTIFICATION_MODE] = s.identityRefreshNotificationMode.name
            p[KEY_SHOW_ADVANCED] = s.showAdvancedSettings
            p[KEY_ALLOW_HTTP_SERVER] = s.allowHttpServer
            p[KEY_IGNORE_SSL_ERRORS] = s.ignoreSslErrors
        }
    }

    suspend fun saveShareTime(context: Context, min: Int) {
        context.settingsStore.edit { it[KEY_SHARE_TIME_MIN] = min }
    }

    suspend fun savePollingInterval(context: Context, ms: Long) {
        context.settingsStore.edit { it[KEY_POLLING_INTERVAL_MS] = ms }
    }

    suspend fun reset(context: Context) {
        context.settingsStore.edit { it.clear() }
    }
}
