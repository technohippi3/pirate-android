package sc.pirate.app.songpicker

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sc.pirate.app.ui.PirateSheetTitle
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongPickerSheet(
  repository: SongPickerRepository,
  ownerAddress: String? = null,
  onSelectSong: (SongPickerSong) -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  var query by remember { mutableStateOf("") }
  var songs by remember(ownerAddress) {
    mutableStateOf<List<SongPickerSong>>(
      repository.cachedSuggestedSongs(ownerAddress = ownerAddress, maxEntries = 24),
    )
  }
  var loading by remember { mutableStateOf(songs.isEmpty()) }
  var error by remember { mutableStateOf<String?>(null) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

  LaunchedEffect(query, ownerAddress) {
    val keepExistingResultsVisible = songs.isNotEmpty()
    if (!keepExistingResultsVisible) loading = true
    error = null
    delay(250)
    runCatching {
      if (query.isBlank()) {
        repository.suggestedSongs(
          context = context,
          ownerAddress = ownerAddress,
          maxEntries = 24,
        )
      } else {
        repository.searchPublishedSongs(
          context = context,
          ownerAddress = ownerAddress,
          query = query,
          maxEntries = 60,
        )
      }
    }.onSuccess { result ->
      songs = result
    }.onFailure { throwable ->
      if (songs.isEmpty()) {
        error = throwable.message ?: "Failed to load songs"
      }
    }
    loading = false
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .heightIn(min = 420.dp)
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      PirateSheetTitle(text = "Choose a Song")
      OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = query,
        onValueChange = { query = it },
        label = { Text("Search title or artist") },
        singleLine = true,
      )

      when {
        loading && songs.isEmpty() -> {
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator()
          }
        }

        error != null -> {
          Text(
            error ?: "Failed to load songs",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
          )
        }

        songs.isEmpty() -> {
          Text(
            if (query.isBlank()) "No likely remixable songs found yet." else "No remixable songs found.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
          )
        }

        else -> {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            items(items = songs, key = { song: SongPickerSong -> song.trackId }) { song ->
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable {
                    onSelectSong(song)
                    onDismiss()
                  }
                  .padding(vertical = 10.dp),
              ) {
                Text(song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                  song.artist,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                  modifier = Modifier.padding(top = 6.dp),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  val commercialAllowed = song.commercialUse
                  TermsPill(
                    label = if (commercialAllowed) "Commercial use allowed" else "Non-commercial only",
                    background = if (commercialAllowed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                    contentColor = if (commercialAllowed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                  )
                  if (commercialAllowed && song.commercialRevSharePpm8 > 0) {
                    TermsPill(
                      label = "Rev share ${formatRevSharePercent(song.commercialRevSharePpm8)}",
                      background = MaterialTheme.colorScheme.secondaryContainer,
                      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                  }
                  val isGated = song.approvalMode.equals("gated", ignoreCase = true)
                  if (isGated) {
                    TermsPill(
                      label = "Needs approval (${formatSlaHours(song.approvalSlaSec)})",
                      background = MaterialTheme.colorScheme.tertiaryContainer,
                      contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                  }
                }
              }
              HorizontalDivider()
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TermsPill(
  label: String,
  background: Color,
  contentColor: Color,
) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelSmall,
    color = contentColor,
    modifier =
      Modifier
        .background(background, RoundedCornerShape(999.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp),
  )
}

private fun formatRevSharePercent(ppm8: Int): String {
  val value = ppm8.toDouble() / 1_000_000.0
  val oneDecimal = String.format(Locale.US, "%.1f", value).removeSuffix(".0")
  return "$oneDecimal%"
}

private fun formatSlaHours(seconds: Int): String {
  val safeSeconds = seconds.coerceAtLeast(0)
  val hours = ((safeSeconds + 3_599) / 3_600).coerceAtLeast(1)
  return "${hours}h"
}
