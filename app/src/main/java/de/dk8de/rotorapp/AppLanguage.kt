package de.dk8de.rotorapp

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** App-Sprache: System, Deutsch oder Englisch. */
object AppLanguage {
    const val SYSTEM = "system"
    const val DE = "de"
    const val EN = "en"

    fun apply(tag: String) {
        val locales = when (tag) {
            DE -> LocaleListCompat.forLanguageTags("de")
            EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        val applyLocales = {
            // Nur setzen wenn geändert — vermeidet unnötiges Activity-Recreate beim Start.
            if (AppCompatDelegate.getApplicationLocales() != locales) {
                AppCompatDelegate.setApplicationLocales(locales)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyLocales()
        } else {
            Handler(Looper.getMainLooper()).post(applyLocales)
        }
    }

    /** Context, der die aktuelle App-Locale für getString() nutzt (auch Application). */
    fun localizedContext(base: Context): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return base
        val platform = locales.unwrap() as? LocaleList ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(platform)
        return base.createConfigurationContext(config)
    }
}
