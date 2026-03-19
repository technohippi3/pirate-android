package sc.pirate.app.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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
        Text(if (signedIn) "Signed in" else "Not signed in")
        if (signedIn) {
          val addr = state.activeAddress()
          val tempoCred = state.tempoCredentialId
          if (!addr.isNullOrBlank()) Text("Address: ${addr.take(10)}…")
          if (!tempoCred.isNullOrBlank()) Text("Tempo cred: ${tempoCred.take(12)}…")
        }

        HorizontalDivider()

        OutlinedTextField(
          value = state.authServiceBaseUrl,
          onValueChange = { onStateChange(state.copy(authServiceBaseUrl = it)) },
          label = { Text("Auth service base URL") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          enabled = !state.busy,
        )
        OutlinedTextField(
          value = state.passkeyRpId,
          onValueChange = { onStateChange(state.copy(passkeyRpId = it)) },
          label = { Text("Passkey RP ID") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
          enabled = !state.busy,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          PiratePrimaryButton(
            text = "Register",
            enabled = !state.busy,
            onClick = onRegister,
          )

          PiratePrimaryButton(
            text = "Login",
            enabled = !state.busy,
            onClick = onLogin,
          )
        }

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
