package com.pirate.app.profile

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pirate.app.onboarding.steps.LocationResult
import com.pirate.app.theme.PiratePalette

@Composable
internal fun ProfileEditBodyContent(
  loading: Boolean,
  error: String?,
  heavenName: String?,
  draft: ContractProfileData,
  coverPreviewBitmap: Bitmap?,
  coverUri: String?,
  avatarPreviewBitmap: Bitmap?,
  avatarUri: String?,
  locationLabel: String,
  schoolName: String,
  onOpenSheet: (ProfileEditSheet) -> Unit,
) {
  when {
    loading -> {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    }

    else -> {
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (!error.isNullOrBlank()) {
          Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (heavenName.isNullOrBlank()) {
          Text(
            "Tip: claim a .heaven or .pirate name to sync cover, avatar, location, and school across name records.",
            color = PiratePalette.TextMuted,
            style = MaterialTheme.typography.bodyMedium,
          )
        }

        SectionCard("Identity") {
          EditSummaryRow(
            title = "Display Name",
            value = draft.displayName.ifBlank { "Not set" },
            onClick = { onOpenSheet(ProfileEditSheet.DisplayName) },
          )
          EditSummaryRow(
            title = "Cover Photo",
            value = when {
              coverPreviewBitmap != null -> "New cover selected"
              !coverUri.isNullOrBlank() -> "Tap to change"
              heavenName.isNullOrBlank() -> "Claim a name to add a cover"
              else -> "No cover photo"
            },
            trailingPreview = {
              ProfileCoverSummaryPreview(
                previewBitmap = coverPreviewBitmap,
                coverUri = coverUri,
              )
            },
            onClick = { if (!heavenName.isNullOrBlank()) onOpenSheet(ProfileEditSheet.Cover) },
          )
          EditSummaryRow(
            title = "Profile Photo",
            value = when {
              avatarPreviewBitmap != null -> "New photo selected"
              !avatarUri.isNullOrBlank() -> "Tap to change"
              else -> "No photo"
            },
            trailingPreview = {
              ProfilePhotoSummaryPreview(
                previewBitmap = avatarPreviewBitmap,
                avatarUri = avatarUri,
              )
            },
            onClick = { onOpenSheet(ProfileEditSheet.Photo) },
          )
        }

        SectionCard("Basics") {
          EditSummaryRow(
            title = "About Me",
            value = basicsSummary(draft),
            onClick = { onOpenSheet(ProfileEditSheet.Basics) },
          )
          EditSummaryRow(
            title = "Languages",
            value = languageSummary(draft.languages),
            onClick = { onOpenSheet(ProfileEditSheet.Languages) },
          )
        }

        SectionCard("Location & Education") {
          EditSummaryRow(
            title = "Location",
            value = locationSummary(locationLabel, draft),
            onClick = { onOpenSheet(ProfileEditSheet.Location) },
          )
          EditSummaryRow(
            title = "School",
            value = schoolSummary(schoolName, draft),
            onClick = { onOpenSheet(ProfileEditSheet.School) },
          )
        }

        SectionCard("Preferences") {
          EditSummaryRow(
            title = "Dating & Lifestyle",
            value = preferenceSummary(draft),
            onClick = { onOpenSheet(ProfileEditSheet.Preferences) },
          )
        }

        Spacer(Modifier.height(16.dp))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileEditSheetHost(
  sheet: ProfileEditSheet?,
  draft: ContractProfileData,
  onDraftChange: (ContractProfileData) -> Unit,
  coverUri: String?,
  coverPreviewBitmap: Bitmap?,
  onPickCover: () -> Unit,
  onRemoveCover: () -> Unit,
  avatarUri: String?,
  avatarPreviewBitmap: Bitmap?,
  onPickPhoto: () -> Unit,
  onRemovePhoto: () -> Unit,
  locationLabel: String,
  selectedLocation: LocationResult?,
  schoolName: String,
  onApplyLocation: (LocationResult?, Boolean) -> Unit,
  onApplySchool: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  if (sheet == null) return
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = Color(0xFF1C1C1C),
  ) {
    when (sheet) {
      ProfileEditSheet.DisplayName -> {
        DisplayNameEditorSheet(
          initialValue = draft.displayName,
          onDone = { displayName ->
            onDraftChange(draft.copy(displayName = displayName))
            onDismiss()
          },
        )
      }

      ProfileEditSheet.Cover -> {
        CoverEditorSheet(
          coverUri = coverUri,
          coverPreviewBitmap = coverPreviewBitmap,
          onPickCover = onPickCover,
          onRemoveCover = onRemoveCover,
          onDone = onDismiss,
        )
      }

      ProfileEditSheet.Photo -> {
        PhotoEditorSheet(
          avatarUri = avatarUri,
          avatarPreviewBitmap = avatarPreviewBitmap,
          onPickPhoto = onPickPhoto,
          onRemovePhoto = onRemovePhoto,
          onDone = onDismiss,
        )
      }

      ProfileEditSheet.Basics -> {
        BasicsEditorSheet(
          draft = draft,
          onDraftChange = onDraftChange,
          onDone = onDismiss,
        )
      }

      ProfileEditSheet.Languages -> {
        LanguagesEditorSheet(
          entries = draft.languages,
          onChange = { onDraftChange(draft.copy(languages = it)) },
          onDone = onDismiss,
        )
      }

      ProfileEditSheet.Location -> {
        LocationEditorSheet(
          initialLocationLabel = locationLabel,
          initialSelection = selectedLocation,
          initialLatE6 = draft.locationLatE6,
          initialLngE6 = draft.locationLngE6,
          onApply = { selection, clearLocation ->
            onApplyLocation(selection, clearLocation)
            onDismiss()
          },
          onCancel = onDismiss,
        )
      }

      ProfileEditSheet.School -> {
        SchoolEditorSheet(
          initialSchoolName = schoolName,
          onApply = { updatedSchool ->
            onApplySchool(updatedSchool)
            onDismiss()
          },
          onCancel = onDismiss,
        )
      }

      ProfileEditSheet.Preferences -> {
        PreferencesEditorSheet(
          draft = draft,
          onDraftChange = onDraftChange,
          onDone = onDismiss,
        )
      }
    }
  }
}
