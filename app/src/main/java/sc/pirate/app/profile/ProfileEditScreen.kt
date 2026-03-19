package sc.pirate.app.profile

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*


import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import sc.pirate.app.ui.PirateTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sc.pirate.app.onboarding.steps.LocationResult
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoAccountFactory
import sc.pirate.app.tempo.TempoSessionKeyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(
  activity: androidx.fragment.app.FragmentActivity,
  ethAddress: String,
  tempoAddress: String?,
  tempoCredentialId: String?,
  tempoPubKeyX: String?,
  tempoPubKeyY: String?,
  tempoRpId: String,
  onBack: () -> Unit,
  onSaved: () -> Unit,
) {
  val appContext = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()
  val tempoAccount = remember(tempoAddress, tempoCredentialId, tempoPubKeyX, tempoPubKeyY, tempoRpId) {
    TempoAccountFactory.fromSession(
      tempoAddress = tempoAddress,
      tempoCredentialId = tempoCredentialId,
      tempoPubKeyX = tempoPubKeyX,
      tempoPubKeyY = tempoPubKeyY,
      tempoRpId = tempoRpId,
    )
  }

  var loading by remember { mutableStateOf(true) }
  var saving by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var draft by remember { mutableStateOf(ProfileContractApi.emptyProfile()) }
  var activeSheet by remember { mutableStateOf<ProfileEditSheet?>(null) }

  var heavenName by remember { mutableStateOf<String?>(null) }
  var profileNode by remember { mutableStateOf<String?>(null) }

  var avatarUri by remember { mutableStateOf<String?>(null) }
  var avatarPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var avatarBase64 by remember { mutableStateOf<String?>(null) }
  var avatarDirty by remember { mutableStateOf(false) }
  var coverUri by remember { mutableStateOf<String?>(null) }
  var coverPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var coverBase64 by remember { mutableStateOf<String?>(null) }
  var coverDirty by remember { mutableStateOf(false) }

  var locationLabel by remember { mutableStateOf("") }
  var selectedLocation by remember { mutableStateOf<LocationResult?>(null) }
  var locationDirty by remember { mutableStateOf(false) }

  var schoolName by remember { mutableStateOf("") }
  var schoolDirty by remember { mutableStateOf(false) }

  var sessionKey by remember { mutableStateOf<SessionKeyManager.SessionKey?>(null) }

  val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    uri ?: return@rememberLauncherForActivityResult
    runCatching { processAvatarImage(appContext, uri) }
      .onSuccess { (bitmap, base64) ->
        avatarPreviewBitmap = bitmap
        avatarBase64 = base64
        avatarDirty = true
      }
      .onFailure { err ->
        error = err.message ?: "Failed to process image"
      }
  }
  val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    uri ?: return@rememberLauncherForActivityResult
    runCatching { processCoverImage(appContext, uri) }
      .onSuccess { (bitmap, base64) ->
        coverPreviewBitmap = bitmap
        coverBase64 = base64
        coverDirty = true
      }
      .onFailure { err ->
        error = err.message ?: "Failed to process cover image"
      }
  }

  ProfileEditLaunchEffects(
    activity = activity,
    ethAddress = ethAddress,
    tempoAccount = tempoAccount,
    onSetLoading = { loading = it },
    onSetError = { error = it },
    onSetDraft = { draft = it },
    onSetPirateName = { heavenName = it },
    onSetProfileNode = { profileNode = it },
    onSetAvatarUri = { avatarUri = it },
    onSetAvatarPreviewBitmap = { avatarPreviewBitmap = it },
    onSetAvatarBase64 = { avatarBase64 = it },
    onSetAvatarDirty = { avatarDirty = it },
    onSetCoverUri = { coverUri = it },
    onSetCoverPreviewBitmap = { coverPreviewBitmap = it },
    onSetCoverBase64 = { coverBase64 = it },
    onSetCoverDirty = { coverDirty = it },
    onSetLocationLabel = { locationLabel = it },
    onSetSelectedLocation = { selectedLocation = it },
    onSetLocationDirty = { locationDirty = it },
    onSetSchoolName = { schoolName = it },
    onSetSchoolDirty = { schoolDirty = it },
    onSetSessionKey = { sessionKey = it },
  )

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PirateIconButton(onClick = onBack, enabled = !saving) {
        Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = "Previous screen")
      }
      Text(
        "Edit Profile",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.weight(1f),
      )
      PirateTextButton(
        onClick = {
          if (saving) return@PirateTextButton
          val activeTempoAccount = tempoAccount
          if (activeTempoAccount == null) {
            error = "Tempo passkey account required."
            return@PirateTextButton
          }
          if (!activeTempoAccount.address.equals(ethAddress, ignoreCase = true)) {
            error = "Active address does not match Tempo passkey account."
            return@PirateTextButton
          }
          saving = true
          error = null
          scope.launch {
            var working = draft
            if (coverDirty && profileNode == null) {
              error = "Claim a .heaven or .pirate name before setting a cover photo."
              saving = false
              return@launch
            }
            var nextAvatarUri = avatarUri.orEmpty()
            var activeSessionKey = sessionKey

            if (avatarDirty) {
              if (!avatarBase64.isNullOrBlank()) {
                val uploadResult = withContext(Dispatchers.IO) {
                  val jpegBytes = Base64.decode(avatarBase64, Base64.DEFAULT)
                  ProfileAvatarUploadApi.uploadAvatarJpeg(
                    ownerEthAddress = activeTempoAccount.address,
                    jpegBytes = jpegBytes,
                  )
                }
                if (!uploadResult.success || uploadResult.avatarRef.isNullOrBlank()) {
                  error = uploadResult.error ?: "Avatar upload failed"
                  saving = false
                  return@launch
                }
                nextAvatarUri = uploadResult.avatarRef
              }
              working = working.copy(photoUri = nextAvatarUri)
            } else if (!avatarUri.isNullOrBlank() && working.photoUri.isBlank()) {
              working = working.copy(photoUri = avatarUri!!)
            }

            var nextCoverUri = coverUri.orEmpty()
            if (coverDirty) {
              if (!coverBase64.isNullOrBlank()) {
                val uploadResult = withContext(Dispatchers.IO) {
                  val jpegBytes = Base64.decode(coverBase64, Base64.DEFAULT)
                  ProfileCoverUploadApi.uploadCoverJpeg(
                    ownerEthAddress = activeTempoAccount.address,
                    jpegBytes = jpegBytes,
                  )
                }
                if (!uploadResult.success || uploadResult.coverRef.isNullOrBlank()) {
                  error = uploadResult.error ?: "Cover image upload failed"
                  saving = false
                  return@launch
                }
                nextCoverUri = uploadResult.coverRef
              } else {
                nextCoverUri = ""
              }
            }

            if (locationDirty) {
              if (locationLabel.isBlank()) {
                working = working.copy(
                  locationCityId = "",
                  locationLatE6 = 0,
                  locationLngE6 = 0,
                )
              } else {
                val picked = selectedLocation
                if (picked != null) {
                  working = working.copy(
                    locationCityId = picked.label,
                    locationLatE6 = (picked.lat * 1_000_000.0).toInt(),
                    locationLngE6 = (picked.lng * 1_000_000.0).toInt(),
                  )
                } else {
                  working = working.copy(locationCityId = locationLabel)
                }
              }
            }

            if (schoolDirty) {
              working = working.copy(schoolId = schoolName.trim())
            }

            val payload = ProfileContractApi.buildProfileInput(working)

            // Ensure session key for silent signing
            if (activeSessionKey == null) {
              Log.d("ProfileEdit", "No session key, authorizing...")
              val authResult = TempoSessionKeyApi.authorizeSessionKey(
                activity = activity,
                account = activeTempoAccount,
                rpId = activeTempoAccount.rpId,
              )
              if (authResult.success && authResult.sessionKey != null) {
                activeSessionKey = authResult.sessionKey
                sessionKey = activeSessionKey
                Log.d("ProfileEdit", "Session key authorized")
              } else {
                Log.w("ProfileEdit", "Session key auth failed: ${authResult.error}, falling back to passkey")
              }
            }

            val profileResult =
              TempoProfileContractApi.upsertProfile(
                activity = activity,
                account = activeTempoAccount,
                profileInput = payload,
                rpId = activeTempoAccount.rpId,
                sessionKey = activeSessionKey,
              )
            val profileError = if (profileResult.success) null else profileResult.error ?: "Profile update failed"
            if (profileError != null) {
              error = profileError
              saving = false
              return@launch
            }

            val node = profileNode
            if (node != null) {
              val keys = mutableListOf<String>()
              val values = mutableListOf<String>()

              if (avatarDirty) {
                keys.add("avatar")
                values.add(nextAvatarUri)
              }
              if (coverDirty) {
                keys.add(TempoNameRecordsApi.PROFILE_COVER_RECORD_KEY)
                values.add(nextCoverUri)
              }
              if (locationDirty) {
                keys.add("heaven.location")
                values.add(locationLabel)
              }
              if (schoolDirty) {
                keys.add("heaven.school")
                values.add(schoolName.trim())
              }

              if (keys.isNotEmpty()) {
                val recordResult =
                  TempoNameRecordsApi.setTextRecords(
                    activity = activity,
                    account = activeTempoAccount,
                    node = node,
                    keys = keys,
                    values = values,
                    rpId = activeTempoAccount.rpId,
                    sessionKey = activeSessionKey,
                  )
                if (!recordResult.success) {
                  error = "Profile saved, but name records failed to sync: ${recordResult.error ?: "Unknown error"}"
                  saving = false
                  return@launch
                }
              }
            }

            draft = working
            avatarUri = nextAvatarUri.ifBlank { null }
            avatarBase64 = null
            avatarPreviewBitmap = null
            avatarDirty = false
            coverUri = nextCoverUri.ifBlank { null }
            coverBase64 = null
            coverPreviewBitmap = null
            coverDirty = false
            locationDirty = false
            schoolDirty = false
            onSaved()
            saving = false
          }
        },
        enabled = !loading && !saving,
      ) {
        Text(if (saving) "Saving..." else "Save")
      }
    }

    ProfileEditBodyContent(
      loading = loading,
      error = error,
      heavenName = heavenName,
      draft = draft,
      coverPreviewBitmap = coverPreviewBitmap,
      coverUri = coverUri,
      avatarPreviewBitmap = avatarPreviewBitmap,
      avatarUri = avatarUri,
      locationLabel = locationLabel,
      schoolName = schoolName,
      onOpenSheet = { activeSheet = it },
    )
  }

  ProfileEditSheetHost(
    sheet = activeSheet,
    draft = draft,
    onDraftChange = { draft = it },
    coverUri = coverUri,
    coverPreviewBitmap = coverPreviewBitmap,
    onPickCover = { coverPicker.launch("image/*") },
    onRemoveCover = {
      coverUri = null
      coverPreviewBitmap = null
      coverBase64 = null
      coverDirty = true
    },
    avatarUri = avatarUri,
    avatarPreviewBitmap = avatarPreviewBitmap,
    onPickPhoto = { imagePicker.launch("image/*") },
    onRemovePhoto = {
      avatarUri = null
      avatarPreviewBitmap = null
      avatarBase64 = null
      avatarDirty = true
    },
    locationLabel = locationLabel,
    selectedLocation = selectedLocation,
    schoolName = schoolName,
    onApplyLocation = { selection, clearLocation ->
      when {
        clearLocation -> {
          locationLabel = ""
          selectedLocation = null
          locationDirty = true
        }

        selection != null && selection.label != locationLabel -> {
          locationLabel = selection.label
          selectedLocation = selection
          locationDirty = true
        }
      }
    },
    onApplySchool = { updatedSchool ->
      val schoolTrimmed = updatedSchool.trim()
      if (schoolTrimmed != schoolName) {
        schoolName = schoolTrimmed
        schoolDirty = true
      }
    },
    onDismiss = { activeSheet = null },
  )
}
