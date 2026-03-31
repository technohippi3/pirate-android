package sc.pirate.app.onboarding

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sc.pirate.app.R
import sc.pirate.app.fetchPublicProfileReadModel
import sc.pirate.app.profile.HeavenNamesApi
import sc.pirate.app.ui.PirateIconButton

private const val TAG = "OnboardingScreen"
private const val ONBOARDING_COMPLETE_V2 = "complete_v2"

enum class OnboardingStep {
  NAME,
  AGE,
  GENDER,
  LOCATION,
  LANGUAGES,
  AVATAR,
  DONE,
  ;

  val index: Int get() = ordinal
  val total: Int get() = entries.size - 1
}

suspend fun checkOnboardingStatus(
  context: Context,
  userAddress: String,
): OnboardingStep? = withContext(Dispatchers.IO) {
  val prefs = context.getSharedPreferences("heaven_onboarding", Context.MODE_PRIVATE)
  val key = "onboarding:${userAddress.lowercase()}"

  if (prefs.getString(key, null) == ONBOARDING_COMPLETE_V2) {
    Log.d(TAG, "checkOnboardingStatus: complete for address=$userAddress")
    return@withContext null
  }

  try {
    val publicProfile = runCatching { fetchPublicProfileReadModel(userAddress, forceRefresh = true) }.getOrNull()
    val resolvedName =
      publicProfile?.records?.primaryName?.trim()?.takeIf { it.isNotBlank() }
        ?: runCatching { HeavenNamesApi.reverse(userAddress) }.getOrNull()?.takeIf { it.label.isNotBlank() }?.fullName
    Log.d(
      TAG,
      "checkOnboardingStatus: address=$userAddress resolvedName=$resolvedName exists=${publicProfile?.exists}",
    )
    if (resolvedName == null) {
      Log.d(TAG, "checkOnboardingStatus: routing to NAME for address=$userAddress")
      return@withContext OnboardingStep.NAME
    }

    if (publicProfile?.exists != true) {
      Log.d(TAG, "checkOnboardingStatus: routing to AGE for address=$userAddress")
      return@withContext OnboardingStep.AGE
    }

    prefs.edit().putString(key, ONBOARDING_COMPLETE_V2).apply()
    Log.d(TAG, "checkOnboardingStatus: marking complete for address=$userAddress")
    null
  } catch (e: Exception) {
    Log.w(TAG, "checkOnboardingStatus failed: ${e.message}")
    OnboardingStep.NAME
  }
}

fun markOnboardingComplete(context: Context, userAddress: String) {
  val prefs = context.getSharedPreferences("heaven_onboarding", Context.MODE_PRIVATE)
  prefs.edit().putString("onboarding:${userAddress.lowercase()}", ONBOARDING_COMPLETE_V2).apply()
}

@Composable
fun OnboardingScreen(
  activity: androidx.fragment.app.FragmentActivity?,
  userEthAddress: String,
  initialStep: OnboardingStep = OnboardingStep.NAME,
  onEnsureMessagingInbox: (suspend () -> String?)? = null,
  onComplete: () -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current

  var step by remember(initialStep, userEthAddress) { mutableStateOf(initialStep) }
  var submitting by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  var claimedName by remember { mutableStateOf("") }
  var claimedTld by remember { mutableStateOf("pirate") }
  var selectedNameTld by remember { mutableStateOf("pirate") }
  var age by remember { mutableIntStateOf(0) }
  var gender by remember { mutableStateOf("") }
  var selectedLocation by remember { mutableStateOf<sc.pirate.app.onboarding.steps.LocationResult?>(null) }
  var location by remember { mutableStateOf("") }

  LaunchedEffect(initialStep, userEthAddress) {
    Log.d(TAG, "OnboardingScreen opened: address=$userEthAddress initialStep=$initialStep")
  }

  LaunchedEffect(step, userEthAddress, claimedName) {
    if (step == OnboardingStep.NAME) return@LaunchedEffect
    if (claimedName.isNotBlank()) return@LaunchedEffect

    val publicProfile = runCatching { fetchPublicProfileReadModel(userEthAddress, forceRefresh = true) }.getOrNull()
    val publicPrimary = publicProfile?.records?.primaryName?.trim().orEmpty()
    if (publicPrimary.isNotBlank()) {
      parseRecoveredNameContext(publicPrimary)?.let { (label, tld) ->
        claimedName = label
        claimedTld = tld
        selectedNameTld = tld
        Log.d(TAG, "Recovered onboarding public profile name context: $publicPrimary")
        return@LaunchedEffect
      }
    }

    val heavenName = runCatching { HeavenNamesApi.reverse(userEthAddress) }.getOrNull() ?: return@LaunchedEffect
    if (heavenName.label.isBlank()) return@LaunchedEffect
    claimedName = heavenName.label
    claimedTld = "heaven"
    selectedNameTld = "heaven"
    Log.d(TAG, "Recovered onboarding heaven name context: ${heavenName.fullName}")
  }

  val progress = step.index.toFloat() / step.total.toFloat()

  Column(
    modifier = Modifier.fillMaxSize().padding(top = 48.dp),
  ) {
    if (step != OnboardingStep.DONE) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
      ) {
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
      ownerAddress = userEthAddress,
      selectedNameTld = selectedNameTld,
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
      onStepChange = { step = it },
      onSubmittingChange = { submitting = it },
      onErrorChange = { error = it },
      context = context,
      onEnsureMessagingInbox = onEnsureMessagingInbox,
      onComplete = onComplete,
    )
  }
}

private fun parseRecoveredNameContext(fullName: String): Pair<String, String>? {
  val normalized = fullName.trim().lowercase().removePrefix("@")
  val separator = normalized.lastIndexOf('.')
  if (separator <= 0 || separator >= normalized.lastIndex) return null
  val label = normalized.substring(0, separator).trim()
  val tld = normalized.substring(separator + 1).trim()
  if (label.isBlank() || tld.isBlank()) return null
  return label to tld
}
