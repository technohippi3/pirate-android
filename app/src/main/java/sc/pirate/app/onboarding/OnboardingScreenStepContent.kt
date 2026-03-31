package sc.pirate.app.onboarding

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import sc.pirate.app.AppLocaleManager
import sc.pirate.app.R
import sc.pirate.app.auth.privy.PrivyRelayClient
import sc.pirate.app.onboarding.steps.AgeStep
import sc.pirate.app.onboarding.steps.AvatarStep
import sc.pirate.app.onboarding.steps.DoneStep
import sc.pirate.app.onboarding.steps.GenderStep
import sc.pirate.app.onboarding.steps.LanguagesStep
import sc.pirate.app.onboarding.steps.LocationResult
import sc.pirate.app.onboarding.steps.LocationStep
import sc.pirate.app.onboarding.steps.NameAvailabilityResult
import sc.pirate.app.onboarding.steps.NameStep
import sc.pirate.app.profile.BaseNameRegistryApi
import sc.pirate.app.profile.HeavenNamesApi

private const val ONBOARDING_STEP_CONTENT_TAG = "OnboardingStepContent"

private data class OnboardingNameClaimResult(
  val success: Boolean,
  val claimedLabel: String? = null,
  val claimedTld: String? = null,
  val error: String? = null,
)

@Composable
internal fun OnboardingStepContent(
  step: OnboardingStep,
  submitting: Boolean,
  error: String?,
  ownerAddress: String,
  selectedNameTld: String,
  claimedName: String,
  claimedTld: String,
  age: Int,
  gender: String,
  selectedLocation: LocationResult?,
  location: String,
  onSelectedNameTldChange: (String) -> Unit,
  onClaimedNameChange: (String) -> Unit,
  onClaimedTldChange: (String) -> Unit,
  onAgeChange: (Int) -> Unit,
  onGenderChange: (String) -> Unit,
  onSelectedLocationChange: (LocationResult?) -> Unit,
  onLocationChange: (String) -> Unit,
  onStepChange: (OnboardingStep) -> Unit,
  onSubmittingChange: (Boolean) -> Unit,
  onErrorChange: (String?) -> Unit,
  context: Context,
  onEnsureMessagingInbox: (suspend () -> String?)?,
  onComplete: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val registrationFailedError = stringResource(R.string.onboarding_name_error_registration_failed)
  val saveProfileError = stringResource(R.string.onboarding_error_save_profile)
  val avatarUploadError = stringResource(R.string.onboarding_error_avatar_upload)
  val finishOnboardingError = stringResource(R.string.onboarding_error_finish)

  AnimatedContent(
    targetState = step,
    transitionSpec = {
      slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
    },
    label = "onboarding-step",
  ) { currentStep ->
    when (currentStep) {
      OnboardingStep.NAME -> NameStep(
        submitting = submitting,
        error = error,
        selectedTld = selectedNameTld,
        onTldChange = onSelectedNameTldChange,
        onCheckAvailable = { label, tld ->
          checkOnboardingNameAvailability(
            label = label,
            tld = tld,
          )
        },
        onContinue = { label, tld ->
          scope.launch {
            onSubmittingChange(true)
            onErrorChange(null)
            try {
              val claimResult =
                registerOnboardingName(
                  context = context,
                  ownerAddress = ownerAddress,
                  label = label,
                  tld = tld,
                  registrationFailedError = registrationFailedError,
                )

              if (claimResult.success) {
                onClaimedNameChange(claimResult.claimedLabel ?: label)
                onClaimedTldChange(claimResult.claimedTld ?: tld.trim().lowercase())
                onStepChange(OnboardingStep.AGE)
              } else {
                onErrorChange(claimResult.error ?: registrationFailedError)
              }
            } catch (e: Exception) {
              onErrorChange(e.message ?: registrationFailedError)
            } finally {
              onSubmittingChange(false)
            }
          }
        },
      )

      OnboardingStep.AGE -> AgeStep(
        submitting = submitting,
        onContinue = { ageValue ->
          onAgeChange(ageValue)
          onStepChange(OnboardingStep.GENDER)
        },
      )

      OnboardingStep.GENDER -> GenderStep(
        submitting = submitting,
        onContinue = { genderValue ->
          onGenderChange(genderValue)
          onStepChange(OnboardingStep.LOCATION)
        },
      )

      OnboardingStep.LOCATION -> LocationStep(
        submitting = submitting,
        onContinue = { result ->
          onSelectedLocationChange(result)
          onLocationChange(result.label)
          onStepChange(OnboardingStep.LANGUAGES)
        },
      )

      OnboardingStep.LANGUAGES -> LanguagesStep(
        submitting = submitting,
        onContinue = { langs ->
          scope.launch {
            onSubmittingChange(true)
            onErrorChange(null)
            try {
              val writeResult =
                submitOnboardingProfileStep(
                  context = context,
                  ownerAddress = ownerAddress,
                  age = age,
                  gender = gender,
                  selectedLocation = selectedLocation,
                  languages = langs,
                  claimedName = claimedName,
                )
              if (!writeResult.success) {
                onErrorChange(writeResult.error ?: saveProfileError)
                return@launch
              }
              AppLocaleManager.preferredLocaleFromOnboardingLanguages(langs)?.let { localeTag ->
                AppLocaleManager.storePreferredLocale(context, localeTag)
              }
              onStepChange(OnboardingStep.AVATAR)
            } catch (e: Exception) {
              onErrorChange(e.message ?: saveProfileError)
            } finally {
              onSubmittingChange(false)
            }
          }
        },
      )

      OnboardingStep.AVATAR -> AvatarStep(
        submitting = submitting,
        error = error,
        onContinue = { base64, _ ->
          scope.launch {
            onSubmittingChange(true)
            onErrorChange(null)
            try {
              val writeResult =
                submitOnboardingAvatarContinue(
                  context = context,
                  ownerAddress = ownerAddress,
                  claimedName = claimedName,
                  claimedTld = claimedTld,
                  locationLabel = location,
                  avatarBase64 = base64,
                )
              if (!writeResult.success) {
                onErrorChange(writeResult.error ?: avatarUploadError)
                return@launch
              }
              val messagingError = onEnsureMessagingInbox?.invoke()
              if (!messagingError.isNullOrBlank()) {
                onErrorChange(messagingError)
                return@launch
              }
              markOnboardingComplete(context, ownerAddress)
              onStepChange(OnboardingStep.DONE)
            } catch (e: Exception) {
              onErrorChange(e.message ?: avatarUploadError)
            } finally {
              onSubmittingChange(false)
            }
          }
        },
        onSkip = {
          scope.launch {
            onSubmittingChange(true)
            onErrorChange(null)
            try {
              val writeResult =
                submitOnboardingAvatarSkip(
                  context = context,
                  ownerAddress = ownerAddress,
                  claimedName = claimedName,
                  claimedTld = claimedTld,
                  locationLabel = location,
                )
              if (!writeResult.success) {
                onErrorChange(writeResult.error ?: finishOnboardingError)
                return@launch
              }
              val messagingError = onEnsureMessagingInbox?.invoke()
              if (!messagingError.isNullOrBlank()) {
                onErrorChange(messagingError)
                return@launch
              }
              markOnboardingComplete(context, ownerAddress)
              onStepChange(OnboardingStep.DONE)
            } finally {
              onSubmittingChange(false)
            }
          }
        },
      )

      OnboardingStep.DONE -> DoneStep(
        claimedName = claimedName,
        onFinished = onComplete,
      )
    }
  }
}

