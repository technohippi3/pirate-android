package sc.pirate.app.onboarding

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sc.pirate.app.R
import sc.pirate.app.profile.TempoNameRecordsApi
import sc.pirate.app.tempo.TempoAccountFactory
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.tempo.SessionKeyManager
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*
import sc.pirate.app.ui.PiratePrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "OnboardingScreen"
private const val ONBOARDING_COMPLETE_V2 = "complete_v2"

enum class OnboardingStep {
  NAME, AGE, GENDER, LOCATION, LANGUAGES, AVATAR, DONE;
  val index: Int get() = ordinal
  val total: Int get() = entries.size - 1 // exclude DONE from progress
}

/**
 * Check if onboarding is needed for the given address.
 * Fast path: SharedPreferences. Slow path: on-chain RPC queries.
 * Returns the step to resume at, or null if onboarding is complete.
 */
suspend fun checkOnboardingStatus(
  context: Context,
  userAddress: String,
): OnboardingStep? = withContext(Dispatchers.IO) {
  val prefs = context.getSharedPreferences("heaven_onboarding", Context.MODE_PRIVATE)
  val key = "onboarding:${userAddress.lowercase()}"

  // Fast path
  if (prefs.getString(key, null) == ONBOARDING_COMPLETE_V2) return@withContext null

  // Slow path: check on-chain
  try {
    val primaryName = TempoNameRecordsApi.getPrimaryNameDetails(userAddress)
    if (primaryName == null || primaryName.label.isBlank()) {
      return@withContext OnboardingStep.NAME
    }

    // Phase-level gate only: once a name exists, resume profile onboarding at AGE.
    val hasProfile = OnboardingRpcHelpers.hasProfile(userAddress)
    if (!hasProfile) return@withContext OnboardingStep.AGE

    // Name + profile are present — treat onboarding as complete.
    prefs.edit().putString(key, ONBOARDING_COMPLETE_V2).apply()
    null
  } catch (e: Exception) {
    Log.w(TAG, "checkOnboardingStatus failed: ${e.message}")
    // If resolution fails, default to name phase so users can still recover.
    OnboardingStep.NAME
  }
}

/** Mark onboarding as complete in SharedPreferences */
fun markOnboardingComplete(context: Context, userAddress: String) {
  val prefs = context.getSharedPreferences("heaven_onboarding", Context.MODE_PRIVATE)
  prefs.edit().putString("onboarding:${userAddress.lowercase()}", ONBOARDING_COMPLETE_V2).apply()
}

