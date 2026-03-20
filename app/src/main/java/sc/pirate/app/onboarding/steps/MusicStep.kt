package sc.pirate.app.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sc.pirate.app.R
import sc.pirate.app.onboarding.OnboardingArtist
import sc.pirate.app.onboarding.POPULAR_ARTISTS
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.ui.PirateToggleChip
import sc.pirate.app.ui.PirateToggleChipVariant

private const val MIN_ARTISTS = 3

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicStep(
  submitting: Boolean,
  onContinue: (List<OnboardingArtist>) -> Unit,
) {
  val selectedMbids = remember { mutableStateListOf<String>() }
  val canContinue = selectedMbids.size >= MIN_ARTISTS && !submitting
  val remaining = (MIN_ARTISTS - selectedMbids.size).coerceAtLeast(0)

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.Start,
  ) {
    Text(
      stringResource(R.string.onboarding_music_title),
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      if (remaining > 0) {
        stringResource(R.string.onboarding_music_remaining, remaining)
      } else {
        stringResource(R.string.onboarding_music_selected_count, selectedMbids.size)
      },
      fontSize = 16.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))

    FlowRow(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .verticalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      POPULAR_ARTISTS.forEach { artist ->
        val isSelected = artist.mbid in selectedMbids
        PirateToggleChip(
          selected = isSelected,
          onClick = {
            if (isSelected) selectedMbids.remove(artist.mbid)
            else selectedMbids.add(artist.mbid)
          },
          label = artist.name,
          variant = PirateToggleChipVariant.Card,
        )
      }
    }

    Spacer(Modifier.height(16.dp))

    PiratePrimaryButton(
      text = stringResource(R.string.common_continue),
      onClick = {
        val selected = POPULAR_ARTISTS.filter { it.mbid in selectedMbids }
        onContinue(selected)
      },
      enabled = canContinue,
      modifier = Modifier.fillMaxWidth().height(48.dp),
      loading = submitting,
    )

    Spacer(Modifier.height(32.dp))
  }
}