private suspend fun checkOnboardingNameAvailability(
  label: String,
  tld: String,
): NameAvailabilityResult {
  return when (tld.trim().lowercase()) {
    "pirate" ->
      if (BaseNameRegistryApi.checkPirateNameAvailable(label)) {
        NameAvailabilityResult.Available
      } else {
        NameAvailabilityResult.Unavailable()
      }

    "heaven" ->
      if (HeavenNamesApi.checkNameAvailable(label)) {
        NameAvailabilityResult.Available
      } else {
        NameAvailabilityResult.Unavailable()
      }

    else ->
      NameAvailabilityResult.Unavailable(
        message = "Unsupported TLD: .$tld",
      )
  }
}

private suspend fun registerOnboardingName(
  context: Context,
  ownerAddress: String,
  label: String,
  tld: String,
  registrationFailedError: String,
): OnboardingNameClaimResult {
  val normalizedTld = tld.trim().lowercase()
  Log.d(
    ONBOARDING_STEP_CONTENT_TAG,
    "registerOnboardingName: address=$ownerAddress label=$label tld=$normalizedTld",
  )
  if (ownerAddress.isBlank()) {
    return OnboardingNameClaimResult(
      success = false,
      error = "Sign in to continue onboarding.",
    )
  }

  return when (normalizedTld) {
    "pirate" -> {
      val result =
        runCatching {
          val quote = BaseNameRegistryApi.quotePirateRegistration(label)
          PrivyRelayClient.registerPirateName(context = context, quote = quote)
        }
      result.exceptionOrNull()?.let { error ->
        Log.w(
          ONBOARDING_STEP_CONTENT_TAG,
          "registerOnboardingName(.pirate) failed: ${error.message}",
          error,
        )
      }
      val registered = result.getOrNull()
      if (registered == null) {
        OnboardingNameClaimResult(
          success = false,
          error = result.exceptionOrNull()?.message ?: registrationFailedError,
        )
      } else {
        OnboardingNameClaimResult(
          success = true,
          claimedLabel = registered.label,
          claimedTld = registered.tld,
        )
      }
    }

    "heaven" -> {
      val result =
        HeavenNamesApi.register(
          context = context,
          ownerAddress = ownerAddress,
          label = label,
        )
      if (!result.success) {
        Log.w(
          ONBOARDING_STEP_CONTENT_TAG,
          "registerOnboardingName(.heaven) failed: ${result.error}",
        )
      }
      if (!result.success) {
        OnboardingNameClaimResult(
          success = false,
          error = result.error ?: registrationFailedError,
        )
      } else {
        OnboardingNameClaimResult(
          success = true,
          claimedLabel = result.label ?: label,
          claimedTld = "heaven",
        )
      }
    }

    else ->
      OnboardingNameClaimResult(
        success = false,
        error = "Unsupported TLD: .$normalizedTld",
      )
  }
}
