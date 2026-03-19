package sc.pirate.app.player

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Fill
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.fill.*
import com.adamglin.phosphoricons.regular.*
import android.graphics.Color as AndroidColor
import android.media.MediaMetadataRetriever
import android.util.Log

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import sc.pirate.app.music.SongPurchaseApi
import sc.pirate.app.music.TrackMenuPolicyResolver
import sc.pirate.app.music.UploadedTrackActions
import sc.pirate.app.music.fetchPurchasedCloudLibraryTracks
import sc.pirate.app.music.resolvePlayableTrackForUi
import sc.pirate.app.music.resolveSongTrackId
import sc.pirate.app.music.ui.SongPurchaseSheet
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.ui.PirateIconButton
import sc.pirate.app.ui.PiratePrimaryButton
import sc.pirate.app.ui.PirateTextButton
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.input.pointer.pointerInput
import sc.pirate.app.R
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG_PLAYER_PURCHASE = "PlayerPurchaseDebug"
private const val TAG_PLAYER_LYRICS = "PlayerLyricsDebug"
private const val TAG_PLAYER_CANVAS = "PlayerCanvasDebug"
private val CanvasLyricViewportGap = 4.dp

private data class PlayerCanvasVideo(
  val url: String,
  val width: Int? = null,
  val height: Int? = null,
)

