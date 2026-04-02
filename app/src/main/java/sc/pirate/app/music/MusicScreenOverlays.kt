package sc.pirate.app.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import sc.pirate.app.ui.PirateTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sc.pirate.app.music.ui.AddToPlaylistSheet
import sc.pirate.app.music.ui.CreatePlaylistSheet
import sc.pirate.app.theme.PirateTokens
import sc.pirate.app.util.shortAddress
import kotlinx.coroutines.launch

@Composable
internal fun CloudPlayBusyBanner(
  cloudPlayBusy: Boolean,
  cloudPlayLabel: String?,
) {
  if (!cloudPlayBusy) return
  Surface(
    modifier = Modifier
      .padding(horizontal = 16.dp, vertical = 18.dp),
    color = PirateTokens.colors.bgElevated,
    shape = MaterialTheme.shapes.extraLarge,
    tonalElevation = 0.dp,
    shadowElevation = PirateTokens.shadow.sm,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = cloudPlayLabel ?: "Decrypting...",
        color = PirateTokens.colors.textPrimary,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
internal fun MusicPlaylistSheets(
  createPlaylistOpen: Boolean,
  addToPlaylistOpen: Boolean,
  selectedTrack: MusicTrack?,
  isAuthenticated: Boolean,
  ownerEthAddress: String?,
  onShowMessage: (String) -> Unit,
  onCreatePlaylistOpenChange: (Boolean) -> Unit,
  onAddToPlaylistOpenChange: (Boolean) -> Unit,
  onCreatePlaylistSuccess: (playlistId: String, successMessage: String) -> Unit,
  onAddToPlaylistSuccess: (playlistId: String, trackAdded: Boolean) -> Unit,
) {
  CreatePlaylistSheet(
    open = createPlaylistOpen,
    isAuthenticated = isAuthenticated,
    ownerEthAddress = ownerEthAddress,
    onClose = { onCreatePlaylistOpenChange(false) },
    onShowMessage = onShowMessage,
    onSuccess = { playlistId, _, successMessage ->
      onCreatePlaylistSuccess(playlistId, successMessage)
    },
  )

  AddToPlaylistSheet(
    open = addToPlaylistOpen,
    track = selectedTrack,
    isAuthenticated = isAuthenticated,
    ownerEthAddress = ownerEthAddress,
    onClose = { onAddToPlaylistOpenChange(false) },
    onShowMessage = onShowMessage,
    onSuccess = { playlistId, _, trackAdded ->
      onAddToPlaylistSuccess(playlistId, trackAdded)
    },
  )
}

@Composable
internal fun MusicShareDialog(
  shareTrack: MusicTrack?,
  ownerEthAddress: String?,
  onDismiss: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val activeTrack = shareTrack ?: return
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var shareRecipientInput by rememberSaveable(activeTrack.id) { mutableStateOf("") }
  var shareBusy by remember(activeTrack.id) { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = {
      if (!shareBusy) {
        onDismiss()
      }
    },
    title = { Text("Share Track") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "Enter wallet address, .heaven, or .pirate name.",
          style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
          value = shareRecipientInput,
          onValueChange = { if (!shareBusy) shareRecipientInput = it },
          singleLine = true,
          label = { Text("Recipient") },
          placeholder = { Text("0x..., alice.heaven, bob.pirate") },
          enabled = !shareBusy,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      PirateTextButton(
        enabled = !shareBusy && shareRecipientInput.trim().isNotEmpty(),
        onClick = {
          val owner = ownerEthAddress
          if (owner.isNullOrBlank()) {
            onShowMessage("Missing share credentials")
            return@PirateTextButton
          }
          shareBusy = true
          scope.launch {
            val result =
              UploadedTrackActions.shareUploadedTrack(
                context = context,
                track = activeTrack,
                recipient = shareRecipientInput,
                ownerAddress = owner,
                onStatusMessage = onShowMessage,
              )
            shareBusy = false
            if (!result.success) {
              onShowMessage("Share failed: ${result.error ?: "unknown error"}")
              return@launch
            }
            onDismiss()
            val target = result.recipientAddress?.let { shortAddress(it, minLengthToShorten = 10) } ?: "recipient"
            onShowMessage("Shared with $target")
          }
        },
      ) {
        Text(if (shareBusy) "Sharing..." else "Share")
      }
    },
    dismissButton = {
      PirateTextButton(
        enabled = !shareBusy,
        onClick = onDismiss,
      ) {
        Text("Cancel")
      }
    },
  )
}
