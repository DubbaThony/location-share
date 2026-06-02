package pl.dubba.share.settings

import android.content.res.Resources
import java.util.Locale

/**
 * App language preference. AUTO defers to the system locale (falling back
 * to English if the system language isn't one we support); the other two
 * pin the language regardless of system setting.
 *
 * [code] is the BCP-47 / ISO-639 lowercase tag we feed to Locale; null for
 * AUTO since it's a meta-choice rather than a real locale. [autonym] is
 * the language's own name (English in English, Polski in Polish, etc.) so
 * the picker reads correctly regardless of which language is currently
 * active in the UI.
 */
enum class Language(val code: String?, val autonym: String) {
    AUTO(null, "Auto"),
    ENGLISH("en", "English"),
    POLISH("pl", "Polski");

    /**
     * The actual [Locale] this preference resolves to. AUTO reads the
     * SYSTEM locale (not [Locale.getDefault], which we may have modified
     * already in [LocaleHelper.applyLocale]) and picks Polish if the
     * system language is Polish, English otherwise. ENGLISH / POLISH map
     * directly to their [code].
     */
    fun resolveLocale(): Locale {
        if (this == AUTO) {
            val sysLocale = Resources.getSystem().configuration.locales[0]
            return if (sysLocale.language == "pl") Locale("pl") else Locale.ENGLISH
        }
        return Locale(code!!)
    }

    companion object {
        /** Lenient parse - unknown / null falls back to [default]. */
        fun parse(name: String?, default: Language): Language =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: default
    }
}