private enum class PlayerTopBarAction {
  NONE,
  BUY,
  MENU,
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
  player: PlayerController,
  ownerEthAddress: String?,
  isAuthenticated: Boolean,
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
  onOpenSongPage: ((trackId: String, title: String?, artist: String?) -> Unit)? = null,
  onOpenArtistPage: ((String) -> Unit)? = null,
  hostActivity: androidx.fragment.app.FragmentActivity? = null,
  tempoAccount: sc.pirate.app.tempo.TempoPasskeyManager.PasskeyAccount? = null,
) {
  val context = LocalContext.current
  val appContext = remember(context) { context.applicationContext }
  val scope = rememberCoroutineScope()
  val currentTrack by player.currentTrack.collectAsState()
  val isPlaying by player.isPlaying.collectAsState()
  val progress by player.progress.collectAsState()
  val shuffleEnabled by player.shuffleEnabled.collectAsState()
  val repeatMode by player.repeatMode.collectAsState()

  val track = currentTrack
  if (track == null) {
    LaunchedEffect(Unit) { onClose() }
    return
  }

  val messageUnknownError = stringResource(R.string.player_message_unknown_error)
  val messageSongUnavailable = stringResource(R.string.player_message_song_unavailable)
  val messageTrackNotPlayable = stringResource(R.string.player_message_track_not_playable)
  val messageSongUnavailableForPurchase = stringResource(R.string.player_message_song_unavailable_for_purchase)
  val messageSignInToBuy = stringResource(R.string.player_message_sign_in_to_buy)
  val messagePurchaseMissingEntitlement = stringResource(R.string.player_message_purchase_missing_entitlement)
  val messagePurchaseComplete = stringResource(R.string.player_message_purchase_complete)
  val messageShareUploadedOnly = stringResource(R.string.player_message_share_uploaded_only)
  val messageSignInToShare = stringResource(R.string.player_message_sign_in_to_share)
  val messageMissingTrackToShare = stringResource(R.string.player_message_missing_track_to_share)
  val messageMissingShareCredentials = stringResource(R.string.player_message_missing_share_credentials)
  val messageShareSuccess = stringResource(R.string.player_message_share_success)

  var menuOpen by remember { mutableStateOf(false) }
  var artworkUri by remember(track.id) { mutableStateOf(track.artworkUri) }
  var artworkFailed by remember(track.id) { mutableStateOf(false) }
  var lyricsDoc by remember(track.id, track.uri, track.canonicalTrackId) { mutableStateOf<PlayerLyricsDoc?>(null) }
  var purchaseSheetOpen by remember(track.id) { mutableStateOf(false) }
  var purchaseSubmitting by remember(track.id) { mutableStateOf(false) }
  var unlockBusy by remember(track.id) { mutableStateOf(false) }
  var purchaseQuoteLoading by remember(track.id) { mutableStateOf(false) }
  var purchasePriceLabel by remember(track.id) { mutableStateOf<String?>(null) }
  var ownedPurchaseId by remember(track.id) { mutableStateOf(track.purchaseId?.trim()?.ifBlank { null }) }
  var canvasVideo by remember(track.id, track.canonicalTrackId, track.contentId) { mutableStateOf<PlayerCanvasVideo?>(null) }
  var canvasVideoFailed by remember(track.id, track.canonicalTrackId, track.contentId) { mutableStateOf(false) }
  var canvasVideoReady by remember(track.id, track.canonicalTrackId, track.contentId) { mutableStateOf(false) }
  var canvasLookupComplete by remember(track.id, track.canonicalTrackId, track.contentId) { mutableStateOf(false) }
  var resumePlaybackAfterCanvasHold by remember(track.id, track.canonicalTrackId, track.contentId) { mutableStateOf(false) }
  var isFavorited by remember(track.id) { mutableStateOf(false) }
  var shareOpen by remember { mutableStateOf(false) }
  var shareRecipientInput by remember { mutableStateOf("") }
  var shareBusy by remember { mutableStateOf(false) }
  var shareTrack by remember { mutableStateOf<sc.pirate.app.music.MusicTrack?>(null) }

  val canonicalTrackId =
    remember(track.id, track.canonicalTrackId, track.contentId) {
      resolveSongTrackId(track)?.trim()?.lowercase()?.ifBlank { null }
    }
  val shouldAwaitCanvasLookup =
    remember(track.uri) {
      val uri = track.uri.trim().lowercase(Locale.ROOT)
      !(uri.startsWith("content://") || uri.startsWith("file://"))
    }

  fun handleArtworkError() {
    if (
      artworkUri == track.artworkUri &&
      !track.artworkFallbackUri.isNullOrBlank() &&
      track.artworkFallbackUri != artworkUri
    ) {
      artworkUri = track.artworkFallbackUri
      return
    }
    artworkFailed = true
  }

  val screenWidth = LocalConfiguration.current.screenWidthDp.dp
  val coverSize = minOf(screenWidth - 80.dp, 400.dp).coerceAtLeast(220.dp)

  LaunchedEffect(track.id, track.uri, track.canonicalTrackId) {
    val lyricsResult = runCatching { PlayerLyricsRepository.loadLyrics(context, track) }
    lyricsDoc = lyricsResult.getOrNull()
    lyricsResult.exceptionOrNull()?.let { error ->
      Log.w(
        TAG_PLAYER_LYRICS,
        "load failed trackId=${canonicalTrackId.orEmpty()} title=${track.title}",
        error,
      )
    }
    Log.d(
      TAG_PLAYER_LYRICS,
      "load trackId=${canonicalTrackId.orEmpty()} title=${track.title} lines=${lyricsDoc?.lines?.size ?: 0} timed=${lyricsDoc?.timed == true}",
    )
  }

  LaunchedEffect(track.id, track.canonicalTrackId, track.contentId, shouldAwaitCanvasLookup) {
    canvasVideoFailed = false
    canvasVideoReady = false
    canvasVideo = null
    if (shouldAwaitCanvasLookup) {
      canvasLookupComplete = false
    } else {
      canvasLookupComplete = true
    }
    val canvasUrl = runCatching { PlayerPresentationRepository.resolveCanvasVideoUrl(track) }.getOrNull()
    if (canvasUrl.isNullOrBlank()) {
      Log.d(
        TAG_PLAYER_CANVAS,
        "resolve trackId=${canonicalTrackId.orEmpty()} title=${track.title} canvas=absent",
      )
    } else {
      canvasVideo = PlayerCanvasVideo(url = canvasUrl)
      Log.d(
        TAG_PLAYER_CANVAS,
        "resolve trackId=${canonicalTrackId.orEmpty()} title=${track.title} canvasUrl=$canvasUrl",
      )
    }
    canvasLookupComplete = true
  }

  LaunchedEffect(track.id) {
    purchaseSheetOpen = false
    purchaseSubmitting = false
    unlockBusy = false
    purchaseQuoteLoading = false
    purchasePriceLabel = null
    ownedPurchaseId = track.purchaseId?.trim()?.ifBlank { null }
  }

  LaunchedEffect(track.id, track.isPreviewOnly, canonicalTrackId, ownerEthAddress, isAuthenticated) {
    if (!track.isPreviewOnly) {
      purchaseQuoteLoading = false
      purchasePriceLabel = null
      Log.d(
        TAG_PLAYER_PURCHASE,
        "skip entitlement check: trackId=${track.id} preview=false purchaseId=${track.purchaseId.orEmpty()}",
      )
      return@LaunchedEffect
    }
    val trackId = canonicalTrackId
    val ownerAddress = ownerEthAddress?.trim().orEmpty()
    Log.d(
      TAG_PLAYER_PURCHASE,
      "entitlement check start: trackId=${track.id} canonical=$trackId owner=${ownerAddress.ifBlank { "<none>" }} isAuthenticated=$isAuthenticated preview=${track.isPreviewOnly} incomingPurchaseId=${track.purchaseId.orEmpty()}",
    )
    if (trackId.isNullOrBlank() || ownerAddress.isBlank()) {
      purchaseQuoteLoading = false
      purchasePriceLabel = null
      Log.w(
        TAG_PLAYER_PURCHASE,
        "entitlement check skipped: canonicalTrackId=$trackId ownerBlank=${ownerAddress.isBlank()}",
      )
      return@LaunchedEffect
    }

    purchaseQuoteLoading = true
    val purchasedRowsResult = runCatching { fetchPurchasedCloudLibraryTracks(ownerEthAddress = ownerAddress) }
    if (purchasedRowsResult.isFailure) {
      purchaseQuoteLoading = false
      purchasePriceLabel = null
      Log.w(
        TAG_PLAYER_PURCHASE,
        "entitlement fetch failed trackId=$trackId owner=$ownerAddress error=${purchasedRowsResult.exceptionOrNull()?.message}",
      )
      return@LaunchedEffect
    }
    val purchasedRows = purchasedRowsResult.getOrThrow()
    Log.d(
      TAG_PLAYER_PURCHASE,
      "entitlement fetch ok trackId=$trackId owner=$ownerAddress rows=${purchasedRows.size}",
    )
    val purchasedId =
      purchasedRows.firstOrNull { row ->
        val candidate = resolveSongTrackId(row)?.trim()?.lowercase().orEmpty()
        candidate == trackId
      }?.purchaseId?.trim()?.ifBlank { null }
    if (!purchasedId.isNullOrBlank()) {
      ownedPurchaseId = purchasedId
      Log.i(
        TAG_PLAYER_PURCHASE,
        "entitlement match trackId=$trackId purchaseId=$purchasedId",
      )
      if (track.purchaseId.isNullOrBlank()) {
        player.updateTrack(track.copy(purchaseId = purchasedId))
      }
      purchaseQuoteLoading = false
      purchasePriceLabel = null
      return@LaunchedEffect
    }
    Log.w(
      TAG_PLAYER_PURCHASE,
      "entitlement missing for trackId=$trackId; fetching quote preview",
    )

    val quote = SongPurchaseApi.fetchQuotePreview(ownerAddress = ownerAddress, songTrackId = trackId)
    purchasePriceLabel = if (quote.success) formatUsdFromMicros(quote.amountRaw) else null
    purchaseQuoteLoading = false
    Log.d(
      TAG_PLAYER_PURCHASE,
      "quote preview result trackId=$trackId success=${quote.success} error=${quote.errorCode.orEmpty()}",
    )
  }

  fun unlockOwnedTrack(purchaseId: String) {
    if (unlockBusy) return
    val trackId = canonicalTrackId
    if (trackId.isNullOrBlank()) {
      onShowMessage(messageSongUnavailable)
      return
    }
    unlockBusy = true
    scope.launch {
      val resolved =
        resolvePlayableTrackForUi(
          track = track.copy(uri = "", purchaseId = purchaseId, isPreviewOnly = false),
          context = context,
          ownerEthAddress = ownerEthAddress,
          purchaseIdsByTrackId = mapOf(trackId to purchaseId),
          activity = hostActivity,
          tempoAccount = tempoAccount,
        )
      unlockBusy = false
      val playable = resolved.track
      if (playable == null) {
        onShowMessage(resolved.message ?: messageTrackNotPlayable)
        return@launch
      }
      player.playTrack(playable, listOf(playable))
    }
  }

  fun buySongFromPlayer() {
    if (purchaseSubmitting) return
    val trackId = canonicalTrackId
    if (trackId.isNullOrBlank()) {
      onShowMessage(messageSongUnavailableForPurchase)
      return
    }
    if (!isAuthenticated || ownerEthAddress.isNullOrBlank() || tempoAccount == null || hostActivity == null) {
      onShowMessage(messageSignInToBuy)
      return
    }

    purchaseSubmitting = true
    scope.launch {
      val owner = ownerEthAddress.trim()
      val loadedSession =
        SessionKeyManager.load(hostActivity)?.takeIf {
          SessionKeyManager.isValid(it, ownerAddress = tempoAccount.address)
        }
      val result =
        SongPurchaseApi.buySong(
          activity = hostActivity,
          appContext = appContext,
          account = tempoAccount,
          ownerAddress = owner,
          songTrackId = trackId,
          sessionKey = loadedSession,
        )
      purchaseSubmitting = false
      if (!result.success) {
        onShowMessage(context.getString(R.string.player_message_purchase_failed, result.error ?: messageUnknownError))
        return@launch
      }
      val purchaseId = result.purchaseId?.trim().orEmpty()
      if (purchaseId.isBlank()) {
        onShowMessage(messagePurchaseMissingEntitlement)
        return@launch
      }

      purchaseSheetOpen = false
      ownedPurchaseId = purchaseId
      player.updateTrack(track.copy(purchaseId = purchaseId))
      val paidLabel = formatUsdFromMicros(result.paidAmountRaw)
      onShowMessage(
        if (paidLabel != null) {
          context.getString(R.string.player_message_purchase_complete_amount, paidLabel)
        } else {
          messagePurchaseComplete
        },
      )
      unlockOwnedTrack(purchaseId)
    }
  }

  val hasOwnedEntitlement = !ownedPurchaseId.isNullOrBlank()
  val canvasVideoUrl = canvasVideo?.url
  val canShareTrack = TrackMenuPolicyResolver.resolve(track, ownerEthAddress).canShare
  val useCanvasPlayerLayout = shouldUseCanvasPlayerLayout(canvasVideoUrl)
  val hasCanvasBackdrop = shouldRenderCanvasBackdrop(canvasVideoUrl, canvasVideoFailed)
  val showCanvasLoadingState = shouldAwaitCanvasLookup && !canvasLookupComplete
  val holdPlaybackUntilCanvasReady =
    shouldHoldPlaybackUntilCanvasReady(
      showCanvasLoadingState = showCanvasLoadingState,
      useCanvasPlayerLayout = useCanvasPlayerLayout,
      hasCanvasBackdrop = hasCanvasBackdrop,
      canvasVideoReady = canvasVideoReady,
    )
  val shouldRunCanvasVideo =
    shouldRunCanvasVideo(
      hasCanvasBackdrop = hasCanvasBackdrop,
      isPlaying = isPlaying,
      holdPlaybackUntilCanvasReady = holdPlaybackUntilCanvasReady,
    )
  val defaultTopBarAction = resolvePlayerTopBarAction(track.isPreviewOnly, hasOwnedEntitlement, immersive = false)
  val immersiveTopBarAction = PlayerTopBarAction.NONE

  fun openShareDialog() {
    if (!canShareTrack) {
      onShowMessage(messageShareUploadedOnly)
      return
    }
    if (!isAuthenticated || ownerEthAddress.isNullOrBlank()) {
      onShowMessage(messageSignInToShare)
      return
    }
    shareTrack = track
    shareRecipientInput = ""
    shareOpen = true
  }

  LaunchedEffect(track.id, track.isPreviewOnly, ownedPurchaseId, purchaseQuoteLoading) {
    Log.d(
      TAG_PLAYER_PURCHASE,
      "ui state trackId=${track.id} preview=${track.isPreviewOnly} ownedPurchaseId=${ownedPurchaseId.orEmpty()} quoteLoading=$purchaseQuoteLoading showBuy=${track.isPreviewOnly && !hasOwnedEntitlement}",
    )
  }

  LaunchedEffect(track.id, holdPlaybackUntilCanvasReady, isPlaying) {
    if (holdPlaybackUntilCanvasReady) {
      if (isPlaying) {
        resumePlaybackAfterCanvasHold = true
        player.pause()
      }
      return@LaunchedEffect
    }

    if (resumePlaybackAfterCanvasHold) {
      resumePlaybackAfterCanvasHold = false
      if (!isPlaying) {
        player.togglePlayPause()
      }
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    if (useCanvasPlayerLayout && hasCanvasBackdrop) {
      PlayerCanvasVideoBackdrop(
        canvasUrl = canvasVideoUrl.orEmpty(),
        shouldPlayVideo = shouldRunCanvasVideo,
        modifier = Modifier.fillMaxSize(),
        onVideoSizeChanged = { width, height ->
          val currentCanvas = canvasVideo ?: return@PlayerCanvasVideoBackdrop
          if (currentCanvas.width == width && currentCanvas.height == height) return@PlayerCanvasVideoBackdrop
          val updatedCanvas = currentCanvas.copy(width = width, height = height)
          canvasVideo = updatedCanvas
          Log.d(
            TAG_PLAYER_CANVAS,
            "video-size trackId=${canonicalTrackId.orEmpty()} title=${track.title} width=$width height=$height",
          )
        },
        onVideoReady = { canvasVideoReady = true },
        onPlaybackError = { canvasVideoFailed = true },
      )
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              0f to Color.Transparent,
              0.62f to Color.Transparent,
              1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f),
            ),
          ),
      )
    }

    if (showCanvasLoadingState) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black),
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                0f to Color.Transparent,
                1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
              ),
            ),
        )
        CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
          color = Color.White,
          strokeWidth = 3.dp,
        )
        Box(
          modifier =
            Modifier
              .align(Alignment.TopCenter)
              .fillMaxWidth()
              .statusBarsPadding()
              .padding(start = 24.dp, top = 8.dp, end = 24.dp),
        ) {
          PlayerTopBar(
            track = track,
            canonicalTrackId = canonicalTrackId,
            hasOwnedEntitlement = hasOwnedEntitlement,
            purchaseSubmitting = purchaseSubmitting,
            unlockBusy = unlockBusy,
            action = immersiveTopBarAction,
            onClose = onClose,
            onOpenMenu = { menuOpen = true },
            onOpenPurchase = { purchaseSheetOpen = true },
          )
        }
      }
    } else if (useCanvasPlayerLayout) {
      CanvasPlayerLayout(
        track = track,
        isPlaying = isPlaying,
        progress = progress,
        lyricsDoc = lyricsDoc,
        canvasVideoReady = canvasVideoReady,
        artworkUri = artworkUri,
        artworkFailed = artworkFailed,
        isFavorited = isFavorited,
        canShare = canShareTrack,
        hasOwnedEntitlement = hasOwnedEntitlement,
        canonicalTrackId = canonicalTrackId,
        purchaseSubmitting = purchaseSubmitting,
        purchasePriceLabel = purchasePriceLabel,
        unlockBusy = unlockBusy,
        topBarAction = immersiveTopBarAction,
        holdPlaybackUntilCanvasReady = holdPlaybackUntilCanvasReady,
        onClose = onClose,
        onOpenMenu = { menuOpen = true },
        onOpenPurchase = { purchaseSheetOpen = true },
        onToggleFavorite = { isFavorited = !isFavorited },
        onShare = { openShareDialog() },
        onOpenSong =
          canonicalTrackId?.let { trackId ->
            onOpenSongPage?.let { openSongPage ->
              { openSongPage(trackId, track.title, track.artist) }
            }
          },
        onArtworkError = { handleArtworkError() },
        onPlayPauseOrUnlock = {
          if (holdPlaybackUntilCanvasReady) return@CanvasPlayerLayout
          if (track.isPreviewOnly && hasOwnedEntitlement && !unlockBusy && !isPlaying) {
            unlockOwnedTrack(ownedPurchaseId.orEmpty())
          } else {
            player.togglePlayPause()
          }
        },
      )
    } else {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .statusBarsPadding()
          .navigationBarsPadding()
          .padding(horizontal = 24.dp),
      ) {
        PlayerTopBar(
          track = track,
          canonicalTrackId = canonicalTrackId,
          hasOwnedEntitlement = hasOwnedEntitlement,
          purchaseSubmitting = purchaseSubmitting,
          unlockBusy = unlockBusy,
          action = defaultTopBarAction,
          onClose = onClose,
          onOpenMenu = { menuOpen = true },
          onOpenPurchase = { purchaseSheetOpen = true },
        )

        Box(
          modifier = Modifier.fillMaxWidth().weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier = Modifier.size(coverSize).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
          ) {
            if (!artworkUri.isNullOrBlank() && !artworkFailed) {
              AsyncImage(
                model = artworkUri,
                contentDescription = stringResource(R.string.player_album_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { handleArtworkError() },
              )
            } else {
              Icon(
                PhosphorIcons.Regular.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(80.dp),
              )
            }
          }
        }

        Column(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
          horizontalAlignment = Alignment.Start,
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          val doc = lyricsDoc
          if (doc != null && doc.lines.isNotEmpty()) {
            PlayerLyricsViewport(
              lyrics = doc,
              positionSec = progress.positionSec,
              durationSec = progress.durationSec,
              modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
          }
          Text(
            track.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start,
            maxLines = 2,
          )
          Text(
            track.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            maxLines = 1,
          )
        }

        PlayerScrubber(
          positionSec = progress.positionSec,
          durationSec = progress.durationSec,
          onSeek = { player.seekTo(it) },
        )

        PlayerTransportRow(
          isPlaying = isPlaying,
          shuffleEnabled = shuffleEnabled,
          repeatMode = repeatMode,
          onPlayPauseOrUnlock = {
            if (track.isPreviewOnly && hasOwnedEntitlement && !unlockBusy && !isPlaying) {
              unlockOwnedTrack(ownedPurchaseId.orEmpty())
            } else {
              player.togglePlayPause()
            }
          },
          onSkipPrevious = { player.skipPrevious() },
          onSkipNext = { player.skipNext() },
          onToggleShuffle = { player.toggleShuffle() },
          onCycleRepeat = { player.cycleRepeatMode() },
        )

        Spacer(modifier = Modifier.height(32.dp))
      }
    }
  }

  if (shareOpen) {
    AlertDialog(
      onDismissRequest = {
        if (!shareBusy) {
          shareOpen = false
          shareTrack = null
          shareRecipientInput = ""
        }
      },
      title = { androidx.compose.material3.Text(stringResource(R.string.player_share_track_title)) },
      text = {
        OutlinedTextField(
          value = shareRecipientInput,
          onValueChange = { if (!shareBusy) shareRecipientInput = it },
          singleLine = true,
          label = { androidx.compose.material3.Text(stringResource(R.string.player_share_recipient_label)) },
          placeholder = { androidx.compose.material3.Text(stringResource(R.string.player_share_recipient_placeholder)) },
          enabled = !shareBusy,
          modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        PirateTextButton(
          enabled = !shareBusy && shareRecipientInput.trim().isNotEmpty(),
          onClick = {
            val targetTrack = shareTrack
            if (targetTrack == null) {
              onShowMessage(messageMissingTrackToShare)
              return@PirateTextButton
            }
            val owner = ownerEthAddress
            if (owner.isNullOrBlank()) {
              onShowMessage(messageMissingShareCredentials)
              return@PirateTextButton
            }
            shareBusy = true
            scope.launch {
              val result =
                UploadedTrackActions.shareUploadedTrack(
                  context = context,
                  track = targetTrack,
                  recipient = shareRecipientInput,
                  ownerAddress = owner,
                )
              shareBusy = false
              if (!result.success) {
                onShowMessage(context.getString(R.string.player_message_share_failed, result.error ?: messageUnknownError))
                return@launch
              }
              shareOpen = false
              shareTrack = null
              shareRecipientInput = ""
              onShowMessage(messageShareSuccess)
            }
          },
        ) {
          androidx.compose.material3.Text(
            if (shareBusy) stringResource(R.string.player_share_action_sharing) else stringResource(R.string.player_share_action_share),
          )
        }
      },
      dismissButton = {
        PirateTextButton(
          enabled = !shareBusy,
          onClick = {
            shareOpen = false
            shareTrack = null
            shareRecipientInput = ""
          },
        ) {
          androidx.compose.material3.Text(stringResource(R.string.player_action_cancel))
        }
      },
    )
  }

  PlayerTrackMenuOverlay(
    track = track,
    player = player,
    menuOpen = menuOpen,
    onMenuOpenChange = { menuOpen = it },
    ownerEthAddress = ownerEthAddress,
    isAuthenticated = isAuthenticated,
    onShowMessage = onShowMessage,
    onOpenShare = { openShareDialog() },
    onOpenSongPage = onOpenSongPage,
    onOpenArtistPage = onOpenArtistPage,
    hostActivity = hostActivity,
    tempoAccount = tempoAccount,
  )

  SongPurchaseSheet(
    open = purchaseSheetOpen && track.isPreviewOnly,
    title = track.title,
    artist = track.artist,
    priceLabel = purchasePriceLabel,
    busy = purchaseSubmitting || unlockBusy,
    onDismiss = { purchaseSheetOpen = false },
    onConfirm = {
      if (hasOwnedEntitlement) {
        unlockOwnedTrack(ownedPurchaseId.orEmpty())
      } else {
        buySongFromPlayer()
      }
    },
    confirmLabel =
      if (hasOwnedEntitlement) {
        if (unlockBusy) stringResource(R.string.player_action_loading) else stringResource(R.string.player_action_play_full)
      } else {
        if (purchaseSubmitting) stringResource(R.string.player_action_buying) else stringResource(R.string.player_action_buy)
      },
  )
}

