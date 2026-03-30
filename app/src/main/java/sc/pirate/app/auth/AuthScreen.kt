package sc.pirate.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sc.pirate.app.ui.PiratePrimaryButton

@Composable
fun AuthScreen(
  state: PirateAuthUiState,
  onStateChange: (PirateAuthUiState) -> Unit,
  onRegister: () -> Unit,
  onLogin: () -> Unit,
  onLogout: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val signedIn = state.hasAnyCredentials()
        val providerLabel =
          when {
            state.provider == PirateAuthUiState.AuthProvider.PRIVY -> "Privy"
            else -> null
          }
        Text(if (signedIn) "Signed in" else "Not signed in")
        if (signedIn) {
          val addr = state.activeAddress()
          if (!addr.isNullOrBlank()) Text("Address: ${addr.take(10)}…")
          if (!providerLabel.isNullOrBlank()) Text("Provider: $providerLabel")
          Text("Wallet chain: ${state.walletChainId}")
          Text("Identity chain: ${state.identityChainId}")
        }

        HorizontalDivider()

        PiratePrimaryButton(
          text = "Continue",
          enabled = !state.busy,
          onClick = onRegister,
          modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        PiratePrimaryButton(
          text = "Log Out",
          enabled = !state.busy && state.hasAnyCredentials(),
          onClick = onLogout,
        )
      }
    }

    Spacer(modifier = Modifier.height(4.dp))
    Text(state.output)
  }
}
