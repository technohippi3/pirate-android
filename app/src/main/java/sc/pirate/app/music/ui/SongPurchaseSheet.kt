package sc.pirate.app.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.ui.PirateSheetTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongPurchaseSheet(
  open: Boolean,
  title: String,
  artist: String,
  priceLabel: String?,
  busy: Boolean,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
  confirmLabel: String,
) {
  if (!open) return

  ModalBottomSheet(
    onDismissRequest = {
      if (!busy) onDismiss()
    },
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PirateSheetTitle(text = "Buy Song")
        Text(
          text = priceLabel ?: "--",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Text(
        text = "$title - $artist",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      PiratePrimaryButton(
        text = confirmLabel,
        modifier = Modifier.fillMaxWidth(),
        onClick = onConfirm,
        enabled = !busy,
      )
    }
  }
}
