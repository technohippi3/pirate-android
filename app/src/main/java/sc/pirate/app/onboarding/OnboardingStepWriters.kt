package sc.pirate.app.onboarding

import android.content.Context
import android.util.Base64
import android.util.Log
import sc.pirate.app.onboarding.steps.LanguageEntry
import sc.pirate.app.onboarding.steps.LocationResult
import sc.pirate.app.onboarding.steps.packLanguages
import sc.pirate.app.profile.ProfileAvatarUploadApi
import sc.pirate.app.profile.TempoNameRecordsApi
import sc.pirate.app.profile.TempoProfileContractApi
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoPasskeyManager
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "OnboardingWriters"

internal data class OnboardingStepWriteResult(
  val success: Boolean,
  val error: String? = null,
  val sessionResult: OnboardingSessionResult? = null,
)

internal suspend fun submitOnboardingProfileStep(
  context: Context,
  activity: androidx.fragment.app.FragmentActivity?,
  account: TempoPasskeyManager.PasskeyAccount?,
  currentSessionKey: SessionKeyManager.SessionKey?,
  age: Int,
  gender: String,
  selectedLocation: LocationResult?,
  languages: List<LanguageEntry>,
  claimedName: String,
  claimedTld: String,
  locationLabel: String,
): OnboardingStepWriteResult {
  val activeAccount =
    account ?: return OnboardingStepWriteResult(
      success = false,
      error = "Tempo account required for onboarding.",
    )

  val sessionResolution =
    resolveOnboardingSessionKeyForWrites(
      activity = activity,
      account = activeAccount,
      currentSessionKey = currentSessionKey,
    )
  val sessionKey =
    sessionResolution.sessionKey
      ?: return OnboardingStepWriteResult(
        success = false,
        error = sessionResolution.error ?: "Session key unavailable.",
        sessionResult = sessionResolution,
      )
  val hostActivity =
    activity ?: return OnboardingStepWriteResult(
      success = false,
      error = "Missing activity for Tempo transaction signing.",
      sessionResult = sessionResolution,
    )

  val contentPubKeyError =
    ensureOnboardingContentPubKeyPublished(
      context = context,
      activity = hostActivity,
      account = activeAccount,
      sessionKey = sessionKey,
      claimedName = claimedName,
      claimedTld = claimedTld,
    )
  if (contentPubKeyError != null) {
    Log.w(TAG, "contentPubKey publish failed at LANGUAGES: $contentPubKeyError")
    return OnboardingStepWriteResult(
      success = false,
      error = contentPubKeyError,
      sessionResult = sessionResolution,
    )
  }

  val profileInput =
    JSONObject()
      .put("age", age)
      .put("gender", genderToEnum(gender))
      .put("languagesPacked", packLanguages(languages))

  if (selectedLocation != null) {
    val cityHash = OnboardingRpcHelpers.keccak256(selectedLocation.label.toByteArray(Charsets.UTF_8))
    profileInput.put("locationCityId", "0x" + OnboardingRpcHelpers.bytesToHex(cityHash))
    profileInput.put("locationLatE6", (selectedLocation.lat * 1_000_000.0).roundToInt())
    profileInput.put("locationLngE6", (selectedLocation.lng * 1_000_000.0).roundToInt())
  }

  val profileResult =
    TempoProfileContractApi.upsertProfile(
      activity = hostActivity,
      account = activeAccount,
      profileInput = profileInput,
      rpId = activeAccount.rpId,
      sessionKey = sessionKey,
    )
  val profileError = if (profileResult.success) null else profileResult.error ?: "setProfile failed"
  if (profileError != null) {
    Log.w(TAG, "setProfile failed: $profileError")
  }

  if (claimedName.isNotBlank() && locationLabel.isNotBlank()) {
    try {
      val node = TempoNameRecordsApi.computeNode("$claimedName.$claimedTld")
      val writeResult =
        TempoNameRecordsApi.setTextRecords(
          activity = hostActivity,
          account = activeAccount,
          node = node,
          keys = listOf("heaven.location"),
          values = listOf(locationLabel),
          rpId = activeAccount.rpId,
          sessionKey = sessionKey,
        )
      if (!writeResult.success) {
        Log.w(TAG, "setTextRecord location failed: ${writeResult.error}")
      }
    } catch (e: Exception) {
      Log.w(TAG, "setTextRecord location failed: ${e.message}")
    }
  }

  return OnboardingStepWriteResult(
    success = true,
    sessionResult = sessionResolution,
  )
}

