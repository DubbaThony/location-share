package pl.dubba.share.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Returns a configuration-wrapped [Context] with [locale] applied - the
 * single point that knows how to graft a locale onto an Activity's base
 * context. Also sets it as the JVM-default so non-Resource I/O (date and
 * number formatting, etc.) uses it too. Called from
 * [pl.dubba.share.MainActivity.attachBaseContext].
 *
 * Kept tiny on purpose - the only state is the JVM default Locale, which
 * is intentional global behavior for things like SimpleDateFormat.
 */
object LocaleHelper {
    fun applyLocale(base: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
