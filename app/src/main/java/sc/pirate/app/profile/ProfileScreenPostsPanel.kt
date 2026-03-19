package sc.pirate.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Play
import sc.pirate.app.home.FeedPostResolved
import sc.pirate.app.theme.PiratePalette

private fun profilePostPosterUrl(post: FeedPostResolved): String? {
  return post.previewUrl?.takeIf { it.isNotBlank() }
    ?: post.songCoverUrl?.takeIf { it.isNotBlank() }
    ?: post.creatorAvatarUrl?.takeIf { it.isNotBlank() }
}

@Composable
internal fun PostsPanel(
  posts: List<FeedPostResolved>,
  loading: Boolean,
  error: String?,
  onOpenPost: ((FeedPostResolved) -> Unit)? = null,
) {
  val gridState = rememberLazyGridState()

  when {
    loading -> CenteredStatus {
      CircularProgressIndicator()
      Text("Loading posts...", modifier = Modifier.padding(top = 12.dp), color = PiratePalette.TextMuted)
    }
    posts.isEmpty() && !error.isNullOrBlank() -> CenteredStatus {
      Text(error, color = MaterialTheme.colorScheme.error)
    }
    posts.isEmpty() -> CenteredStatus {
      Text("No posts yet.", color = PiratePalette.TextMuted)
    }
    else -> LazyVerticalGrid(
      columns = GridCells.Fixed(3),
      state = gridState,
      modifier = Modifier.fillMaxSize(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      items(posts, key = { it.id }) { post ->
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onOpenPost != null) Modifier.clickable { onOpenPost(post) } else Modifier),
        ) {
          val posterUrl = profilePostPosterUrl(post)
          if (!posterUrl.isNullOrBlank()) {
            AsyncImage(
              model = posterUrl,
              contentDescription = null,
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
            )
          }
          if (!post.videoUrl.isNullOrBlank()) {
            Box(
              modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                .padding(5.dp),
            ) {
              Icon(
                imageVector = PhosphorIcons.Regular.Play,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
              )
            }
          }
        }
      }
    }
  }
}
