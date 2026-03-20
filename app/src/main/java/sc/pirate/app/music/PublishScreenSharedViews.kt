package sc.pirate.app.music

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sc.pirate.app.theme.PirateTokens

@Composable
internal fun PublishingStep(progress: Float) {
  val (stageIndex, label) =
    when {
      progress < 0.15f -> 1 to "Preparing files..."
      progress < 0.35f -> 2 to "Uploading audio..."
      progress < 0.50f -> 3 to "Uploading cover, lyrics, and stems..."
      progress < 0.70f -> 4 to "Checking upload..."
      progress < 0.88f -> 5 to "Registering your release..."
      else -> 6 to "Finalizing release..."
    }

  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = "Publishing your song",
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(10.dp))
    Text(
      text = "$stageIndex/6 $label",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(20.dp))
    CircularProgressIndicator(
      modifier = Modifier.size(40.dp),
      strokeWidth = 3.dp,
    )
  }
}

@Composable
internal fun SuccessStep(
  formData: SongPublishService.SongFormData? = null,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (formData?.coverUri != null) {
      coil.compose.AsyncImage(
        model = formData.coverUri,
        contentDescription = "Cover Art",
        modifier =
          Modifier
            .size(180.dp)
            .clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop,
      )
      Spacer(modifier = Modifier.height(16.dp))
    }
    if (formData != null) {
      Text(formData.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
      Text(formData.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
internal fun ErrorStep(
  error: String,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier =
        Modifier
          .size(64.dp)
          .clip(CircleShape)
          .background(PirateTokens.colors.accentDanger),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        PhosphorIcons.Regular.Warning,
        contentDescription = null,
        tint = PirateTokens.colors.textOnAccent,
        modifier = Modifier.size(36.dp),
      )
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text("Publishing failed", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(12.dp))
    Text(
      error,
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
internal fun FilePickerButton(
  label: String,
  fileName: String?,
  icon: @Composable () -> Unit,
  onClick: () -> Unit,
  onClear: (() -> Unit)? = null,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
      .clickable { onClick() }
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    icon()
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.bodyLarge)
      if (fileName != null) {
        Text(
          fileName,
          maxLines = 1,
          softWrap = false,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
          "Choose file",
          maxLines = 1,
          softWrap = false,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (fileName != null && onClear != null) {
      PirateIconButton(onClick = onClear) {
        Icon(
          PhosphorIcons.Regular.X,
          contentDescription = "Remove $label",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      Icon(
        PhosphorIcons.Regular.CaretRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DropdownField(
  label: String,
  options: List<DropdownOption>,
  selectedValue: String,
  onSelect: (String) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val selectedLabel = options.find { it.value == selectedValue }?.label ?: selectedValue

  ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
    OutlinedTextField(
      value = selectedLabel,
      onValueChange = {},
      readOnly = true,
      label = { Text(label) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
    )
    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(option.label) },
          onClick = {
            onSelect(option.value)
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
internal fun SummaryRow(label: String, value: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
  }
}

@Composable
internal fun MonoRow(label: String, value: String) {
  Column {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
      value,
      style = MaterialTheme.typography.bodyMedium,
      fontFamily = FontFamily.Monospace,
      maxLines = 1,
    )
  }
}
