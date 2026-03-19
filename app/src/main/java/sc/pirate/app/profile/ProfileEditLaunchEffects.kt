package sc.pirate.app.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import sc.pirate.app.onboarding.steps.LocationResult
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoPasskeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ProfileEditLaunchEffects(
  activity: FragmentActivity,
  ethAddress: String,
  tempoAccount: TempoPasskeyManager.PasskeyAccount?,
  onSetLoading: (Boolean) -> Unit,
  onSetError: (String?) -> Unit,
  onSetDraft: (ContractProfileData) -> Unit,
  onSetPirateName: (String?) -> Unit,
  onSetProfileNode: (String?) -> Unit,
  onSetAvatarUri: (String?) -> Unit,
  onSetAvatarPreviewBitmap: (android.graphics.Bitmap?) -> Unit,
  onSetAvatarBase64: (String?) -> Unit,
  onSetAvatarDirty: (Boolean) -> Unit,
  onSetCoverUri: (String?) -> Unit,
  onSetCoverPreviewBitmap: (android.graphics.Bitmap?) -> Unit,
  onSetCoverBase64: (String?) -> Unit,
  onSetCoverDirty: (Boolean) -> Unit,
  onSetLocationLabel: (String) -> Unit,
  onSetSelectedLocation: (LocationResult?) -> Unit,
  onSetLocationDirty: (Boolean) -> Unit,
  onSetSchoolName: (String) -> Unit,
  onSetSchoolDirty: (Boolean) -> Unit,
  onSetSessionKey: (SessionKeyManager.SessionKey?) -> Unit,
) {
  LaunchedEffect(ethAddress, tempoAccount?.address, tempoAccount?.credentialId) {
    onSetLoading(true)
    onSetError(null)
    runCatching {
      withContext(Dispatchers.IO) {
        val profile = ProfileContractApi.fetchProfile(ethAddress) ?: ProfileContractApi.emptyProfile()
        val name = TempoNameRecordsApi.getPrimaryName(ethAddress)
        val node = name?.let { TempoNameRecordsApi.computeNode(it) }
        val avatarRecord = node?.let { TempoNameRecordsApi.getTextRecord(it, "avatar") }
        val coverRecord = node?.let { TempoNameRecordsApi.getTextRecord(it, TempoNameRecordsApi.PROFILE_COVER_RECORD_KEY) }
        val locationRecord = node?.let { TempoNameRecordsApi.getTextRecord(it, "heaven.location") }
        val schoolRecord = node?.let { TempoNameRecordsApi.getTextRecord(it, "heaven.school") }
        LoadedProfileContext(
          profile = profile,
          heavenName = name,
          node = node,
          avatarRecord = avatarRecord,
          coverRecord = coverRecord,
          locationRecord = locationRecord,
          schoolRecord = schoolRecord,
        )
      }
    }
      .onSuccess { loadedContext ->
        val loadedProfile = loadedContext.profile
        onSetDraft(loadedProfile)
        onSetPirateName(loadedContext.heavenName)
        onSetProfileNode(loadedContext.node)

        onSetAvatarUri(loadedContext.avatarRecord?.ifBlank { null } ?: loadedProfile.photoUri.ifBlank { null })
        onSetAvatarPreviewBitmap(null)
        onSetAvatarBase64(null)
        onSetAvatarDirty(false)
        onSetCoverUri(loadedContext.coverRecord?.ifBlank { null })
        onSetCoverPreviewBitmap(null)
        onSetCoverBase64(null)
        onSetCoverDirty(false)

        val nextLocationLabel = loadedContext.locationRecord?.trim().orEmpty()
        onSetLocationLabel(nextLocationLabel)
        onSetSelectedLocation(
          if (nextLocationLabel.isNotBlank() && ProfileContractApi.hasCoords(loadedProfile.locationLatE6, loadedProfile.locationLngE6)) {
            LocationResult(
              label = nextLocationLabel,
              lat = loadedProfile.locationLatE6.toDouble() / 1_000_000.0,
              lng = loadedProfile.locationLngE6.toDouble() / 1_000_000.0,
              countryCode = null,
            )
          } else {
            null
          },
        )
        onSetLocationDirty(false)

        onSetSchoolName(loadedContext.schoolRecord?.trim().orEmpty())
        onSetSchoolDirty(false)

        // Load session key for silent signing.
        if (tempoAccount != null) {
          val loaded = SessionKeyManager.load(activity)
          if (SessionKeyManager.isValid(loaded, ownerAddress = tempoAccount.address) &&
            loaded?.keyAuthorization?.isNotEmpty() == true
          ) {
            onSetSessionKey(loaded)
          }
        }
        onSetLoading(false)
      }
      .onFailure { error ->
        onSetLoading(false)
        onSetError(error.message ?: "Failed to load profile")
      }
  }
}
