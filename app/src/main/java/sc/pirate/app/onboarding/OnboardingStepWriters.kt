package sc.pirate.app.onboarding

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sc.pirate.app.auth.privy.PrivyRelayClient
import sc.pirate.app.auth.privy.PrivyRelayLanguageEntry
import sc.pirate.app.auth.privy.PrivyRelayProfileInput
import sc.pirate.app.onboarding.steps.LanguageEntry
import sc.pirate.app.onboarding.steps.LocationResult
import sc.pirate.app.profile.ProfileAvatarUploadApi

private const val TAG = "OnboardingWriters"

internal data class OnboardingStepWriteResult(
  val success: Boolean,
  val error: String? = null,
)

internal suspend fun submitOnboardingProfileStep(
  context: Context,
  ownerAddress: String,
  age: Int,
  gender: String,
  selectedLocation: LocationResult?,
  languages: List<LanguageEntry>,
  claimedName: String,
): OnboardingStepWriteResult {
  if (ownerAddress.isBlank()) {
    return OnboardingStepWriteResult(
      success = false,
      error = "Sign in to continue onboarding.",
    )
  }

  val profileInput =
    PrivyRelayProfileInput(
      displayName = claimedName.trim().ifBlank { "pirate" },
      age = age,
      gender = gender.trim().lowercase(),
      location = selectedLocation?.label,
      languages =
        languages.map { entry ->
          PrivyRelayLanguageEntry(
            code = entry.code,
            proficiency = entry.proficiency,
          )
        },
    )
  val profileResult =
    runCatching { PrivyRelayClient.upsertProfile(context = context, input = profileInput) }
  val profileError = profileResult.exceptionOrNull()?.message
  if (profileError != null) {
    Log.w(TAG, "setProfile failed: $profileError")
    return OnboardingStepWriteResult(success = false, error = profileError)
  }
  return OnboardingStepWriteResult(success = true)
}

internal suspend fun submitOnboardingAvatarContinue(
  context: Context,
  ownerAddress: String,
  claimedName: String,
  claimedTld: String,
  locationLabel: String,
  avatarBase64: String,
): OnboardingStepWriteResult {
  if (ownerAddress.isBlank()) {
    return OnboardingStepWriteResult(
      success = false,
      error = "Sign in to continue onboarding.",
    )
  }

  val jpegBytes = Base64.decode(avatarBase64, Base64.DEFAULT)
  Log.d(TAG, "submitOnboardingAvatarContinue: decoded ${jpegBytes.size} bytes, uploading...")
  val uploadResult = withContext(Dispatchers.IO) {
    ProfileAvatarUploadApi.uploadAvatarJpeg(
      ownerEthAddress = ownerAddress,
      jpegBytes = jpegBytes,
    )
  }
  Log.d(TAG, "submitOnboardingAvatarContinue: upload result success=${uploadResult.success} ref=${uploadResult.avatarRef} error=${uploadResult.error}")
  if (!uploadResult.success || uploadResult.avatarRef.isNullOrBlank()) {
    return OnboardingStepWriteResult(
      success = false,
      error = uploadResult.error ?: "Avatar upload failed",
    )
  }

  if (claimedName.isNotBlank()) {
    val writeResult =
      runCatching {
        PrivyRelayClient.submitOnboardingRecords(
          context = context,
          nameLabel = claimedName,
          nameTld = claimedTld,
          location = locationLabel,
          avatarRef = uploadResult.avatarRef,
        )
      }
    val writeError = writeResult.exceptionOrNull()?.message
    Log.d(TAG, "submitOnboardingAvatarContinue: onboarding records error=$writeError")
    if (writeError != null) {
      return OnboardingStepWriteResult(success = false, error = writeError)
    }
  } else {
    Log.w(TAG, "submitOnboardingAvatarContinue: claimedName is blank; avatar ref uploaded but not written")
  }

  return OnboardingStepWriteResult(success = true)
}

internal suspend fun submitOnboardingAvatarSkip(
  context: Context,
  ownerAddress: String,
  claimedName: String,
  claimedTld: String,
  locationLabel: String,
): OnboardingStepWriteResult {
  if (ownerAddress.isBlank()) {
    return OnboardingStepWriteResult(
      success = false,
      error = "Sign in to continue onboarding.",
    )
  }
  if (claimedName.isBlank()) return OnboardingStepWriteResult(success = true)

  val writeResult =
    runCatching {
      PrivyRelayClient.submitOnboardingRecords(
        context = context,
        nameLabel = claimedName,
        nameTld = claimedTld,
        location = locationLabel,
        avatarRef = null,
      )
    }
  val writeError = writeResult.exceptionOrNull()?.message
  if (writeError != null) {
    return OnboardingStepWriteResult(success = false, error = writeError)
  }

  return OnboardingStepWriteResult(success = true)
}