@Composable
fun OnboardingScreen(
  activity: androidx.fragment.app.FragmentActivity?,
  userEthAddress: String,
  tempoAddress: String?,
  tempoCredentialId: String?,
  tempoPubKeyX: String?,
  tempoPubKeyY: String?,
  tempoRpId: String = TempoPasskeyManager.DEFAULT_RP_ID,
  initialStep: OnboardingStep = OnboardingStep.NAME,
  onEnsureMessagingInbox: (suspend (SessionKeyManager.SessionKey?) -> String?)? = null,
  onComplete: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val context = androidx.compose.ui.platform.LocalContext.current
  val tempoAccount = remember(tempoAddress, tempoCredentialId, tempoPubKeyX, tempoPubKeyY, tempoRpId, userEthAddress) {
    TempoAccountFactory
      .fromSession(
        tempoAddress = tempoAddress,
        tempoCredentialId = tempoCredentialId,
        tempoPubKeyX = tempoPubKeyX,
        tempoPubKeyY = tempoPubKeyY,
        tempoRpId = tempoRpId,
      )
      ?.takeIf { account ->
        userEthAddress.isBlank() || account.address.equals(userEthAddress, ignoreCase = true)
      }
  }
  val canUseTempoNameRegistration = tempoAccount != null && activity != null

  var step by remember { mutableStateOf(initialStep) }
  var submitting by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  // Collected data
  var claimedName by remember { mutableStateOf("") }
  var claimedTld by remember { mutableStateOf("heaven") }
  var selectedNameTld by remember { mutableStateOf("heaven") }
  var age by remember { mutableIntStateOf(0) }
  var gender by remember { mutableStateOf("") }
  var selectedLocation by remember { mutableStateOf<sc.pirate.app.onboarding.steps.LocationResult?>(null) }
  var location by remember { mutableStateOf("") }
  var onboardingSessionKey by remember(tempoAccount?.address) { mutableStateOf<SessionKeyManager.SessionKey?>(null) }
  var sessionSetupStatus by remember(tempoAccount?.address) { mutableStateOf(SessionSetupStatus.CHECKING) }
  var sessionSetupError by remember(tempoAccount?.address) { mutableStateOf<String?>(null) }

  // When resuming profile phase directly, recover claimed name/tld from chain.
  LaunchedEffect(step, userEthAddress, claimedName) {
    if (step == OnboardingStep.NAME) return@LaunchedEffect
    if (claimedName.isNotBlank()) return@LaunchedEffect
    val primary = runCatching { TempoNameRecordsApi.getPrimaryNameDetails(userEthAddress) }.getOrNull()
    val primaryName = primary ?: return@LaunchedEffect
    val recoveredLabel = primaryName.label.trim().lowercase()
    if (recoveredLabel.isBlank()) return@LaunchedEffect
    claimedName = recoveredLabel
    val recoveredTld = primaryName.tld?.trim()?.lowercase().orEmpty()
    if (recoveredTld.isNotBlank()) {
      claimedTld = recoveredTld
      selectedNameTld = recoveredTld
    }
    Log.d(TAG, "Recovered onboarding name context: ${primaryName.fullName}")
  }

  fun applySessionResult(sessionResult: OnboardingSessionResult?) {
    if (sessionResult == null) return
    onboardingSessionKey = sessionResult.sessionKey
    sessionSetupStatus = sessionResult.status
    sessionSetupError = sessionResult.error
  }

  fun stepNeedsSessionKey(currentStep: OnboardingStep): Boolean {
    return currentStep != OnboardingStep.NAME && currentStep != OnboardingStep.DONE
  }

  LaunchedEffect(activity, tempoAccount?.address, step) {
    val hostActivity = activity
    val account = tempoAccount
    if (hostActivity == null || account == null) return@LaunchedEffect

    if (stepNeedsSessionKey(step)) {
      sessionSetupStatus = SessionSetupStatus.AUTHORIZING
      sessionSetupError = null
      val sessionResult =
        ensureOnboardingSessionKey(
          activity = hostActivity,
          account = account,
          currentSessionKey = onboardingSessionKey,
          forceRefresh = false,
          onProgress = { status -> sessionSetupStatus = status },
        )
      onboardingSessionKey = sessionResult.sessionKey
      sessionSetupStatus = sessionResult.status
      sessionSetupError = sessionResult.error
    } else {
      val known =
        resolveKnownOnboardingSessionKey(
          account = account,
          hostActivity = hostActivity,
          currentSessionKey = onboardingSessionKey,
        )
      if (known != null) {
        onboardingSessionKey = known
        sessionSetupStatus = SessionSetupStatus.READY
        sessionSetupError = null
      }
    }
  }

  val progress = step.index.toFloat() / step.total.toFloat()

  if (stepNeedsSessionKey(step) && sessionSetupStatus != SessionSetupStatus.READY) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      if (sessionSetupStatus != SessionSetupStatus.FAILED) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
      }
      val statusText = when (sessionSetupStatus) {
        SessionSetupStatus.CHECKING,
        SessionSetupStatus.AUTHORIZING,
        -> stringResource(R.string.onboarding_status_preparing)
        SessionSetupStatus.SIGNATURE_1 -> stringResource(R.string.onboarding_status_confirm_continue)
        SessionSetupStatus.SIGNATURE_2 -> stringResource(R.string.onboarding_status_one_more_confirmation)
        SessionSetupStatus.FINALIZING -> stringResource(R.string.onboarding_status_finishing_up)
        SessionSetupStatus.READY -> ""
        SessionSetupStatus.FAILED -> sessionSetupError ?: stringResource(R.string.onboarding_status_session_setup_failed)
      }
      if (sessionSetupStatus == SessionSetupStatus.FAILED) {
        Text(
          text = statusText,
          color = MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        PiratePrimaryButton(
          text = stringResource(R.string.common_retry),
          onClick = {
            scope.launch {
              sessionSetupStatus = SessionSetupStatus.AUTHORIZING
              sessionSetupError = null
              val sessionResult =
                ensureOnboardingSessionKey(
                  activity = activity,
                  account = tempoAccount,
                  currentSessionKey = onboardingSessionKey,
                  forceRefresh = true,
                  onProgress = { status -> sessionSetupStatus = status },
                )
              onboardingSessionKey = sessionResult.sessionKey
              sessionSetupStatus = sessionResult.status
              sessionSetupError = sessionResult.error
            }
          },
        )
      } else {
        Text(
          text = statusText,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
    return
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(top = 48.dp),
  ) {
    // Back button + progress bar (Duolingo-style)
    if (step != OnboardingStep.DONE) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
      ) {
        // Back button — invisible on first step to keep layout stable
        PirateIconButton(
          onClick = {
            if (!submitting && step.ordinal > 0) {
              val prev = OnboardingStep.entries[step.ordinal - 1]
              step = prev
              error = null
            }
          },
          enabled = step.ordinal > 0,
          colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onBackground,
            disabledContentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0f),
          ),
        ) {
          Icon(
            PhosphorIcons.Regular.ArrowLeft,
            contentDescription = stringResource(R.string.onboarding_previous_screen),
            modifier = Modifier.size(24.dp),
          )
        }

        LinearProgressIndicator(
          progress = { progress.coerceIn(0f, 1f) },
          modifier = Modifier.weight(1f).padding(start = 8.dp),
          color = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
      }
      Spacer(Modifier.height(24.dp))
    }

    if (!error.isNullOrBlank() && step != OnboardingStep.NAME && step != OnboardingStep.AVATAR) {
      Text(
        text = error ?: "",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 24.dp),
      )
      Spacer(Modifier.height(12.dp))
    }

    OnboardingStepContent(
      step = step,
      submitting = submitting,
      error = error,
      selectedNameTld = selectedNameTld,
      canUseTempoNameRegistration = canUseTempoNameRegistration,
      activity = activity,
      tempoAccount = tempoAccount,
      onboardingSessionKey = onboardingSessionKey,
      claimedName = claimedName,
      claimedTld = claimedTld,
      age = age,
      gender = gender,
      selectedLocation = selectedLocation,
      location = location,
      onSelectedNameTldChange = { selectedNameTld = it },
      onClaimedNameChange = { claimedName = it },
      onClaimedTldChange = { claimedTld = it },
      onAgeChange = { age = it },
      onGenderChange = { gender = it },
      onSelectedLocationChange = { selectedLocation = it },
      onLocationChange = { location = it },
      onOnboardingSessionKeyChange = { onboardingSessionKey = it },
      onSessionSetupStatusChange = { sessionSetupStatus = it },
      onSessionSetupErrorChange = { sessionSetupError = it },
      onStepChange = { step = it },
      onSubmittingChange = { submitting = it },
      onErrorChange = { error = it },
      onApplySessionResult = { applySessionResult(it) },
      context = context,
      onEnsureMessagingInbox = onEnsureMessagingInbox,
      onComplete = onComplete,
    )
  }
}
