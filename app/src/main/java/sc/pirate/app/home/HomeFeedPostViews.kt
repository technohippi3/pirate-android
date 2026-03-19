package sc.pirate.app.home

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.Heart
import com.adamglin.phosphoricons.fill.Lock
import com.adamglin.phosphoricons.fill.MusicNote
import com.adamglin.phosphoricons.fill.ShareNetwork
import com.adamglin.phosphoricons.fill.Tag
import com.adamglin.phosphoricons.regular.Play
import coil.compose.AsyncImage
import android.view.LayoutInflater
import sc.pirate.app.R
import sc.pirate.app.player.OverlayActionCircleButton
import sc.pirate.app.player.OverlayActionIcon
import sc.pirate.app.player.OverlayRailBottomPadding
import sc.pirate.app.player.OverlayRailEndPadding
import sc.pirate.app.player.OverlayTextBottomPadding
import sc.pirate.app.player.OverlayTextEndPadding
import sc.pirate.app.player.OverlayTextStartPadding
import sc.pirate.app.player.formatOverlayCount
import sc.pirate.app.util.shortAddress

@Composable
internal fun FeedPostCard(
  post: FeedPostResolved,
  captionTextOverride: String?,
  isCaptionTranslating: Boolean,
  likeState: LikeUiState,
  previewFrameMs: Long,
  isActive: Boolean,
  player: ExoPlayer?,
  playerSurfacePostId: String?,
  playerReadyPostId: String?,
  userPaused: Boolean,
  blockedByMusic: Boolean,
  videoError: String?,
  onTogglePlayPause: () -> Unit,
  onOpenProfile: () -> Unit,
  purchasePriceLabel: String,
  onOpenPurchase: () -> Unit,
  onToggleLike: () -> Unit,
  onShare: () -> Unit,
  taggedItemCount: Int = 0,
  onOpenTaggedItems: (() -> Unit)? = null,
  showTranslateCaption: Boolean,
  onTranslateCaption: () -> Unit,
  onOpenSong: () -> Unit,
) {
  val posterHandle = resolvePosterHandle(post)
  val songText = formatSongText(post)
  val captionText = (captionTextOverride?.trim().takeUnless { it.isNullOrBlank() } ?: post.captionText.trim()).ifBlank { null }
  val hasSongMetadata = songText != null
  val showTaggedItemsAction = taggedItemCount > 0 && onOpenTaggedItems != null
  val context = LocalContext.current
  val previewModel =
    remember(context, post.previewUrl, previewFrameMs) {
      post.previewUrl
    }
  val maskColor = Color(0xFF0A0A0E)
  val shouldMaskVideo = !isActive || playerReadyPostId != post.id || videoError != null
  val shouldAttachPlayer = isActive && videoError == null && playerSurfacePostId == post.id

  LaunchedEffect(isActive, post.id, shouldMaskVideo, playerReadyPostId, videoError, previewModel) {
    if (!isActive) return@LaunchedEffect
    Log.d(
      "HomeFeedDebug",
      "card active postId=${post.id} mask=$shouldMaskVideo previewFrameMs=$previewFrameMs readyPostId=$playerReadyPostId hasExplicitPreview=${!post.previewUrl.isNullOrBlank()} hasPreview=${previewModel != null} hasVideo=${!post.videoUrl.isNullOrBlank()} error=${videoError != null}",
    )
    if (!shouldMaskVideo && playerReadyPostId != post.id) {
      Log.w(
        "HomeFeedDebug",
        "card active unmaskedWithoutReady postId=${post.id} readyPostId=$playerReadyPostId hasPreview=${previewModel != null}",
      )
    }
  }

  LaunchedEffect(post.id, isActive, shouldAttachPlayer, playerSurfacePostId, videoError) {
    if (!isActive) return@LaunchedEffect
    Log.d(
      "HomeFeedDebug",
      "card bind postId=${post.id} attach=$shouldAttachPlayer surfacePostId=$playerSurfacePostId error=${videoError != null}",
    )
  }

  // Base layer: solid dark background so no transparent holes show raw black
  Box(modifier = Modifier.fillMaxSize().background(maskColor)) {
    // Keep the PlayerView composed for visible pages so swipe handoff doesn't recreate the
    // underlying Android view on every active-page transition.
    if (!post.videoUrl.isNullOrBlank() && player != null) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
          (LayoutInflater.from(context).inflate(R.layout.view_feed_player, null, false) as PlayerView).apply {
            // Defensive runtime setup to mirror XML defaults.
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
          }
        },
        update = { view ->
          view.player = if (shouldAttachPlayer) player else null
        },
      )
    }
    // Always mask video until this page is truly ready; use preview image when available,
    // otherwise an opaque cover to block stale-frame flashes.
    if (shouldMaskVideo) {
      if (previewModel != null) {
        AsyncImage(
          model = previewModel,
          placeholder = ColorPainter(maskColor),
          error = ColorPainter(maskColor),
          fallback = ColorPainter(maskColor),
          contentDescription = "Post preview image",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      } else {
        Box(modifier = Modifier.fillMaxSize().background(maskColor))
      }
    }

    if (isActive) {
      val showPlayOverlay = videoError != null || userPaused || blockedByMusic
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = onTogglePlayPause,
            ),
      )
      if (showPlayOverlay) {
        Box(
          modifier =
            Modifier
              .align(Alignment.Center)
              .size(220.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = PhosphorIcons.Regular.Play,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(56.dp),
          )
        }
      }
    }

    Column(
      modifier =
        Modifier
          .align(Alignment.BottomEnd)
          .padding(end = OverlayRailEndPadding, bottom = OverlayRailBottomPadding),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      OverlayActionCircleButton(
        contentDescription = "Profile",
        onClick = onOpenProfile,
      ) {
        if (!post.creatorAvatarUrl.isNullOrBlank()) {
          AsyncImage(
            model = post.creatorAvatarUrl,
            contentDescription = "Poster avatar",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
          )
        } else {
          Text(
            text = creatorInitial(posterHandle),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
      OverlayActionIcon(
        icon = PhosphorIcons.Fill.ShareNetwork,
        contentDescription = "Share",
        onClick = onShare,
      )
      if (showTaggedItemsAction) {
        OverlayActionIcon(
          icon = PhosphorIcons.Fill.Tag,
          contentDescription = "Tagged items",
          count = formatOverlayCount(taggedItemCount.toLong()),
          onClick = { onOpenTaggedItems?.invoke() },
        )
      }
      OverlayActionIcon(
        icon = PhosphorIcons.Fill.Heart,
        contentDescription = "Like",
        count = formatOverlayCount(likeState.likeCount),
        active = likeState.liked,
        onClick = onToggleLike,
      )
      OverlayActionIcon(
        icon = PhosphorIcons.Fill.Lock,
        contentDescription = "Purchase",
        count = purchasePriceLabel,
        onClick = onOpenPurchase,
      )
      if (hasSongMetadata) {
        OverlayActionCircleButton(
          contentDescription = "Song",
          onClick = onOpenSong,
        ) {
          if (!post.songCoverUrl.isNullOrBlank()) {
            AsyncImage(
              model = post.songCoverUrl,
              contentDescription = "Song cover art",
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
            )
          } else {
            Icon(
              imageVector = PhosphorIcons.Fill.MusicNote,
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(24.dp),
            )
          }
        }
      }
    }

    Column(
      modifier =
        Modifier
          .align(Alignment.BottomStart)
          .padding(
            start = OverlayTextStartPadding,
            end = OverlayTextEndPadding,
            bottom = OverlayTextBottomPadding,
          ),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (videoError != null) {
        Text(
          text = videoError,
          color = Color.White,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall.copy(
            fontSize = 14.sp,
            shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 1f), blurRadius = 4f),
          ),
        )
      }
      Text(
        text = "@$posterHandle",
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenProfile),
        style = MaterialTheme.typography.bodyMedium.copy(
          fontSize = 17.sp,
          fontWeight = FontWeight.Bold,
          shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 1f), blurRadius = 4f),
        ),
      )
      if (captionText != null) {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = captionText,
            color = Color.White.copy(alpha = 0.9f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium.copy(
              fontSize = 16.sp,
              shadow = Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 1f), blurRadius = 4f),
            ),
          )
          if (showTranslateCaption) {
            Text(
              text = if (isCaptionTranslating) "Translating..." else "Translate",
              color = Color.White.copy(alpha = if (isCaptionTranslating) 0.62f else 0.92f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier =
                Modifier
                  .clip(RoundedCornerShape(999.dp))
                  .background(Color.Black.copy(alpha = 0.28f))
                  .clickable(enabled = !isCaptionTranslating, onClick = onTranslateCaption)
                  .padding(horizontal = 10.dp, vertical = 5.dp),
              style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                shadow = Shadow(color = Color.Black.copy(alpha = 0.65f), offset = Offset(0f, 1f), blurRadius = 3f),
              ),
            )
          }
        }
      }
      if (songText != null) {
        Text(
          text = songText,
          color = Color.White.copy(alpha = 0.76f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSong),
          style = MaterialTheme.typography.bodySmall.copy(
            fontSize = 16.sp,
            shadow = Shadow(color = Color.Black.copy(alpha = 0.65f), offset = Offset(0f, 1f), blurRadius = 3f),
          ),
        )
      }
    }
  }
}

private fun formatSongText(post: FeedPostResolved): String? {
  return when {
    !post.songTitle.isNullOrBlank() && !post.songArtist.isNullOrBlank() ->
      "${post.songTitle.orEmpty()} - ${post.songArtist.orEmpty()}"
    !post.songTitle.isNullOrBlank() -> post.songTitle.orEmpty()
    !post.songArtist.isNullOrBlank() -> post.songArtist.orEmpty()
    else -> null
  }
}

private fun resolvePosterHandle(post: FeedPostResolved): String {
  val fromHandle = post.creatorHandle?.trim()?.removePrefix("@").orEmpty()
  if (fromHandle.isNotBlank()) return fromHandle
  val fromDisplay = post.creatorDisplayName?.trim()?.removePrefix("@").orEmpty()
  if (fromDisplay.isNotBlank()) return fromDisplay
  val creator = post.creator.trim()
  if (creator.isBlank()) return "unknown.pirate"
  return shortAddress(creator, minLengthToShorten = 14)
}

private fun creatorInitial(creator: String): String {
  val normalized = creator.trim().removePrefix("@").removePrefix("0x")
  return normalized.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
