package sc.pirate.app.identity

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sc.pirate.app.music.SongPublishService
import sc.pirate.app.ui.PirateOutlinedButton
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.ui.VerifiedSealBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "SelfGate"

private enum class VerifyState { LOADING, UNVERIFIED, OPENING, POLLING, VERIFIED, ERROR }

private const val POLL_INTERVAL_MS = 2_000L
private const val POLL_MAX_ATTEMPTS = 300 // 10 minutes at 2s intervals
private const val SELF_INSTALL_URL = "https://play.google.com/store/apps/details?id=com.proofofpassportapp"

/**
 * Identity verification gate. Shows verification flow if user is not Self-verified,
 * otherwise renders [content].
 */
@Composable
fun SelfVerificationGate(
  userAddress: String,
  cachedVerified: Boolean = false,
  apiBaseUrl: String = SongPublishService.API_CORE_URL,
  onVerified: () -> Unit = {},
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  var state by remember(cachedVerified) {
    mutableStateOf(if (cachedVerified) VerifyState.VERIFIED else VerifyState.LOADING)
  }
  var errorMessage by remember { mutableStateOf("") }
  var sessionId by remember { mutableStateOf<String?>(null) }
  var showInstallAction by remember { mutableStateOf(false) }

  // Initial identity check
  LaunchedEffect(userAddress, cachedVerified, apiBaseUrl) {
    if (cachedVerified) {
      state = VerifyState.VERIFIED
      onVerified()
      return@LaunchedEffect
    }
    state = VerifyState.LOADING
    val result = withContext(Dispatchers.IO) {
      SelfVerificationService.checkIdentity(apiBaseUrl, userAddress)
    }
    state = when (result) {
      is SelfVerificationService.IdentityResult.Verified -> {
        onVerified()
        VerifyState.VERIFIED
      }
      is SelfVerificationService.IdentityResult.NotVerified -> VerifyState.UNVERIFIED
      is SelfVerificationService.IdentityResult.Error -> {
        Log.e(TAG, "Identity check failed: ${result.message}")
        errorMessage = result.message
        VerifyState.ERROR
      }
    }
  }

  // Stateful side effects: session creation and polling.
  LaunchedEffect(state, sessionId, userAddress, apiBaseUrl) {
    when (state) {
      VerifyState.OPENING -> {
        val session = withContext(Dispatchers.IO) {
          SelfVerificationService.createSession(apiBaseUrl, userAddress)
        }
        when (session) {
          is SelfVerificationService.SessionResult.Success -> {
            sessionId = session.sessionId
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(session.deeplinkUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Log.i(TAG, "Opening verification URL: ${session.deeplinkUrl}")
            try {
              context.startActivity(intent)
              showInstallAction = false
              state = VerifyState.POLLING
            } catch (e: ActivityNotFoundException) {
              Log.e(TAG, "No app can open verification URL", e)
              errorMessage = "No app can open the Self verification link. Install the Self app to continue."
              showInstallAction = true
              state = VerifyState.ERROR
            }
          }
          is SelfVerificationService.SessionResult.Error -> {
            Log.e(TAG, "Session creation failed: ${session.message}")
            errorMessage = session.message
            showInstallAction = false
            state = VerifyState.ERROR
          }
        }
      }
      VerifyState.POLLING -> {
        val sid = sessionId ?: return@LaunchedEffect
        var attempts = 0
        while (attempts < POLL_MAX_ATTEMPTS) {
          delay(POLL_INTERVAL_MS)
          attempts++
          val poll = withContext(Dispatchers.IO) {
            SelfVerificationService.pollSession(apiBaseUrl, sid)
          }
          when (poll) {
            is SelfVerificationService.SessionStatus.Success -> {
              when (poll.status) {
                "verified" -> {
                  state = VerifyState.VERIFIED
                  onVerified()
                  return@LaunchedEffect
                }
                "failed" -> {
                  errorMessage = poll.reason ?: "Verification failed"
                  state = VerifyState.ERROR
                  return@LaunchedEffect
                }
                "expired" -> {
                  errorMessage = "Session expired. Please try again."
                  state = VerifyState.ERROR
                  return@LaunchedEffect
                }
              }
            }
            is SelfVerificationService.SessionStatus.Error -> {
              Log.w(TAG, "Poll error (attempt $attempts): ${poll.message}")
            }
          }
        }
        errorMessage = "Verification timed out. If Self didn't open, install/update the Self app and try again."
        showInstallAction = true
        state = VerifyState.ERROR
      }
      else -> Unit
    }
  }

  when (state) {
    VerifyState.LOADING -> {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    }

    VerifyState.VERIFIED -> {
      content()
    }

    VerifyState.UNVERIFIED -> {
      Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 32.dp, vertical = 40.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          VerifiedSealBadge(size = 72.dp)
          Spacer(Modifier.height(24.dp))
          Text(
            "Verify your ID",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(12.dp))
          Text(
            "Pirate pays humans, not bots.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(10.dp))
          Text(
            "The check is free and confirms that you are a unique human, 18+, and where you are from without storing a passport photo or facial scan.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        VerificationActions(
          primaryLabel = "Verify with Self",
          onPrimaryClick = {
            errorMessage = ""
            showInstallAction = false
            state = VerifyState.OPENING
          },
          onSecondaryClick = {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SELF_INSTALL_URL))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
          },
        )
      }
    }

    VerifyState.OPENING -> {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          CircularProgressIndicator()
          Spacer(Modifier.height(16.dp))
          Text("Opening Self.xyz...", style = MaterialTheme.typography.bodyLarge)
        }
      }
    }

    VerifyState.POLLING -> {
      Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(
          "Waiting for verification...",
          style = MaterialTheme.typography.headlineSmall,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
          "Complete the verification in the Self app, then return here.",
          style = MaterialTheme.typography.bodyLarge,
          textAlign = TextAlign.Center,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    VerifyState.ERROR -> {
      Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 32.dp, vertical = 40.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Icon(
            PhosphorIcons.Regular.Warning,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
          )
          Spacer(Modifier.height(24.dp))
          Text(
            "Verification failed",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(12.dp))
          Text(
            errorMessage,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        VerificationActions(
          primaryLabel = "Try Again",
          onPrimaryClick = {
            errorMessage = ""
            showInstallAction = false
            state = VerifyState.UNVERIFIED
          },
          secondaryLabel = if (showInstallAction) "Get Self App" else null,
          onSecondaryClick = if (showInstallAction) {
            {
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SELF_INSTALL_URL))
              intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              context.startActivity(intent)
            }
          } else {
            null
          },
        )
      }
    }
  }
}

@Composable
private fun VerificationActions(
  primaryLabel: String,
  onPrimaryClick: () -> Unit,
  secondaryLabel: String? = "Get Self App",
  onSecondaryClick: (() -> Unit)?,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
    tonalElevation = 3.dp,
    shadowElevation = 8.dp,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 24.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      PiratePrimaryButton(
        text = primaryLabel,
        onClick = onPrimaryClick,
        modifier = Modifier.fillMaxWidth(),
      )
      if (secondaryLabel != null && onSecondaryClick != null) {
        PirateOutlinedButton(
          onClick = onSecondaryClick,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(secondaryLabel)
        }
      }
    }
  }
}
