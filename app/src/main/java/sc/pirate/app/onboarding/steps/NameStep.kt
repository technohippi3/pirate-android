package sc.pirate.app.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import sc.pirate.app.theme.PirateTokens
import sc.pirate.app.ui.PirateOutlinedTextField
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.ui.PirateToggleChip
import kotlinx.coroutines.delay

private const val MIN_LABEL_LENGTH = 3

sealed interface NameAvailabilityResult {
  data object Available : NameAvailabilityResult

  data class Unavailable(
    val message: String? = null,
  ) : NameAvailabilityResult
}

@Composable
fun NameStep(
  submitting: Boolean,
  error: String?,
  selectedTld: String,
  onTldChange: (String) -> Unit,
  onCheckAvailable: suspend (label: String, tld: String) -> NameAvailabilityResult,
  onContinue: (label: String, tld: String) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var checking by remember { mutableStateOf(false) }
  var available by remember { mutableStateOf<Boolean?>(null) }
  var checkError by remember { mutableStateOf<String?>(null) }
  val minLabelError = stringResource(R.string.onboarding_name_error_min_chars, MIN_LABEL_LENGTH)
  val maxLabelError = stringResource(R.string.onboarding_name_error_max_chars)
  val takenError = stringResource(R.string.onboarding_name_error_taken)
  val checkFailedError = stringResource(R.string.onboarding_name_error_check_failed)

  // Debounced availability check — skip when already submitting (avoids
  // "coroutine escaped" when AnimatedContent transitions away mid-check)
  LaunchedEffect(name, selectedTld, submitting) {
    if (submitting) return@LaunchedEffect
    available = null
    checkError = null
    val sanitized = name.lowercase().filter { it.isLetterOrDigit() || it == '-' }
    if (sanitized.length < MIN_LABEL_LENGTH) {
      if (sanitized.isNotEmpty()) checkError = minLabelError
      return@LaunchedEffect
    }
    if (sanitized.length > 32) {
      checkError = maxLabelError
      return@LaunchedEffect
    }
    checking = true
    delay(400)
    try {
      when (val result = onCheckAvailable(sanitized, selectedTld)) {
        NameAvailabilityResult.Available -> {
          available = true
        }
        is NameAvailabilityResult.Unavailable -> {
          available = false
          checkError = result.message ?: takenError
        }
      }
    } catch (e: Exception) {
      checkError = e.message ?: checkFailedError
    } finally {
      checking = false
    }
  }

  val sanitized = name.lowercase().filter { it.isLetterOrDigit() || it == '-' }
  val canContinue = available == true && !submitting && sanitized.length >= MIN_LABEL_LENGTH

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.Start,
  ) {
    Text(
      stringResource(R.string.onboarding_name_title),
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      stringResource(R.string.onboarding_name_subtitle),
      fontSize = 16.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PirateToggleChip(
        selected = selectedTld == "pirate",
        onClick = { onTldChange("pirate") },
        label = ".pirate",
      )
      PirateToggleChip(selected = selectedTld == "heaven", onClick = { onTldChange("heaven") }, label = ".heaven")
    }
    Spacer(Modifier.height(14.dp))

    PirateOutlinedTextField(
      value = name,
      onValueChange = { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' } },
      modifier = Modifier.fillMaxWidth(),
      label = { Text(stringResource(R.string.onboarding_name_username_label)) },
      suffix = { Text(".${selectedTld.lowercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
      singleLine = true,
    )
    Spacer(Modifier.height(8.dp))

    // Status indicator
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
      horizontalArrangement = Arrangement.Start,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      when {
        checking -> {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Text(
            "  ${stringResource(R.string.onboarding_name_checking)}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        available == true -> {
          Text(
            "✓ ${stringResource(R.string.onboarding_name_available)}",
            fontSize = 14.sp,
            color = PirateTokens.colors.accentSuccess,
          )
        }
        checkError != null -> {
          Text(checkError!!, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
        }
      }
    }

    if (error != null) {
      Spacer(Modifier.height(8.dp))
      Text(error, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.weight(1f))

    PiratePrimaryButton(
      text = stringResource(R.string.onboarding_name_claim_button),
      onClick = { onContinue(sanitized, selectedTld) },
      enabled = canContinue,
      modifier = Modifier.fillMaxWidth().height(48.dp),
      loading = submitting,
    )

    Spacer(Modifier.height(32.dp))
  }
}
