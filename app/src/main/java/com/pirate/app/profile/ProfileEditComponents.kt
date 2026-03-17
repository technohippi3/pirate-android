package com.pirate.app.profile

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pirate.app.onboarding.steps.LANGUAGE_OPTIONS
import com.pirate.app.theme.PiratePalette
import com.pirate.app.util.resolveAvatarUrl
import com.pirate.app.util.resolveProfileCoverUrl

@Composable
internal fun SectionCard(
  title: String,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = Color(0xFF1C1C1C),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
      content()
    }
  }
}

@Composable
internal fun EditSummaryRow(
  title: String,
  value: String,
  trailingPreview: (@Composable () -> Unit)? = null,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
      Text(value, style = MaterialTheme.typography.bodyMedium, color = PiratePalette.TextMuted)
    }
    trailingPreview?.invoke()
    if (trailingPreview != null) Spacer(Modifier.width(10.dp))
    Icon(
      imageVector = PhosphorIcons.Regular.CaretRight,
      contentDescription = null,
      tint = PiratePalette.TextMuted,
    )
  }
  HorizontalDivider(color = Color(0xFF333333))
}

@Composable
internal fun ProfilePhotoSummaryPreview(
  previewBitmap: Bitmap?,
  avatarUri: String?,
) {
  Surface(
    modifier = Modifier.size(40.dp),
    shape = RoundedCornerShape(20.dp),
    color = Color(0xFF262626),
  ) {
    val resolved = resolveAvatarUrl(avatarUri)
    Box(contentAlignment = Alignment.Center) {
      when {
        previewBitmap != null -> {
          Image(
            bitmap = previewBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        }
        resolved != null -> {
          AsyncImage(
            model = resolved,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        }
        else -> {
          Icon(
            imageVector = PhosphorIcons.Regular.CameraPlus,
            contentDescription = null,
            tint = PiratePalette.TextMuted,
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }
  }
}

@Composable
internal fun ProfileCoverSummaryPreview(
  previewBitmap: Bitmap?,
  coverUri: String?,
) {
  Surface(
    modifier = Modifier.size(width = 64.dp, height = 40.dp),
    shape = RoundedCornerShape(12.dp),
    color = Color(0xFF262626),
  ) {
    val resolved = resolveProfileCoverUrl(coverUri)
    Box(contentAlignment = Alignment.Center) {
      when {
        previewBitmap != null -> {
          Image(
            bitmap = previewBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        }
        resolved != null -> {
          AsyncImage(
            model = resolved,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        }
        else -> {
          Icon(
            imageVector = PhosphorIcons.Regular.ImageSquare,
            contentDescription = null,
            tint = PiratePalette.TextMuted,
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }
  }
}

@Composable
internal fun LabeledTextField(
  label: String,
  value: String,
  placeholder: String,
  keyboardType: KeyboardType = KeyboardType.Text,
  onValueChange: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = PiratePalette.TextMuted)
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      placeholder = { Text(placeholder) },
      keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
  }
}

@Composable
internal fun NumericField(
  label: String,
  value: Int,
  onValueChange: (Int) -> Unit,
) {
  val display = if (value == 0) "" else value.toString()
  LabeledTextField(
    label = label,
    value = display,
    placeholder = "0",
    keyboardType = KeyboardType.Number,
    onValueChange = { next ->
      val parsed = next.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
      onValueChange(parsed)
    },
  )
}

@Composable
internal fun FriendsMaskField(
  mask: Int,
  onValueChange: (Int) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Open to dating", style = MaterialTheme.typography.bodyMedium, color = PiratePalette.TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FriendsMaskChip(
        label = "Men",
        selected = (mask and 0x1) != 0,
        onClick = {
          onValueChange(if ((mask and 0x1) != 0) mask and 0x1.inv() else mask or 0x1)
        },
      )
      FriendsMaskChip(
        label = "Women",
        selected = (mask and 0x2) != 0,
        onClick = {
          onValueChange(if ((mask and 0x2) != 0) mask and 0x2.inv() else mask or 0x2)
        },
      )
      FriendsMaskChip(
        label = "Non-binary",
        selected = (mask and 0x4) != 0,
        onClick = {
          onValueChange(if ((mask and 0x4) != 0) mask and 0x4.inv() else mask or 0x4)
        },
      )
    }
  }
}

@Composable
internal fun FriendsMaskChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(label) },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnumDropdownField(
  label: String,
  value: Int,
  options: List<ProfileEnumOption>,
  onValueChange: (Int) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val selected = options.firstOrNull { it.value == value } ?: options.first()

  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = PiratePalette.TextMuted)
    ExposedDropdownMenuBox(
      expanded = expanded,
      onExpandedChange = { expanded = it },
    ) {
      OutlinedTextField(
        value = selected.label,
        onValueChange = {},
        readOnly = true,
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        modifier = Modifier
          .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
          .fillMaxWidth(),
      )
      ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
      ) {
        options.forEach { option ->
          DropdownMenuItem(
            text = { Text(option.label) },
            onClick = {
              expanded = false
              onValueChange(option.value)
            },
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageEditor(
  entries: List<ProfileLanguageEntry>,
  onChange: (List<ProfileLanguageEntry>) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Languages", style = MaterialTheme.typography.bodyMedium, color = PiratePalette.TextMuted)

    entries.forEachIndexed { index, entry ->
      val usedCodes = entries
        .mapIndexedNotNull { i, v -> if (i == index) null else v.code.lowercase() }
        .toSet()

      Surface(
        color = Color(0xFF262626),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          var langExpanded by remember(index, entry.code) { mutableStateOf(false) }
          ExposedDropdownMenuBox(
            expanded = langExpanded,
            onExpandedChange = { langExpanded = it },
            modifier = Modifier.weight(1f),
          ) {
            val selectedLanguage = LANGUAGE_OPTIONS.firstOrNull { it.code == entry.code.lowercase() }
            OutlinedTextField(
              value = selectedLanguage?.label ?: entry.code.uppercase(),
              onValueChange = {},
              readOnly = true,
              label = { Text("Language") },
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
              modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
              singleLine = true,
            )
            ExposedDropdownMenu(
              expanded = langExpanded,
              onDismissRequest = { langExpanded = false },
            ) {
              LANGUAGE_OPTIONS
                .filter { it.code !in usedCodes || it.code == entry.code.lowercase() }
                .forEach { option ->
                  DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                      val updated = entries.toMutableList()
                      updated[index] = entry.copy(code = option.code)
                      onChange(updated)
                      langExpanded = false
                    },
                  )
                }
            }
          }

          Spacer(Modifier.width(8.dp))

          var profExpanded by remember(index, entry.proficiency) { mutableStateOf(false) }
          ExposedDropdownMenuBox(
            expanded = profExpanded,
            onExpandedChange = { profExpanded = it },
            modifier = Modifier.weight(1f),
          ) {
            val selected = proficiencyOptions.firstOrNull { it.value == entry.proficiency } ?: proficiencyOptions.first()
            OutlinedTextField(
              value = selected.label,
              onValueChange = {},
              readOnly = true,
              label = { Text("Level") },
              trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = profExpanded) },
              modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
              singleLine = true,
            )
            ExposedDropdownMenu(
              expanded = profExpanded,
              onDismissRequest = { profExpanded = false },
            ) {
              proficiencyOptions.forEach { option ->
                DropdownMenuItem(
                  text = { Text(option.label) },
                  onClick = {
                    val updated = entries.toMutableList()
                    updated[index] = entry.copy(proficiency = option.value)
                    onChange(updated)
                    profExpanded = false
                  },
                )
              }
            }
          }

          PirateIconButton(
            onClick = {
              val updated = entries.toMutableList()
              updated.removeAt(index)
              onChange(updated)
            },
          ) {
            Icon(PhosphorIcons.Regular.X, contentDescription = "Remove language")
          }
        }
      }
    }

    if (entries.size < 8) {
      PirateTextButton(
        onClick = {
          val existing = entries.map { it.code.lowercase() }.toSet()
          val code = LANGUAGE_OPTIONS.firstOrNull { it.code !in existing }?.code ?: "en"
          onChange(entries + ProfileLanguageEntry(code = code, proficiency = 1))
        },
      ) {
        Text("Add Language")
      }
    }
  }
}
