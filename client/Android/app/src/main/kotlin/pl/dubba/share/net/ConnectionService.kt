package pl.dubba.share.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import pl.dubba.share.Config
import pl.dubba.share.MainActivity
import pl.dubba.share.R
import pl.dubba.share.gps.CompassManager
import pl.dubba.share.gps.GpsFix
import pl.dubba.share.gps.GpsManager
import pl.dubba.share.log.DebugLog
import pl.dubba.share.net.identity.IdentityFetcher
import pl.dubba.share.net.identity.IdentityRefreshNotificationMode
import pl.dubba.share.net.identity.IdentityRefreshNotifier
import pl.dubba.share.net.identity.IdentityStore
import pl.dubba.share.notify.AlertClass
import pl.dubba.share.notify.Vibration
import pl.dubba.share.notify.VibrationPattern
import pl.dubba.share.settings.Settings
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Foreground service holding two independent subsystems:
 *
 *  - **Connection**: the UDP [ConnectionDriver] doing handshake + ping loop +
 *    DataFrame send queue
 *  - **GPS**: subscribes to [GpsManager], forwards each fix through a
 *    [PositionEncoder] to the driver's outbound queue
 *
 * Either can be started/stopped independently. Service stays alive while
 * either is running; shuts itself down only when both are off.
 *
 * The foreground service type is recomputed when the active subsystems change
 * - `LOCATION` when GPS is on, `DATA_SYNC` when connection is on, OR'd
 * together when both are on. This keeps us honest about what types we're
 * actually using (Android 14 enforces this).
 */
class ConnectionService : Service() {

