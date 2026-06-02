package pl.dubba.share.settings

import android.content.Context
import pl.dubba.share.Config

/**
 * Language preference, deliberately stored OUTSIDE the DataStore-backed
 * [Settings]. Reason: the locale needs to be applied in
 * `Application.attachBaseContext`, which runs before the Application
 * instance is fully initialized - at that point `Context.applicationContext`
 * is null, and DataStore's singleton delegate requires it. SharedPreferences
 * has no such constraint: it's keyed off the file system path the caller's
 * Context resolves, and works fine with the not-yet-attached base context.
 *
 * Trade-off: language is no longer part of the [Settings.Snapshot] surface.
 * That's fine - it's a meta-setting that controls how every OTHER setting
 * is rendered, so keeping it separate also expresses the layering cleanly.
 *
 * Writes use `commit()` (not `apply()`) so the next Activity recreation
 * (which runs after `activity.recreate()` in the picker) immediately sees
 * the new value when its `attachBaseContext` re-reads it.
 */
object LanguagePref {
    private const val PREFS_NAME = "language_pref"
    private const val KEY = "language"

    fun read(context: Context): Language {
        val name = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null)
        return Language.parse(name, Config.DEFAULT_SETTING_LANGUAGE)
    }

    @Suppress("ApplySharedPref") // commit() is intentional - see class kdoc
    fun write(context: Context, language: Language) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, language.name)
            .commit()
    }
}
