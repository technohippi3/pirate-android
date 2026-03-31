package sc.pirate.app.auth.privy

import android.content.Context
import androidx.fragment.app.FragmentActivity
import io.privy.auth.PrivyUser
import io.privy.auth.oAuth.OAuthProvider
import io.privy.sdk.Privy
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.auth.PirateAuthMethod
import sc.pirate.app.auth.PirateAuthProvider
import sc.pirate.app.auth.PirateAuthRequest
import sc.pirate.app.auth.PirateAuthUiState
import sc.pirate.app.auth.PiratePasskeyDefaults
import sc.pirate.app.auth.PiratePasskeyRpId
import sc.pirate.app.auth.PirateWalletSession

internal class PrivyAuthProvider(
  private val appContext: Context,
  private val config: PrivyRuntimeConfig = PrivyRuntimeConfig.fromBuildConfig(),
) : PirateAuthProvider {
  override val availableMethods: List<PirateAuthMethod> =
    listOf(
      PirateAuthMethod.GOOGLE,
      PirateAuthMethod.EMAIL,
      PirateAuthMethod.TWITTER,
    )

  override fun currentSession(state: PirateAuthUiState): PirateWalletSession? {
    if (state.provider != PirateAuthUiState.AuthProvider.PRIVY) return null
    return PirateWalletSession.fromAuthState(state)
  }

  override suspend fun authenticate(
    activity: FragmentActivity,
    currentState: PirateAuthUiState,
    request: PirateAuthRequest,
  ): PirateAuthUiState {
    val user =
      when (request.method) {
        PirateAuthMethod.PASSKEY -> authenticateWithPasskey(currentState)
        PirateAuthMethod.GOOGLE -> authenticateWithOAuth(OAuthProvider.Google)
        PirateAuthMethod.TWITTER -> authenticateWithOAuth(OAuthProvider.Twitter)
        PirateAuthMethod.EMAIL -> authenticateWithEmail(request)
      }
    val wallet = resolvePrimaryWallet(user)
    return currentState.withPrivySession(
      user = user,
      wallet = wallet,
      output = "${request.method.label} ready: ${wallet.address.take(10)}...",
    )
  }

  override suspend fun sendOneTimeCode(
    method: PirateAuthMethod,
    identifier: String,
  ) {
    val trimmedIdentifier = identifier.trim()
    when (method) {
      PirateAuthMethod.EMAIL -> privy().email.sendCode(trimmedIdentifier).getOrThrow()
      else -> error("${method.label} does not use one-time codes.")
    }
  }

  override suspend fun clearSession(currentState: PirateAuthUiState): PirateAuthUiState {
    if (config.disabledReason() == null) {
      privy().logout()
    }
    return currentState.copy(
      provider = null,
      walletAddress = null,
      authWalletAddress = null,
      walletSource = null,
      privyUserId = null,
      privyWalletId = null,
      selfVerified = false,
      output = "Logged out.",
      busy = false,
    )
  }

  private suspend fun resolvePrimaryWallet(user: PrivyUser): PrivyWalletIdentity {
    val existingWallet = user.embeddedEthereumWallets.firstOrNull { it.hdWalletIndex == 0 }
    if (existingWallet != null) {
      return PrivyWalletIdentity(
        id = existingWallet.id,
        address = existingWallet.address,
      )
    }
    val createdWallet = user.createEthereumWallet().getOrThrow()
    return PrivyWalletIdentity(
      id = createdWallet.id,
      address = createdWallet.address,
    )
  }

  private suspend fun PirateAuthUiState.withPrivySession(
    user: PrivyUser,
    wallet: PrivyWalletIdentity,
    output: String,
  ): PirateAuthUiState {
    return copy(
      busy = false,
      provider = PirateAuthUiState.AuthProvider.PRIVY,
      walletAddress = wallet.address,
      authWalletAddress = wallet.address,
      walletChainId = PirateChainConfig.STORY_AENEID_CHAIN_ID,
      identityChainId = PirateChainConfig.BASE_SEPOLIA_CHAIN_ID,
      walletSource = PirateAuthUiState.WalletSource.EMBEDDED,
      privyUserId = user.id,
      privyWalletId = wallet.id,
      selfVerified = false,
      output = output,
    )
  }

  private suspend fun authenticateWithPasskey(currentState: PirateAuthUiState): PrivyUser {
    val rpId = PiratePasskeyRpId.normalize(currentState.passkeyRpId)
    val signupResult =
      privy()
        .passkey
        .signup(
          relyingParty = rpId,
          displayName = PiratePasskeyDefaults.DEFAULT_RP_NAME,
        )
    val signupError = signupResult.exceptionOrNull()
    if (signupError == null) {
      return signupResult.getOrThrow()
    }
    if (!shouldFallBackToPasskeyLogin(signupError)) {
      throw signupError
    }
    return privy().passkey.login(relyingParty = rpId).getOrThrow()
  }

  private fun shouldFallBackToPasskeyLogin(error: Throwable): Boolean {
    val markers = buildPasskeyErrorMarkers(error)
    if (
      markers.any { marker ->
        listOf(
          "cancel",
          "abort",
          "dismiss",
          "user canceled",
          "user cancelled",
          "cancellationexception",
        ).any(marker::contains)
      }
    ) {
      return false
    }
    return markers.any { marker ->
      listOf(
        "already exists",
        "credential already exists",
        "credentialexists",
        "duplicate",
        "exist",
        "registered",
      ).any(marker::contains)
    }
  }

  private fun buildPasskeyErrorMarkers(error: Throwable): List<String> {
    val markers = mutableListOf<String>()
    var cursor: Throwable? = error
    while (cursor != null) {
      markers += cursor::class.java.name.lowercase()
      cursor.message?.lowercase()?.takeIf { it.isNotBlank() }?.let(markers::add)
      cursor = cursor.cause
    }
    return markers
  }

  private suspend fun authenticateWithOAuth(provider: OAuthProvider): PrivyUser {
    PrivyOAuthSupport.unavailableReason(
      context = appContext,
      config = config,
      method =
        when (provider) {
          OAuthProvider.Google -> PirateAuthMethod.GOOGLE
          OAuthProvider.Twitter -> PirateAuthMethod.TWITTER
          else -> error("Unsupported OAuth provider: $provider")
        },
    )?.let { reason ->
      error(reason)
    }

    return privy()
      .oAuth
      .login(
        oAuthProvider = provider,
        appUrlScheme = config.redirectScheme,
      ).getOrThrow()
  }

  private suspend fun authenticateWithEmail(request: PirateAuthRequest): PrivyUser {
    val email = request.identifier?.trim().orEmpty()
    val code = request.code?.trim().orEmpty()
    require(email.isNotBlank()) { "Email is required." }
    require(code.isNotBlank()) { "Email code is required." }
    return privy()
      .email
      .loginWithCode(
        code = code,
        email = email,
      ).getOrThrow()
  }

  private fun privy(): Privy {
    val reason = config.disabledReason()
    check(reason == null) { reason ?: "Privy auth is unavailable." }
    return PrivyClientStore.get(
      context = appContext,
      config = config,
    )
  }
}

private data class PrivyWalletIdentity(
  val id: String?,
  val address: String,
)
