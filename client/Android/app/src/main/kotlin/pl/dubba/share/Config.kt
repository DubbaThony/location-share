package pl.dubba.share

import pl.dubba.share.notify.VibrationPattern

/**
 * App-level tunables. The single place to change deploy-time defaults - server
 * address, timing knobs, alert policy, etc. NOT for protocol spec constants
 * (PROTO_VERSION, nonce/tag sizes) - those live in the protocol module because
 * changing them changes the wire format.
 *
 * Most values here are sourced from `BuildConfig` fields generated at build
 * time by the gradle script reading `client/Android.env` (or
 * `client/Android.env.sample` as fallback). Forkers customize their fork's
 * prefilled defaults and alert policy by editing that env file, NOT by
 * touching this Kotlin source. See `app/build.gradle.kts` for the gradle
 * wiring and `client/Android.env.sample` for the schema + per-knob docs.
 *
 * What stays as Kotlin `const val`:
 *   - structural UI knobs that aren't safely editable from text config
 *     (SHARE_TIME_DETENTS_MIN / POLLING_INTERVAL_DETENTS_MS arrays)
 *   - sentinel values (SHARE_TIME_INFINITE_SENTINEL)
 *   - enum-typed defaults whose only sensible value is the enum literal
 *     (DEFAULT_SETTING_LANGUAGE, DEFAULT_SETTING_IDENTITY_REFRESH_NOTIFICATION_MODE)
 *
 * What moved to BuildConfig:
 *   - everything else with a primitive type (Int / Long / Boolean / String)
 *   - the four per-alert-class VibrationPattern defaults, as enum names
 *     parsed via `VibrationPattern.valueOf(...)` at access time
 *
 * `BuildConfig.X` is a static final in the generated class; reading it from
 * a Kotlin `val` is folded by the JIT, same effective cost as a `const val`.
 * Bad values (typos in a Boolean, invalid VibrationPattern enum name, etc.)
 * throw at first read - which for an Application-scoped object is at app
 * startup, the right time to surface a build-time mis-config.
 */
object Config {
    // --- Server connection ---
    val DEFAULT_SERVER_HOST: String = BuildConfig.DEFAULT_SERVER_HOST
    val DEFAULT_SERVER_PORT: Int = BuildConfig.DEFAULT_SERVER_PORT.toInt()

    /**
     * URL prefix the viewer is served from. Combined with the server-issued
     * access key and the per-session inner-e2ee key to produce a shareable
     * link of shape `<prefix>?id=<accessKey>#<encKey>` - access key as a
     * query parameter so changing it triggers a real page reload, encryption
     * key in the fragment so it's never sent to the server.
     */
    val DEFAULT_URL_PREFIX: String = BuildConfig.DEFAULT_URL_PREFIX

    // --- Timing ---
    /** How long to block on a single UDP receive before giving up and looping. */
    val RECV_TIMEOUT_MS: Int = BuildConfig.RECV_TIMEOUT_MS.toInt()

    /**
     * Heartbeat cadence. Keep below typical NAT UDP eviction (~30s). Also
     * determines how fast the viewer learns that the sender is gone (since
     * the subscriber-count + pong responses ride this same cycle), so a
     * tighter cadence makes the viewer feel more "live" at the cost of more
     * battery on the sender.
     */
    val PING_INTERVAL_MS: Long = BuildConfig.PING_INTERVAL_MS.toLong()

    /** How many times to re-send Bye on graceful disconnect (UDP loss tolerance). */
    val BYE_BURST_SIZE: Int = BuildConfig.BYE_BURST_SIZE.toInt()

    // --- Share session defaults ---

    /**
     * Sentinel for the "infinite" share-time detent - no auto-stop. Chosen as
     * Int.MAX_VALUE so that any equality / >= check works without a separate
     * nullable. Stored verbatim in DataStore and surfaced in the UI as a red
     * "∞" label so the user notices they're disabling the safety net.
     *
     * Stays in Kotlin (not env) because it's a structural sentinel referenced
     * by SHARE_TIME_DETENTS_MIN, not a user-tunable value.
     */
    const val SHARE_TIME_INFINITE_SENTINEL: Int = Int.MAX_VALUE

    /**
     * Detent values for the share-time encoder (minutes). Last entry is the
     * infinity sentinel. Structural UI knob: changing this changes the dial
     * granularity, not the kind of thing forkers should tweak via text
     * config; stays in Kotlin.
     */
    val SHARE_TIME_DETENTS_MIN: IntArray = intArrayOf(
        1, 5, 15, 30, 60, 120, 240, 480, 720, 1440, SHARE_TIME_INFINITE_SENTINEL,
    )

    /** Detent values for the polling-rate encoder (interval in ms). Same rationale: structural. */
    val POLLING_INTERVAL_DETENTS_MS: LongArray = longArrayOf(
        60_000, 30_000, 15_000, 5_000, 2_000, 1_000, 500, 200, 100,
    )

    val DEFAULT_SHARE_TIME_MIN: Int = BuildConfig.DEFAULT_SHARE_TIME_MIN.toInt()
    val DEFAULT_POLLING_INTERVAL_MS: Long = BuildConfig.DEFAULT_POLLING_INTERVAL_MS.toLong()

