package sc.pirate.app.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import sc.pirate.app.R
import sc.pirate.app.ui.PirateOutlinedTextField
import sc.pirate.app.ui.PiratePrimaryButton

@Composable
fun AgeStep(
  submitting: Boolean,
  onContinue: (Int) -> Unit,
) {
  var ageText by remember { mutableStateOf("") }
  val age = ageText.toIntOrNull()
  val validNumberError = stringResource(R.string.onboarding_age_error_valid_number)
  val minimumAgeError = stringResource(R.string.onboarding_age_error_minimum)
  val invalidAgeError = stringResource(R.string.onboarding_age_error_invalid_age)
  val ageError = when {
    ageText.isBlank() -> null
    age == null -> validNumberError
    age < 13 -> minimumAgeError
    age > 120 -> invalidAgeError
    else -> null
  }
  val canContinue = age != null && age in 13..120 && !submitting

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.Start,
  ) {
    Text(
      stringResource(R.string.onboarding_age_title),
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(32.dp))

    PirateOutlinedTextField(
      value = ageText,
      onValueChange = { ageText = it.filter { c -> c.isDigit() }.take(3) },
      modifier = Modifier.fillMaxWidth(),
      label = { Text(stringResource(R.string.onboarding_age_label)) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      isError = ageError != null,
      supportingText = if (ageError != null) {{ Text(ageError) }} else null,
    )

    Spacer(Modifier.weight(1f))

    PiratePrimaryButton(
      text = stringResource(R.string.common_continue),
      onClick = { age?.let { onContinue(it) } },
      enabled = canContinue,
      modifier = Modifier.fillMaxWidth().height(48.dp),
      loading = submitting,
    )

    Spacer(Modifier.height(32.dp))
  }
}