@Composable
private fun PlayerCanvasVideoBackdrop(
  canvasUrl: String,
  shouldPlayVideo: Boolean,
  modifier: Modifier = Modifier,
  onVideoSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
  onVideoReady: () -> Unit = {},
  onPlaybackError: () -> Unit = {},
) {
  val context = LocalContext.current
  val exoPlayer = remember(canvasUrl) {
    ExoPlayer.Builder(context).build().apply {
      setAudioAttributes(AudioAttributes.DEFAULT, false)
      volume = 0f
      repeatMode = Player.REPEAT_MODE_ONE
      setMediaItem(MediaItem.fromUri(canvasUrl))
      prepare()
    }
  }

  LaunchedEffect(exoPlayer, shouldPlayVideo) {
    exoPlayer.playWhenReady = shouldPlayVideo
    if (shouldPlayVideo) exoPlayer.play() else exoPlayer.pause()
  }

  DisposableEffect(exoPlayer, onPlaybackError, onVideoReady) {
    val listener =
      object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
          onPlaybackError()
        }

        override fun onRenderedFirstFrame() {
          onVideoReady()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
          val width = videoSize.width
          val height = videoSize.height
          if (width > 0 && height > 0) {
            onVideoSizeChanged(width, height)
          }
        }
      }
    exoPlayer.addListener(listener)
    onDispose {
      exoPlayer.removeListener(listener)
      exoPlayer.release()
    }
  }

  AndroidView(
    modifier = modifier,
    factory = { viewContext ->
      PlayerView(viewContext).apply {
        player = exoPlayer
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        setShutterBackgroundColor(AndroidColor.TRANSPARENT)
        setKeepContentOnPlayerReset(true)
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
      }
    },
    update = { view -> view.player = exoPlayer },
  )
}

