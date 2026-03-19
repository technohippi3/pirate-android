package sc.pirate.app

import sc.pirate.app.onboarding.steps.LanguageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLocaleManagerTest {
  @Test
  fun preferredLocaleFromOnboardingLanguages_prefersFirstNativeLanguage() {
    val locale =
      AppLocaleManager.preferredLocaleFromOnboardingLanguages(
        listOf(
          LanguageEntry(code = "es", proficiency = 5),
          LanguageEntry(code = "zh-CN", proficiency = 7),
          LanguageEntry(code = "fr", proficiency = 7),
        ),
      )

    assertEquals("zh-Hans", locale)
  }

  @Test
  fun preferredLocaleFromOnboardingLanguages_fallsBackToFirstLanguage() {
    val locale =
      AppLocaleManager.preferredLocaleFromOnboardingLanguages(
        listOf(
          LanguageEntry(code = "es", proficiency = 3),
          LanguageEntry(code = "fr", proficiency = 2),
        ),
      )

    assertEquals("es", locale)
  }

  @Test
  fun preferredLocaleFromOnboardingLanguages_returnsNullWhenEmpty() {
    assertNull(AppLocaleManager.preferredLocaleFromOnboardingLanguages(emptyList()))
  }

  @Test
  fun normalizeLocaleTag_mapsChineseRegionsToScriptLocales() {
    assertEquals("zh-Hans", AppLocaleManager.normalizeLocaleTagForTesting("zh-MY"))
    assertEquals("zh-Hant", AppLocaleManager.normalizeLocaleTagForTesting("zh-MO"))
  }
}
