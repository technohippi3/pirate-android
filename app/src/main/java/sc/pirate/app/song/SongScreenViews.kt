package sc.pirate.app.song

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.*
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.DotsThree
import sc.pirate.app.music.CoverRef
import sc.pirate.app.music.MusicTrack
import sc.pirate.app.music.resolveSongTrackId
import sc.pirate.app.resolvePublicProfileIdentityWithRetry
import sc.pirate.app.theme.PiratePalette
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.ui.PirateSheetTitle
import sc.pirate.app.ui.PirateShimmer
import sc.pirate.app.util.shortAddress
import java.util.Locale

@Composable
internal fun SongScreenLoadingSkeleton() {
  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 16.dp)
          .padding(top = 8.dp, bottom = 6.dp)
          .heightIn(min = 56.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PirateShimmer(modifier = Modifier.size(40.dp).clip(CircleShape))
      PirateShimmer(modifier = Modifier.size(40.dp).clip(CircleShape))
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      PirateShimmer(modifier = Modifier.size(220.dp).clip(RoundedCornerShape(14.dp)))
    }

    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      PirateShimmer(modifier = Modifier.fillMaxWidth(0.8f).height(28.dp).clip(RoundedCornerShape(8.dp)))
      PirateShimmer(modifier = Modifier.fillMaxWidth(0.5f).height(22.dp).clip(RoundedCornerShape(8.dp)))
      PirateShimmer(modifier = Modifier.fillMaxWidth(0.35f).height(18.dp).clip(RoundedCornerShape(8.dp)))
    }

    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      repeat(5) {
        PirateShimmer(modifier = Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(12.dp)))
      }
    }
  }
}

@Composable
internal fun SongTopSection(
  title: String,
  artist: String?,
  coverCid: String?,
  localCoverUri: String?,
  scrobbleCountTotal: Long,
  onArtistClick: () -> Unit,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  isSongPlaying: Boolean,
  onTogglePlayback: () -> Unit,
) {
  var refreshMenuExpanded by remember { mutableStateOf(false) }
  val coverUrl = CoverRef.resolveCoverUrl(ref = coverCid, width = 800, height = 800, format = "webp", quality = 85)
  val localCover = localCoverUri?.trim().orEmpty().ifBlank { null }
  val displayCover = coverUrl ?: localCover
  var coverLoading by remember(displayCover) { mutableStateOf(!displayCover.isNullOrBlank()) }
  val titleText = title.ifBlank { "Song" }
  val artistText = artist?.ifBlank { null }
  val artistInitial = artistText?.take(1)?.uppercase(Locale.US) ?: titleText.take(1).uppercase(Locale.US)

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(bottom = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 16.dp)
          .padding(top = 8.dp, bottom = 6.dp)
          .heightIn(min = 56.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PirateIconButton(
        onClick = onBack,
      ) {
        Icon(
          PhosphorIcons.Regular.ArrowLeft,
          contentDescription = "Previous screen",
          tint = MaterialTheme.colorScheme.onBackground,
        )
      }

      Box {
        PirateIconButton(onClick = { refreshMenuExpanded = true }) {
          Icon(
            PhosphorIcons.Regular.DotsThree,
            contentDescription = "More",
            tint = MaterialTheme.colorScheme.onBackground,
          )
        }
        DropdownMenu(
          expanded = refreshMenuExpanded,
          onDismissRequest = { refreshMenuExpanded = false },
        ) {
          DropdownMenuItem(
            text = { Text("Refresh") },
            onClick = {
              refreshMenuExpanded = false
              onRefresh()
            },
          )
        }
      }
    }

    Box(
      modifier = Modifier.fillMaxWidth(),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        modifier = Modifier.size(220.dp),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
      ) {
        if (!displayCover.isNullOrBlank()) {
          Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
              model = displayCover,
              contentDescription = "Song cover",
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
              onLoading = { coverLoading = true },
              onSuccess = { coverLoading = false },
              onError = { coverLoading = false },
            )
            if (coverLoading) {
              PirateShimmer(modifier = Modifier.fillMaxSize())
            }
          }
        } else {
          Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              titleText.take(1).uppercase(Locale.US),
              style = MaterialTheme.typography.headlineMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          titleText,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        if (!artistText.isNullOrBlank()) {
          Row(
            modifier = Modifier.clickable(onClick = onArtistClick),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(
              modifier = Modifier.size(24.dp),
              shape = CircleShape,
              color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
              if (!displayCover.isNullOrBlank()) {
                AsyncImage(
                  model = displayCover,
                  contentDescription = "Artist",
                  modifier = Modifier.fillMaxSize(),
                  contentScale = ContentScale.Crop,
                )
              } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  Text(
                    artistInitial,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              artistText,
              style = MaterialTheme.typography.titleSmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Text(
          "$scrobbleCountTotal scrobbles",
          style = MaterialTheme.typography.bodyLarge,
          color = PiratePalette.TextMuted,
        )
      }

      Surface(
        modifier = Modifier.size(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 6.dp,
      ) {
        PirateIconButton(onClick = onTogglePlayback) {
          Icon(
            imageVector = if (isSongPlaying) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
            contentDescription = if (isSongPlaying) "Pause" else "Play",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(26.dp),
          )
        }
      }
    }
  }
}

@Composable
internal fun SongExerciseFooterCta(
  label: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    tonalElevation = 3.dp,
    shadowElevation = 8.dp,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
      PiratePrimaryButton(
        text = label,
        onClick = onClick,
        enabled = enabled,
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
      )
    }
  }
}

