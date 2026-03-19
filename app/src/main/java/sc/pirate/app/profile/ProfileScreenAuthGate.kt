package sc.pirate.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import sc.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.ui.PiratePrimaryButton

@Composable
internal fun ProfileAuthRequiredContent(
  busy: Boolean,
  onRegister: () -> Unit,
  onLogin: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("Sign in to view your profile", color = PiratePalette.TextMuted, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(24.dp))
    PiratePrimaryButton(
      text = "Sign Up",
      onClick = onRegister,
      enabled = !busy,
      modifier = Modifier.fillMaxWidth(0.6f),
    )
    Spacer(Modifier.height(12.dp))
    PirateOutlinedButton(onClick = onLogin, enabled = !busy, modifier = Modifier.fillMaxWidth(0.6f)) {
      Text("Sign In")
    }
    if (busy) {
      Spacer(Modifier.height(16.dp))
      CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
  }
}
