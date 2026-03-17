package com.pirate.app.profile

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.pirate.app.ui.PirateTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pirate.app.theme.PiratePalette
import com.pirate.app.ui.PiratePrimaryButton
import com.pirate.app.util.resolveAvatarUrl
import com.pirate.app.util.resolveProfileCoverUrl

@Composable
internal fun DisplayNameEditorSheet(
  initialValue: String,
  onDone: (displayName: String) -> Unit,
) {
  var value by remember(initialValue) { mutableStateOf(initialValue) }
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Display Name", style = MaterialTheme.typography.titleLarge)
    LabeledTextField(
      label = "Name",
      value = value,
      placeholder = "Enter a display name",
      onValueChange = { value = it },
    )
    PiratePrimaryButton(
      text = "Done",
      onClick = { onDone(value.trim()) },
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
internal fun PhotoEditorSheet(
  avatarUri: String?,
  avatarPreviewBitmap: Bitmap?,
  onPickPhoto: () -> Unit,
  onRemovePhoto: () -> Unit,
  onDone: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text("Profile Photo", style = MaterialTheme.typography.titleLarge)
    Surface(
      modifier =
        Modifier
          .size(124.dp)
          .align(Alignment.CenterHorizontally),
      shape = RoundedCornerShape(62.dp),
      color = Color(0xFF262626),
    ) {
      val resolved = resolveAvatarUrl(avatarUri)
      Box(contentAlignment = Alignment.Center) {
        when {
          avatarPreviewBitmap != null -> {
            Image(
              bitmap = avatarPreviewBitmap.asImageBitmap(),
              contentDescription = "Avatar preview",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          }
          resolved != null -> {
            AsyncImage(
              model = resolved,
              contentDescription = "Avatar",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          }
          else -> {
            Icon(
              imageVector = PhosphorIcons.Regular.CameraPlus,
              contentDescription = null,
              tint = PiratePalette.TextMuted,
              modifier = Modifier.size(30.dp),
            )
          }
        }
      }
    }
    Text(
      "Pick an image and we will upload it to IPFS through Pirate API.",
      color = PiratePalette.TextMuted,
      style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PiratePrimaryButton(
        text = "Choose Photo",
        onClick = onPickPhoto,
        modifier = Modifier.weight(1f),
      )
      PirateTextButton(
        onClick = onRemovePhoto,
        enabled = avatarUri != null || avatarPreviewBitmap != null,
        modifier = Modifier.weight(1f),
      ) {
        Icon(PhosphorIcons.Regular.Trash, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Remove")
      }
    }
    PiratePrimaryButton(
      text = "Done",
      onClick = onDone,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
internal fun CoverEditorSheet(
  coverUri: String?,
  coverPreviewBitmap: Bitmap?,
  onPickCover: () -> Unit,
  onRemoveCover: () -> Unit,
  onDone: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text("Cover Photo", style = MaterialTheme.typography.titleLarge)
    Surface(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(132.dp)
          .align(Alignment.CenterHorizontally),
      shape = RoundedCornerShape(22.dp),
      color = Color(0xFF262626),
    ) {
      val resolved = resolveProfileCoverUrl(coverUri)
      Box(contentAlignment = Alignment.Center) {
        when {
          coverPreviewBitmap != null -> {
            Image(
              bitmap = coverPreviewBitmap.asImageBitmap(),
              contentDescription = "Cover preview",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          }
          resolved != null -> {
            AsyncImage(
              model = resolved,
              contentDescription = "Profile cover",
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          }
          else -> {
            Icon(
              imageVector = PhosphorIcons.Regular.ImageSquare,
              contentDescription = null,
              tint = PiratePalette.TextMuted,
              modifier = Modifier.size(30.dp),
            )
          }
        }
      }
    }
    Text(
      "Pick a wide image and we will upload it to IPFS through Pirate API.",
      color = PiratePalette.TextMuted,
      style = MaterialTheme.typography.bodyMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PiratePrimaryButton(
        text = "Choose Cover",
        onClick = onPickCover,
        modifier = Modifier.weight(1f),
      )
      PirateTextButton(
        onClick = onRemoveCover,
        enabled = coverUri != null || coverPreviewBitmap != null,
        modifier = Modifier.weight(1f),
      ) {
        Icon(PhosphorIcons.Regular.Trash, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Remove")
      }
    }
    PiratePrimaryButton(
      text = "Done",
      onClick = onDone,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
internal fun LanguagesEditorSheet(
  entries: List<ProfileLanguageEntry>,
  onChange: (List<ProfileLanguageEntry>) -> Unit,
  onDone: () -> Unit,
) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.9f)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Languages", style = MaterialTheme.typography.titleLarge)
    LanguageEditor(
      entries = entries,
      onChange = onChange,
    )
    PiratePrimaryButton(
      text = "Done",
      onClick = onDone,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
