package sc.pirate.app.auth

data class PirateWalletSession(
  val provider: PirateAuthUiState.AuthProvider?,
  val walletAddress: String,
  val authWalletAddress: String,
  val walletChainId: Long,
  val identityChainId: Long,
  val walletSource: PirateAuthUiState.WalletSource?,
  val privyUserId: String? = null,
  val privyWalletId: String? = null,
) {
  companion object {
    fun fromAuthState(
      state: PirateAuthUiState,
    ): PirateWalletSession? {
      val walletAddress = state.walletAddress?.takeIf { it.isNotBlank() } ?: return null
      return PirateWalletSession(
        provider = state.provider,
        walletAddress = walletAddress,
        authWalletAddress = state.authWalletAddress?.takeIf { it.isNotBlank() } ?: walletAddress,
        walletChainId = state.walletChainId,
        identityChainId = state.identityChainId,
        walletSource = state.walletSource,
        privyUserId = state.privyUserId,
        privyWalletId = state.privyWalletId,
      )
    }
  }
}
