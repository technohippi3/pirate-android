package sc.pirate.app.home

import android.os.SystemClock
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*
import sc.pirate.app.ViewerContentLocaleResolver
import sc.pirate.app.identity.SelfVerificationService
import sc.pirate.app.music.SongPurchaseApi
import coil.compose.AsyncImage
import sc.pirate.app.learn.StudyProgressApi
import sc.pirate.app.music.SongPublishService
import sc.pirate.app.music.ui.SongPurchaseSheet
import sc.pirate.app.player.PlayerController
import sc.pirate.app.post.PostTxRepository
import sc.pirate.app.song.SongArtistApi
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.util.resolveAvatarUrl
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeFeedScreen(
  musicPlayer: PlayerController,
  isCallActive: Boolean,
  onOpenDrawer: () -> Unit,
  onOpenPost: () -> Unit,
  onOpenProfile: (address: String) -> Unit,
  onOpenSongPage: (trackId: String, title: String?, artist: String?) -> Unit,
  isAuthenticated: Boolean,
  ownerAddress: String?,
  primaryName: String?,
  avatarUri: String?,
  tempoAccount: TempoPasskeyManager.PasskeyAccount?,
  hostActivity: FragmentActivity?,
  onRequireVerification: () -> Unit,
  onOpenLearn: (studySetRef: String?, trackId: String, language: String, version: Int, title: String?, artist: String?) -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val appContext = LocalContext.current.applicationContext
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  val learnerLanguage = remember { Locale.getDefault().language.ifBlank { "en" } }
  val sessionState = remember(appContext, scope) { FeedSessionState(context = appContext, scope = scope) }
  val likeUiStateMap = remember { mutableStateMapOf<String, LikeUiState>() }
  val studyUiStateMap = remember { mutableStateMapOf<String, StudyUiState>() }
  val translatedCaptionMap = remember { mutableStateMapOf<String, String>() }
  val translatingCaptionMap = remember { mutableStateMapOf<String, Boolean>() }
  val appMusicPlaying by musicPlayer.isPlaying.collectAsState()
  var viewerLocaleTag by remember {
    mutableStateOf(
      runCatching { Locale.getDefault().toLanguageTag() }
        .getOrNull()
        ?.trim()
        ?.ifBlank { Locale.getDefault().language.ifBlank { "en" } }
        ?: "en",
    )
  }

  var player by remember { mutableStateOf<ExoPlayer?>(null) }
  var loadedPostId by remember { mutableStateOf<String?>(null) }
  var loadedVideoUrl by remember { mutableStateOf<String?>(null) }
  var userPaused by remember { mutableStateOf(false) }
  var videoError by remember { mutableStateOf<String?>(null) }
  var requestedPostId by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(ownerAddress) {
    viewerLocaleTag = ViewerContentLocaleResolver.resolve(appContext)
  }
  var requestedLoadSeq by remember { mutableStateOf(0L) }
  var lastSettledAtMs by remember { mutableStateOf(0L) }
  var requestedLoadStartedAtMs by remember { mutableStateOf(0L) }
  var playerSurfacePostId by remember { mutableStateOf<String?>(null) }
  var viewerNationality by remember { mutableStateOf<String?>(null) }
  var purchaseSheetPost by remember { mutableStateOf<FeedPostResolved?>(null) }
  var shareSheetPost by remember { mutableStateOf<FeedPostResolved?>(null) }
  var taggedItemsSheetPost by remember { mutableStateOf<FeedPostResolved?>(null) }
  var purchaseSubmitting by remember { mutableStateOf(false) }
  // Set to loadedPostId only after onRenderedFirstFrame fires for that post.
  // Preview stays visible until a real frame is on screen (covers shutter, black first frames,
  // and the window where the player is still attached to the previous post's media).
  var playerReadyPostId by remember { mutableStateOf<String?>(null) }

  val upstreamFactory =
    remember(appContext) {
      DefaultDataSource.Factory(
        appContext,
        DefaultHttpDataSource.Factory()
          .setAllowCrossProtocolRedirects(true)
          .setConnectTimeoutMs(30_000)
          .setReadTimeoutMs(120_000),
      )
    }
  val cacheDataSourceFactory =
    remember(appContext, upstreamFactory) {
      CacheDataSource.Factory()
        .setCache(FeedVideoCacheStore.getOrCreate(appContext))
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
  val videoPreloader =
    remember(appContext, scope, upstreamFactory) {
      FeedVideoPreloader(
        context = appContext,
        scope = scope,
        upstreamFactory = upstreamFactory,
      )
    }

  LaunchedEffect(Unit) {
    sessionState.initialize()
  }

  val feedItems = sessionState.feedItems
  val feedIdsKey = remember(feedItems) { feedItems.joinToString(separator = ",") { it.id } }

  LaunchedEffect(feedIdsKey) {
    val activeIds = feedItems.map { it.id }.toSet()
    translatedCaptionMap.keys.toList().forEach { id ->
      if (!activeIds.contains(id)) translatedCaptionMap.remove(id)
    }
    translatingCaptionMap.keys.toList().forEach { id ->
      if (!activeIds.contains(id)) translatingCaptionMap.remove(id)
    }
    feedItems.forEach { post ->
      if (likeUiStateMap[post.id] == null) {
        likeUiStateMap[post.id] = LikeUiState(likeCount = post.likeCount)
      }
      if (studyUiStateMap[post.id] == null) {
        studyUiStateMap[post.id] = StudyUiState()
      }
    }
  }

  LaunchedEffect(feedIdsKey, isAuthenticated, ownerAddress) {
    val owner = ownerAddress?.trim().orEmpty()
    if (!isAuthenticated || owner.isBlank() || feedItems.isEmpty()) return@LaunchedEffect
    runCatching {
      FeedRepository.fetchViewerLikedPostIds(
        ownerAddress = owner,
        postIds = feedItems.map { it.id },
      )
    }.onSuccess { likedIds ->
      feedItems.forEach { post ->
        val existing = likeUiStateMap[post.id] ?: LikeUiState(likeCount = post.likeCount)
        val remoteLiked = likedIds.contains(post.id)
        likeUiStateMap[post.id] =
          when {
            existing.localOverride && existing.liked != remoteLiked -> existing
            else -> existing.copy(liked = remoteLiked, localOverride = false)
          }
      }
    }
  }

  val pagerState = rememberPagerState(pageCount = { feedItems.size })
  val activePageIndex by remember(feedItems.size, pagerState) {
    derivedStateOf {
      if (feedItems.isEmpty()) 0 else pagerState.currentPage.coerceIn(0, feedItems.lastIndex)
    }
  }
  val preloadAnchorPageIndex by remember(feedItems.size, pagerState) {
    derivedStateOf {
      if (feedItems.isEmpty()) {
        0
      } else {
        val base = pagerState.currentPage.coerceIn(0, feedItems.lastIndex)
        val offset = pagerState.currentPageOffsetFraction
        val directional =
          when {
            offset > 0.2f -> base + 1
            offset < -0.2f -> base - 1
            else -> base
          }
        directional.coerceIn(0, feedItems.lastIndex)
      }
    }
  }
  val activePost = feedItems.getOrNull(activePageIndex)
  val purchasePreview = remember(viewerNationality) { resolveFeedPurchasePreviewPrice(viewerNationality) }
  val purchasePriceLabel = remember(purchasePreview.amountMicros) { formatUsdFromMicros(purchasePreview.amountMicros) }

  LaunchedEffect(purchaseSheetPost?.id) {
    purchaseSubmitting = false
  }

  LaunchedEffect(isAuthenticated, ownerAddress) {
    val normalizedOwner = ownerAddress?.trim().orEmpty()
    if (!isAuthenticated || normalizedOwner.isBlank()) {
      viewerNationality = null
      return@LaunchedEffect
    }
    val apiBase = runCatching { SongPublishService.API_CORE_URL }.getOrNull() ?: run {
      viewerNationality = null
      return@LaunchedEffect
    }
    val identity =
      withContext(Dispatchers.IO) {
        SelfVerificationService.checkIdentity(apiBase, normalizedOwner)
      }
    viewerNationality =
      when (identity) {
        is SelfVerificationService.IdentityResult.Verified -> identity.nationality?.trim()?.uppercase()?.ifBlank { null }
        else -> null
      }
  }

  LaunchedEffect(activePost?.id, learnerLanguage) {
    val post = activePost ?: return@LaunchedEffect
    val trackId = post.songTrackId.trim()
    val existing = studyUiStateMap[post.id] ?: StudyUiState()

    if (trackId.isBlank()) {
      studyUiStateMap[post.id] =
        existing.copy(
          ready = false,
          studySetRef = null,
          statusTrackId = null,
          flashcardAdderCount = 0,
          statusLoading = false,
          countLoading = false,
        )
      return@LaunchedEffect
    }

    if (existing.statusLoading || existing.countLoading || existing.generateBusy) return@LaunchedEffect

    studyUiStateMap[post.id] = existing.copy(statusLoading = true, countLoading = true)

    val flashcardAdderCount = runCatching {
      StudyProgressApi.fetchTrackFlashcardAdderCount(trackId = trackId)
    }.getOrElse { err ->
      if (err is CancellationException) throw err
      null
    }

    val primaryStatus = runCatching {
      SongArtistApi.fetchStudySetStatus(trackId = trackId, language = learnerLanguage)
    }.getOrElse { err ->
      if (err is CancellationException) throw err
      null
    }

    var effectiveStatus = primaryStatus
    if (effectiveStatus?.ready != true && !learnerLanguage.equals("en", ignoreCase = true)) {
      val fallbackStatus = runCatching {
        SongArtistApi.fetchStudySetStatus(trackId = trackId, language = "en")
      }.getOrElse { err ->
        if (err is CancellationException) throw err
        null
      }
      if (fallbackStatus?.ready == true) {
        effectiveStatus = fallbackStatus
      }
    }

    val ready = effectiveStatus?.ready == true
    studyUiStateMap[post.id] =
      (studyUiStateMap[post.id] ?: existing).copy(
        ready = ready,
        studySetRef = if (ready) effectiveStatus?.studySetRef else null,
        statusTrackId = if (ready) trackId else null,
        flashcardAdderCount = flashcardAdderCount ?: existing.flashcardAdderCount ?: 0,
        statusLoading = false,
        countLoading = false,
      )
  }

  LaunchedEffect(activePageIndex, feedItems.size) {
    if (feedItems.isNotEmpty()) {
      lastSettledAtMs = SystemClock.elapsedRealtime()
      feedDebug(
        "pager active=$activePageIndex postId=${activePost?.id} current=${pagerState.currentPage} offset=${pagerState.currentPageOffsetFraction}",
      )
      sessionState.onPageVisible(activePageIndex)
    }
  }

  LaunchedEffect(feedIdsKey, preloadAnchorPageIndex, videoPreloader) {
    if (feedItems.isEmpty()) {
      feedDebug("preload clear: feed empty")
      videoPreloader.clear()
      return@LaunchedEffect
    }
    val nextTargetId = feedItems.getOrNull(preloadAnchorPageIndex + 1)?.id
    val previousTargetId = feedItems.getOrNull(preloadAnchorPageIndex - 1)?.id
    feedDebug(
      "preload update anchor=$preloadAnchorPageIndex current=${pagerState.currentPage} settled=${pagerState.settledPage} offset=${pagerState.currentPageOffsetFraction} next=$nextTargetId prev=$previousTargetId",
    )
    videoPreloader.updateTargets(posts = feedItems, anchorIndex = preloadAnchorPageIndex)
  }

  DisposableEffect(videoPreloader) {
    onDispose { videoPreloader.clear() }
  }

  DisposableEffect(Unit) {
    val exo =
      ExoPlayer.Builder(appContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        playWhenReady = true
        addListener(
          object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
              val mediaId = this@apply.currentMediaItem?.mediaId.orEmpty()
              feedDebug("player isPlaying=$isPlaying mediaId=$mediaId readyPostId=$playerReadyPostId")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
              val mediaId = this@apply.currentMediaItem?.mediaId.orEmpty()
              feedDebug("player state=${playbackStateName(playbackState)} mediaId=$mediaId error=${videoError != null}")
            }

            override fun onRenderedFirstFrame() {
              // Real video frame is now on the surface — safe to hide the preview thumbnail.
              // This is later than onIsPlayingChanged(true) and correctly handles videos that
              // start with a black frame, since we wait for actual pixel data.
              val mediaItem = this@apply.currentMediaItem ?: return
              val currentMediaId = mediaItem.mediaId.trim()
              val currentTag = mediaItem.localConfiguration?.tag as? String
              val expectedPostId = requestedPostId?.trim().orEmpty()
              val expectedTag =
                if (expectedPostId.isBlank()) null else buildFeedMediaTag(postId = expectedPostId, loadSeq = requestedLoadSeq)
              val nowMs = SystemClock.elapsedRealtime()
              val loadMs = (nowMs - requestedLoadStartedAtMs).coerceAtLeast(0L)
              val settleMs = (nowMs - lastSettledAtMs).coerceAtLeast(0L)
              feedDebug(
                "firstFrame mediaId=$currentMediaId expected=$expectedPostId tag=$currentTag expectedTag=$expectedTag loadMs=$loadMs settleMs=$settleMs",
              )
              if (currentMediaId.isNotBlank() && currentMediaId == expectedPostId && currentTag == expectedTag) {
                playerReadyPostId = currentMediaId
                feedDebug("firstFrame accepted readyPostId=$playerReadyPostId")
              } else {
                feedDebug("firstFrame ignored mediaId=$currentMediaId expected=$expectedPostId tag=$currentTag expectedTag=$expectedTag")
              }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
              val currentMediaId = this@apply.currentMediaItem?.mediaId?.trim().orEmpty().ifBlank { loadedPostId.orEmpty() }
              feedDebug(
                "player error mediaId=$currentMediaId expected=$requestedPostId active=${activePost?.id} state=${playbackStateName(this@apply.playbackState)}",
              )
              Log.e(
                "HomeFeedScreen",
                "Playback failed postId=$currentMediaId videoUrl=$loadedVideoUrl message=${error.message}",
                error,
              )
              videoError = error.message ?: "Playback failed"
              playerReadyPostId = null
            }
          },
        )
      }
    player = exo
    onDispose {
      persistWatchForPost(
        player = exo,
        postId = loadedPostId,
        sessionState = sessionState,
      )
      runCatching { exo.release() }
      if (player === exo) player = null
    }
  }

  LaunchedEffect(activePost?.id, player) {
    val exo = player ?: return@LaunchedEffect
    val nextPost = activePost
    if (loadedPostId == nextPost?.id) return@LaunchedEffect

    feedDebug(
      "switch request from=$loadedPostId to=${nextPost?.id} current=${pagerState.currentPage} settled=${pagerState.settledPage} offset=${pagerState.currentPageOffsetFraction}",
    )
    persistWatchForPost(
      player = exo,
      postId = loadedPostId,
      sessionState = sessionState,
    )

    loadedPostId = nextPost?.id
    requestedPostId = nextPost?.id
    requestedLoadSeq += 1L
    userPaused = false
    playerSurfacePostId = null
    playerReadyPostId = null  // preview covers until onRenderedFirstFrame fires for new post
    loadedVideoUrl = null
    videoError = null
    if (nextPost == null || nextPost.videoUrl.isNullOrBlank()) {
      requestedPostId = null
      feedDebug("switch clear: post missing or videoUrl blank postId=${nextPost?.id}")
      exo.clearMediaItems()
      exo.playWhenReady = false
      return@LaunchedEffect
    }

    val mediaUrl = nextPost.videoUrl.orEmpty().trim()
    requestedLoadStartedAtMs = SystemClock.elapsedRealtime()
    val previewFrameMs = resolveFeedPreviewFrameMs(nextPost.previewAtMs)
    loadedVideoUrl = mediaUrl
    feedDebug(
      "switch load postId=${nextPost.id} mediaUrl=$mediaUrl previewUrl=${nextPost.previewUrl} previewAtMs=${nextPost.previewAtMs} previewFrameMs=$previewFrameMs requestSeq=$requestedLoadSeq",
    )
    Log.i("HomeFeedScreen", "Loading video postId=${nextPost.id} videoUrl=$mediaUrl ref=${nextPost.videoRef}")
    val mediaItem =
      MediaItem.Builder()
        .setUri(mediaUrl)
        .setMediaId(nextPost.id)
        .setTag(buildFeedMediaTag(postId = nextPost.id, loadSeq = requestedLoadSeq))
        .build()
    val mediaSource =
      runCatching {
        ProgressiveMediaSource.Factory(cacheDataSourceFactory).createMediaSource(mediaItem)
      }.getOrElse {
        ProgressiveMediaSource.Factory(upstreamFactory).createMediaSource(mediaItem)
      }
    exo.setMediaSource(mediaSource)
    exo.prepare()
    exo.playWhenReady = !userPaused && !appMusicPlaying
    playerSurfacePostId = nextPost.id
    feedDebug("switch prepared postId=${nextPost.id} playWhenReady=${exo.playWhenReady}")
  }

  LaunchedEffect(activePost?.id, player) {
    val exo = player ?: return@LaunchedEffect
    while (isActive) {
      delay(5_000)
      if (loadedPostId == activePost?.id) {
        persistWatchForPost(
          player = exo,
          postId = loadedPostId,
          sessionState = sessionState,
        )
      }
    }
  }

  DisposableEffect(lifecycleOwner, player, activePost?.id, videoError, appMusicPlaying) {
    val observer =
      LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_RESUME -> {
            if (activePost?.videoUrl != null && videoError == null && !appMusicPlaying) {
              feedDebug("lifecycle resume play postId=${activePost?.id}")
              player?.playWhenReady = !userPaused
            } else {
              player?.playWhenReady = false
            }
          }

          Lifecycle.Event.ON_PAUSE,
          Lifecycle.Event.ON_STOP,
          -> {
            persistWatchForPost(
              player = player,
              postId = loadedPostId,
              sessionState = sessionState,
            )
            feedDebug("lifecycle pause/stop pause postId=$loadedPostId")
            player?.playWhenReady = false
          }

          else -> Unit
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(isCallActive, player) {
    val exo = player ?: return@LaunchedEffect
    exo.volume = if (isCallActive) 0f else 1f
  }

  LaunchedEffect(appMusicPlaying, player, activePost?.id, videoError, userPaused) {
    val exo = player ?: return@LaunchedEffect
    if (activePost?.videoUrl.isNullOrBlank() || videoError != null) return@LaunchedEffect
    if (appMusicPlaying) {
      if (exo.playWhenReady || exo.isPlaying) {
        feedDebug("music active -> pausing feed postId=${activePost?.id}")
        exo.pause()
        exo.playWhenReady = false
      }
      return@LaunchedEffect
    }
    if (!userPaused) {
      feedDebug("music inactive -> resume feed postId=${activePost?.id}")
      exo.playWhenReady = true
      exo.play()
    } else if (exo.playWhenReady || exo.isPlaying) {
      feedDebug("user paused -> keeping feed paused postId=${activePost?.id}")
      exo.pause()
      exo.playWhenReady = false
    }
  }

  fun onToggleLike(post: FeedPostResolved) {
    val existing = likeUiStateMap[post.id] ?: LikeUiState(likeCount = post.likeCount)
    if (existing.txBusy) return
    if (post.id.isBlank()) {
      onShowMessage("No active post to like")
      return
    }
    if (!isAuthenticated || ownerAddress.isNullOrBlank() || tempoAccount == null || hostActivity == null) {
      onShowMessage("Sign in to like posts")
      return
    }
    if (!PostTxRepository.isConfigured()) {
      onShowMessage("Feed contract is not configured")
      return
    }

    val previousLiked = existing.liked
    val previousCount = existing.likeCount
    val nextLiked = !previousLiked
    likeUiStateMap[post.id] =
      existing.copy(
        liked = nextLiked,
        likeCount = if (nextLiked) previousCount + 1L else maxOf(0L, previousCount - 1L),
        txBusy = true,
      )

    scope.launch {
      val loadedSession =
        SessionKeyManager.load(hostActivity)?.takeIf {
          SessionKeyManager.isValid(it, ownerAddress = tempoAccount.address) &&
            it.keyAuthorization?.isNotEmpty() == true
        }

      val result =
        if (nextLiked) {
          PostTxRepository.likePost(
            activity = hostActivity,
            account = tempoAccount,
            ownerAddress = ownerAddress,
            postId = post.id,
            sessionKey = loadedSession,
          )
        } else {
          PostTxRepository.unlikePost(
            activity = hostActivity,
            account = tempoAccount,
            ownerAddress = ownerAddress,
            postId = post.id,
            sessionKey = loadedSession,
          )
        }

      if (!result.success) {
        likeUiStateMap[post.id] =
          existing.copy(
            liked = previousLiked,
            likeCount = previousCount,
            txBusy = false,
          )
        val normalizedError = result.error?.lowercase().orEmpty()
        val verificationRequired =
          normalizedError.contains("self verification required") ||
            normalizedError.contains("verification required")
        if (verificationRequired && nextLiked) {
          onRequireVerification()
          return@launch
        }
        onShowMessage("Like update failed: ${result.error ?: "unknown error"}")
        return@launch
      }
      val finalState = likeUiStateMap[post.id] ?: LikeUiState(likeCount = previousCount)
      likeUiStateMap[post.id] = finalState.copy(txBusy = false, localOverride = true)
      val txPreview = result.txHash?.take(10).orEmpty()
      onShowMessage("Like updated tx=${txPreview}…")
    }
  }

  fun onOpenStudy(post: FeedPostResolved) {
    val trackId = post.songTrackId.trim()
    if (trackId.isBlank()) {
      onShowMessage("No track available for exercises")
      return
    }

    val existing = studyUiStateMap[post.id] ?: StudyUiState()
    if (existing.generateBusy) return
    if (existing.statusLoading || existing.countLoading) {
      onShowMessage("Checking existing exercises...")
      return
    }
    if (existing.ready) {
      onOpenLearn(
        existing.studySetRef,
        existing.statusTrackId ?: trackId,
        learnerLanguage,
        2,
        post.songTitle?.takeIf { it.isNotBlank() },
        post.songArtist?.takeIf { it.isNotBlank() },
      )
      return
    }
    if (!isAuthenticated || ownerAddress.isNullOrBlank()) {
      onShowMessage("Sign in to generate exercises")
      return
    }

    val normalizedOwner = ownerAddress.trim()
    studyUiStateMap[post.id] = existing.copy(generateBusy = true)

    scope.launch {
      runCatching {
        SongArtistApi.generateStudySet(
          trackId = trackId,
          language = learnerLanguage,
          userAddress = normalizedOwner,
          title = post.songTitle,
          artist = post.songArtist,
          album = null,
        )
      }.onSuccess { result ->
        if (result.success) {
          val resolvedRef = result.studySetRef ?: (studyUiStateMap[post.id] ?: existing).studySetRef
          studyUiStateMap[post.id] =
            (studyUiStateMap[post.id] ?: existing).copy(
              ready = true,
              studySetRef = resolvedRef,
              statusTrackId = trackId,
              statusLoading = false,
              countLoading = true,
              generateBusy = false,
            )

          val refreshedFlashcardCount =
            runCatching { StudyProgressApi.fetchTrackFlashcardAdderCount(trackId = trackId) }
              .getOrElse { err ->
                if (err is CancellationException) throw err
                null
              }
          studyUiStateMap[post.id] =
            (studyUiStateMap[post.id] ?: existing).copy(
              countLoading = false,
              flashcardAdderCount = refreshedFlashcardCount ?: (studyUiStateMap[post.id] ?: existing).flashcardAdderCount ?: 0,
            )
          onShowMessage(if (result.cached) "Study set already available" else "Study set generated")
          onOpenLearn(
            resolvedRef,
            trackId,
            learnerLanguage,
            2,
            post.songTitle?.takeIf { it.isNotBlank() },
            post.songArtist?.takeIf { it.isNotBlank() },
          )
        } else {
          studyUiStateMap[post.id] = (studyUiStateMap[post.id] ?: existing).copy(generateBusy = false)
          onShowMessage(result.error ?: "Generation failed")
        }
      }.onFailure { err ->
        if (err is CancellationException) throw err
        studyUiStateMap[post.id] = (studyUiStateMap[post.id] ?: existing).copy(generateBusy = false)
        onShowMessage("Generate failed: ${err.message ?: "unknown error"}")
      }
    }
  }

  fun openPurchaseDrawer(post: FeedPostResolved) {
    val trackId = post.songTrackId.trim()
    if (trackId.isBlank()) {
      onShowMessage("Song unavailable for purchase")
      return
    }
    purchaseSheetPost = post
  }

  fun openTaggedItems(post: FeedPostResolved) {
    if (post.taggedItems.isEmpty()) {
      onShowMessage("No items linked to this post")
      return
    }
    taggedItemsSheetPost = post
  }

  fun openSongPage(post: FeedPostResolved) {
    val trackId = post.songTrackId.trim()
    if (trackId.isBlank()) {
      onShowMessage("Song unavailable")
      return
    }
    onOpenSongPage(trackId, post.songTitle, post.songArtist)
  }

  fun onBuySong(post: FeedPostResolved) {
    if (purchaseSubmitting) return

    val trackId = post.songTrackId.trim().lowercase()
    if (trackId.isBlank()) {
      onShowMessage("Song unavailable for purchase")
      return
    }
    if (!isAuthenticated || ownerAddress.isNullOrBlank() || tempoAccount == null || hostActivity == null) {
      onShowMessage("Sign in to buy songs")
      return
    }

    purchaseSubmitting = true
    scope.launch {
      val loadedSession =
        SessionKeyManager.load(hostActivity)?.takeIf {
          SessionKeyManager.isValid(it, ownerAddress = tempoAccount.address)
        }
      val result =
        SongPurchaseApi.buySong(
          activity = hostActivity,
          appContext = appContext,
          account = tempoAccount,
          ownerAddress = ownerAddress.trim(),
          songTrackId = trackId,
          sessionKey = loadedSession,
        )
      purchaseSubmitting = false
      if (!result.success) {
        onShowMessage("Purchase failed: ${result.error ?: "unknown error"}")
        return@launch
      }

      purchaseSheetPost = null
      val paidLabel = result.paidAmountRaw?.toLongOrNull()?.let(::formatUsdFromMicros)
      if (paidLabel != null) {
        onShowMessage("Purchase complete: $paidLabel")
      } else {
        onShowMessage("Purchase complete")
      }
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        brush = Brush.verticalGradient(
          colors = listOf(Color(0xFF2A2230), Color(0xFF121018), Color(0xFF08090D)),
        ),
      ),
  ) {
    if (feedItems.isNotEmpty()) {
      VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 2,
        key = { idx -> "${feedItems[idx].id}#$idx" },
      ) { index ->
        val post = feedItems[index]
        val likeState = likeUiStateMap[post.id] ?: LikeUiState(likeCount = post.likeCount)
        val isActive = index == activePageIndex
        FeedPostCard(
          post = post,
          captionTextOverride = translatedCaptionMap[post.id],
          isCaptionTranslating = translatingCaptionMap[post.id] == true,
          likeState = likeState,
          previewFrameMs = resolveFeedPreviewFrameMs(post.previewAtMs),
          isActive = isActive,
          player = player,
          playerSurfacePostId = playerSurfacePostId,
          playerReadyPostId = playerReadyPostId,
          userPaused = userPaused,
          blockedByMusic = appMusicPlaying,
          videoError = if (isActive) videoError else null,
          onTogglePlayPause = {
            val exo = player ?: return@FeedPostCard
            val shouldResumeFeed = userPaused || !exo.isPlaying
            if (shouldResumeFeed) {
              // Feed video takes playback priority on Home; keep music queue but pause it.
              musicPlayer.pause()
              userPaused = false
              exo.playWhenReady = true
              exo.play()
            } else {
              userPaused = true
              exo.pause()
              exo.playWhenReady = false
            }
          },
          onOpenProfile = {
            val creatorAddress = post.creator.trim()
            if (creatorAddress.isBlank()) {
              onShowMessage("Creator profile unavailable")
            } else {
              onOpenProfile(creatorAddress)
            }
          },
          purchasePriceLabel = purchasePriceLabel,
          onOpenPurchase = {
            openPurchaseDrawer(post)
          },
          onToggleLike = {
            onToggleLike(post)
          },
          onShare = {
            shareSheetPost = post
          },
          taggedItemCount = post.taggedItems.size,
          onOpenTaggedItems = {
            openTaggedItems(post)
          },
          showTranslateCaption = shouldShowTranslateCaption(
            post = post,
            viewerLocaleTag = viewerLocaleTag,
          ),
          onTranslateCaption = {
            if (translatingCaptionMap[post.id] == true) return@FeedPostCard
            translatingCaptionMap[post.id] = true
            scope.launch {
              val localeTag = viewerLocaleTag
              runCatching {
                withContext(Dispatchers.IO) {
                  FeedTranslationApi.resolvePostCaption(
                    postId = post.id,
                    locale = localeTag,
                  )
                }
              }.onSuccess { translated ->
                translatedCaptionMap[post.id] = translated.text
              }.onFailure { error ->
                val reason = error.message?.trim().orEmpty().ifBlank { "unknown error" }
                onShowMessage("Translate failed: $reason")
              }
              translatingCaptionMap[post.id] = false
            }
          },
          onOpenSong = {
            openSongPage(post)
          },
        )
      }
    } else {
      val hasError = !sessionState.loadError.isNullOrBlank()
      val message =
        when {
          hasError -> sessionState.loadError.orEmpty()
          sessionState.isLoading -> ""
          else -> "No active posts yet."
        }
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        if (message.isNotBlank()) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text(
              text = message,
              color = Color.White.copy(alpha = 0.92f),
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
            if (hasError) {
              Spacer(modifier = Modifier.height(16.dp))
              Button(
                onClick = { sessionState.retry() },
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color.White.copy(alpha = 0.15f),
                  contentColor = Color.White,
                ),
              ) {
                Text("Try Again")
              }
            }
          }
        }
      }
    }

    Row(
      modifier = Modifier
        .align(Alignment.TopStart)
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(horizontal = 16.dp)
        .padding(top = 8.dp, bottom = 6.dp)
        .heightIn(min = 56.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      PirateIconButton(
        onClick = onOpenDrawer,
      ) {
        val avatarUrl = resolveAvatarUrl(avatarUri)
        if (!avatarUrl.isNullOrBlank()) {
          AsyncImage(
            model = avatarUrl,
            contentDescription = "Avatar",
            modifier = Modifier.size(36.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
          )
        } else {
          val bg = if (isAuthenticated) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
          val fg = if (isAuthenticated) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
          val fallbackInitial = when {
            !primaryName.isNullOrBlank() -> primaryName.take(1)
            !ownerAddress.isNullOrBlank() -> ownerAddress.take(2).removePrefix("0x").ifEmpty { "?" }
            else -> "P"
          }.uppercase()
          Surface(
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = bg,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Text(
                text = fallbackInitial,
                color = fg,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }

      PirateIconButton(onClick = onOpenPost) {
        Icon(
          imageVector = PhosphorIcons.Regular.Plus,
          contentDescription = "Create post",
          tint = Color.White,
        )
      }
    }

    val sheetPost = purchaseSheetPost
    SongPurchaseSheet(
      open = sheetPost != null,
      title = sheetPost?.songTitle?.takeIf { it.isNotBlank() } ?: "Untitled Song",
      artist = sheetPost?.songArtist?.takeIf { it.isNotBlank() } ?: "Unknown Artist",
      priceLabel = purchasePriceLabel,
      busy = purchaseSubmitting,
      onDismiss = { purchaseSheetPost = null },
      onConfirm = { if (sheetPost != null) onBuySong(sheetPost) },
      confirmLabel = if (purchaseSubmitting) "Buying..." else "Buy",
    )

    HomeFeedShareSheet(
      sharePost = shareSheetPost,
      onDismiss = { shareSheetPost = null },
      onShowMessage = onShowMessage,
    )

    HomeFeedTaggedItemsSheet(
      post = taggedItemsSheetPost,
      onDismiss = { taggedItemsSheetPost = null },
      onShowMessage = onShowMessage,
    )
  }
}

private fun persistWatchForPost(
  player: ExoPlayer?,
  postId: String?,
  sessionState: FeedSessionState,
) {
  val normalizedPostId = postId?.trim()?.lowercase().orEmpty()
  if (player == null || normalizedPostId.isBlank()) return
  val duration = player.duration
  if (duration == C.TIME_UNSET || duration <= 0L) return
  val position = player.currentPosition.coerceAtLeast(0L)
  if (position <= 0L) return
  val pct = (position.toDouble() / duration.toDouble()).toFloat().coerceIn(0f, 1f)
  if (pct <= 0f) return
  sessionState.recordWatch(postId = normalizedPostId, watchPct = pct)
}

internal data class LikeUiState(
  val liked: Boolean = false,
  val likeCount: Long = 0L,
  val txBusy: Boolean = false,
  val localOverride: Boolean = false,
)

internal data class StudyUiState(
  val ready: Boolean = false,
  val studySetRef: String? = null,
  val statusTrackId: String? = null,
  val flashcardAdderCount: Int? = null,
  val statusLoading: Boolean = false,
  val countLoading: Boolean = false,
  val generateBusy: Boolean = false,
)

private fun formatStudyCountLabel(studyState: StudyUiState): String {
  if (studyState.statusLoading || studyState.countLoading || studyState.generateBusy) return "..."
  return (studyState.flashcardAdderCount ?: 0).toString()
}

private data class FeedPurchasePreviewPrice(
  val amountMicros: Long,
  val countryCode: String?,
  val source: String,
  val pricingTier: String,
  val tierMultiplierBps: Long,
)

private const val FEED_DEFAULT_BASE_PRICE_MICROS = 1_000_000L
private const val FEED_DEFAULT_BASE_MULTIPLIER_BPS = 10_000L
private const val FEED_T1_MULTIPLIER_BPS = 9_333L
private const val FEED_T2_MULTIPLIER_BPS = 7_500L
private const val FEED_T3_MULTIPLIER_BPS = 1_430L
private const val FEED_DEFAULT_VERIFIED_TIER = "T1"

private val feedTierByIso3: Map<String, String> =
  mapOf(
    "IND" to "T3",
    "NGA" to "T2",
  )

private fun resolveFeedPurchasePreviewPrice(nationality: String?): FeedPurchasePreviewPrice {
  val countryCode =
    nationality
      ?.trim()
      ?.uppercase()
      ?.takeIf { it.length == 3 && it.all { ch -> ch in 'A'..'Z' } }
  if (countryCode == null) {
    return FeedPurchasePreviewPrice(
      amountMicros = FEED_DEFAULT_BASE_PRICE_MICROS,
      countryCode = null,
      source = "default_base",
      pricingTier = "T0",
      tierMultiplierBps = FEED_DEFAULT_BASE_MULTIPLIER_BPS,
    )
  }
  val pricingTier = feedTierByIso3[countryCode] ?: FEED_DEFAULT_VERIFIED_TIER
  val multiplier =
    when (pricingTier) {
      "T1" -> FEED_T1_MULTIPLIER_BPS
      "T2" -> FEED_T2_MULTIPLIER_BPS
      "T3" -> FEED_T3_MULTIPLIER_BPS
      else -> FEED_DEFAULT_BASE_MULTIPLIER_BPS
    }
  val scaled = (FEED_DEFAULT_BASE_PRICE_MICROS * multiplier) / 10_000L
  return FeedPurchasePreviewPrice(
    amountMicros = maxOf(1L, scaled),
    countryCode = countryCode,
    source = "tier_bucket",
    pricingTier = pricingTier,
    tierMultiplierBps = multiplier,
  )
}

private fun formatUsdFromMicros(amountMicros: Long): String {
  val dollars = amountMicros.toDouble() / 1_000_000.0
  return "$" + String.format(Locale.US, "%.2f", dollars)
}

private fun buildFeedMediaTag(
  postId: String,
  loadSeq: Long,
): String = "$postId#$loadSeq"

private fun feedDebug(message: String) {
  Log.d("HomeFeedDebug", message)
}

private fun playbackStateName(state: Int): String {
  return when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "UNKNOWN($state)"
  }
}

private fun shouldShowTranslateCaption(
  post: FeedPostResolved,
  viewerLocaleTag: String,
): Boolean {
  if (post.captionText.isBlank()) return false
  if (!post.translationText.isNullOrBlank()) return false
  val viewerLanguage = normalizeLocaleToLanguage(viewerLocaleTag)
  val captionLanguage = normalizeLocaleToLanguage(post.captionLanguage ?: post.translationSourceLanguage)
  if (viewerLanguage.isNotBlank() && captionLanguage.isNotBlank() && viewerLanguage == captionLanguage) return false
  return true
}

private fun normalizeLocaleToLanguage(raw: String?): String {
  val cleaned = raw?.trim().orEmpty().replace('_', '-')
  if (cleaned.isBlank()) return ""
  return cleaned.substringBefore('-').lowercase(Locale.ROOT)
}
