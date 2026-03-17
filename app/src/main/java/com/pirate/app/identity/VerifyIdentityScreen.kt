package com.pirate.app.identity

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pirate.app.auth.PirateAuthUiState

@Composable
fun VerifyIdentityScreen(
  authState: PirateAuthUiState,
  ownerAddress: String?,
  isAuthenticated: Boolean,
  onSelfVerifiedChange: (Boolean) -> Unit = {},
  onVerified: (() -> Unit)? = null,
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  if (!isAuthenticated || ownerAddress.isNullOrBlank()) {
    LaunchedEffect(Unit) {
      onShowMessage("Please sign in first")
      onClose()
    }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
      Text("Redirecting...", style = MaterialTheme.typography.bodyLarge)
    }
    return
  }

  SelfVerificationGate(
    userAddress = ownerAddress,
    cachedVerified = false,
    onVerified = {
      onSelfVerifiedChange(true)
      onVerified?.invoke() ?: onClose()
    },
  ) {
    // Verified state immediately closes this route.
    Box(Modifier.fillMaxSize())
  }
}
