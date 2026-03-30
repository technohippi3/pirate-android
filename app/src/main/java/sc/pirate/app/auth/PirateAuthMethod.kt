package sc.pirate.app.auth

internal enum class PirateAuthMethod(
  val label: String,
  val description: String,
  val requiresOneTimeCode: Boolean = false,
) {
  GOOGLE(
    label = "Google",
    description = "Use Google and create or recover your embedded wallet.",
  ),
  EMAIL(
    label = "Email",
    description = "We'll send a 6-digit code to your inbox.",
    requiresOneTimeCode = true,
  ),
  PASSKEY(
    label = "Passkey",
    description = "Use the device-native passkey flow.",
  ),
  TWITTER(
    label = "X",
    description = "Authenticate with your X account.",
  ),
}

internal data class PirateAuthRequest(
  val method: PirateAuthMethod,
  val identifier: String? = null,
  val code: String? = null,
)
