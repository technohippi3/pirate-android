package sc.pirate.app.auth

import androidx.fragment.app.FragmentActivity

internal interface PirateAuthProvider {
  val availableMethods: List<PirateAuthMethod>

  fun currentSession(state: PirateAuthUiState): PirateWalletSession?

  suspend fun authenticate(
    activity: FragmentActivity,
    currentState: PirateAuthUiState,
    request: PirateAuthRequest,
  ): PirateAuthUiState

  suspend fun sendOneTimeCode(
    method: PirateAuthMethod,
    identifier: String,
  )

  suspend fun clearSession(currentState: PirateAuthUiState): PirateAuthUiState
}
