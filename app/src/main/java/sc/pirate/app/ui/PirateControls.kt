package sc.pirate.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import sc.pirate.app.theme.PirateTokens

object PirateButtonDefaults {
  val MinButtonHeight = 48.dp
  val MinIconButtonSize = 40.dp
}

@Composable
fun PiratePrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  loading: Boolean = false,
  containerColor: Color = MaterialTheme.colorScheme.primary,
  disabledContainerColor: Color = PirateTokens.colors.surfaceDisabled,
  leadingIcon: (@Composable () -> Unit)? = null,
) {
  Button(
    onClick = onClick,
    enabled = enabled && !loading,
    modifier = modifier.heightIn(min = PirateButtonDefaults.MinButtonHeight),
    shape = MaterialTheme.shapes.medium,
    colors =
      ButtonDefaults.buttonColors(
        containerColor = containerColor,
        contentColor = PirateTokens.colors.textOnAccent,
        disabledContainerColor = disabledContainerColor,
        disabledContentColor = PirateTokens.colors.textOnAccent.copy(alpha = 0.9f),
      ),
  ) {
    if (loading) {
      PirateButtonLoader()
    } else {
      if (leadingIcon != null) {
        leadingIcon()
        Spacer(modifier = Modifier.width(8.dp))
      }
      Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
  }
}

@Composable
fun PirateOutlinedButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  shape: Shape = MaterialTheme.shapes.medium,
  colors: ButtonColors =
    ButtonDefaults.outlinedButtonColors(
      containerColor = Color.Transparent,
      contentColor = PirateTokens.colors.textPrimary,
      disabledContainerColor = Color.Transparent,
      disabledContentColor = PirateTokens.colors.textDisabled,
    ),
  elevation: ButtonElevation? = null,
  border: BorderStroke? = BorderStroke(1.dp, PirateTokens.colors.borderSoft),
  contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
  interactionSource: MutableInteractionSource? = null,
  content: @Composable RowScope.() -> Unit,
) {
  OutlinedButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = PirateButtonDefaults.MinButtonHeight),
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
  )
}

@Composable
fun PirateTextButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  shape: Shape = MaterialTheme.shapes.medium,
  colors: ButtonColors =
    ButtonDefaults.textButtonColors(
      contentColor = PirateTokens.colors.accentBrand,
      disabledContentColor = PirateTokens.colors.textDisabled,
    ),
  elevation: ButtonElevation? = null,
  border: BorderStroke? = null,
  contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
  interactionSource: MutableInteractionSource? = null,
  content: @Composable RowScope.() -> Unit,
) {
  TextButton(
    onClick = onClick,
    modifier = modifier.heightIn(min = PirateButtonDefaults.MinButtonHeight),
    enabled = enabled,
    shape = shape,
    colors = colors,
    elevation = elevation,
    border = border,
    contentPadding = contentPadding,
    interactionSource = interactionSource,
    content = content,
  )
}

@Composable
fun PirateIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
  interactionSource: MutableInteractionSource? = null,
  content: @Composable () -> Unit,
) {
  IconButton(
    onClick = onClick,
    modifier =
      modifier.sizeIn(
        minWidth = PirateButtonDefaults.MinIconButtonSize,
        minHeight = PirateButtonDefaults.MinIconButtonSize,
      ),
    enabled = enabled,
    colors = colors,
    interactionSource = interactionSource,
    content = content,
  )
}

@Composable
fun PirateButtonLoader(
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.onPrimary,
) {
  CircularProgressIndicator(
    modifier = modifier.size(18.dp),
    color = color,
    strokeWidth = 2.dp,
  )
}

@Composable
fun PirateOutlinedTextField(
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  label: @Composable (() -> Unit)? = null,
  placeholder: @Composable (() -> Unit)? = null,
  suffix: @Composable (() -> Unit)? = null,
  trailingIcon: @Composable (() -> Unit)? = null,
  supportingText: @Composable (() -> Unit)? = null,
  singleLine: Boolean = true,
  isError: Boolean = false,
  readOnly: Boolean = false,
  enabled: Boolean = true,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    label = label,
    placeholder = placeholder,
    suffix = suffix,
    trailingIcon = trailingIcon,
    supportingText = supportingText,
    singleLine = singleLine,
    isError = isError,
    readOnly = readOnly,
    enabled = enabled,
    keyboardOptions = keyboardOptions,
    shape = MaterialTheme.shapes.medium,
    colors =
      androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedBorderColor = PirateTokens.colors.accentBrand,
        unfocusedBorderColor = PirateTokens.colors.borderDefault,
        disabledBorderColor = PirateTokens.colors.borderSoft,
        focusedTextColor = PirateTokens.colors.textPrimary,
        unfocusedTextColor = PirateTokens.colors.textPrimary,
        cursorColor = PirateTokens.colors.accentBrand,
        focusedContainerColor = PirateTokens.colors.bgSurface,
        unfocusedContainerColor = PirateTokens.colors.bgSurface,
        disabledContainerColor = PirateTokens.colors.surfaceDisabled,
        focusedLabelColor = PirateTokens.colors.textSecondary,
        unfocusedLabelColor = PirateTokens.colors.textSecondary,
        focusedPlaceholderColor = PirateTokens.colors.textSecondary,
        unfocusedPlaceholderColor = PirateTokens.colors.textSecondary,
      ),
  )
}