    // --- Notification class defaults ---------------------------------------
    //
    // Each AlertClass child pulls its baseline values from these. Names are
    // intentionally verbose ("DEFAULT_SETTING_NOTIFICATION_<class>_<field>")
    // so grepping for "DEFAULT_SETTING_NOTIFICATION_" yields the whole policy
    // in one shot. VibrationPattern values are parsed from the env's enum
    // name via VibrationPattern.valueOf().

    // Connection-error alert class
    val DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_IS_ENABLED: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_IS_ENABLED.toBooleanStrict()
    val DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_NOTIFY: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_NOTIFY.toBooleanStrict()
    val DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_VIBRATION: VibrationPattern = VibrationPattern.valueOf(BuildConfig.DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_VIBRATION)
    val DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_AUTO_CANCEL: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_AUTO_CANCEL.toBooleanStrict()

    // GPS lock-lost alert class
    val DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_IS_ENABLED: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_IS_ENABLED.toBooleanStrict()
    val DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_NOTIFY: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_NOTIFY.toBooleanStrict()
    val DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_VIBRATION: VibrationPattern = VibrationPattern.valueOf(BuildConfig.DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_VIBRATION)
    val DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_AUTO_CANCEL: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_AUTO_CANCEL.toBooleanStrict()

    // Share-ended (auto-stop) alert class
    val DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_IS_ENABLED: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_IS_ENABLED.toBooleanStrict()
    val DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_NOTIFY: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_NOTIFY.toBooleanStrict()
    val DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_VIBRATION: VibrationPattern = VibrationPattern.valueOf(BuildConfig.DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_VIBRATION)
    val DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_AUTO_CANCEL: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_AUTO_CANCEL.toBooleanStrict()

    // Network-instability alert class (default OFF - opt-in)
    val DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_IS_ENABLED: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_IS_ENABLED.toBooleanStrict()
    val DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_NOTIFY: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_NOTIFY.toBooleanStrict()
    val DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_VIBRATION: VibrationPattern = VibrationPattern.valueOf(BuildConfig.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_VIBRATION)
    val DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_AUTO_CANCEL: Boolean = BuildConfig.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_AUTO_CANCEL.toBooleanStrict()
    val DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_THRESHOLD_SEC: Int = BuildConfig.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_THRESHOLD_SEC.toInt()

    // --- Advanced / dev-y settings gating ---
    // Off by default. Flips on the Advanced section at the bottom of
    // Settings, which exposes power-user knobs (ping interval, viewer
    // ?debug=1, HTTP-server allow-list).
    val DEFAULT_SETTING_SHOW_ADVANCED: Boolean = BuildConfig.DEFAULT_SETTING_SHOW_ADVANCED.toBooleanStrict()
    // Off by default. Required to be on for the connect flow to accept an
    // http:// URL prefix - otherwise HTTPS is enforced. Lives under Advanced
    // because consciously running over HTTP turns identity verification
    // into security theatre.
    val DEFAULT_SETTING_ALLOW_HTTP_SERVER: Boolean = BuildConfig.DEFAULT_SETTING_ALLOW_HTTP_SERVER.toBooleanStrict()
    // Off by default. When on, HTTPS identity fetches accept ANY server
    // certificate. Useful for self-signed certs on a private deployment,
    // dangerous everywhere else.
    val DEFAULT_SETTING_IGNORE_SSL_ERRORS: Boolean = BuildConfig.DEFAULT_SETTING_IGNORE_SSL_ERRORS.toBooleanStrict()

    // --- i18n ---
    // AUTO follows the system locale (with English fallback when the system
    // language isn't one we support). Stays in Kotlin: the enum-typed value
    // with one sensible default (AUTO) doesn't gain much from being in env,
    // and serializing/parsing it adds friction for no practical fork benefit.
    val DEFAULT_SETTING_LANGUAGE: pl.dubba.share.settings.Language =
        pl.dubba.share.settings.Language.AUTO

    // --- Server identity / MITM mitigation defaults ---
    // On by default: this is the only thing standing between a UDP-MITM
    // attacker and the inner-encrypted session, so anything but on-by-default
    // would be papering over the threat model.
    val DEFAULT_SETTING_VERIFY_SERVER_IDENTITY: Boolean = BuildConfig.DEFAULT_SETTING_VERIFY_SERVER_IDENTITY.toBooleanStrict()
    // On by default: opportunistic first-fetch over HTTPS keeps the happy
    // path zero-tap.
    val DEFAULT_SETTING_AUTO_REFRESH_SERVER_IDENTITY: Boolean = BuildConfig.DEFAULT_SETTING_AUTO_REFRESH_SERVER_IDENTITY.toBooleanStrict()
    // Stays in Kotlin: same rationale as DEFAULT_SETTING_LANGUAGE - enum-typed
    // default with one sensible value, not worth the parsing dance for forks.
    val DEFAULT_SETTING_IDENTITY_REFRESH_NOTIFICATION_MODE:
        pl.dubba.share.net.identity.IdentityRefreshNotificationMode =
            pl.dubba.share.net.identity.IdentityRefreshNotificationMode.TOAST
}
