package pl.dubba.share.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import pl.dubba.share.Config
import pl.dubba.share.MainActivity
import pl.dubba.share.R

/**
 * Base for the per-alert-class singletons.
 *
 * Each subclass is a Kotlin `object` (singleton) and declares its own
 * `fire(...)` method with whatever signature its trigger site naturally
 * provides - `fire(err: UiError)`, `fire(silenceSec: Int)`, `fire()`.
 * Behavior intentionally NOT abstracted into a uniform `shouldFire()` /
 * `justFired()` pair; the trigger sites already have all the context they
 * need to decide *when*, and forcing them through a uniform API would only
 * shuffle plumbing around.
 *
 * What lives on the base:
 *   - shared config (vibration + notify + autoCancel + master enable)
 *   - shared post + cancel mechanics
 *
 * What lives on the child:
 *   - the public `fire(...)` overload (specific args, formats its own title/detail)
 *   - optional class-specific live params held as `@Volatile var` fields
 *     (e.g. [NetUnstableAlert.thresholdSec])
 *
 * Config is mutated through [config] from a single point in
 * `ConnectionService.onCreate` (via the Settings observer), so no
 * synchronization is needed beyond `@Volatile`.
 */
sealed class AlertClass(
    /** Settings key prefix - `alert_<storageKey>_<field>` in DataStore. */
    val storageKey: String,
    /** Stable system-notification ID for this class (one notification at a time per class). */
    val notificationId: Int,
    /** Notification-channel ID. All current classes share one heads-up channel. */
    val channelId: String,
    /** Localized display name shown in the Notifications list. Use [displayName] with a Context. */
    val displayNameRes: Int,
    /** Notification small-icon drawable resource (from android.R.drawable). */
    val smallIconRes: Int,
    /** Defaults - also drives the "Reset to defaults" path. */
    val defaultConfig: AlertConfig,
) {

    fun displayName(ctx: Context): String = ctx.getString(displayNameRes)

    /**
     * Live config - written by the [Settings.observe] collector in the
     * service, read on every [fire] / [resolve]. `@Volatile` because reads
     * and writes happen on different threads (UI / service / driver).
     */
    @Volatile
    var config: AlertConfig = defaultConfig

    /**
     * Subclasses with extra knobs (currently only [NetUnstableAlert]) return
     * a non-empty list. The AlertEditScreen renders them after the standard
     * controls. Kept as a simple sealed hierarchy of [ExtraParam] descriptors
     * so the screen can pattern-match.
     */
    open fun extraParams(): List<ExtraParam> = emptyList()

    /**
     * Shared "actually do the alert" core. Each subclass's public `fire(...)`
     * formats its own title/detail then delegates here. Returns early if the
     * master switch is off.
     */
    protected fun postCore(ctx: Context, title: String, detail: String) {
        val cfg = config
        if (!cfg.enabled) return
        Vibration.trigger(ctx, cfg.vibration)
        if (!cfg.notify) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        val openPi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, channelId)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .build()
        mgr.notify(notificationId, n)
    }

    /**
     * Called when the underlying condition that may have caused this alert
     * to fire has gone away (lock returns, pong returns, share restarts,
     * user dismisses dialog, ...). Cancels the system notification iff the
     * user has [AlertConfig.autoCancelOnResolve] on and the master is on.
     * Cheap no-op otherwise.
     */
    fun resolve(ctx: Context) {
        val cfg = config
        if (!cfg.enabled || !cfg.autoCancelOnResolve) return
        ctx.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
    }

    /**
     * Returns a one-line summary for the Notifications list row. Examples:
     *   - "Off"
     *   - "On · 3×short · push"
     *   - "On · 3×short · silent"
     */
    fun tldr(ctx: Context): String {
        val cfg = config
        if (!cfg.enabled) return ctx.getString(R.string.tldr_off)
        val vib = cfg.vibration.displayName(ctx).lowercase()
        val push = ctx.getString(if (cfg.notify) R.string.tldr_push else R.string.tldr_silent)
        return ctx.getString(R.string.tldr_on_template, vib, push)
    }

    // --- Class instances ----------------------------------------------------

    /** "Connection error" - server unreachable, handshake failed, etc. */
    object ConnError : AlertClass(
        storageKey = "conn_error",
        notificationId = 2,
        channelId = EVENT_CHANNEL_ID,
        displayNameRes = R.string.alert_conn_error_name,
        smallIconRes = android.R.drawable.stat_notify_error,
        defaultConfig = AlertConfig(
            enabled = Config.DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_IS_ENABLED,
            notify = Config.DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_NOTIFY,
            vibration = Config.DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_VIBRATION,
            autoCancelOnResolve = Config.DEFAULT_SETTING_NOTIFICATION_CONN_ERROR_AUTO_CANCEL,
        ),
    ) {
        /** Driver / service caught a UiError. Fire title + detail from it. */
        fun fire(ctx: Context, title: String, detail: String) = postCore(ctx, title, detail)
    }

    /** "GPS lock lost" - chip stopped delivering fresh fixes mid-session. */
    object LockLost : AlertClass(
        storageKey = "lock_lost",
        notificationId = 4,
        channelId = EVENT_CHANNEL_ID,
        displayNameRes = R.string.alert_lock_lost_name,
        smallIconRes = android.R.drawable.stat_notify_error,
        defaultConfig = AlertConfig(
            enabled = Config.DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_IS_ENABLED,
            notify = Config.DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_NOTIFY,
            vibration = Config.DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_VIBRATION,
            autoCancelOnResolve = Config.DEFAULT_SETTING_NOTIFICATION_LOCK_LOST_AUTO_CANCEL,
        ),
    ) {
        fun fire(ctx: Context) = postCore(
            ctx,
            title = ctx.getString(R.string.alert_lock_lost_title),
            detail = ctx.getString(R.string.alert_lock_lost_detail),
        )

        /**
         * Variant for cases where the cause is more specific than "chip went
         * quiet" - currently the system-wide location toggle being switched
         * off mid-session. Same notificationId so this overwrites any
         * already-posted generic LockLost, no double-buzz issue.
         */
        fun fire(ctx: Context, detail: String) = postCore(
            ctx,
            title = ctx.getString(R.string.alert_lock_lost_title),
            detail = detail,
        )
    }

    /** "Share ended" - the share timer expired and graceful auto-disconnect ran. */
    object AutoStop : AlertClass(
        storageKey = "auto_stop",
        notificationId = 3,
        channelId = EVENT_CHANNEL_ID,
        displayNameRes = R.string.alert_auto_stop_name,
        smallIconRes = android.R.drawable.ic_menu_mylocation,
        defaultConfig = AlertConfig(
            enabled = Config.DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_IS_ENABLED,
            notify = Config.DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_NOTIFY,
            vibration = Config.DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_VIBRATION,
            autoCancelOnResolve = Config.DEFAULT_SETTING_NOTIFICATION_AUTO_STOP_AUTO_CANCEL,
        ),
    ) {
        fun fire(ctx: Context) = postCore(
            ctx,
            title = ctx.getString(R.string.alert_auto_stop_title),
            detail = ctx.getString(R.string.alert_auto_stop_detail),
        )
    }

    /** "Network unstable" - silence threshold tripped while protocol still retries. */
    object NetUnstable : AlertClass(
        storageKey = "net_unstable",
        notificationId = 5,
        channelId = EVENT_CHANNEL_ID,
        displayNameRes = R.string.alert_net_unstable_name,
        smallIconRes = android.R.drawable.stat_notify_error,
        defaultConfig = AlertConfig(
            enabled = Config.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_IS_ENABLED,
            notify = Config.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_NOTIFY,
            vibration = Config.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_VIBRATION,
            autoCancelOnResolve = Config.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_AUTO_CANCEL,
        ),
    ) {
        /**
         * Class-specific live param: seconds of silence before the alert fires.
         * Read by the driver's ping loop, written by the Settings observer in
         * the service. `@Volatile` for the cross-thread read.
         */
        @Volatile
        var thresholdSec: Int = Config.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_THRESHOLD_SEC

        override fun extraParams(): List<ExtraParam> = listOf(
            ExtraParam.IntSeconds(
                storageKey = "threshold_sec",
                labelRes = R.string.alert_net_unstable_extra_label,
                helpTextRes = R.string.alert_net_unstable_extra_help,
                defaultValue = Config.DEFAULT_SETTING_NOTIFICATION_NET_UNSTABLE_THRESHOLD_SEC,
                range = 5..3600,
            ),
        )

        fun fire(ctx: Context, silenceSec: Int) = postCore(
            ctx,
            title = ctx.getString(R.string.alert_net_unstable_title),
            detail = ctx.getString(R.string.alert_net_unstable_detail, silenceSec),
        )
    }

    companion object {
        /** Shared heads-up channel for all alerts (created in ConnectionService.ensureChannel). */
        const val EVENT_CHANNEL_ID = "share_events"

        /** All instances in stable order (the order shown in the Notifications list). */
        val all: List<AlertClass> = listOf(ConnError, LockLost, AutoStop, NetUnstable)
    }
}

/**
 * Descriptor for a class-specific extra knob, used by AlertEditScreen to
 * render the right UI control. Each subclass returns a list of these from
 * [AlertClass.extraParams]; the screen pattern-matches and renders.
 */
sealed class ExtraParam(val storageKey: String, val labelRes: Int, val helpTextRes: Int) {
    /** An integer-in-range, surfaced as a numeric text field. */
    class IntSeconds(
        storageKey: String,
        labelRes: Int,
        helpTextRes: Int,
        val defaultValue: Int,
        val range: IntRange,
    ) : ExtraParam(storageKey, labelRes, helpTextRes)
}
