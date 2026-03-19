package sc.pirate.app

import sc.pirate.app.profile.ProfileLanguageEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerContentLocaleResolverTest {
  @Test
  fun selectPreferredLocaleTag_prefersFirstNativeLanguage() {
    val locale =
      ViewerContentLocaleResolver.selectPreferredLocaleTagForTesting(
        listOf(
          ProfileLanguageEntry(code = "es", proficiency = 6),
          ProfileLanguageEntry(code = "fr", proficiency = 7),
          ProfileLanguageEntry(code = "pt", proficiency = 7),
        ),
        fallbackLocaleTag = "en-US",
      )

    assertEquals("fr", locale)
  }

  @Test
  fun selectPreferredLocaleTag_fallsBackToFirstProfileLanguage() {
    val locale =
      ViewerContentLocaleResolver.selectPreferredLocaleTagForTesting(
        listOf(
          ProfileLanguageEntry(code = "de", proficiency = 5),
          ProfileLanguageEntry(code = "it", proficiency = 4),
        ),
        fallbackLocaleTag = "en-US",
      )

    assertEquals("de", locale)
  }

  @Test
  fun selectPreferredLocaleTag_usesFallbackWhenProfileHasNoLanguages() {
    val locale =
      ViewerContentLocaleResolver.selectPreferredLocaleTagForTesting(
        emptyList(),
        fallbackLocaleTag = "pt-BR",
      )

    assertEquals("pt-BR", locale)
  }

  @Test
  fun selectPreferredLocaleTag_normalizesTraditionalChineseRegions() {
    val locale =
      ViewerContentLocaleResolver.selectPreferredLocaleTagForTesting(
        listOf(ProfileLanguageEntry(code = "zh-MO", proficiency = 7)),
        fallbackLocaleTag = "en-US",
      )

    assertEquals("zh-Hant", locale)
  }

  @Test
  fun selectPreferredLocaleTag_normalizesSimplifiedChineseRegions() {
    val locale =
      ViewerContentLocaleResolver.selectPreferredLocaleTagForTesting(
        listOf(ProfileLanguageEntry(code = "zh-MY", proficiency = 7)),
        fallbackLocaleTag = "en-US",
      )

    assertEquals("zh-Hans", locale)
  }
}
