package sc.pirate.app.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import sc.pirate.app.onboarding.steps.LocationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ProfileEditLaunchEffects(
  ethAddress: String,
  onSetLoading: (Boolean) -> Unit,
  onSetError: (String?) -> Unit,
  onSetDraft: (ContractProfileData) -> Unit,
  onSetPrimaryName: (String?) -> Unit,
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
) {
  LaunchedEffect(ethAddress) {
    onSetLoading(true)
    onSetError(null)
    runCatching {
      withContext(Dispatchers.IO) {
        val profile = ProfileContractApi.fetchProfile(ethAddress) ?: ProfileContractApi.emptyProfile()
        val name = PirateNameRecordsApi.getPrimaryName(ethAddress)
        val node = name?.let { PirateNameRecordsApi.computeNode(it) }
        val avatarRecord = node?.let { PirateNameRecordsApi.getTextRecord(it, "avatar") }
        val coverRecord = node?.let { PirateNameRecordsApi.getTextRecord(it, PirateNameRecordsApi.PROFILE_COVER_RECORD_KEY) }
        val locationRecord = node?.let { PirateNameRecordsApi.getTextRecord(it, "heaven.location") }
        val schoolRecord = node?.let { PirateNameRecordsApi.getTextRecord(it, "heaven.school") }
        LoadedProfileContext(
          profile = profile,
          primaryName = name,
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
        onSetPrimaryName(loadedContext.primaryName)
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
        onSetLoading(false)
      }
      .onFailure { error ->
        onSetLoading(false)
        onSetError(error.message ?: "Failed to load profile")
      }
  }
}