@Composable
private fun PlayerTransportRow(
  isPlaying: Boolean,
  shuffleEnabled: Boolean,
  repeatMode: PlayerRepeatMode,
  onPlayPauseOrUnlock: () -> Unit,
  onSkipPrevious: () -> Unit,
  onSkipNext: () -> Unit,
  onToggleShuffle: () -> Unit,
  onCycleRepeat: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SoftIconButton(onClick = onToggleShuffle, size = 48.dp, selected = shuffleEnabled) {
      Icon(
        PhosphorIcons.Regular.Shuffle,
        contentDescription =
          if (shuffleEnabled) {
            stringResource(R.string.player_action_disable_shuffle)
          } else {
            stringResource(R.string.player_action_enable_shuffle)
          },
        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
      )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
      SoftIconButton(onClick = onSkipPrevious) {
        Icon(
          PhosphorIcons.Regular.SkipBack,
          contentDescription = stringResource(R.string.player_action_previous_track),
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(28.dp),
        )
      }
      Surface(modifier = Modifier.size(80.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        PirateIconButton(onClick = onPlayPauseOrUnlock) {
          Icon(
            if (isPlaying) PhosphorIcons.Fill.Pause else PhosphorIcons.Fill.Play,
            contentDescription =
              if (isPlaying) {
                stringResource(R.string.player_action_play_pause_pause)
              } else {
                stringResource(R.string.player_action_play_pause_play)
              },
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(40.dp),
          )
        }
      }
      SoftIconButton(onClick = onSkipNext) {
        Icon(
          PhosphorIcons.Regular.SkipForward,
          contentDescription = stringResource(R.string.player_action_next_track),
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.size(28.dp),
        )
      }
    }

    SoftIconButton(onClick = onCycleRepeat, size = 48.dp, selected = repeatMode != PlayerRepeatMode.OFF) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          PhosphorIcons.Regular.Repeat,
          contentDescription = when (repeatMode) {
            PlayerRepeatMode.OFF -> stringResource(R.string.player_action_repeat_off)
            PlayerRepeatMode.ALL -> stringResource(R.string.player_action_repeat_queue)
            PlayerRepeatMode.ONE -> stringResource(R.string.player_action_repeat_current_track)
          },
          tint = if (repeatMode == PlayerRepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(22.dp),
        )
        if (repeatMode == PlayerRepeatMode.ONE) {
          Text("1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd))
        }
      }
    }
  }
}

