package com.pirate.app

import android.content.Context
import com.pirate.app.auth.PirateAuthUiState
import com.pirate.app.profile.ProfileLanguageEntry
import java.util.Locale

internal object ViewerContentLocaleResolver {
  private const val NATIVE_PROFICIENCY = 7
  private const val CACHE_TTL_MS = 30_000L
  private const val CACHE_MAX_ENTRIES = 16

  private data class CachedViewerLocale(
    val localeTag: String,
    val expiresAtMs: Long,
  )

  private val cacheLock = Any()
  private val localeCache = LinkedHashMap<String, CachedViewerLocale>(8, 0.75f, true)

  suspend fun resolve(context: Context): String {
    val fallbackLocaleTag = systemLocaleTag()
    val address = PirateAuthUiState.load(context).activeAddress()?.trim().orEmpty()
    if (address.isBlank()) return fallbackLocaleTag
    val nowMs = System.currentTimeMillis()
    synchronized(cacheLock) {
      val cached = localeCache[address]
      if (cached != null && cached.expiresAtMs > nowMs) return cached.localeTag
    }

    val profile = runCatching { fetchPublicProfileReadModel(address) }.getOrNull()
    val languages = profile?.contractProfile?.languages.orEmpty()
    val resolvedLocale = selectPreferredLocaleTag(languages, fallbackLocaleTag)
    synchronized(cacheLock) {
      localeCache[address] = CachedViewerLocale(
        localeTag = resolvedLocale,
        expiresAtMs = nowMs + CACHE_TTL_MS,
      )
      while (localeCache.size > CACHE_MAX_ENTRIES) {
        val eldestKey = localeCache.entries.firstOrNull()?.key ?: break
        localeCache.remove(eldestKey)
      }
    }
    return resolvedLocale
  }

  internal fun selectPreferredLocaleTagForTesting(
    languages: List<ProfileLanguageEntry>,
    fallbackLocaleTag: String = "en",
  ): String = selectPreferredLocaleTag(languages, fallbackLocaleTag)

  private fun selectPreferredLocaleTag(
    languages: List<ProfileLanguageEntry>,
    fallbackLocaleTag: String,
  ): String {
    val preferredCode =
      languages.firstOrNull { it.proficiency == NATIVE_PROFICIENCY }?.code
        ?: languages.firstOrNull()?.code
        ?: fallbackLocaleTag
    return normalizeLocaleTag(preferredCode.ifBlank { fallbackLocaleTag })
  }

  private fun systemLocaleTag(): String {
    val locale = Locale.getDefault()
    val tag = runCatching { locale.toLanguageTag() }.getOrNull().orEmpty().trim()
    if (tag.isNotBlank()) return normalizeLocaleTag(tag)
    return normalizeLocaleTag(locale.language.ifBlank { "en" })
  }

  private fun normalizeLocaleTag(raw: String): String {
    val cleaned = raw.trim().replace('_', '-')
    if (cleaned.isBlank()) return "en"
    val tokens = cleaned.split('-').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return "en"

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