@Composable
internal fun SongInsufficientAnnotationsPanel(
) {
  Box(
    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Text(
        text = "Insufficient annotations. Annotate the lyrics on Genius to generate exercises.",
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
internal fun SongLeaderboardPanel(
  rows: List<SongListenerRow>,
  onOpenProfile: (String) -> Unit,
) {
  val leaderboardListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState(0, 0) }

  if (rows.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("No leaderboard entries yet.", color = PiratePalette.TextMuted)
    }
    return
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    state = leaderboardListState,
    contentPadding = PaddingValues(bottom = 20.dp),
  ) {
    item {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
    itemsIndexed(rows, key = { _, row -> row.userAddress }) { idx, row ->
      SongLeaderboardRow(
        rank = idx + 1,
        address = row.userAddress,
        count = row.scrobbleCount.toLong(),
        onOpenProfile = onOpenProfile,
      )
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
  }
}

@Composable
internal fun SongLeaderboardRow(
  rank: Int,
  address: String,
  count: Long,
  onOpenProfile: (String) -> Unit,
) {
  var resolvedName by remember(address) { mutableStateOf<String?>(null) }

  LaunchedEffect(address) {
    resolvedName = runCatching { resolvePublicProfileIdentityWithRetry(address, attempts = 1).first }.getOrNull()
      .orEmpty()
      .ifBlank { null }
  }

  val displayName = resolvedName ?: shortAddress(address)
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(72.dp)
        .background(
          if (pressed) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
          } else {
            Color.Transparent
          },
        )
        .clickable(
          interactionSource = interactionSource,
          indication = null,
        ) { onOpenProfile(address) }
        .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      "#$rank",
      style = MaterialTheme.typography.bodyLarge,
      color = PiratePalette.TextMuted,
      modifier = Modifier.width(32.dp),
    )
    Text(
      displayName,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.weight(1f),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      count.toString(),
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onBackground,
    )
  }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SongArtistPickerSheet(
  open: Boolean,
  effectiveArtist: String?,
  onDismiss: () -> Unit,
  onPickArtist: (String) -> Unit,
) {
  if (!open) return
  val artists = parseAllArtists(effectiveArtist.orEmpty())
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFF1C1C1C),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 24.dp)
          .padding(bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      PirateSheetTitle(
        text = "Go to Artist",
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 8.dp),
      )
      artists.forEach { artist ->
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .clickable { onPickArtist(artist) }
              .padding(vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text(
                artist.take(1).uppercase(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
              )
            }
          }
          Text(artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        }
      }
    }
  }
}

internal fun resolveLocalSongArtworkUri(
  tracks: List<MusicTrack>,
  resolvedTrackId: String,
  title: String,
  artist: String?,
): String? {
  if (tracks.isEmpty()) return null
  val normalizedResolvedTrackId = normalizeBytes32(resolvedTrackId) ?: resolvedTrackId.trim().lowercase()
  val targetTitleNorm = normalizeSongTitleLoose(title)
  val targetArtistNorm = normalizeArtistName(artist.orEmpty())

  for (track in tracks) {
    val artwork = track.artworkUri?.ifBlank { null } ?: track.artworkFallbackUri?.ifBlank { null } ?: continue

    val candidateTrackId = resolveSongTrackId(track)
    val normalizedCandidateTrackId =
      candidateTrackId?.let { normalizeBytes32(it) ?: it.trim().lowercase() }.orEmpty()
    if (normalizedResolvedTrackId.isNotBlank() &&
      normalizedCandidateTrackId.isNotBlank() &&
      normalizedCandidateTrackId == normalizedResolvedTrackId
    ) {
      return artwork
    }

    if (targetTitleNorm.isBlank()) continue
    val candidateTitleNorm = normalizeSongTitleLoose(track.title)
    if (candidateTitleNorm.isBlank()) continue
    val titleMatch =
      candidateTitleNorm == targetTitleNorm ||
        candidateTitleNorm.contains(targetTitleNorm) ||
        targetTitleNorm.contains(candidateTitleNorm)
    if (!titleMatch) continue

    if (targetArtistNorm.isBlank()) return artwork
    val candidateArtistNorm = normalizeArtistName(track.artist)
    val artistMatch =
      artistMatchesTarget(track.artist, targetArtistNorm) ||
        candidateArtistNorm.contains(targetArtistNorm) ||
        targetArtistNorm.contains(candidateArtistNorm)
    if (artistMatch) return artwork
  }

  return null
}

private fun normalizeSongTitleLoose(raw: String): String {
  return raw
    .trim()
    .lowercase(Locale.US)
    .replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
    .replace(Regex("\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b"), " ")
    .replace(Regex("[^a-z0-9]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
}
