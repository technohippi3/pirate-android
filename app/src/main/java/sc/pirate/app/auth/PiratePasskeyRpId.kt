package sc.pirate.app.auth

import java.net.URL

internal object PiratePasskeyRpId {
  fun normalize(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return PiratePasskeyDefaults.DEFAULT_RP_ID
    val withScheme =
      if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
        trimmed
      } else {
        "https://$trimmed"
      }
    val host =
      try {
        URL(withScheme).host
      } catch (_: Throwable) {
        ""
      }
    return host.trim().lowercase().ifEmpty { PiratePasskeyDefaults.DEFAULT_RP_ID }
  }
}