internal suspend fun submitOnboardingMusicStep(
  activity: androidx.fragment.app.FragmentActivity?,
  account: TempoPasskeyManager.PasskeyAccount?,
  currentSessionKey: SessionKeyManager.SessionKey?,
  selectedArtists: List<OnboardingArtist>,
  claimedName: String,
  claimedTld: String,
): OnboardingStepWriteResult {
  if (selectedArtists.isEmpty() || claimedName.isBlank()) {
    return OnboardingStepWriteResult(success = true)
  }
  val activeAccount =
    account ?: return OnboardingStepWriteResult(
      success = false,
      error = "Tempo account required for onboarding.",
    )
  val hostActivity =
    activity ?: return OnboardingStepWriteResult(
      success = false,
      error = "Missing activity for Tempo transaction signing.",
    )

  val sessionResolution =
    resolveOnboardingSessionKeyForWrites(
      activity = hostActivity,
      account = activeAccount,
      currentSessionKey = currentSessionKey,
    )
  val sessionKey =
    sessionResolution.sessionKey
      ?: return OnboardingStepWriteResult(
        success = false,
        error = sessionResolution.error ?: "Session key unavailable.",
        sessionResult = sessionResolution,
      )

  val node = TempoNameRecordsApi.computeNode("$claimedName.$claimedTld")
  val mbids = selectedArtists.map { it.mbid }.distinct()
  val musicPayload =
    JSONObject()
      .put("version", 1)
      .put("source", "manual")
      .put("updatedAt", System.currentTimeMillis() / 1000)
      .put("artistMbids", JSONArray(mbids))
  val writeResult =
    TempoNameRecordsApi.setTextRecords(
      activity = hostActivity,
      account = activeAccount,
      node = node,
      keys = listOf("heaven.music.v1", "heaven.music.count"),
      values = listOf(musicPayload.toString(), mbids.size.toString()),
      rpId = activeAccount.rpId,
      sessionKey = sessionKey,
    )
  if (!writeResult.success) {
    Log.w(TAG, "Music save failed: ${writeResult.error}")
  }

  return OnboardingStepWriteResult(
    success = true,
    sessionResult = sessionResolution,
  )
}

