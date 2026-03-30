package sc.pirate.app.auth.privy

import android.content.Context
import android.content.Intent
import android.net.Uri
import sc.pirate.app.auth.PirateAuthMethod

internal object PrivyOAuthSupport {
  private val CUSTOM_TABS_SERVICE_ACTIONS =
    listOf(
      "androidx.browser.customtabs.action.CustomTabsService",
      "android.support.customtabs.action.CustomTabsService",
    )
  private val AUTH_URI: Uri = Uri.parse("https://auth.privy.io")

  fun unavailableReason(
    context: Context,
    config: PrivyRuntimeConfig,
    method: PirateAuthMethod,
  ): String? {
    if (method != PirateAuthMethod.GOOGLE && method != PirateAuthMethod.TWITTER) {
      return null
    }

    config.oauthDisabledReason()?.let { return it }

    val packageManager = context.packageManager
    val browserIntent = Intent(Intent.ACTION_VIEW, AUTH_URI)
    if (browserIntent.resolveActivity(packageManager) == null) {
      return "${method.label} sign-in needs a browser on this device."
    }

    val hasCustomTabsService =
      CUSTOM_TABS_SERVICE_ACTIONS.any { action ->
        packageManager.queryIntentServices(Intent(action), 0).isNotEmpty()
      }
    if (!hasCustomTabsService) {
      return "${method.label} sign-in needs a browser with Custom Tabs on this device."
    }

    return null
  }
}
