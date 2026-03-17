package com.pirate.app.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun LiveRoomsSection(
  rooms: List<LiveRoomCardModel>,
  roomsLoading: Boolean,
  roomsError: String?,
  onOpenRoom: (LiveRoomCardModel) -> Unit,
) {
  val state =
    when {
      roomsLoading && rooms.isEmpty() -> "loading"
      !roomsError.isNullOrBlank() && rooms.isEmpty() -> "error"
      rooms.isEmpty() -> "empty"
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
          roomsError.orEmpty(),
          modifier = Modifier.padding(horizontal = 20.dp),
          color = MaterialTheme.colorScheme.error,
        )
      }

      "empty" -> {
        Text(
          "No live rooms or tickets right now",
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
            items = rooms,
            key = { item -> item.roomId },
          ) { item ->
            AlbumCard(
              title = item.title,
              artist = item.subtitle.orEmpty(),
              imageUri = resolveReleaseCoverUrl(item.coverRef),
              imageFallbackUri = item.coverRef,
              onClick = { onOpenRoom(item) },
            )
          }
        }
      }
    }
  }
  Spacer(modifier = Modifier.height(28.dp))
}