@Composable
private fun CanvasPlayerLayout(
  track: sc.pirate.app.music.MusicTrack,
  isPlaying: Boolean,
  progress: PlayerController.PlayerProgress,
  lyricsDoc: PlayerLyricsDoc?,
  canvasVideoReady: Boolean,
  artworkUri: String?,
  artworkFailed: Boolean,
  isFavorited: Boolean,
  canShare: Boolean,
  hasOwnedEntitlement: Boolean,
  canonicalTrackId: String?,
  purchaseSubmitting: Boolean,
  purchasePriceLabel: String?,
  unlockBusy: Boolean,
  topBarAction: PlayerTopBarAction,
  holdPlaybackUntilCanvasReady: Boolean,
  onClose: () -> Unit,
  onOpenMenu: () -> Unit,
  onOpenPurchase: () -> Unit,
  onToggleFavorite: () -> Unit,
  onShare: () -> Unit,
  onOpenSong: (() -> Unit)?,
  onArtworkError: () -> Unit,
  onPlayPauseOrUnlock: () -> Unit,
) {
  val unknownPosterLabel = stringResource(R.string.player_unknown_poster_label)
  val posterLabel = remember(track.artist, unknownPosterLabel) {
    track.artist.trim().removePrefix("@").ifBlank { unknownPosterLabel }
  }
  val songText = remember(track.title, track.artist) { formatCanvasSongText(track.title, track.artist) }
  val hasTranslationLines = remember(lyricsDoc) { hasCanvasLineTranslations(lyricsDoc) }
  var showTranslation by rememberSaveable(track.id) { mutableStateOf(true) }
  Box(
    modifier = Modifier
      .fillMaxSize()
      .pointerInput(holdPlaybackUntilCanvasReady) {
        detectTapGestures {
          if (!holdPlaybackUntilCanvasReady) {
            onPlayPauseOrUnlock()
          }
        }
      },
  ) {
    // Black hold until canvas video renders its first frame
    if (!canvasVideoReady) {
      Box(modifier = Modifier.fillMaxSize().background(Color.Black))
    }

    if (!isPlaying && !holdPlaybackUntilCanvasReady) {
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

    Box(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(start = 24.dp, top = 8.dp, end = 24.dp),
    ) {
      PlayerTopBar(
        track = track,
        canonicalTrackId = canonicalTrackId,
        hasOwnedEntitlement = hasOwnedEntitlement,
        purchaseSubmitting = purchaseSubmitting,
        unlockBusy = unlockBusy,
        action = topBarAction,
        onClose = onClose,
        onOpenMenu = onOpenMenu,
        onOpenPurchase = onOpenPurchase,
      )
    }

    Column(
      modifier =
        Modifier
          .align(Alignment.BottomEnd)
          .padding(end = OverlayRailEndPadding, bottom = OverlayRailBottomPadding),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      if (canShare) {
        OverlayActionIcon(
          icon = PhosphorIcons.Fill.ShareNetwork,
          contentDescription = stringResource(R.string.player_action_overlay_share),
          onClick = onShare,
        )
      }
      OverlayActionIcon(
        icon = PhosphorIcons.Fill.Heart,
        contentDescription =
          if (isFavorited) {
            stringResource(R.string.player_action_overlay_unlike)
          } else {
            stringResource(R.string.player_action_overlay_like)
          },
        active = isFavorited,
        onClick = onToggleFavorite,
      )
      if (hasTranslationLines) {
        OverlayActionIcon(
          icon = PhosphorIcons.Regular.ChatCircle,
          contentDescription =
            if (showTranslation) {
              stringResource(R.string.player_action_overlay_hide_translation)
            } else {
              stringResource(R.string.player_action_overlay_show_translation)
            },
          active = showTranslation,
          onClick = { showTranslation = !showTranslation },
        )
      }
      if (track.isPreviewOnly && !hasOwnedEntitlement) {
        OverlayActionIcon(
          icon = PhosphorIcons.Fill.Lock,
          contentDescription = stringResource(R.string.player_action_overlay_buy_song),
          count = purchasePriceLabel,
          enabled = !purchaseSubmitting && !unlockBusy && !canonicalTrackId.isNullOrBlank(),
          onClick = onOpenPurchase,
        )
      } else if (track.isPreviewOnly && hasOwnedEntitlement) {
        val unlockLabel = when {
          unlockBusy -> stringResource(R.string.player_action_loading)
          purchaseSubmitting -> stringResource(R.string.player_action_buying)
          else -> stringResource(R.string.player_action_play)
        }
        OverlayActionIcon(
          icon = PhosphorIcons.Fill.Play,
          contentDescription = stringResource(R.string.player_action_overlay_play_full),
          count = unlockLabel,
          enabled = !purchaseSubmitting && !unlockBusy,
          onClick = onPlayPauseOrUnlock,
        )
      }
    }

    Column(
      modifier =
        Modifier
          .align(Alignment.BottomStart)
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(start = OverlayTextStartPadding, end = 16.dp, bottom = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Bottom,
      ) {
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          val doc = lyricsDoc
          if (doc != null && doc.lines.isNotEmpty()) {
            CanvasPlayerLyricsBlock(
              lyrics = doc,
              positionSec = progress.positionSec,
              durationSec = progress.durationSec,
              showTranslation = showTranslation,
              modifier = Modifier.fillMaxWidth(),
            )
          }
          Text(
            text = "@$posterLabel",
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style =
              MaterialTheme.typography.bodyMedium.copy(
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                shadow = overlayTextShadow(),
              ),
          )
          if (songText != null) {
            Text(
              text = songText,
              color = Color.White.copy(alpha = 0.76f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              style =
                MaterialTheme.typography.bodySmall.copy(
                  fontSize = 16.sp,
                  shadow = overlayTextShadow(),
                ),
            )
          }
        }

        OverlayActionCircleButton(
          contentDescription = stringResource(R.string.player_action_overlay_song),
          onClick = onOpenSong ?: onOpenMenu,
        ) {
          if (!artworkUri.isNullOrBlank() && !artworkFailed) {
            AsyncImage(
              model = artworkUri,
              contentDescription = stringResource(R.string.player_song_cover_art),
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
              onError = { onArtworkError() },
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
  }
}

@Composable
private fun PlayerTopBar(
  track: sc.pirate.app.music.MusicTrack,
  canonicalTrackId: String?,
  hasOwnedEntitlement: Boolean,
  purchaseSubmitting: Boolean,
  unlockBusy: Boolean,
  action: PlayerTopBarAction = PlayerTopBarAction.MENU,
  onClose: () -> Unit,
  onOpenMenu: () -> Unit,
  onOpenPurchase: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().height(56.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PirateIconButton(onClick = onClose) {
      Icon(
        PhosphorIcons.Regular.CaretDown,
        contentDescription = stringResource(R.string.player_close),
        tint = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.size(28.dp),
      )
    }

    when (action) {
      PlayerTopBarAction.BUY -> {
        PiratePrimaryButton(
          text = stringResource(R.string.player_action_buy),
          onClick = onOpenPurchase,
          enabled = !purchaseSubmitting && !unlockBusy && !canonicalTrackId.isNullOrBlank(),
        )
      }
      PlayerTopBarAction.MENU -> {
        PirateIconButton(onClick = onOpenMenu) {
          Icon(
            PhosphorIcons.Regular.DotsThree,
            contentDescription = stringResource(R.string.player_track_menu),
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(28.dp),
          )
        }
      }
      PlayerTopBarAction.NONE -> Spacer(modifier = Modifier.size(48.dp))
    }
  }
}

@Composable
private fun PlayerLyricsViewport(
  lyrics: PlayerLyricsDoc,
  positionSec: Float,
  durationSec: Float,
  modifier: Modifier = Modifier,
) {
  val activeIndex = remember(lyrics, positionSec, durationSec) {
    currentLyricsIndex(lyrics, positionSec, durationSec)
  }
  val currentLine = lyrics.lines.getOrNull(activeIndex)
  val activeWordIndex = remember(currentLine, positionSec) {
    currentWordIndex(currentLine, positionSec)
  }
  val activeWordStyle = SpanStyle(
    fontWeight = FontWeight.ExtraBold,
    color = MaterialTheme.colorScheme.primary,
  )
  val wordRanges = remember(currentLine?.text, currentLine?.words) {
    val line = currentLine
    val words = line?.words
    if (line == null || words.isNullOrEmpty()) null else locateWordRanges(line.text, words)
  }
  val activeWordRange = remember(wordRanges, activeWordIndex) {
    val index = activeWordIndex ?: return@remember null
    wordRanges?.getOrNull(index)
  }
  val highlightedCurrentLine = remember(currentLine?.text, activeWordRange, activeWordStyle) {
    buildHighlightedCurrentLine(currentLine?.text, activeWordRange, activeWordStyle)
  }
  val translation = currentLine?.translationText.orEmpty()
  val showTranslation = remember(currentLine?.text, translation) {
    shouldDisplayTranslation(currentLine?.text.orEmpty(), translation)
  }

  LaunchedEffect(currentLine?.text, activeWordIndex, activeWordRange) {
    val line = currentLine ?: return@LaunchedEffect
    val words = line.words ?: return@LaunchedEffect
    val index = activeWordIndex ?: return@LaunchedEffect
    val token = words.getOrNull(index)?.text?.trim().orEmpty()
    Log.d(
      TAG_PLAYER_LYRICS,
      "highlight line='${line.text}' activeWordIndex=$index token='$token' range=$activeWordRange words=${words.size}",
    )
    if (activeWordRange == null) {
      Log.w(
        TAG_PLAYER_LYRICS,
        "highlight range missing line='${line.text}' activeWordIndex=$index token='$token'",
      )
    }
  }

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(4.dp),
    horizontalAlignment = Alignment.Start,
  ) {
    if (highlightedCurrentLine != null) {
      Text(
        text = highlightedCurrentLine,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    } else if (!currentLine?.text.isNullOrBlank()) {
      Text(
        text = currentLine?.text.orEmpty(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    if (showTranslation) {
      Text(
        text = translation,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun CanvasPlayerLyricsBlock(
  lyrics: PlayerLyricsDoc,
  positionSec: Float,
  durationSec: Float,
  showTranslation: Boolean,
  modifier: Modifier = Modifier,
) {
  val activeIndex = remember(lyrics, positionSec, durationSec) {
    currentCanvasLyricsIndex(lyrics, positionSec, durationSec)
  }
  val currentLine = lyrics.lines.getOrNull(activeIndex)
  val activeWordIndex = remember(currentLine, positionSec) {
    currentWordIndex(currentLine, positionSec)
  }
  val activeWordStyle = SpanStyle(fontWeight = FontWeight.ExtraBold, color = Color.White)
  val wordRanges = remember(currentLine?.text, currentLine?.words) {
    val line = currentLine
    val words = line?.words
    if (line == null || words.isNullOrEmpty()) null else locateWordRanges(line.text, words)
  }
  val activeWordRange = remember(wordRanges, activeWordIndex) {
    val index = activeWordIndex ?: return@remember null
    wordRanges?.getOrNull(index)
  }
  val highlightedActiveLine = remember(currentLine?.text, activeWordRange, activeWordStyle) {
    buildHighlightedCurrentLine(currentLine?.text, activeWordRange, activeWordStyle)
  }
  val primaryStyle =
    MaterialTheme.typography.bodyMedium.copy(
      fontSize = 17.sp,
      fontWeight = FontWeight.SemiBold,
      shadow = overlayTextShadow(),
    )
  val translationStyle =
    MaterialTheme.typography.bodySmall.copy(
      fontSize = 15.sp,
      shadow = overlayTextShadow(),
    )

  AnimatedContent(
    targetState = activeIndex,
    modifier = modifier,
    transitionSpec = {
      (slideInVertically(animationSpec = tween(durationMillis = 220), initialOffsetY = { it }) +
        fadeIn(animationSpec = tween(durationMillis = 180))) togetherWith
        (slideOutVertically(animationSpec = tween(durationMillis = 180), targetOffsetY = { -it / 2 }) +
          fadeOut(animationSpec = tween(durationMillis = 140)))
    },
    label = "canvas-lyric-line",
  ) { displayedIndex ->
    val line = lyrics.lines.getOrNull(displayedIndex)
    val lineText = line?.text.orEmpty()
    val translation = line?.translationText.orEmpty()
    val showLineTranslation = showTranslation && shouldDisplayTranslation(lineText, translation)
    val highlighted = if (displayedIndex == activeIndex) highlightedActiveLine else null

    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(CanvasLyricViewportGap),
      horizontalAlignment = Alignment.Start,
    ) {
      if (lineText.isNotBlank()) {
        if (highlighted != null) {
          Text(
            text = highlighted,
            style = primaryStyle,
            color = Color.White.copy(alpha = 0.45f),
          )
        } else {
          Text(
            text = lineText,
            style = primaryStyle,
            color = Color.White.copy(alpha = 0.45f),
          )
        }
      }

      if (showLineTranslation) {
        Text(
          text = translation,
          style = translationStyle,
          color = Color.White.copy(alpha = 0.7f),
        )
      }
    }
  }
}

private fun buildHighlightedCurrentLine(
  lineText: String?,
  activeWordRange: IntRange?,
  activeWordStyle: SpanStyle,
): AnnotatedString? {
  val text = lineText?.takeIf { it.isNotBlank() } ?: return null
  val activeRange = activeWordRange ?: return null
  return buildAnnotatedString {
    append(text.substring(0, activeRange.first))
    withStyle(style = activeWordStyle) {
      append(text.substring(activeRange.first, activeRange.last + 1))
    }
    append(text.substring(activeRange.last + 1))
  }
}

private fun locateWordRanges(
  lineText: String,
  words: List<PlayerLyricsWord>,
): List<IntRange>? {
  if (lineText.isBlank() || words.isEmpty()) return null
  val out = ArrayList<IntRange>(words.size)
  var searchStart = 0
  for (word in words) {
    val token = word.text.trim()
    if (token.isBlank()) return null
    val foundAt = lineText.indexOf(token, startIndex = searchStart, ignoreCase = true)
    if (foundAt < 0) return null
    out.add(foundAt until (foundAt + token.length))
    searchStart = foundAt + token.length
  }
  return out
}

private fun shouldDisplayTranslation(
  current: String,
  translation: String,
): Boolean {
  if (translation.isBlank()) return false
  return normalizeComparableLyricsText(current) != normalizeComparableLyricsText(translation)
}

private fun normalizeComparableLyricsText(raw: String): String {
  return raw
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")
    .trim()
}

private fun formatUsdFromMicros(amountRaw: String?): String? {
  val micros = amountRaw?.trim()?.toLongOrNull() ?: return null
  val dollars = micros.toDouble() / 1_000_000.0
  return "$" + String.format(Locale.US, "%.2f", dollars)
}

private fun formatCanvasSongText(
  title: String,
  artist: String,
): String? {
  val normalizedTitle = title.trim()
  val normalizedArtist = artist.trim()
  return when {
    normalizedTitle.isNotBlank() && normalizedArtist.isNotBlank() ->
      "$normalizedTitle - $normalizedArtist"
    normalizedTitle.isNotBlank() -> normalizedTitle
    normalizedArtist.isNotBlank() -> normalizedArtist
    else -> null
  }
}

private fun currentCanvasLyricsIndex(
  lyrics: PlayerLyricsDoc,
  positionSec: Float,
  durationSec: Float,
): Int {
  val activeIndex = currentLyricsIndex(lyrics, positionSec, durationSec)
  if (activeIndex >= 0) return activeIndex
  return 0
}

private fun hasCanvasLineTranslations(lyrics: PlayerLyricsDoc?): Boolean {
  return lyrics?.lines?.any { line ->
    shouldDisplayTranslation(line.text, line.translationText.orEmpty())
  } == true
}

private fun currentLyricsIndex(
  lyrics: PlayerLyricsDoc,
  positionSec: Float,
  durationSec: Float,
): Int {
  val lines = lyrics.lines
  if (lines.isEmpty()) return 0

  val positionMs = (positionSec.coerceAtLeast(0f) * 1000f).toLong()
  val hasTiming = lines.any { it.startMs != null }
  if (hasTiming) {
    var lastTimedIndex = 0
    for (i in lines.indices) {
      val start = lines[i].startMs ?: continue
      lastTimedIndex = i
      if (positionMs < start) return if (i == 0) -1 else i - 1
      val explicitEnd = lines[i].endMs
      val nextStart =
        ((i + 1) until lines.size)
          .firstNotNullOfOrNull { idx -> lines[idx].startMs }
      val end = explicitEnd ?: nextStart ?: Long.MAX_VALUE
      if (positionMs in start until end) return i
    }
    return lastTimedIndex.coerceIn(0, lines.lastIndex)
  }

  // Untimed lyrics are not a reliable sync source; do not pseudo-map by playback progress.
  return 0
}

private fun currentWordIndex(
  line: PlayerLyricsLine?,
  positionSec: Float,
): Int? {
  val words = line?.words
  if (words.isNullOrEmpty()) return null
  val positionMs = (positionSec.coerceAtLeast(0f) * 1000f).toLong()
  if (positionMs < words.first().startMs) return null
  var lastSeenIndex = 0
  for (i in words.indices) {
    val word = words[i]
    if (positionMs in word.startMs until word.endMs) return i
    if (positionMs < word.startMs) return lastSeenIndex
    lastSeenIndex = i
  }
  return lastSeenIndex
}

internal fun highlightedWordRangeForTesting(
  lineText: String,
  words: List<String>,
  activeWordIndex: Int,
): IntRange? {
  val line =
    PlayerLyricsLine(
      text = lineText,
      words = words.mapIndexed { index, word ->
        PlayerLyricsWord(
          text = word,
          startMs = index.toLong(),
          endMs = index.toLong() + 1L,
        )
      },
    )
  return locateWordRanges(line.text, line.words.orEmpty())?.getOrNull(activeWordIndex)
}

internal fun currentWordIndexForTesting(
  words: List<PlayerLyricsWord>,
  positionSec: Float,
): Int? {
  return currentWordIndex(
    line = PlayerLyricsLine(text = "test", words = words),
    positionSec = positionSec,
  )
}

internal fun currentLyricsIndexForTesting(
  lyrics: PlayerLyricsDoc,
  positionSec: Float,
  durationSec: Float,
): Int = currentLyricsIndex(lyrics, positionSec, durationSec)

internal fun currentCanvasLyricsIndexForTesting(
  lyrics: PlayerLyricsDoc,
  positionSec: Float,
  durationSec: Float,
): Int = currentCanvasLyricsIndex(lyrics, positionSec, durationSec)

internal fun hasCanvasLineTranslationsForTesting(
  lyrics: PlayerLyricsDoc?,
): Boolean = hasCanvasLineTranslations(lyrics)

private fun overlayTextShadow(): Shadow =
  Shadow(color = Color.Black.copy(alpha = 0.7f), offset = Offset(0f, 1f), blurRadius = 4f)

private fun shouldUseCanvasPlayerLayout(canvasUrl: String?): Boolean = !canvasUrl.isNullOrBlank()

private fun shouldRenderCanvasBackdrop(
  canvasUrl: String?,
  canvasVideoFailed: Boolean,
): Boolean = !canvasUrl.isNullOrBlank() && !canvasVideoFailed

private fun shouldHoldPlaybackUntilCanvasReady(
  showCanvasLoadingState: Boolean,
  useCanvasPlayerLayout: Boolean,
  hasCanvasBackdrop: Boolean,
  canvasVideoReady: Boolean,
): Boolean = showCanvasLoadingState || (useCanvasPlayerLayout && hasCanvasBackdrop && !canvasVideoReady)

private fun shouldRunCanvasVideo(
  hasCanvasBackdrop: Boolean,
  isPlaying: Boolean,
  holdPlaybackUntilCanvasReady: Boolean,
): Boolean = hasCanvasBackdrop && (isPlaying || holdPlaybackUntilCanvasReady)

private fun resolvePlayerTopBarAction(
  isPreviewOnly: Boolean,
  hasOwnedEntitlement: Boolean,
  immersive: Boolean,
): PlayerTopBarAction {
  if (immersive) return PlayerTopBarAction.NONE
  if (isPreviewOnly && !hasOwnedEntitlement) return PlayerTopBarAction.BUY
  return PlayerTopBarAction.MENU
}

internal fun shouldUseCanvasPlayerLayoutForTesting(
  canvasUrl: String?,
): Boolean = shouldUseCanvasPlayerLayout(canvasUrl)

internal fun shouldRenderCanvasBackdropForTesting(
  canvasUrl: String?,
  canvasVideoFailed: Boolean,
): Boolean = shouldRenderCanvasBackdrop(canvasUrl, canvasVideoFailed)

internal fun shouldHoldPlaybackUntilCanvasReadyForTesting(
  showCanvasLoadingState: Boolean,
  useCanvasPlayerLayout: Boolean,
  hasCanvasBackdrop: Boolean,
  canvasVideoReady: Boolean,
): Boolean =
  shouldHoldPlaybackUntilCanvasReady(
    showCanvasLoadingState = showCanvasLoadingState,
    useCanvasPlayerLayout = useCanvasPlayerLayout,
    hasCanvasBackdrop = hasCanvasBackdrop,
    canvasVideoReady = canvasVideoReady,
  )

internal fun shouldRunCanvasVideoForTesting(
  hasCanvasBackdrop: Boolean,
  isPlaying: Boolean,
  holdPlaybackUntilCanvasReady: Boolean,
): Boolean =
  shouldRunCanvasVideo(
    hasCanvasBackdrop = hasCanvasBackdrop,
    isPlaying = isPlaying,
    holdPlaybackUntilCanvasReady = holdPlaybackUntilCanvasReady,
  )

internal fun resolvePlayerTopBarActionForTesting(
  isPreviewOnly: Boolean,
  hasOwnedEntitlement: Boolean,
  immersive: Boolean,
): String = resolvePlayerTopBarAction(isPreviewOnly, hasOwnedEntitlement, immersive).name
