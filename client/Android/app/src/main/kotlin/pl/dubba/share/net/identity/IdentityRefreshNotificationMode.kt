package pl.dubba.share.net.identity

import android.content.Context
import pl.dubba.share.R

/**
 * How to surface identity-refresh events (first fetch on a new server,
 * manual refresh tap, etc.) to the user. The default is [TOAST] - visible
 * enough to notice but not blocking.
 *
 * Stored by [name] in DataStore via [parse]'s round-trip; the localized
 * label is fetched on demand via [displayName] so it tracks the active
 * locale.
 */
enum class IdentityRefreshNotificationMode(val displayNameRes: Int) {
    MUTE(R.string.identity_refresh_mode_mute),
    TOAST(R.string.identity_refresh_mode_toast),
    POPUP(R.string.identity_refresh_mode_popup);

    fun displayName(ctx: Context): String = ctx.getString(displayNameRes)

    companion object {
        /** Lenient parse - unknown / null names fall back to [default]. */
        fun parse(name: String?, default: IdentityRefreshNotificationMode): IdentityRefreshNotificationMode =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: default
    }
}
