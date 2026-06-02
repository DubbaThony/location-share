package pl.dubba.share

import android.app.Application
import android.content.Context
import pl.dubba.share.settings.LanguagePref
import pl.dubba.share.settings.LocaleHelper

/**
 * Applies the persisted language preference to the Application context at
 * process start - BEFORE any Activity or Service is created. Without this,
 * the Service (which reads strings via `applicationContext.getString`) would
 * resolve into the system locale even when the user has Polish picked,
 * because MainActivity's attachBaseContext only wraps the Activity instance.
 *
 * Reads from [LanguagePref] (SharedPreferences) rather than the DataStore-
 * backed Settings - see the kdoc on LanguagePref for why DataStore can't
 * be used at this point in the Application lifecycle.
 */
class ShareApplication : Application() {
    override fun attachBaseContext(newBase: Context) {
        val language = LanguagePref.read(newBase)
        super.attachBaseContext(LocaleHelper.applyLocale(newBase, language.resolveLocale()))
    }
}
