package com.pirate.app.music.ui

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pirate.app.ui.PiratePrimaryButton
import com.pirate.app.ui.PirateSheetTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TurboCreditsSheet(
  open: Boolean,
  message: String,
  onDismiss: () -> Unit,
  onGetCredits: () -> Unit,
) {
  if (!open) return

  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    sheetState = sheetState,
    onDismissRequest = onDismiss,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PirateSheetTitle(text = "Arweave Credits Needed")
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(6.dp))
      PiratePrimaryButton(
        text = "Buy Credits",
        onClick = onGetCredits,
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = {
          Icon(
            imageVector = PhosphorIcons.Regular.ArrowSquareOut,
            contentDescription = "Opens browser",
          )
        },
      )
      Spacer(modifier = Modifier.height(4.dp))
    }
  }
}
