package sc.pirate.app.music

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import sc.pirate.app.theme.PiratePalette

@Composable
internal fun NewReleasesSection(
  newReleases: List<AlbumCardModel>,
  newReleasesLoading: Boolean,
  newReleasesError: String?,
  onPlayRelease: (AlbumCardModel) -> Unit,
) {
  val state =
    when {
      newReleasesLoading && newReleases.isEmpty() -> "loading"
      !newReleasesError.isNullOrBlank() && newReleases.isEmpty() -> "error"
      newReleases.isEmpty() -> "empty"
      else -> "content"
    }

  AnimatedContent(
    targetState = state,
    transitionSpec = {
      fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(180))
    },
  ) { currentState ->
    when (currentState) {
      "loading" -> NewReleasesSkeleton()

      "error" -> {
        Text(
          newReleasesError.orEmpty(),
          modifier = Modifier.padding(horizontal = 20.dp),
          color = MaterialTheme.colorScheme.error,
        )
      }

      "empty" -> {
        Text(
          "No new releases yet",
          modifier = Modifier.padding(horizontal = 20.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      else -> {
        LazyRow(
          contentPadding = PaddingValues(horizontal = 20.dp),
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(
            items = newReleases,
            key = { item -> "${item.trackId.orEmpty()}|${item.title}|${item.artist}" },
          ) { item ->
            AlbumCard(
              title = item.title,
              artist = item.artist,
              imageUri = resolveReleaseCoverUrl(item.coverRef),
              imageFallbackUri = item.coverRef,
              onClick = if (!item.trackId.isNullOrBlank()) ({ onPlayRelease(item) }) else null,
            )
          }
        }
      }
    }
  }
}

@Composable
internal fun NewReleasesSkeleton() {
  val skeletonTrack = MaterialTheme.colorScheme.surfaceVariant
  val shimmer = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
  LazyRow(
    contentPadding = PaddingValues(horizontal = 20.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    items(3) {
      Column(modifier = Modifier.width(140.dp)) {
        Surface(
          modifier = Modifier.size(140.dp),
          color = shimmer,
          shape = MaterialTheme.shapes.large,
        ) {}
        Spacer(modifier = Modifier.height(10.dp))
        Box(
          modifier =
            Modifier
              .height(16.dp)
              .fillMaxWidth()
              .clip(MaterialTheme.shapes.small)
              .background(skeletonTrack.copy(alpha = 0.8f)),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
          modifier =
            Modifier
              .height(14.dp)
              .width(80.dp)
              .clip(MaterialTheme.shapes.small)
              .background(skeletonTrack.copy(alpha = 0.55f)),
        )
      }
    }
  }
}

@Composable
internal fun AlbumCard(
  title: String,
  artist: String,
  imageUri: String? = null,
  imageFallbackUri: String? = null,
  onClick: (() -> Unit)? = null,
) {
  var displayImageUri by remember(imageUri, imageFallbackUri) { mutableStateOf(imageUri) }

  fun handleImageError() {
    val fallback = imageFallbackUri?.trim().orEmpty()
    if (fallback.isNotBlank() && fallback != displayImageUri) {
      displayImageUri = fallback
      return
    }
    displayImageUri = null
  }

  Column(
    modifier =
      Modifier
        .width(140.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
  ) {
    Surface(
      modifier = Modifier.size(140.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      shape = MaterialTheme.shapes.large,
    ) {
      Box(contentAlignment = Alignment.Center) {
        if (!displayImageUri.isNullOrBlank()) {
          AsyncImage(
            model = displayImageUri,
            contentDescription = "Cover art",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onError = { handleImageError() },
          )
        } else {
          Icon(PhosphorIcons.Regular.MusicNote, contentDescription = null, tint = PiratePalette.TextMuted, modifier = Modifier.size(24.dp))
        }
      }
    }
    Spacer(modifier = Modifier.height(10.dp))
    Text(
      title,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = MaterialTheme.colorScheme.onBackground,
      fontWeight = FontWeight.Medium,
    )
    Text(
      artist,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      color = PiratePalette.TextMuted,
    )
  }
}
