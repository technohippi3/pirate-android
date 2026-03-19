package sc.pirate.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import sc.pirate.app.ui.PirateTextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.ui.PiratePrimaryButton
import java.util.Locale

@Composable
internal fun AboutPanel(
  profile: ContractProfileData?,
  loading: Boolean,
  error: String?,
  locationLabel: String?,
  schoolLabel: String?,
  isOwnProfile: Boolean,
  onEditProfile: (() -> Unit)?,
  onRetry: () -> Unit,
) {
  if (loading) {
    CenteredStatus { CircularProgressIndicator(Modifier.size(32.dp)); Spacer(Modifier.height(12.dp)); Text("Loading profile fields...", color = PiratePalette.TextMuted) }
    return
  }
  if (!error.isNullOrBlank()) {
    CenteredStatus {
      Text(error, color = MaterialTheme.colorScheme.error)
      Spacer(Modifier.height(8.dp))
      PirateTextButton(onClick = onRetry) { Text("Retry") }
    }
    return
  }
  if (profile == null) {
    CenteredStatus {
      Text(
        if (isOwnProfile) "No on-chain profile fields yet." else "No profile fields yet.",
        color = PiratePalette.TextMuted,
      )
      if (isOwnProfile && onEditProfile != null) {
        Spacer(Modifier.height(12.dp))
        PiratePrimaryButton(text = "Create Profile", onClick = onEditProfile)
      }
    }
    return
  }

  val identityRows = buildList<Pair<String, String>> {
    if (profile.displayName.isNotBlank()) add("Display Name" to profile.displayName)
    if (profile.nationality.isNotBlank()) {
      val code = profile.nationality.trim().uppercase()
      val country = runCatching { Locale.Builder().setRegion(code).build().displayCountry.trim() }.getOrDefault("")
      add("Nationality" to if (country.isNotBlank()) country else code)
    }
  }

  val basicsRows = buildList<Pair<String, String>> {
    if (profile.age > 0) add("Age" to "${profile.age}")
    if (profile.heightCm > 0) add("Height" to "${profile.heightCm} cm")
    ProfileContractApi.enumLabel(ProfileContractApi.GENDER_OPTIONS, profile.gender)?.let { add("Gender" to it) }
    if (profile.languages.isNotEmpty()) {
      val langs = profile.languages.joinToString(" · ") { entry ->
        "${ProfileContractApi.languageLabel(entry.code)} (${ProfileContractApi.proficiencyLabel(entry.proficiency)})"
      }
      add("Languages" to langs)
    }
  }

  val locationRows = buildList<Pair<String, String>> {
    if (!locationLabel.isNullOrBlank()) {
      add("Location" to locationLabel)
    } else if (profile.locationCityId.isNotBlank()) {
      add("Location" to "Set")
    }
  }

  val preferenceRows = buildList<Pair<String, String>> {
    val openTo = ProfileContractApi.selectedFriendsLabels(profile.friendsOpenToMask)
    if (openTo.isNotEmpty()) add("Open to dating" to openTo.joinToString(", "))
    ProfileContractApi.enumLabel(ProfileContractApi.RELOCATE_OPTIONS, profile.relocate)?.let { add("Willing to relocate" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.DEGREE_OPTIONS, profile.degree)?.let { add("Degree" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.FIELD_OPTIONS, profile.fieldBucket)?.let { add("Field" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.PROFESSION_OPTIONS, profile.profession)?.let { add("Profession" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.INDUSTRY_OPTIONS, profile.industry)?.let { add("Industry" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.RELATIONSHIP_OPTIONS, profile.relationshipStatus)?.let { add("Relationship" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.SEXUALITY_OPTIONS, profile.sexuality)?.let { add("Sexuality" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.ETHNICITY_OPTIONS, profile.ethnicity)?.let { add("Ethnicity" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.DATING_STYLE_OPTIONS, profile.datingStyle)?.let { add("Dating Style" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.CHILDREN_OPTIONS, profile.children)?.let { add("Children" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.WANTS_CHILDREN_OPTIONS, profile.wantsChildren)?.let { add("Wants Children" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.LOOKING_FOR_OPTIONS, profile.lookingFor)?.let { add("Looking For" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.DRINKING_OPTIONS, profile.drinking)?.let { add("Drinking" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.SMOKING_OPTIONS, profile.smoking)?.let { add("Smoking" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.DRUGS_OPTIONS, profile.drugs)?.let { add("Drugs" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.RELIGION_OPTIONS, profile.religion)?.let { add("Religion" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.PETS_OPTIONS, profile.pets)?.let { add("Pets" to it) }
    ProfileContractApi.enumLabel(ProfileContractApi.DIET_OPTIONS, profile.diet)?.let { add("Diet" to it) }
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (identityRows.isNotEmpty()) {
      item { AboutSectionCard("Identity", identityRows) }
    }
    if (basicsRows.isNotEmpty()) {
      item { AboutSectionCard("Basics", basicsRows) }
    }
    if (locationRows.isNotEmpty()) {
      item { AboutSectionCard("Location", locationRows) }
    }
    if (preferenceRows.isNotEmpty()) {
      item { AboutSectionCard("Preferences", preferenceRows) }
    }
    if (identityRows.isEmpty() && basicsRows.isEmpty() && locationRows.isEmpty() && preferenceRows.isEmpty()) {
      item {
        Text(
          "No populated fields yet.",
          color = PiratePalette.TextMuted,
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.padding(8.dp),
        )
      }
    }
  }
}

@Composable
internal fun AboutSectionCard(
  title: String,
  rows: List<Pair<String, String>>,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = Color(0xFF1C1C1C),
    shape = RoundedCornerShape(14.dp),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      rows.forEachIndexed { index, row ->
        if (index > 0) HorizontalDivider(color = Color(0xFF2A2A2A))
        Text(row.first, style = MaterialTheme.typography.labelLarge, color = PiratePalette.TextMuted)
        Text(row.second, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
      }
    }
  }
}
