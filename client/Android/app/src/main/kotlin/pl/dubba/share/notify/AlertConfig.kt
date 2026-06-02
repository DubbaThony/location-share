package pl.dubba.share.notify

/**
 * The four knobs every alert class shares. Class-specific parameters (e.g.
 * the network-instability silence threshold) live on the [AlertClass] child
 * objects directly, not in here - they vary per class and would force this
 * shape into a typed map otherwise.
 *
 * Persisted via flat-prefix keys in DataStore: `alert_<storageKey>_enabled`,
 * `alert_<storageKey>_notify`, etc. See [Settings].
 */
data class AlertConfig(
    /** Master gate. When false, [AlertClass.fire] is a no-op for this class. */
    val enabled: Boolean,
    /** Whether to post a system notification (heads-up). Independent of [vibration]. */
    val notify: Boolean,
    /** Vibration pattern. [VibrationPattern.NONE] disables vibration without disabling the class. */
    val vibration: VibrationPattern,
    /**
     * If true, [AlertClass.resolve] cancels the in-flight notification when
     * the underlying condition that triggered it goes away (lock returns,
     * pong returns, user starts a new session, etc.). Mostly relevant for
     * transient alerts; harmless for one-shot ones.
     */
    val autoCancelOnResolve: Boolean,
)
