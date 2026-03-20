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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sc.pirate.app.R
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.ui.PirateToggleChip
import sc.pirate.app.ui.PirateToggleChipVariant

// Matches ProfileV2.sol enum Gender { Unset, Woman, Man, NonBinary, TransWoman, TransMan, Intersex, Other }
private val GENDER_OPTIONS = listOf(
  "woman",
  "man",
  "nonbinary",
  "transwoman",
  "transman",
  "intersex",
  "other",
)

private fun genderLabelRes(value: String): Int =
  when (value) {
    "woman" -> R.string.onboarding_gender_woman
    "man" -> R.string.onboarding_gender_man
    "nonbinary" -> R.string.onboarding_gender_nonbinary
    "transwoman" -> R.string.onboarding_gender_transwoman
    "transman" -> R.string.onboarding_gender_transman
    "intersex" -> R.string.onboarding_gender_intersex
    else -> R.string.onboarding_gender_other
  }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenderStep(
  submitting: Boolean,
  onContinue: (String) -> Unit,
) {
  var selected by remember { mutableStateOf<String?>(null) }
  val canContinue = selected != null && !submitting

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.Start,
  ) {
    Text(
      stringResource(R.string.onboarding_gender_title),
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(32.dp))

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      GENDER_OPTIONS.forEach { value ->
        PirateToggleChip(
          selected = selected == value,
          onClick = { selected = if (selected == value) null else value },
          label = stringResource(genderLabelRes(value)),
          variant = PirateToggleChipVariant.Card,
        )
      }
    }

    Spacer(Modifier.weight(1f))

    PiratePrimaryButton(
      text = stringResource(R.string.common_continue),
      onClick = { selected?.let { onContinue(it) } },
      enabled = canContinue,
      modifier = Modifier.fillMaxWidth().height(48.dp),
      loading = submitting,
    )

    Spacer(Modifier.height(32.dp))
  }
}