    /**
     * The Application context's locale wrap from [pl.dubba.share.ShareApplication]
     * doesn't cascade here - Android creates the Service's ContextImpl from
     * the LoadedApk's shared Resources, which our `createConfigurationContext`
     * call on the Application doesn't affect. So we wrap the Service's own
     * base context too, reading LanguagePref directly. Now every `getString`
     * on `this` (foreground notification title/status, AlertClass.fire
     * titles, UiError text built in fetchAndStoreIdentity, etc.) resolves
     * into the user's chosen locale.
     */
    override fun attachBaseContext(newBase: Context) {
        val language = pl.dubba.share.settings.LanguagePref.read(newBase)
        super.attachBaseContext(
            pl.dubba.share.settings.LocaleHelper.applyLocale(newBase, language.resolveLocale()),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Connection subsystem
    private var connectionJob: Job? = null
    private var driver: ConnectionDriver? = null
    @Volatile private var connectionActive = false

    // GPS subsystem
    private var gpsCollectionJob: Job? = null
    @Volatile private var gpsActive = false

    // Share timer (countdown + grace period)
    private var shareTimerJob: Job? = null
    private var gpsAutoStopJob: Job? = null

    // Misc
    private var wakeLock: PowerManager.WakeLock? = null
    private val terminated = AtomicBoolean(false)

    /**
     * Cached polling-rate (ms between GPS fixes) from settings - read at
     * service start and kept fresh by the observer in [onCreate]. Holds the
     * value we'll pass to [GpsManager.start] / [GpsManager.updateRate].
     */
    @Volatile
    private var currentPollingIntervalMs: Long = Config.DEFAULT_POLLING_INTERVAL_MS

    /**
     * Cached "use compass only" flag. Critical hot-path optimisation: the GPS
     * fix collector runs under [kotlinx.coroutines.flow.collectLatest], whose
     * contract is "cancel the in-flight transform if a new value arrives." If
     * we read this via [Settings.snapshot] inside the transform, the resulting
     * DataStore suspension lets the next fix slip in and cancel the whole
     * lambda - silently dropping the payload. Cache here, settings observer
     * keeps it fresh.
     */
    @Volatile
    private var currentUseCompassOnly: Boolean = false

    // Alert classes own their own cached config (mutated by the Settings
    // observer below, read by fire/resolve callers). No per-class @Volatile
    // fields on the service anymore - see [AlertClass].

    /**
     * Outbound payload encoder. Becomes an [EncryptedPayloadEncoder] wrapping
     * the plaintext one as soon as a session key is minted in
     * [handleStartConnection]; falls back to plaintext when no session is
     * active so any stray callers don't NPE. The single chokepoint every
     * payload goes through - [postPayload] is the only place that touches it.
     */
    @Volatile
    private var payloadEncoder: PayloadEncoder = PlaintextPayloadEncoder

    /** Direction-resolver state - reset on each GPS session. */
    private val directionResolver = DirectionResolver()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Service can be created cold (system-triggered) before any Activity
        // ever runs - wire the log store here too so the very first log line
        // hits disk. Both this and MainActivity call init; it's idempotent.
        DebugLog.init(this)
        ConnectionState.bootstrapLogsFromDisk()
        ensureChannel()
        ConnectionState.setServiceRunning(true)
        ConnectionState.appendLog("service started")

        // Read settings synchronously up front so the very next startGps()
        // (likely arriving in the same onStartCommand pass) gets user values
        // not defaults. DataStore is in-memory cached after first load; this
        // is sub-ms in practice.
        runBlocking {
            val s = Settings.snapshot(this@ConnectionService)
            currentPollingIntervalMs = s.pollingIntervalMs
            currentUseCompassOnly = s.useCompassOnly
            applyAlertSettings(s)
        }

        // Start the share timer when the connection goes "fully live" (first
        // pong received). Stop the timer when the connection drops.
        scope.launch {
            ConnectionState.pongReceived.collect { received ->
                if (received) startShareTimer() else stopShareTimer()
            }
        }

        // Restart the share timer when the user rotates the share-time dial
        // mid-session. Trigger condition is "the connection is currently live"
        // (pongReceived), not "a timer is currently running" - otherwise the
        // INF → finite transition would be missed (no active timer to restart).
        scope.launch {
            Settings.observe(this@ConnectionService)
                .map { it.shareTimeMin }
                .distinctUntilChanged()
                .collect {
                    if (ConnectionState.pongReceived.value) startShareTimer()
                }
        }

        // Push polling-rate changes through to GpsManager live - user can spin
        // the dial mid-session and the GPS subscription re-registers with the
        // new minTime without flickering or restarting.
        scope.launch {
            Settings.observe(this@ConnectionService)
                .map { it.pollingIntervalMs }
                .distinctUntilChanged()
                .collect { ms ->
                    currentPollingIntervalMs = ms
                    if (gpsActive && GpsManager.updateRate(ms)) {
                        ConnectionState.appendLog("GPS rate → ${ms}ms")
                    }
                }
        }

        // Track useCompassOnly the same way - must not be read inside the
        // collectLatest hot path or its DataStore suspension drops fixes.
        scope.launch {
            Settings.observe(this@ConnectionService)
                .map { it.useCompassOnly }
                .distinctUntilChanged()
                .collect { currentUseCompassOnly = it }
        }

        // Mirror the alert configs into their AlertClass singletons so each
        // alert's fire()/resolve() can read its own config without going
        // through DataStore on the hot path.
        scope.launch {
            Settings.observe(this@ConnectionService).collect { s -> applyAlertSettings(s) }
        }

        // GPS lock-loss → vibrate. Triggered on non-null → null transition of
        // [GpsManager.fix] - which happens when GpsManager's watchdog fires
        // because the chip stopped delivering new fixes. Only buzz while GPS
        // is actually active (gpsActive): the same null transition happens
        // when the user manually stops GPS, and that's not an error event.
        scope.launch {
            var prevHadFix = false
            GpsManager.fix.collect { fix ->
                val haveFix = fix != null
                if (prevHadFix && !haveFix && gpsActive) {
                    AlertClass.LockLost.fire(this@ConnectionService)
                } else if (!prevHadFix && haveFix) {
                    // Lock returned - clear any in-flight notification per
                    // AlertConfig.autoCancelOnResolve.
                    AlertClass.LockLost.resolve(this@ConnectionService)
                }
                prevHadFix = haveFix
            }
        }

        // System-wide location toggle was flipped OFF while we were running.
        // GpsManager's BroadcastReceiver detected it and signaled here.
        // Order matters:
        //   1. Flip gpsActive=false FIRST so the fix-observer above sees it
        //      false when GpsManager.stop() nulls _fix below - otherwise it
        //      would fire the generic LockLost and overwrite our specific one.
        //   2. Fire LockLost with the system-loc-specific detail.
        //   3. Run the rest of stopGps() inline (META OFF to viewers, log,
        //      foreground-state update). Can't call stopGps() directly - it
        //      early-returns on !gpsActive.
        scope.launch {
            GpsManager.systemLocationDisabled.collect {
                if (!gpsActive) return@collect
                gpsActive = false
                AlertClass.LockLost.fire(
                    this@ConnectionService,
                    detail = getString(R.string.alert_lock_lost_detail_system_off),
                )
                cancelGpsAutoStop()
                postPayload(SharePayload.Meta(GpsStatus.OFF))
                gpsCollectionJob?.cancel()
                gpsCollectionJob = null
                GpsManager.stop()
                CompassManager.stop()
                directionResolver.reset()
                ConnectionState.appendLog("GPS subscription stopped (system location off)")
                if (connectionActive) {
                    refreshForeground()
                } else {
                    terminate()
                }
            }
        }

        // Watch for newly-set UiErrors and surface them as a high-importance
        // notification so the user knows even if the app isn't in the
        // foreground. StateFlow already dedupes adjacent equal emissions, so
        // a "user dismissed → null" doesn't re-fire if the previous value was
        // null. When the user dismisses (err → null) we resolve the in-flight
        // notification per the user's autoCancelOnResolve preference.
        scope.launch {
            ConnectionState.lastError.collect { err ->
                if (err != null) AlertClass.ConnError.fire(
                    this@ConnectionService,
                    title = err.title,
                    detail = err.detail ?: "Tap to open the app",
                )
                else AlertClass.ConnError.resolve(this@ConnectionService)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStartConnection(intent)
            ACTION_STOP -> requestConnectionGracefulStop()
            ACTION_GPS_START -> startGps()
            ACTION_GPS_STOP -> stopGps()
            ACTION_STOP_ALL -> {
                // Notification "Stop" action: tear down both subsystems. Mirrors
                // the in-app big-red-button behaviour.
                requestConnectionGracefulStop()
                stopGps()
            }
        }
        // Failsafe: if a malformed intent left us with nothing running, terminate.
        if (!connectionActive && !gpsActive) {
            terminate()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        connectionJob?.cancel()
        gpsCollectionJob?.cancel()
        shareTimerJob?.cancel()
        gpsAutoStopJob?.cancel()
        driver?.stop()
        GpsManager.stop()
        scope.cancel()
        ConnectionState.setConnectionActive(false)
        ConnectionState.setShareEndTimeMs(null)
        ConnectionState.setServiceRunning(false)
        ConnectionState.appendLog("service destroyed")
    }

    // --- Connection lifecycle ---

    private fun handleStartConnection(intent: Intent) {
        if (connectionActive) return
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        val keepAwake = intent.getBooleanExtra(EXTRA_KEEP_AWAKE, false)
        val pingIntervalMs = intent.getLongExtra(EXTRA_PING_INTERVAL_MS, 0)
        if (host.isNullOrBlank() || port !in 1..65535 || pingIntervalMs <= 0) {
            ConnectionState.appendLog("✗ missing/invalid extras (host=$host port=$port pingMs=$pingIntervalMs)")
            ConnectionState.setLastError(UiError(
                title = getString(R.string.err_invalid_connection_settings_title),
                detail = getString(
                    R.string.err_invalid_connection_settings_detail,
                    host.takeUnless { it.isNullOrBlank() } ?: getString(R.string.err_invalid_connection_settings_host_empty),
                    port,
                    pingIntervalMs,
                ),
            ))
            return
        }

        // User is bringing the Server toggle back up - cancel any in-flight
        // GPS auto-stop grace period.
        cancelGpsAutoStop()

        connectionActive = true
        ConnectionState.setConnectionActive(true)

        if (keepAwake && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "share:connection")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
            ConnectionState.appendLog("wake lock acquired")
        }

        refreshForeground()

        connectionJob = scope.launch {
            // Resolve the server's trusted Ed25519 identity BEFORE we open a
            // socket. Three outcomes, only one of which is "ready to drive":
            //   - Disabled: user turned verification off; driver runs with
            //     a null identity (skips verify, logs a warning each session).
            //   - Resolved(key): driver runs and verifies.
            //   - Unresolvable: error already surfaced, unwind cleanly.
            val identity = when (val r = resolveServerIdentity(host)) {
                IdentityResolution.Disabled -> null
                is IdentityResolution.Resolved -> r.key
                IdentityResolution.Unresolvable -> {
                    connectionActive = false
                    ConnectionState.setConnectionActive(false)
                    releaseWakeLock()
                    if (gpsActive) refreshForeground() else terminate()
                    return@launch
                }
            }

            // Snapshot the relevant settings ONCE - driver lifetime is one
            // session; if the user toggles auto-refresh mid-session, the
            // next reconnect picks it up.
            val sessionSettings = Settings.snapshot(this@ConnectionService)

            // Wrap the application context with the current language preference
            // before handing it to the driver. Application.attachBaseContext only
            // runs at process start - if the user changed language mid-session
            // without restarting the app, the raw applicationContext would still
            // resolve strings into the old locale. This wrap fixes that for
            // every UiError the driver produces this session. Reading directly
            // from LanguagePref (SharedPreferences) so we always get the LATEST
            // committed value, even if the Service started before the change.
            val driverContext = pl.dubba.share.settings.LocaleHelper.applyLocale(
                applicationContext,
                pl.dubba.share.settings.LanguagePref.read(applicationContext).resolveLocale(),
            )

            val d = ConnectionDriver(
                appContext = driverContext,
                host = host,
                port = port,
                urlPrefix = sessionSettings.urlPrefix,
                serverIdentity = identity,
                refreshIdentity = refreshIdentityFor(host, sessionSettings),
                log = { line -> ConnectionState.appendLog(line) },
                pingIntervalMs = pingIntervalMs,
                onAccessKey = { key -> ConnectionState.setAccessKey(key) },
                onFirstPong = { ConnectionState.setPongReceived(true) },
                onSubCount = { count -> ConnectionState.setSubCount(count) },
                onInnerKey = { keyBytes ->
                    // Driver mints the inner-e2ee key the moment it builds the
                    // first handshake frame - the key is a session artifact, born
                    // when the session is. We just wire it into the payload
                    // chokepoint and expose its hex form so the UI can build the
                    // share URL.
                    payloadEncoder = EncryptedPayloadEncoder(PlaintextPayloadEncoder, keyBytes)
                    ConnectionState.setInnerKey(pl.dubba.share.util.Hex.encode(keyBytes))
                },
                netUnstableThresholdSec = { AlertClass.NetUnstable.thresholdSec },
                onNetworkUnstable = { silenceSec ->
                    AlertClass.NetUnstable.fire(this@ConnectionService, silenceSec)
                },
                onNetworkStable = {
                    AlertClass.NetUnstable.resolve(this@ConnectionService)
                },
            )
            driver = d
            try {
                d.run()
            } finally {
                ConnectionState.appendLog("driver exited")
                connectionActive = false
                ConnectionState.setConnectionActive(false)
                driver = null
                releaseWakeLock()
                if (gpsActive) {
                    refreshForeground()
                } else {
                    terminate()
                }
            }
        }
    }

    /**
     * Three-state resolve outcome - matches the cases the start-connection
     * coroutine has to handle: verification opted out, key in hand, or
     * truly stuck (with a UiError already set).
     */
    private sealed class IdentityResolution {
        /** User turned off verification in Settings; driver runs without a check. */
        object Disabled : IdentityResolution()

        /** Cache hit OR successful HTTPS auto-fetch. */
        data class Resolved(val key: ByteArray) : IdentityResolution()

        /** No cached identity and either auto-refresh is off or the fetch failed. */
        object Unresolvable : IdentityResolution()
    }

    /**
     * Resolves the trusted Ed25519 public key for [host] at connect time:
     *
     *   - `verifyServerIdentity` OFF → [IdentityResolution.Disabled].
     *   - Cache hit → [IdentityResolution.Resolved] (driver will also handle
     *     mid-handshake refresh via the callback we install separately).
     *   - Cache miss + auto-refresh ON → HTTPS fetch + persist + per-mode
     *     notification → Resolved. Fetch failures → Unresolvable + UiError.
     *   - Cache miss + auto-refresh OFF → Unresolvable + UiError pointing
     *     at the manual Refresh button.
     */
    private suspend fun resolveServerIdentity(host: String): IdentityResolution {
        val settings = Settings.snapshot(this)
        if (!settings.verifyServerIdentity) return IdentityResolution.Disabled

        // IdentityStore is keyed by the Viewer URL prefix (the entity whose
        // TLS chain we trust), not by the UDP host. `host` stays in error
        // text for user context - it's what they see in the toggle.
        IdentityStore.get(this, settings.urlPrefix)?.let { return IdentityResolution.Resolved(it) }

        if (!settings.autoRefreshServerIdentity) {
            ConnectionState.setLastError(UiError(
                title = getString(R.string.err_identity_not_stored_title),
                detail = getString(R.string.err_identity_not_stored_detail, settings.urlPrefix.ifBlank { host }),
            ))
            return IdentityResolution.Unresolvable
        }

        val fresh = fetchAndStoreIdentity(host, settings, surfaceErrors = true)
        return if (fresh != null) IdentityResolution.Resolved(fresh) else IdentityResolution.Unresolvable
    }

    /**
     * Builds the per-session refresh callback handed to the driver. Called
     * by the driver when its cached identity fails to verify the handshake
     * signature - TLS on the /identity fetch is the trust anchor, so a
     * successful refresh delivers an authentic (possibly rotated) key.
     *
     * Returns null when auto-refresh is off (driver will surface the
     * "auto-refresh disabled" path) or when the HTTPS fetch fails (also
     * surfaced as a UiError so the user sees what happened).
     */
    private fun refreshIdentityFor(
        host: String,
        sessionSettings: Settings.Snapshot,
    ): suspend () -> ByteArray? = {
        if (!sessionSettings.autoRefreshServerIdentity) {
            null
        } else {
            fetchAndStoreIdentity(host, sessionSettings, surfaceErrors = true)
        }
    }

    /**
     * Shared fetch + store + notify logic used by both the initial resolve
     * path and the driver's mid-handshake refresh callback. Returns the
     * fresh pubkey on success, null on any failure (with [surfaceErrors]
     * controlling whether a UiError is set - both call sites currently
     * want errors visible, kept parameterised in case we ever need a
     * background silent refresh).
     */
    private suspend fun fetchAndStoreIdentity(
        host: String,
        settings: Settings.Snapshot,
        surfaceErrors: Boolean,
    ): ByteArray? {
        if (settings.urlPrefix.isBlank()) {
            if (surfaceErrors) {
                ConnectionState.setLastError(UiError(
                    title = getString(R.string.err_identity_cant_fetch_title),
                    detail = getString(R.string.err_identity_cant_fetch_detail, host),
                ))
            }
            return null
        }
        val target = settings.urlPrefix.trimEnd('/') + "/identity"
        ConnectionState.appendLog("fetching identity from $target")
        // Wrap applicationContext with the current language so the inner
        // failure messages from IdentityFetcher match the rest of the UI's
        // locale. Same pattern as the driver wrap above.
        val fetchCtx = pl.dubba.share.settings.LocaleHelper.applyLocale(
            applicationContext,
            pl.dubba.share.settings.LanguagePref.read(applicationContext).resolveLocale(),
        )
        val result = withContext(Dispatchers.IO) {
            IdentityFetcher.fetch(
                context = fetchCtx,
                urlPrefix = settings.urlPrefix,
                trustAllCerts = settings.ignoreSslErrors,
            )
        }
        return when (result) {
            is IdentityFetcher.Result.Success -> {
                IdentityStore.put(this, settings.urlPrefix, result.pubkey)
                val preview = pl.dubba.share.util.Hex.encode(result.pubkey.copyOfRange(0, 8), uppercase = true)
                ConnectionState.appendLog("✓ identity fetched and persisted (pub=0x$preview...)")
                showIdentityRefreshNotice(settings.identityRefreshNotificationMode, result.pubkey)
                result.pubkey
            }
            is IdentityFetcher.Result.HttpFailure -> {
                if (surfaceErrors) {
                    ConnectionState.setLastError(UiError(
                        title = getString(R.string.err_identity_fetch_failed_title),
                        detail = getString(R.string.err_identity_fetch_failed_detail, target, result.message),
                    ))
                }
                null
            }
            is IdentityFetcher.Result.BadResponse -> {
                if (surfaceErrors) {
                    ConnectionState.setLastError(UiError(
                        title = getString(R.string.err_identity_bad_response_title),
                        detail = getString(R.string.err_identity_bad_response_detail, target, result.message),
                    ))
                }
                null
            }
        }
    }

    /**
     * Surface an identity-refresh event per the user's notification-mode pref.
     *
     *   - MUTE: nothing (debug log already records it via appendLog).
     *   - TOAST: brief on-screen toast. Must be posted to the main looper.
     *   - POPUP: high-priority heads-up notification - the user can dismiss
     *     it but it auto-cancels on tap. Reusing the shared event channel
     *     keeps it grouped with the regular alerts.
     */
    private fun showIdentityRefreshNotice(mode: IdentityRefreshNotificationMode, pubkey: ByteArray) {
        // Delegated to a shared helper so the manual Refresh button in the
        // identity screen routes through the exact same code path - flipping
        // the mode to MUTE silences both surfaces consistently.
        IdentityRefreshNotifier.notify(this, mode, pubkey)
    }

    private fun requestConnectionGracefulStop() {
        val d = driver
        val j = connectionJob
        if (d == null || j == null || !j.isActive) return
        d.requestGracefulDisconnect()
        // Watchdog: if the driver doesn't exit within the timeout, hard-close
        // the socket so its run() throws and falls through to its finally.
        scope.launch {
            val finishedCleanly = withTimeoutOrNull(GRACEFUL_SHUTDOWN_TIMEOUT_MS) {
                j.join()
                true
            }
            if (finishedCleanly != true) {
                d.stop()
                j.join()
            }
        }
    }

    // --- GPS lifecycle ---

    private fun startGps() {
        if (gpsActive) return
        if (!GpsManager.hasFineLocationPermission(this)) {
            ConnectionState.appendLog("✗ GPS start failed - no precise location permission")
            return
        }
        if (!GpsManager.start(applicationContext, minTimeMs = currentPollingIntervalMs)) {
            ConnectionState.appendLog("✗ GPS start failed (provider unavailable?)")
            return
        }
        // User explicitly turning GPS on cancels any pending auto-stop.
        cancelGpsAutoStop()
        gpsActive = true
        directionResolver.reset()
        CompassManager.start(this)
        refreshForeground()
        ConnectionState.appendLog("GPS subscription started @ ${currentPollingIntervalMs}ms")

        gpsCollectionJob = scope.launch {
            // Tell any current viewer "we're acquiring." Will also be repeated
            // by the heartbeat below for late-arriving viewers.
            postPayload(SharePayload.Meta(GpsStatus.ACQUIRING))

            // While GPS is on but no fix yet, resend "acquiring" periodically
            // so a viewer that subscribes mid-acquisition learns the state
            // without having to wait for an actual fix. Cancelled when the
            // outer gpsCollectionJob is cancelled.
            val heartbeat = launch {
                while (isActive) {
                    delay(META_HEARTBEAT_INTERVAL_MS)
                    if (GpsManager.fix.value == null) {
                        postPayload(SharePayload.Meta(GpsStatus.ACQUIRING))
                    }
                }
            }

            try {
                // collectLatest drops in-flight handling if a newer fix arrives
                // mid-encode - we always send the freshest one. CRITICAL: this
                // lambda must NOT suspend on anything that can take longer
                // than the GPS interval, or the next fix will cancel it
                // mid-flight and the payload never reaches the wire. That's
                // why useCompassOnly is read from the cached @Volatile,
                // not via Settings.snapshot. payloadEncoder.encode + the
                // driver's outboundData.trySend are both non-suspending.
                GpsManager.fix.collectLatest { fix ->
                    if (fix == null) {
                        ConnectionState.appendLog("collector: fix==null, skipping")
                        return@collectLatest
                    }
                    val direction = directionResolver.resolve(
                        fix = fix,
                        useCompassOnly = currentUseCompassOnly,
                        compassBearing = CompassManager.bearing.value,
                    )
                    ConnectionState.appendLog("collector: → postPayload (dir=${direction?.let { "%.0f".format(it) } ?: "?"})")
                    postPayload(SharePayload.Position(fix, direction))
                }
            } finally {
                heartbeat.cancel()
            }
        }
    }

    private fun stopGps() {
        if (!gpsActive) return
        cancelGpsAutoStop()
        // Tell viewers we're gone before tearing down. Posted to the driver's
        // outbound queue; will go out on the wire as the sender drains it.
        postPayload(SharePayload.Meta(GpsStatus.OFF))
        gpsCollectionJob?.cancel()
        gpsCollectionJob = null
        GpsManager.stop()
        CompassManager.stop()
        directionResolver.reset()
        gpsActive = false
        ConnectionState.appendLog("GPS subscription stopped")
        if (connectionActive) {
            refreshForeground()
        } else {
            terminate()
        }
    }

    // --- Share timer ---

    /**
     * Starts (or restarts) the share countdown from [Settings.Snapshot.shareTimeMin].
     * Cancels any existing timer first. While running, ticks once per second to
     * refresh the foreground notification's mm:ss display. On expiry: simulates
     * the user hitting the Server toggle off (graceful disconnect) and schedules
     * a GPS auto-stop after [GPS_AUTO_STOP_GRACE_MS], which is cancelled if the
     * user takes any explicit toggle action in the meantime.
     */
    private fun startShareTimer() {
        shareTimerJob?.cancel()
        shareTimerJob = scope.launch {
            val shareTimeMin = Settings.snapshot(this@ConnectionService).shareTimeMin
            // INF sentinel: no auto-stop. Clear the displayed end-time, drop
            // any in-flight GPS grace job, log, and bail. The job itself
            // returns immediately, so a later finite dial change re-enters
            // here via the settings observer and starts a real countdown.
            if (shareTimeMin >= Config.SHARE_TIME_INFINITE_SENTINEL) {
                ConnectionState.setShareEndTimeMs(null)
                cancelGpsAutoStop()
                ConnectionState.appendLog("share timer disabled (∞)")
                refreshForeground()
                return@launch
            }
            val endMs = System.currentTimeMillis() + shareTimeMin * 60_000L
            ConnectionState.setShareEndTimeMs(endMs)
            ConnectionState.appendLog("share timer started - $shareTimeMin min")

            while (isActive) {
                val now = System.currentTimeMillis()
                if (now >= endMs) break
                refreshForeground()
                delay(1_000)
            }

            // Expiry path.
            ConnectionState.setShareEndTimeMs(null)
            ConnectionState.appendLog("share time expired, stopping connection")
            AlertClass.AutoStop.fire(this@ConnectionService)
            requestConnectionGracefulStop()

            // 30 s grace before the GPS toggle is also flipped off.
            cancelGpsAutoStop()
            gpsAutoStopJob = scope.launch {
                delay(GPS_AUTO_STOP_GRACE_MS)
                if (!connectionActive && gpsActive) {
                    ConnectionState.appendLog("grace period ended, stopping GPS")
                    stopGps()
                }
            }
        }
    }

    private fun stopShareTimer() {
        shareTimerJob?.cancel()
        shareTimerJob = null
        ConnectionState.setShareEndTimeMs(null)
    }

    private fun cancelGpsAutoStop() {
        gpsAutoStopJob?.cancel()
        gpsAutoStopJob = null
    }

    private fun postPayload(payload: SharePayload) {
        val kind = when (payload) {
            is SharePayload.Position -> "Position"
            is SharePayload.Meta -> "Meta(${payload.gps.code})"
        }
        // Encode can technically throw - AES-GCM doesn't in practice, but if
        // anything ever did, the exception would tear down the gpsCollectionJob
        // silently. Wrap defensively.
        val bytes = try {
            payloadEncoder.encode(payload)
        } catch (e: Throwable) {
            ConnectionState.appendLog("⚠ encode($kind) threw: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val d = driver
        if (d == null) {
            ConnectionState.appendLog("⚠ postPayload($kind): driver is null - dropping ${bytes.size}B")
            return
        }
        val accepted = d.postData(bytes)
        if (!accepted) {
            ConnectionState.appendLog("⚠ postPayload($kind): outbound channel full - dropping ${bytes.size}B")
        }
    }

    // --- Foreground / notification ---

    private fun refreshForeground() {
        val notif = buildNotification(statusText())
        // The 3-arg startForeground (with foreground-service type) lives at
        // API 29+. On Pie (API 28) only the 2-arg form exists; the manifest's
        // `android:foregroundServiceType` attribute is silently ignored at
        // that level. Both branches are functionally equivalent to the OS.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, computeForegroundType())
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun computeForegroundType(): Int {
        var t = 0
        if (gpsActive) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (connectionActive) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // Fallback for the transient "starting up" state - must declare something.
        if (t == 0) t = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return t
    }

    private fun statusText(): String {
        val base = when {
            connectionActive && gpsActive -> getString(R.string.service_status_sharing)
            connectionActive -> getString(R.string.service_status_connected)
            gpsActive -> getString(R.string.service_status_gps_only)
            else -> getString(R.string.service_status_idle)
        }
        val end = ConnectionState.shareEndTimeMs.value
        return if (end != null) {
            val remaining = (end - System.currentTimeMillis()).coerceAtLeast(0L)
            "$base · ${formatMmSs(remaining)} left"
        } else {
            base
        }
    }

    private fun formatMmSs(ms: Long): String {
        val totalSec = ms / 1000L
        return "%d:%02d".format(totalSec / 60L, totalSec % 60L)
    }

    private fun terminate() {
        if (!terminated.compareAndSet(false, true)) return
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent share session - UDP connection and/or GPS"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
        // High-importance heads-up channel shared by every AlertClass - each
        // class posts under a stable AlertClass.notificationId on this channel
        // so they don't stomp each other but per-channel mute / sound / etc.
        // is unified.
        if (mgr.getNotificationChannel(AlertClass.EVENT_CHANNEL_ID) == null) {
            val ch = NotificationChannel(AlertClass.EVENT_CHANNEL_ID, "Errors & alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Connection errors and share-finished alerts"
                enableVibration(true)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    /**
     * Push the latest [Settings.Snapshot]'s alert configs into the singleton
     * [AlertClass] objects. Called once eagerly in [onCreate] (via runBlocking
     * snapshot) and on every subsequent Settings.observe emission.
     */
    private fun applyAlertSettings(s: Settings.Snapshot) {
        for (c in AlertClass.all) c.config = s.alertOf(c)
        AlertClass.NetUnstable.thresholdSec = s.netUnstableThresholdSec
    }

    private fun buildNotification(status: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAllPi = PendingIntent.getService(
            this, 1,
            Intent(this, ConnectionService::class.java).apply { action = ACTION_STOP_ALL },
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Modern Notification.Action.Builder path - replaces the deprecated
        // 3-arg addAction(int, CharSequence, PendingIntent). Same effect at
        // runtime, no deprecation noise in the lint output.
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.service_notif_action_stop),
            stopAllPi,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.service_notif_title))
            .setContentText(status)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    companion object {
        const val ACTION_START = "pl.dubba.share.action.START"
        const val ACTION_STOP = "pl.dubba.share.action.STOP"
        const val ACTION_GPS_START = "pl.dubba.share.action.GPS_START"
        const val ACTION_GPS_STOP = "pl.dubba.share.action.GPS_STOP"
        const val ACTION_STOP_ALL = "pl.dubba.share.action.STOP_ALL"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_KEEP_AWAKE = "keep_awake"
        const val EXTRA_PING_INTERVAL_MS = "ping_interval_ms"
        private const val CHANNEL_ID = "share_connection"
        // Foreground-service notification - unrelated to alerts.
        private const val NOTIFICATION_ID = 1
        // IDs 2..5 are owned by AlertClass children. ID 6 (identity-refresh
        // pop-up) lives in IdentityRefreshNotifier - kept reserved.
        // Per-alert channel ID + per-class notification IDs live on AlertClass now.
        private const val GRACEFUL_SHUTDOWN_TIMEOUT_MS = 2_000L
        private const val META_HEARTBEAT_INTERVAL_MS = 5_000L
        private const val GPS_AUTO_STOP_GRACE_MS = 30_000L
    }
}

/**
 * Stateful helper that picks the best available direction estimate for a
 * given GPS fix, with two modes (driven by the user's `useCompassOnly`
 * setting):
 *
 *  - **Preciser mode** (default): when the device is moving fast enough
 *    (speed > [movingSpeedThresholdMps]) AND the position has actually
 *    changed since the last fix, compute the bearing from last → current
 *    using the spherical-Earth formula. Cache it as the "last precise"
 *    direction. When stationary, reuse the cached precise direction. If no
 *    precise direction is available yet, fall through to compass.
 *  - **Compass-only mode**: skip the GPS-derived path entirely and always
 *    use the compass reading.
 *
 * Returns null when no source can produce a value (e.g. compass-only mode
 * but the sensor hasn't settled yet).
 */
private class DirectionResolver {

    // 2 km/h in m/s - below this we don't trust GPS-derived bearing because
    // position noise dominates real motion.
    private val movingSpeedThresholdMps: Float = 2f / 3.6f

    private var previousFix: GpsFix? = null
    private var lastPreciseDirectionDeg: Float? = null

    fun reset() {
        previousFix = null
        lastPreciseDirectionDeg = null
    }

    fun resolve(fix: GpsFix, useCompassOnly: Boolean, compassBearing: Float?): Float? {
        if (useCompassOnly) {
            previousFix = fix
            // Don't update lastPreciseDirectionDeg - we want it stale-but-valid
            // if user flips compass-only off again later.
            return compassBearing
        }

        val speed = fix.speedMps ?: 0f
        val prev = previousFix
        if (speed > movingSpeedThresholdMps && prev != null &&
            (prev.lat != fix.lat || prev.lon != fix.lon)
        ) {
            val bearing = bearingDegrees(prev.lat, prev.lon, fix.lat, fix.lon)
            lastPreciseDirectionDeg = bearing
            previousFix = fix
            return bearing
        }

        previousFix = fix
        return lastPreciseDirectionDeg ?: compassBearing
    }
}

/**
 * Initial bearing (forward azimuth) from point 1 → point 2 on a sphere, in
 * degrees clockwise from north. Standard great-circle formula.
 */
private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLambda = Math.toRadians(lon2 - lon1)
    val y = sin(dLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
    val theta = atan2(y, x)
    return ((Math.toDegrees(theta) + 360.0) % 360.0).toFloat()
}
