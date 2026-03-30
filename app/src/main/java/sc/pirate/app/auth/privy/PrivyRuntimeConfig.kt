package sc.pirate.app.auth.privy

import sc.pirate.app.BuildConfig

data class PrivyRuntimeConfig(
  val enabled: Boolean,
  val appId: String,
  val appClientId: String,
  val redirectScheme: String,
) {
  fun disabledReason(): String? =
    when {
      !enabled -> "Privy auth is disabled for this build."
      appId.isBlank() -> "Missing PRIVY_APP_ID."
      appClientId.isBlank() -> "Missing PRIVY_APP_CLIENT_ID."
      redirectScheme.isBlank() -> "Missing PRIVY_REDIRECT_SCHEME."
      else -> null
    }

  fun oauthDisabledReason(): String? =
    disabledReason()

  companion object {
    fun fromBuildConfig(): PrivyRuntimeConfig =
      PrivyRuntimeConfig(
        enabled = BuildConfig.PRIVY_ENABLED,
        appId = BuildConfig.PRIVY_APP_ID.trim(),
        appClientId = BuildConfig.PRIVY_APP_CLIENT_ID.trim(),
        redirectScheme = BuildConfig.PRIVY_REDIRECT_SCHEME.trim(),
      )
  }
}