internal suspend fun submitOnboardingAvatarContinue(
  context: Context,
  activity: androidx.fragment.app.FragmentActivity?,
  account: TempoPasskeyManager.PasskeyAccount?,
  currentSessionKey: SessionKeyManager.SessionKey?,
  claimedName: String,
  claimedTld: String,
  avatarBase64: String,
): OnboardingStepWriteResult {
  val hostActivity =
    activity ?: return OnboardingStepWriteResult(
      success = false,
      error = "Tempo account required for avatar upload.",
    )
  val activeAccount =
    account ?: return OnboardingStepWriteResult(
      success = false,
      error = "Tempo account required for avatar upload.",
    )

  val sessionResolution =
    resolveOnboardingSessionKeyForWrites(
      activity = hostActivity,
      account = activeAccount,
      currentSessionKey = currentSessionKey,
    )
  val sessionKey =
    sessionResolution.sessionKey
      ?: return OnboardingStepWriteResult(
        success = false,
        error = sessionResolution.error ?: "Session key unavailable.",
        sessionResult = sessionResolution,
      )
  val contentPubKeyError =
    ensureOnboardingContentPubKeyPublished(
      context = context,
      activity = hostActivity,
      account = activeAccount,
      sessionKey = sessionKey,
      claimedName = claimedName,
      claimedTld = claimedTld,
    )
  if (contentPubKeyError != null) {
    Log.w(TAG, "contentPubKey publish failed at AVATAR_CONTINUE: $contentPubKeyError")
    return OnboardingStepWriteResult(
      success = false,
      error = contentPubKeyError,
      sessionResult = sessionResolution,
    )
  }

  val jpegBytes = Base64.decode(avatarBase64, Base64.DEFAULT)
  Log.d(TAG, "submitOnboardingAvatarContinue: decoded ${jpegBytes.size} bytes, uploading...")
  val uploadResult = withContext(Dispatchers.IO) {
    ProfileAvatarUploadApi.uploadAvatarJpeg(
      ownerEthAddress = activeAccount.address,
      jpegBytes = jpegBytes,
    )
  }
  Log.d(TAG, "submitOnboardingAvatarContinue: upload result success=${uploadResult.success} ref=${uploadResult.avatarRef} error=${uploadResult.error}")
  if (!uploadResult.success || uploadResult.avatarRef.isNullOrBlank()) {
    return OnboardingStepWriteResult(
      success = false,
      error = uploadResult.error ?: "Avatar upload failed",
      sessionResult = sessionResolution,
    )
  }

  if (claimedName.isNotBlank()) {
    val node = TempoNameRecordsApi.computeNode("$claimedName.$claimedTld")
    Log.d(TAG, "submitOnboardingAvatarContinue: setting avatar text record for $claimedName.$claimedTld node=$node ref=${uploadResult.avatarRef}")
    val writeResult =
      TempoNameRecordsApi.setTextRecords(
        activity = hostActivity,
        account = activeAccount,
        node = node,
        keys = listOf("avatar"),
        values = listOf(uploadResult.avatarRef),
        rpId = activeAccount.rpId,
        sessionKey = sessionKey,
      )
    Log.d(TAG, "submitOnboardingAvatarContinue: setTextRecords success=${writeResult.success} error=${writeResult.error}")
    if (!writeResult.success) {
      return OnboardingStepWriteResult(
        success = false,
        error = writeResult.error ?: "Failed to set avatar record",
        sessionResult = sessionResolution,
      )
    }
  } else {
    Log.w(TAG, "submitOnboardingAvatarContinue: claimedName is blank — avatar ref uploaded but NOT written to text record!")
  }

  return OnboardingStepWriteResult(
    success = true,
    sessionResult = sessionResolution,
  )
}

internal suspend fun submitOnboardingAvatarSkip(
  context: Context,
  activity: androidx.fragment.app.FragmentActivity?,
  account: TempoPasskeyManager.PasskeyAccount?,
  currentSessionKey: SessionKeyManager.SessionKey?,
  claimedName: String,
  claimedTld: String,
): OnboardingStepWriteResult {
  val activeAccount =
    account ?: return OnboardingStepWriteResult(
      success = false,
      error = "Tempo account required for onboarding.",
    )
  val hostActivity =
    activity ?: return OnboardingStepWriteResult(
      success = false,
      error = "Missing activity for Tempo transaction signing.",
    )

  val sessionResolution =
    resolveOnboardingSessionKeyForWrites(
      activity = hostActivity,
      account = activeAccount,
      currentSessionKey = currentSessionKey,
    )
  val sessionKey =
    sessionResolution.sessionKey
      ?: return OnboardingStepWriteResult(
        success = false,
        error = sessionResolution.error ?: "Session key unavailable.",
        sessionResult = sessionResolution,
      )

  val contentPubKeyError =
    ensureOnboardingContentPubKeyPublished(
      context = context,
      activity = hostActivity,
      account = activeAccount,
      sessionKey = sessionKey,
      claimedName = claimedName,
      claimedTld = claimedTld,
    )
  if (contentPubKeyError != null) {
    Log.w(TAG, "contentPubKey publish failed at AVATAR_SKIP: $contentPubKeyError")
    return OnboardingStepWriteResult(
      success = false,
      error = contentPubKeyError,
      sessionResult = sessionResolution,
    )
  }

  return OnboardingStepWriteResult(
    success = true,
    sessionResult = sessionResolution,
  )
}

private fun genderToEnum(gender: String): Int {
  return when (gender) {
    "woman" -> 1
    "man" -> 2
    "nonbinary" -> 3
    "transwoman" -> 4
    "transman" -> 5
    "intersex" -> 6
    "other" -> 7
    else -> 0
  }
}
