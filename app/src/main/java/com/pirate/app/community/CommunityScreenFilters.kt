package com.pirate.app.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import com.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pirate.app.ui.PiratePrimaryButton
import com.pirate.app.ui.PirateSheetTitle
import com.pirate.app.onboarding.steps.LANGUAGE_OPTIONS
import com.pirate.app.util.shortAddress
import kotlin.math.roundToInt

private data class GenderOption(
  val value: Int?,
  val label: String,
)

private val GenderOptions = listOf(
  GenderOption(null, "Gender"),
  GenderOption(1, "Woman"),
  GenderOption(2, "Man"),
  GenderOption(3, "Non-binary"),
  GenderOption(4, "Trans woman"),
  GenderOption(5, "Trans man"),
  GenderOption(6, "Intersex"),
  GenderOption(7, "Other"),
)

private val RadiusOptionsKm = listOf(10, 25, 50, 100)

@Composable
internal fun CommunityPreviewRow(
  member: CommunityMemberPreview,
  resolvedPrimaryName: String?,
  onClick: () -> Unit,
) {
  val rawDisplay = member.displayName.trim()
  val hasReadableDisplay = rawDisplay.isNotBlank() && !rawDisplay.startsWith("0x", ignoreCase = true)
  val handle =
    when {
      !resolvedPrimaryName.isNullOrBlank() -> resolvedPrimaryName
      hasReadableDisplay -> rawDisplay
      else -> shortAddress(member.address)
    }
  val meta = buildList {
    member.age?.let { add("$it") }
    CommunityApi.genderLabel(member.gender)?.let { add(it) }
    member.distanceKm?.let { add(distanceLabel(it)) }
  }.joinToString(" • ")

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (!member.photoUrl.isNullOrBlank()) {
      AsyncImage(
        model = member.photoUrl,
        contentDescription = "Profile photo",
        modifier = Modifier.size(52.dp).clip(CircleShape),
        contentScale = ContentScale.Crop,
      )
    } else {
      Box(
        modifier = Modifier
          .size(52.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          handle.take(1).ifBlank { "?" }.uppercase(),
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = handle,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (meta.isNotBlank()) {
        Text(
          text = meta,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityFilterSheet(
  filters: CommunityFilters,
  defaultNativeLanguage: String,
  hasViewerCoords: Boolean,
  onDismiss: () -> Unit,
  onApply: (CommunityFilters) -> Unit,
) {
  var gender by remember(filters.gender) { mutableStateOf(filters.gender) }
  var minAgeText by remember(filters.minAge) { mutableStateOf(filters.minAge?.toString().orEmpty()) }
  var maxAgeText by remember(filters.maxAge) { mutableStateOf(filters.maxAge?.toString().orEmpty()) }
  var nativeLanguage by remember(filters.nativeLanguage) { mutableStateOf(filters.nativeLanguage) }
  var learningLanguage by remember(filters.learningLanguage) { mutableStateOf(filters.learningLanguage) }
  var radiusKm by remember(filters.radiusKm, hasViewerCoords) {
    mutableStateOf(if (hasViewerCoords) filters.radiusKm else null)
  }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    sheetState = sheetState,
    onDismissRequest = onDismiss,
    modifier = Modifier.fillMaxHeight(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxHeight()
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(top = 12.dp, bottom = 16.dp),
    ) {
      LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          PirateSheetTitle(text = "Filter Members")
        }
        item {
          GenderDropdown(
            selectedGender = gender,
            onSelected = { gender = it },
          )
        }
        item {
          Text("Age range", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = minAgeText,
              onValueChange = { minAgeText = it.filter { ch -> ch.isDigit() }.take(2) },
              modifier = Modifier.weight(1f),
              label = { Text("Min age") },
              placeholder = { Text("Any") },
              singleLine = true,
            )
            OutlinedTextField(
              value = maxAgeText,
              onValueChange = { maxAgeText = it.filter { ch -> ch.isDigit() }.take(2) },
              modifier = Modifier.weight(1f),
              label = { Text("Max age") },
              placeholder = { Text("Any") },
              singleLine = true,
            )
          }
        }
        item {
          LanguageDropdown(
            label = "Native Language",
            selectedLanguage = nativeLanguage,
            onSelected = { nativeLanguage = it },
          )
        }
        item {
          LanguageDropdown(
            label = "Learning Language",
            selectedLanguage = learningLanguage,
            onSelected = { learningLanguage = it },
          )
        }
        item {
          Text("Nearby radius", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
          if (hasViewerCoords) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              item {
                FilterChip(
                  selected = radiusKm == null,
                  onClick = { radiusKm = null },
                  label = { Text("Any distance") },
                )
              }
              items(RadiusOptionsKm, key = { it }) { radius ->
                FilterChip(
                  selected = radiusKm == radius,
                  onClick = { radiusKm = radius },
                  label = { Text("${radius}km") },
                )
              }
            }
          } else {
            Text(
              "Set your location in profile to enable nearby radius filtering.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }

      Spacer(Modifier.height(12.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        PirateOutlinedButton(
          modifier = Modifier.weight(1f),
          onClick = {
            onApply(CommunityFilters(nativeLanguage = defaultNativeLanguage))
          },
        ) {
          Text("Reset")
        }
        PiratePrimaryButton(
          text = "Apply",
          modifier = Modifier.weight(1f),
          onClick = {
            val parsedMin = minAgeText.toIntOrNull()?.coerceIn(18, 99)
            val parsedMax = maxAgeText.toIntOrNull()?.coerceIn(18, 99)
            val (minAge, maxAge) =
              if (parsedMin != null && parsedMax != null && parsedMin > parsedMax) {
                parsedMax to parsedMin
              } else {
                parsedMin to parsedMax
              }
            onApply(
              CommunityFilters(
                gender = gender,
                minAge = minAge,
                maxAge = maxAge,
                nativeLanguage = nativeLanguage,
                learningLanguage = learningLanguage,
                radiusKm = if (hasViewerCoords) radiusKm else null,
              ),
            )
          },
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GenderDropdown(
  selectedGender: Int?,
  onSelected: (Int?) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val selectedLabel = GenderOptions.firstOrNull { it.value == selectedGender }?.label ?: "Gender"

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
  ) {
    OutlinedTextField(
      value = selectedLabel,
      onValueChange = {},
      readOnly = true,
      label = { Text("Gender") },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
      singleLine = true,
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      GenderOptions.forEach { option ->
        DropdownMenuItem(
          text = { Text(option.label) },
          onClick = {
            onSelected(option.value)
            expanded = false
          },
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageDropdown(
  label: String,
  selectedLanguage: String?,
  onSelected: (String?) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val options = remember {
    listOf(null to "Any") + LANGUAGE_OPTIONS.map { it.code.lowercase() to it.label }
  }
  val selectedLabel = options.firstOrNull { it.first == selectedLanguage?.lowercase() }?.second ?: "Any"

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
  ) {
    OutlinedTextField(
      value = selectedLabel,
      onValueChange = {},
      readOnly = true,
      label = { Text(label) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
      singleLine = true,
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(option.second) },
          onClick = {
            onSelected(option.first)
            expanded = false
          },
        )
      }
    }
  }
}

private fun distanceLabel(distanceKm: Double): String {
  return if (distanceKm < 1.0) "<1 km away" else "${distanceKm.roundToInt()} km away"
}
