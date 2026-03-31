package sc.pirate.app.auth

import sc.pirate.app.PirateChainConfig

data class PirateAuthUiState(
  val provider: AuthProvider? = null,
  val walletAddress: String? = null,
  val authWalletAddress: String? = null,
  val walletChainId: Long = PirateChainConfig.STORY_AENEID_CHAIN_ID,
  val identityChainId: Long = PirateChainConfig.BASE_SEPOLIA_CHAIN_ID,
  val walletSource: WalletSource? = null,
  val privyUserId: String? = null,
  val privyWalletId: String? = null,
  val passkeyRpId: String = PiratePasskeyDefaults.DEFAULT_RP_ID,
  val selfVerified: Boolean = false,
  val output: String = "Ready.",
  val busy: Boolean = false,
) {
  enum class AuthProvider {
    PRIVY,
  }

  enum class WalletSource {
    EMBEDDED,
    EXTERNAL,
  }

  fun hasPrivyCredentials(): Boolean {
    if (provider != AuthProvider.PRIVY) return false
    return !walletAddress.isNullOrBlank()
  }

  fun hasAnyCredentials(): Boolean = hasPrivyCredentials()

  fun activeAddress(): String? {
    return walletAddress?.takeIf { it.isNotBlank() }
      ?: authWalletAddress?.takeIf { it.isNotBlank() }
  }

  companion object {
    private const val PREFS_NAME = "pirate_auth"

    fun save(context: android.content.Context, state: PirateAuthUiState) {
      context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE).edit()
        .putString("provider", state.provider?.name)
        .putString("walletAddress", state.walletAddress)
        .putString("authWalletAddress", state.authWalletAddress)
        .putLong("walletChainId", state.walletChainId)
        .putLong("identityChainId", state.identityChainId)
        .putString("walletSource", state.walletSource?.name)
        .putString("privyUserId", state.privyUserId)
        .putString("privyWalletId", state.privyWalletId)
        .putString("passkeyRpId", PiratePasskeyRpId.normalize(state.passkeyRpId))
        .putBoolean("selfVerified", state.selfVerified)
        .apply()
    }

    fun load(context: android.content.Context): PirateAuthUiState {
      val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
      val rawProvider =
        prefs.getString("provider", null)?.let { raw ->
          runCatching { AuthProvider.valueOf(raw) }.getOrNull()
        }
      val walletSource =
        prefs.getString("walletSource", null)?.let { raw ->
          runCatching { WalletSource.valueOf(raw) }.getOrNull()
        }
      val walletAddress = prefs.getString("walletAddress", null)
      val walletChainId =
        prefs.takeIf { it.contains("walletChainId") }?.getLong("walletChainId", PirateChainConfig.STORY_AENEID_CHAIN_ID)
          ?: PirateChainConfig.STORY_AENEID_CHAIN_ID
      val identityChainId =
        prefs.takeIf { it.contains("identityChainId") }?.getLong("identityChainId", PirateChainConfig.BASE_SEPOLIA_CHAIN_ID)
          ?: PirateChainConfig.BASE_SEPOLIA_CHAIN_ID
      val provider = rawProvider?.takeIf { it == AuthProvider.PRIVY }
      val activeWalletAddress = walletAddress?.takeIf { provider == AuthProvider.PRIVY && it.isNotBlank() }
      return PirateAuthUiState(
        provider = provider,
        walletAddress = activeWalletAddress,
        authWalletAddress =
          prefs.getString("authWalletAddress", null)?.takeIf { provider == AuthProvider.PRIVY && it.isNotBlank() }
            ?: activeWalletAddress,
        walletChainId = walletChainId,
        identityChainId = identityChainId,
        walletSource = walletSource,
        privyUserId = prefs.getString("privyUserId", null)?.takeIf { provider == AuthProvider.PRIVY },
        privyWalletId = prefs.getString("privyWalletId", null)?.takeIf { provider == AuthProvider.PRIVY },
        passkeyRpId =
          PiratePasskeyRpId.normalize(
            prefs.getString("passkeyRpId", null) ?: PiratePasskeyDefaults.DEFAULT_RP_ID,
          ),
        selfVerified = prefs.getBoolean("selfVerified", false),
      )
    }

    fun clear(context: android.content.Context) {
      context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }
  }
}
