package sc.pirate.app.auth

import android.content.Context
import sc.pirate.app.auth.privy.PrivyAuthProvider
import sc.pirate.app.auth.privy.PrivyRuntimeConfig

internal object PirateAuthProviderFactory {
  fun create(
    context: Context,
    state: PirateAuthUiState,
    privyConfig: PrivyRuntimeConfig = PrivyRuntimeConfig.fromBuildConfig(),
  ): PirateAuthProvider {
    return PrivyAuthProvider(
      appContext = context,
      config = privyConfig,
    )
  }
}
