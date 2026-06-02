package pl.dubba.share.net.identity

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import pl.dubba.share.R
import pl.dubba.share.notify.AlertClass

/**
 * Single point that surfaces a "server identity refreshed" event per the
 * user's [IdentityRefreshNotificationMode] setting. Used by both the
 * background path (ConnectionService.fetchAndStoreIdentity, after the
 * driver pulls a fresh /identity) and the manual path (the Refresh
 * button in the identity screen).
 *
 * Centralising means the picker setting actually controls every surface
 * the user can see - flipping it to MUTE silences the manual button too,
 * not just background refreshes.
 */
object IdentityRefreshNotifier {

    /**
     * Notification ID for POPUP mode. Stable so subsequent refreshes
     * overwrite the previous notification rather than stacking up.
     */
    private const val NOTIF_ID = 6

    /**
     * Posts the appropriate feedback for a freshly-loaded identity:
     *
     *   - MUTE: nothing visible
     *   - TOAST: brief on-screen toast (main-looper posted, so caller can
     *     be on any thread)
     *   - POPUP: heads-up notification on the shared events channel
     */
    fun notify(
        context: Context,
        mode: IdentityRefreshNotificationMode,
        pubkey: ByteArray,
    ) {
        if (mode == IdentityRefreshNotificationMode.MUTE) return
        val preview = pl.dubba.share.util.Hex.encode(pubkey.copyOfRange(0, 8), uppercase = true)
        val fullHex = pl.dubba.share.util.Hex.encode(pubkey, uppercase = true)
        when (mode) {
            IdentityRefreshNotificationMode.TOAST -> {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context.applicationContext,
                        context.getString(R.string.identity_refresh_toast_success, preview),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            IdentityRefreshNotificationMode.POPUP -> {
                val n = Notification.Builder(context, AlertClass.EVENT_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentTitle(context.getString(R.string.identity_refresh_notif_title))
                    .setContentText(context.getString(R.string.identity_refresh_notif_content, preview))
                    .setStyle(
                        Notification.BigTextStyle()
                            .bigText(context.getString(R.string.identity_refresh_notif_full, fullHex)),
                    )
                    .setAutoCancel(true)
                    .build()
                context.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
            }
            IdentityRefreshNotificationMode.MUTE -> Unit // filtered above
        }
    }
}
