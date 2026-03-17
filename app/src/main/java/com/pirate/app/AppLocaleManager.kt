package com.pirate.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.pirate.app.onboarding.steps.LanguageEntry
import java.util.Locale

internal object AppLocaleManager {
  private const val PREFS_NAME = "pirate_app_locale"
  private const val PREF_APP_LOCALE = "app_locale"
  private const val NATIVE_PROFICIENCY = 7

  fun applyStoredLocale(context: Context) {
    val storedLocaleTag = readStoredLocaleTag(context)
    val targetLocales =
      if (storedLocaleTag.isNullOrBlank()) LocaleListCompat.getEmptyLocaleList()
      else LocaleListCompat.forLanguageTags(storedLocaleTag)
    val currentLocales = AppCompatDelegate.getApplicationLocales()
    if (currentLocales.toLanguageTags() == targetLocales.toLanguageTags()) return
    AppCompatDelegate.setApplicationLocales(targetLocales)
  }

  fun storePreferredLocale(context: Context, rawLocaleTag: String?): Boolean {
    val normalizedLocaleTag = normalizeLocaleTag(rawLocaleTag)
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val current = normalizeLocaleTag(prefs.getString(PREF_APP_LOCALE, null))
    if (current == normalizedLocaleTag) return false
    prefs.edit().putString(PREF_APP_LOCALE, normalizedLocaleTag).apply()
    return true
  }

  fun readStoredLocaleTag(context: Context): String? {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return normalizeLocaleTag(prefs.getString(PREF_APP_LOCALE, null))
  }

  fun preferredLocaleFromOnboardingLanguages(languages: List<LanguageEntry>): String? {
    val preferredCode =
      languages.firstOrNull { it.proficiency == NATIVE_PROFICIENCY }?.code
        ?: languages.firstOrNull()?.code
        ?: return null
    return normalizeLocaleTag(preferredCode)
  }

  internal fun normalizeLocaleTagForTesting(rawLocaleTag: String?): String? = normalizeLocaleTag(rawLocaleTag)

  private fun normalizeLocaleTag(rawLocaleTag: String?): String? {
    val cleaned = rawLocaleTag?.trim()?.replace('_', '-').orEmpty()
    if (cleaned.isBlank()) return null
    val tokens = cleaned.split('-').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return null

    val language = tokens[0].lowercase(Locale.ROOT)
    val scriptOrRegion = tokens.getOrNull(1).orEmpty()
    if (language == "zh" && scriptOrRegion.equals("cn", ignoreCase = true)) return "zh-Hans"
    if (language == "zh" && scriptOrRegion.equals("sg", ignoreCase = true)) return "zh-Hans"
    if (language == "zh" && scriptOrRegion.equals("my", ignoreCase = true)) return "zh-Hans"
    if (language == "zh" && scriptOrRegion.equals("tw", ignoreCase = true)) return "zh-Hant"
    if (language == "zh" && scriptOrRegion.equals("hk", ignoreCase = true)) return "zh-Hant"
    if (language == "zh" && scriptOrRegion.equals("mo", ignoreCase = true)) return "zh-Hant"
    return cleaned
  }
}
