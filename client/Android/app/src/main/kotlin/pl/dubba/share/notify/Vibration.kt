package pl.dubba.share.notify

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import pl.dubba.share.R

/**
 * The fixed set of patterns the user can pick for "session finished" /
 * "connection failed" haptic feedback. Timings are an alternating off/on
 * sequence in milliseconds - first value is the initial delay before the
 * first buzz. [NONE] disables.
 *
 * Display name comes from [displayNameRes] (a localized string resource);
 * any code that wants the label calls [displayName] with a Context. Both
 * the tldr() summary in NotificationsScreen and the picker in
 * AlertEditScreen go through this path.
 */
enum class VibrationPattern(val displayNameRes: Int, val timings: LongArray?) {
    NONE(R.string.vib_none, null),
    SHORT(R.string.vib_short, longArrayOf(0, 200)),
    LONG(R.string.vib_long, longArrayOf(0, 700)),
    TWO_SHORT(R.string.vib_two_short, longArrayOf(0, 200, 150, 200)),
    TWO_LONG(R.string.vib_two_long, longArrayOf(0, 700, 250, 700)),
    THREE_SHORT(R.string.vib_three_short, longArrayOf(0, 200, 150, 200, 150, 200));

    fun displayName(ctx: Context): String = ctx.getString(displayNameRes)

    companion object {
        /** Lenient parse - falls back to [default] on unknown / malformed names. */
        fun parse(name: String?, default: VibrationPattern): VibrationPattern =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: default
    }
}

object Vibration {
    /**
     * Fires the given [pattern] once. No-op for [VibrationPattern.NONE], no-op
     * if the device has no vibrator (rare - tablets, some kiosk devices), no-op
     * if the system service can't be resolved.
     */
    fun trigger(context: Context, pattern: VibrationPattern) {
        if (pattern == VibrationPattern.NONE) return
        val timings = pattern.timings ?: return
        val vibrator = resolveVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(timings, /* repeat */ -1)
        vibrator.vibrate(effect)
    }

    @Suppress("DEPRECATION")
    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
