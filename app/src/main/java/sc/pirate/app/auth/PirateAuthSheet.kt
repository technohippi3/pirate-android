package sc.pirate.app.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.CaretRight
import com.adamglin.phosphoricons.regular.EnvelopeSimple
import com.adamglin.phosphoricons.regular.FingerprintSimple
import com.adamglin.phosphoricons.regular.GoogleLogo
import com.adamglin.phosphoricons.regular.XLogo
import sc.pirate.app.theme.PirateTokens
import sc.pirate.app.ui.PirateOutlinedButton
import sc.pirate.app.ui.PirateOutlinedTextField
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.ui.PirateTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PirateAuthSheet(
  busy: Boolean,
  enabledMethods: List<PirateAuthMethod>,
  codeMethod: PirateAuthMethod?,
  identifier: String,
  code: String,
  codeSent: Boolean,
  onDismiss: () -> Unit,
  onMethodSelected: (PirateAuthMethod) -> Unit,
  onIdentifierChange: (String) -> Unit,
  onCodeChange: (String) -> Unit,
  onSendCode: () -> Unit,
  onSubmitCode: () -> Unit,
  onBackToMethods: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val resolvedCodeMethod = codeMethod?.takeIf(enabledMethods::contains)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    if (resolvedCodeMethod?.requiresOneTimeCode == true) {
      PirateAuthCodeEntry(
        method = resolvedCodeMethod,
        busy = busy,
        identifier = identifier,
        code = code,
        codeSent = codeSent,
        onIdentifierChange = onIdentifierChange,
        onCodeChange = onCodeChange,
        onSendCode = onSendCode,
        onSubmitCode = onSubmitCode,
        onBackToMethods = onBackToMethods,
      )
    } else {
      PirateAuthMethodPicker(
        busy = busy,
        enabledMethods = enabledMethods,
        onMethodSelected = onMethodSelected,
      )
    }
  }
}

@Composable
private fun PirateAuthMethodPicker(
  busy: Boolean,
  enabledMethods: List<PirateAuthMethod>,
  onMethodSelected: (PirateAuthMethod) -> Unit,
) {
  val orderedMethods = enabledMethods.distinct().sortedBy(PirateAuthMethod::ordinal)

  Column(
    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = "Sign in",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
    )
    Spacer(modifier = Modifier.size(2.dp))
    orderedMethods.forEach { method ->
      PirateAuthMethodRow(
        method = method,
        enabled = true,
        busy = busy,
        onClick = { onMethodSelected(method) },
      )
    }
  }
}

@Composable
private fun PirateAuthMethodRow(
  method: PirateAuthMethod,
  enabled: Boolean,
  busy: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    modifier =
      Modifier
        .fillMaxWidth()
        .alpha(
          when {
            busy -> 0.7f
            enabled -> 1f
            else -> 0.62f
          },
        )
        .clickable(enabled = enabled && !busy, onClick = onClick),
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface,
    border =
      androidx.compose.foundation.BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
      ),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = method.icon,
          contentDescription = null,
          tint = PirateTokens.colors.textPrimary,
        )
      }
      Text(
        text = method.label,
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (enabled) {
        Icon(
          imageVector = PhosphorIcons.Regular.CaretRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun PirateAuthCodeEntry(
  method: PirateAuthMethod,
  busy: Boolean,
  identifier: String,
  code: String,
  codeSent: Boolean,
  onIdentifierChange: (String) -> Unit,
  onCodeChange: (String) -> Unit,
  onSendCode: () -> Unit,
  onSubmitCode: () -> Unit,
  onBackToMethods: () -> Unit,
) {
  Column(
    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    PirateTextButton(onClick = onBackToMethods, enabled = !busy) {
      Icon(
        imageVector = PhosphorIcons.Regular.ArrowLeft,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.size(8.dp))
      Text("All methods")
    }

    Text(
      text = method.label,
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text = method.description,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    PirateOutlinedTextField(
      value = identifier,
      onValueChange = onIdentifierChange,
      modifier = Modifier.fillMaxWidth(),
      enabled = !busy,
      label = { Text("Email") },
      placeholder = { Text("name@example.com") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )

    if (codeSent) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Text(
        text = "Enter the 6-digit code sent to $identifier.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      PirateOutlinedTextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
        label = { Text("Code") },
        placeholder = { Text("123456") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PirateOutlinedButton(
          onClick = onSendCode,
          enabled = !busy && identifier.isNotBlank(),
          modifier = Modifier.weight(1f),
        ) {
          Text("Resend")
        }
        PiratePrimaryButton(
          text = "Sign in",
          onClick = onSubmitCode,
          enabled = !busy && identifier.isNotBlank() && code.isNotBlank(),
          modifier = Modifier.weight(1f),
        )
      }
    } else {
      PiratePrimaryButton(
        text = "Send code",
        onClick = onSendCode,
        enabled = !busy && identifier.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

private val PirateAuthMethod.icon: ImageVector
  get() =
    when (this) {
      PirateAuthMethod.GOOGLE -> PhosphorIcons.Regular.GoogleLogo
      PirateAuthMethod.EMAIL -> PhosphorIcons.Regular.EnvelopeSimple
      PirateAuthMethod.PASSKEY -> PhosphorIcons.Regular.FingerprintSimple
      PirateAuthMethod.TWITTER -> PhosphorIcons.Regular.XLogo
    }
