package sc.pirate.app.profile

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import sc.pirate.app.ui.PirateTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import sc.pirate.app.onboarding.steps.LocationResult
import sc.pirate.app.onboarding.steps.searchLocations
import sc.pirate.app.theme.PirateTokens
import sc.pirate.app.ui.PiratePrimaryButton

@Composable
internal fun BasicsEditorSheet(
  draft: ContractProfileData,
  onDraftChange: (ContractProfileData) -> Unit,
  onDone: () -> Unit,
) {
  val selectedCountry = countryOptions.firstOrNull { it.code.equals(draft.nationality, ignoreCase = true) }
    ?: countryOptions.first()
  var nationalityQuery by remember { mutableStateOf("") }
  var nationalityFocused by remember { mutableStateOf(false) }
  val filteredCountries = remember(nationalityQuery) {
    val q = nationalityQuery.trim().lowercase()
    if (q.isBlank()) {
      countryOptions.take(20)
    } else {
      countryOptions.filter { it.label.lowercase().contains(q) }
    }
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .fillMaxHeight(0.9f)
      .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Basics", style = MaterialTheme.typography.titleLarge)
    NumericField(
      label = "Age",
      value = draft.age,
      onValueChange = { onDraftChange(draft.copy(age = it.coerceIn(0, 120))) },
    )
    NumericField(
      label = "Height (cm)",
      value = draft.heightCm,
      onValueChange = { onDraftChange(draft.copy(heightCm = it.coerceIn(0, 300))) },
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Nationality", style = MaterialTheme.typography.bodyMedium, color = PirateTokens.colors.textSecondary)
      OutlinedTextField(
        value = if (nationalityFocused) nationalityQuery else selectedCountry.label,
        onValueChange = { nationalityQuery = it },
        modifier = Modifier
          .fillMaxWidth()
          .onFocusChanged { state ->
            if (state.isFocused && !nationalityFocused) {
              nationalityFocused = true
              nationalityQuery = ""
            } else if (!state.isFocused) {
              nationalityFocused = false
            }
          },
        singleLine = true,
        placeholder = { Text("Search country...") },
        trailingIcon = {
          if (nationalityFocused && nationalityQuery.isNotBlank()) {
            PirateIconButton(onClick = { nationalityQuery = "" }) {
              Icon(PhosphorIcons.Regular.X, contentDescription = "Clear")
            }
          }
        },
      )
      if (nationalityFocused && filteredCountries.isNotEmpty()) {
        Surface(color = PirateTokens.colors.bgElevated, shape = RoundedCornerShape(PirateTokens.radius.lg)) {
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 220.dp),
          ) {
            items(filteredCountries, key = { it.code }) { option ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable {
                    onDraftChange(draft.copy(nationality = option.code))
                    nationalityFocused = false
                    nationalityQuery = ""
                  }
                  .padding(horizontal = 12.dp, vertical = 10.dp),
              ) {
                Text(option.label)
              }
            }
          }
        }
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
internal fun LocationEditorSheet(
  initialLocationLabel: String,
  initialSelection: LocationResult?,
  initialLatE6: Int,
  initialLngE6: Int,
  onApply: (selection: LocationResult?, clearLocation: Boolean) -> Unit,
  onCancel: () -> Unit,
) {
  var query by remember(initialLocationLabel) { mutableStateOf(initialLocationLabel) }
  val derivedSelection = remember(initialLocationLabel, initialSelection, initialLatE6, initialLngE6) {
    initialSelection ?: if (initialLocationLabel.isNotBlank() && ProfileContractApi.hasCoords(initialLatE6, initialLngE6)) {
      LocationResult(
        label = initialLocationLabel,
        lat = initialLatE6.toDouble() / 1_000_000.0,
        lng = initialLngE6.toDouble() / 1_000_000.0,
        countryCode = null,
      )
    } else {
      null
    }
  }

  var selection by remember(derivedSelection) { mutableStateOf(derivedSelection) }
  var clearLocation by remember { mutableStateOf(false) }
  var searching by remember { mutableStateOf(false) }
  var suggestions by remember { mutableStateOf<List<LocationResult>>(emptyList()) }

  LaunchedEffect(query) {
    if (query == selection?.label) return@LaunchedEffect
    clearLocation = false
    selection = null
    suggestions = emptyList()

    if (query.length < 2) {
      searching = false
      return@LaunchedEffect
    }

    searching = true
    runCatching { searchLocations(query) }
      .onSuccess { suggestions = it }
      .onFailure { suggestions = emptyList() }
    searching = false
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .fillMaxHeight(0.9f)
      .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Location", style = MaterialTheme.typography.titleLarge)
    Text(
      "Search and pick your city. We keep this user-friendly and hash internals automatically.",
      style = MaterialTheme.typography.bodyMedium,
      color = PirateTokens.colors.textSecondary,
    )

    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      modifier = Modifier.fillMaxWidth(),
      label = { Text("City") },
      placeholder = { Text("e.g. New York, US") },
      singleLine = true,
      trailingIcon = {
        if (searching) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
      },
    )

    if (selection != null) {
      Text(
        "Selected: ${selection!!.label}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
      )
    } else if (initialLocationLabel.isNotBlank() && query == initialLocationLabel) {
      Text(
        "Current: $initialLocationLabel",
        style = MaterialTheme.typography.bodyMedium,
        color = PirateTokens.colors.textSecondary,
      )
    }

    if (suggestions.isNotEmpty()) {
      Surface(color = PirateTokens.colors.bgElevated, shape = RoundedCornerShape(PirateTokens.radius.lg)) {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        ) {
          items(suggestions, key = { it.label }) { result ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  selection = result
                  query = result.label
                  suggestions = emptyList()
                  clearLocation = false
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
              Text(result.label)
            }
          }
        }
      }
    }

    PirateTextButton(
      onClick = {
        selection = null
        query = ""
        clearLocation = true
        suggestions = emptyList()
      },
      enabled = query.isNotBlank() || initialLocationLabel.isNotBlank(),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("Clear Location")
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PirateTextButton(
        onClick = onCancel,
        modifier = Modifier.weight(1f),
      ) {
        Text("Cancel")
      }
      PiratePrimaryButton(
        text = "Done",
        onClick = { onApply(selection, clearLocation) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
internal fun SchoolEditorSheet(
  initialSchoolName: String,
  onApply: (school: String) -> Unit,
  onCancel: () -> Unit,
) {
  var school by remember(initialSchoolName) { mutableStateOf(initialSchoolName) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .fillMaxHeight(0.6f)
      .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("School", style = MaterialTheme.typography.titleLarge)
    LabeledTextField(
      label = "School",
      value = school,
      placeholder = "Enter your school",
      onValueChange = { school = it },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      PirateTextButton(
        onClick = onCancel,
        modifier = Modifier.weight(1f),
      ) {
        Text("Cancel")
      }
      PiratePrimaryButton(
        text = "Done",
        onClick = { onApply(school) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
internal fun PreferencesEditorSheet(
  draft: ContractProfileData,
  onDraftChange: (ContractProfileData) -> Unit,
  onDone: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .fillMaxHeight(0.9f)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 20.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Preferences", style = MaterialTheme.typography.titleLarge)
    EnumDropdownField("Gender", draft.gender, ProfileContractApi.GENDER_OPTIONS) {
      onDraftChange(draft.copy(gender = it))
    }
    EnumDropdownField("Willing to relocate", draft.relocate, ProfileContractApi.RELOCATE_OPTIONS) {
      onDraftChange(draft.copy(relocate = it))
    }
    EnumDropdownField("Degree", draft.degree, ProfileContractApi.DEGREE_OPTIONS) {
      onDraftChange(draft.copy(degree = it))
    }
    EnumDropdownField("Field", draft.fieldBucket, ProfileContractApi.FIELD_OPTIONS) {
      onDraftChange(draft.copy(fieldBucket = it))
    }
    EnumDropdownField("Profession", draft.profession, ProfileContractApi.PROFESSION_OPTIONS) {
      onDraftChange(draft.copy(profession = it))
    }
    EnumDropdownField("Industry", draft.industry, ProfileContractApi.INDUSTRY_OPTIONS) {
      onDraftChange(draft.copy(industry = it))
    }
    EnumDropdownField("Relationship", draft.relationshipStatus, ProfileContractApi.RELATIONSHIP_OPTIONS) {
      onDraftChange(draft.copy(relationshipStatus = it))
    }
    FriendsMaskField(
      mask = draft.friendsOpenToMask,
      onValueChange = { onDraftChange(draft.copy(friendsOpenToMask = it and 0x07)) },
    )
    EnumDropdownField("Sexuality", draft.sexuality, ProfileContractApi.SEXUALITY_OPTIONS) {
      onDraftChange(draft.copy(sexuality = it))
    }
    EnumDropdownField("Ethnicity", draft.ethnicity, ProfileContractApi.ETHNICITY_OPTIONS) {
      onDraftChange(draft.copy(ethnicity = it))
    }
    EnumDropdownField("Dating Style", draft.datingStyle, ProfileContractApi.DATING_STYLE_OPTIONS) {
      onDraftChange(draft.copy(datingStyle = it))
    }
    EnumDropdownField("Children", draft.children, ProfileContractApi.CHILDREN_OPTIONS) {
      onDraftChange(draft.copy(children = it))
    }
    EnumDropdownField("Wants Children", draft.wantsChildren, ProfileContractApi.WANTS_CHILDREN_OPTIONS) {
      onDraftChange(draft.copy(wantsChildren = it))
    }
    EnumDropdownField("Looking For", draft.lookingFor, ProfileContractApi.LOOKING_FOR_OPTIONS) {
      onDraftChange(draft.copy(lookingFor = it))
    }
    EnumDropdownField("Drinking", draft.drinking, ProfileContractApi.DRINKING_OPTIONS) {
      onDraftChange(draft.copy(drinking = it))
    }
    EnumDropdownField("Smoking", draft.smoking, ProfileContractApi.SMOKING_OPTIONS) {
      onDraftChange(draft.copy(smoking = it))
    }
    EnumDropdownField("Drugs", draft.drugs, ProfileContractApi.DRUGS_OPTIONS) {
      onDraftChange(draft.copy(drugs = it))
    }
    EnumDropdownField("Religion", draft.religion, ProfileContractApi.RELIGION_OPTIONS) {
      onDraftChange(draft.copy(religion = it))
    }
    EnumDropdownField("Pets", draft.pets, ProfileContractApi.PETS_OPTIONS) {
      onDraftChange(draft.copy(pets = it))
    }
    EnumDropdownField("Diet", draft.diet, ProfileContractApi.DIET_OPTIONS) {
      onDraftChange(draft.copy(diet = it))
    }

    PiratePrimaryButton(
      text = "Done",
      onClick = onDone,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}
