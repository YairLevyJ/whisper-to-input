/*
 * This file is part of Whisper To Input, see <https://github.com/j3soon/whisper-to-input>.
 *
 * Copyright (c) 2023-2025 Yan-Bin Diau, Johnson Sun
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.whispertoinput

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Storage and application of the UI language.
 *
 * This is the one setting that cannot live in the DataStore alongside the others: it has to be
 * applied in attachBaseContext(), before any resource is resolved, which is earlier than a
 * suspending DataStore read can complete. SharedPreferences is used instead because it reads
 * synchronously.
 */
object LocaleHelper {
    // Persisted values. They are deliberately not display strings, since those are translated.
    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_HEBREW = "he"

    private const val PREFERENCES_NAME = "ui-language"
    private const val KEY_LANGUAGE = "ui-language"

    fun getLanguage(context: Context): String =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM

    fun setLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language)
            .apply()
    }

    /**
     * Returns a context whose resources resolve in the selected UI language, along with the
     * matching layout direction so that Hebrew lays out right-to-left. When the device language
     * is being followed, the original context is returned untouched.
     */
    fun applyToContext(context: Context): Context {
        val language = getLanguage(context)
        if (language == LANGUAGE_SYSTEM) {
            return context
        }

        // Note that Locale normalizes "he" to the legacy code "iw", which is also why the Hebrew
        // translations live in res/values-iw rather than res/values-he.
        val locale = Locale.forLanguageTag(language)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}
