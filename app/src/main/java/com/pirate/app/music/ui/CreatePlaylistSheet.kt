package com.pirate.app.music.ui

import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import com.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pirate.app.music.LocalPlaylistsStore
import com.pirate.app.music.ONCHAIN_PLAYLISTS_ENABLED
import com.pirate.app.music.OnChainPlaylistsApi
import com.pirate.app.music.TempoPlaylistApi
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoPasskeyManager
import com.pirate.app.tempo.TempoSessionKeyApi
import com.pirate.app.ui.PiratePrimaryButton
import com.pirate.app.ui.PirateSheetTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePlaylistSheet(
  open: Boolean,
  isAuthenticated: Boolean,
  ownerEthAddress: String?,
  tempoAccount: TempoPasskeyManager.PasskeyAccount?,
  hostActivity: FragmentActivity?,
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
  onSuccess: (playlistId: String, playlistName: String, successMessage: String) -> Unit,
) {
  if (!open) return

  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var name by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }

  LaunchedEffect(open) {
    if (open) {
      name = ""
      busy = false
    }
  }

  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    sheetState = sheetState,
    onDismissRequest = {
      if (!busy) onClose()
    },
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      PirateSheetTitle(text = "New Playlist")
      HorizontalDivider()

      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Playlist name") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !busy,
      )

      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PirateOutlinedButton(
          enabled = !busy,
          onClick = onClose,
        ) { Text("Cancel") }

        PiratePrimaryButton(
          text = "Create",
          enabled = !busy && name.trim().isNotEmpty(),
          loading = busy,
          onClick = {
            val trimmed = name.trim()
            scope.launch {
              busy = true
              val owner = ownerEthAddress?.trim()?.lowercase().orEmpty()
              if (ONCHAIN_PLAYLISTS_ENABLED && isAuthenticated && owner.isNotBlank() && tempoAccount != null) {
                val loaded =
                  SessionKeyManager.load(context)?.takeIf {
                    SessionKeyManager.isValid(it, ownerAddress = owner) &&
                      it.keyAuthorization?.isNotEmpty() == true
                  }
                val sessionKey =
                  loaded
                    ?: run {
                      val activity = hostActivity
                      if (activity == null) {
                        onShowMessage("Session expired. Sign in again to create playlists.")
                        busy = false
                        return@launch
                      }
                      onShowMessage("Authorizing session key...")
                      val auth = TempoSessionKeyApi.authorizeSessionKey(activity = activity, account = tempoAccount)
                      val authorized =
                        auth.sessionKey?.takeIf {
                          auth.success &&
                            SessionKeyManager.isValid(it, ownerAddress = owner) &&
                            it.keyAuthorization?.isNotEmpty() == true
                        }
                      if (authorized == null) {
                        onShowMessage(
                          auth.error ?: "Session key authorization failed. Sign in again to create playlists.",
                        )
                        busy = false
                        return@launch
                      }
                      authorized
                    }

                val result =
                  TempoPlaylistApi.createPlaylist(
                    account = tempoAccount,
                    sessionKey = sessionKey,
                    name = trimmed,
                    coverCid = "",
                    visibility = 0,
                    trackIds = emptyList(),
                  )
                if (!result.success) {
                  onShowMessage("Create failed: ${result.error ?: "unknown error"}")
                  busy = false
                  return@launch
                }

                val resolvedId = resolveCreatedPlaylistId(owner, trimmed, result.playlistId)
                val successMessage = "Created $trimmed"
                onSuccess(
                  resolvedId ?: "pending:${System.currentTimeMillis()}",
                  trimmed,
                  successMessage,
                )
              } else {
                val created = LocalPlaylistsStore.createLocalPlaylist(context, trimmed, initialTrack = null)
                val successMessage = "Created ${created.name}"
                onSuccess(created.id, created.name, successMessage)
              }
              busy = false
              onClose()
            }
          },
        )
      }

      Spacer(modifier = Modifier.height(8.dp))
    }
  }
}

private suspend fun resolveCreatedPlaylistId(
  ownerAddress: String,
  playlistName: String,
  immediateId: String?,
): String? {
  val direct = immediateId?.trim().orEmpty()
  if (direct.startsWith("0x") && direct.length == 66) return direct.lowercase()

  repeat(4) {
    val candidates = runCatching { OnChainPlaylistsApi.fetchUserPlaylists(ownerAddress, maxEntries = 30) }.getOrNull()
    val match =
      candidates
        ?.firstOrNull { playlist ->
          playlist.name.trim().equals(playlistName.trim(), ignoreCase = true)
        }
        ?.id
        ?.trim()
    if (!match.isNullOrBlank()) return match.lowercase()
    delay(1_200L)
  }

  return null
}
