package sc.pirate.app.post

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import com.adamglin.phosphoricons.regular.MusicNote
import com.adamglin.phosphoricons.regular.Plus
import com.adamglin.phosphoricons.regular.Scissors
import com.adamglin.phosphoricons.regular.SlidersHorizontal
import com.adamglin.phosphoricons.regular.Tag
import com.adamglin.phosphoricons.regular.X
import sc.pirate.app.songpicker.DefaultSongPickerRepository
import sc.pirate.app.songpicker.SongPickerSheet
import sc.pirate.app.songpicker.SongPickerSong
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoPasskeyManager
import sc.pirate.app.ui.PirateIconButton
import sc.pirate.app.ui.PirateOutlinedButton
import sc.pirate.app.ui.PiratePrimaryButton
import coil.compose.AsyncImage
import java.text.NumberFormat
import java.util.Currency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PreviewStage {
  EDIT,
  DETAILS,
}

private const val MAX_POST_TAGGED_ITEMS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostPreviewScreen(
  videoUri: Uri,
  initialSong: SongPickerSong?,
  ownerAddress: String,
  tempoAccount: TempoPasskeyManager.PasskeyAccount?,
  onBack: () -> Unit,
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val tag = "PostPreviewScreen"
  val context = LocalContext.current
  val hostActivity = context as? FragmentActivity
  val scope = rememberCoroutineScope()

  var stage by remember { mutableStateOf(PreviewStage.EDIT) }
  var videoDurationMs by remember { mutableStateOf<Long?>(null) }
  var previewAtMs by remember { mutableStateOf(1_000L) }
  var selectedSong by remember { mutableStateOf(initialSong) }
  var showSongPicker by remember { mutableStateOf(false) }
  var showTrimSheet by remember { mutableStateOf(false) }
  var showAudioSheet by remember { mutableStateOf(false) }
  var showFramePicker by remember { mutableStateOf(false) }
  var posting by remember { mutableStateOf(false) }
  var captionText by remember { mutableStateOf("") }
  var taggedItemsInput by remember { mutableStateOf("") }
  var taggedItems by remember { mutableStateOf<List<PostTaggedItem>>(emptyList()) }
  var taggedItemsResolving by remember { mutableStateOf(false) }
  var taggedItemsStatusText by remember { mutableStateOf<String?>(null) }
  var taggedItemsStatusError by remember { mutableStateOf(false) }
  var previewPlayer by remember { mutableStateOf<Player?>(null) }
  var previewSongPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
  var includeOriginalAudio by remember(initialSong?.trackId) { mutableStateOf(initialSong == null) }
  var originalAudioVolume by remember { mutableStateOf(1f) }
  var addedSoundVolume by remember { mutableStateOf(1f) }
  var songOffsetMs by remember { mutableStateOf(0L) }
  var restartPreviewFromBeginning by remember { mutableStateOf(false) }
  var previewVideoReady by remember { mutableStateOf(false) }
  var previewSongReady by remember { mutableStateOf(false) }

  LaunchedEffect(ownerAddress) {
    DefaultSongPickerRepository.preloadSuggestedSongs(
      context = context,
      ownerAddress = ownerAddress,
      maxEntries = 24,
    )
  }

  LaunchedEffect(videoUri) {
    val durationMs = withContext(Dispatchers.IO) { readVideoDurationMs(context = context, uri = videoUri) }
    videoDurationMs = durationMs
    val maxPreviewMs = (durationMs ?: 30_000L).coerceAtLeast(1_000L)
    previewAtMs = previewAtMs.coerceIn(0L, maxPreviewMs)
  }

  LaunchedEffect(selectedSong?.trackId, selectedSong?.durationSec, videoDurationMs) {
    if (selectedSong == null) {
      songOffsetMs = 0L
    } else {
      val maxTrimOffsetMs =
        resolveSoundTrimMaxOffsetMs(
          songDurationMs = resolveSoundTrimSongDurationMs(selectedSong),
          videoDurationMs = videoDurationMs,
        )
      songOffsetMs = songOffsetMs.coerceIn(0L, maxTrimOffsetMs)
    }
  }

  DisposableEffect(videoUri, posting) {
    if (posting) {
      previewPlayer = null
      previewVideoReady = false
      return@DisposableEffect onDispose {}
    }

    val player =
      runCatching {
        PostAudioMixService.buildPreviewPlayer(
          context = context,
          videoUri = videoUri,
          songTrackId = selectedSong?.trackId,
          config = PostAudioMixConfig(
            includeOriginalAudio = includeOriginalAudio,
            includeAddedSound = selectedSong != null,
            originalVolume = originalAudioVolume,
            addedVolume = addedSoundVolume,
            songOffsetMs = songOffsetMs,
            videoDurationMs = videoDurationMs,
          ),
        )
      }.getOrElse {
        Log.e(tag, "Unable to build preview audio mix", it)
        onShowMessage("Unable to preview current audio mix: ${it.message ?: "unknown"}")
        null
      }
    val playerListener =
      object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
          previewVideoReady = state == Player.STATE_READY
        }
      }
    previewVideoReady = player?.playbackState == Player.STATE_READY
    player?.addListener(playerListener)
    previewPlayer = player
    onDispose {
      player?.removeListener(playerListener)
      runCatching { player?.release() }
      if (previewPlayer === player) previewPlayer = null
      previewVideoReady = false
    }
  }

  DisposableEffect(
    selectedSong?.trackId,
    selectedSong?.durationSec,
    songOffsetMs,
    videoDurationMs,
    posting,
  ) {
    if (posting) {
      previewSongPlayer = null
      previewSongReady = false
      return@DisposableEffect onDispose {}
    }

    val clipDurationMs =
      selectedSong?.let { song ->
        resolveSoundTrimClipDurationMs(
          songDurationMs = resolveSoundTrimSongDurationMs(song),
          videoDurationMs = videoDurationMs,
        )
      }
    val songPlayer =
      if (selectedSong != null && clipDurationMs != null) {
        buildSongPreviewPlayer(
          context = context,
          songTrackId = selectedSong?.trackId,
          songOffsetMs = songOffsetMs,
          clipDurationMs = clipDurationMs,
        )
      } else {
        null
      }
    val songPlayerListener =
      object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
          previewSongReady = state == Player.STATE_READY
        }
      }
    previewSongReady = songPlayer?.playbackState == Player.STATE_READY
    songPlayer?.addListener(songPlayerListener)
    previewSongPlayer = songPlayer
    onDispose {
      songPlayer?.removeListener(songPlayerListener)
      runCatching { songPlayer?.release() }
      if (previewSongPlayer === songPlayer) previewSongPlayer = null
      previewSongReady = false
    }
  }

  LaunchedEffect(
    previewPlayer,
    previewSongPlayer,
    includeOriginalAudio,
    originalAudioVolume,
    addedSoundVolume,
  ) {
    val usesSeparateSongPreview = previewSongPlayer != null
    previewPlayer?.volume =
      if (usesSeparateSongPreview) {
        if (includeOriginalAudio) originalAudioVolume.coerceIn(0f, 1f) else 0f
      } else {
        1f
      }
    previewSongPlayer?.volume = addedSoundVolume.coerceIn(0f, 1f)
  }

  LaunchedEffect(
    previewPlayer,
    previewSongPlayer,
    stage,
    showSongPicker,
    showTrimSheet,
    showAudioSheet,
    posting,
    previewAtMs,
    restartPreviewFromBeginning,
    previewVideoReady,
    previewSongReady,
    selectedSong?.trackId,
  ) {
    val shouldAutoPlayPreview =
      stage == PreviewStage.EDIT &&
        !showSongPicker &&
        !showTrimSheet &&
        !showAudioSheet &&
        !posting
    val videoPlayer = previewPlayer
    val songPlayer = previewSongPlayer

    if (stage == PreviewStage.DETAILS) {
      val previewPositionMs = previewAtMs.coerceAtLeast(0L)
      videoPlayer?.seekTo(previewPositionMs)
      songPlayer?.seekTo(previewPositionMs)
      videoPlayer?.pause()
      songPlayer?.pause()
      return@LaunchedEffect
    }

    if (!shouldAutoPlayPreview) {
      videoPlayer?.pause()
      songPlayer?.pause()
      return@LaunchedEffect
    }

    if (videoPlayer == null) return@LaunchedEffect

    val requiresSongReady = selectedSong != null && songPlayer != null
    if (!previewVideoReady || (requiresSongReady && !previewSongReady)) {
      videoPlayer.pause()
      songPlayer?.pause()
      return@LaunchedEffect
    }

    val shouldRestart = restartPreviewFromBeginning
    if (shouldRestart) {
      videoPlayer.pause()
      songPlayer?.pause()
      videoPlayer.seekTo(0L)
      songPlayer?.seekTo(0L)
    }

    if (songPlayer != null) {
      if (shouldRestart || !songPlayer.isPlaying) {
        songPlayer.play()
        repeat(12) {
          if (songPlayer.isPlaying) return@repeat
          delay(16L)
        }
      }
      if (shouldRestart || !videoPlayer.isPlaying) {
        videoPlayer.play()
      }
    } else if (shouldRestart || !videoPlayer.isPlaying) {
      videoPlayer.play()
    }

    if (shouldRestart) {
      restartPreviewFromBeginning = false
    }
  }

  if (showFramePicker) {
    PostFramePickerScreen(
      videoUri = videoUri,
      initialPreviewAtMs = previewAtMs,
      initialDurationMs = videoDurationMs,
      onBack = { showFramePicker = false },
      onApply = { selectedMs, durationMs ->
        previewAtMs = selectedMs
        if (durationMs != null && durationMs > 0L) videoDurationMs = durationMs
        showFramePicker = false
      },
    )
    return
  }

  val songLabel = selectedSong?.let { "${it.title} - ${it.artist}" }

  fun resolveTaggedItems() {
    val pastedText = taggedItemsInput.trim()
    if (pastedText.isBlank()) {
      taggedItemsStatusText = "Paste a TheRealReal or Vestiaire link."
      taggedItemsStatusError = true
      return
    }
    if (ownerAddress.isBlank()) {
      taggedItemsStatusText = "Sign in to add shopping links."
      taggedItemsStatusError = true
      return
    }
    if (taggedItems.size >= MAX_POST_TAGGED_ITEMS) {
      taggedItemsStatusText = "You can add up to $MAX_POST_TAGGED_ITEMS items."
      taggedItemsStatusError = true
      return
    }

    taggedItemsResolving = true
    taggedItemsStatusText = null
    taggedItemsStatusError = false

    scope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          PostTaggedItemsApi.resolve(
            userAddress = ownerAddress,
            pastedText = pastedText,
          )
        }
      }.onSuccess { result ->
        val merged = mergeTaggedItems(existing = taggedItems, incoming = result.items, maxItems = MAX_POST_TAGGED_ITEMS)
        val addedCount = merged.size - taggedItems.size
        taggedItems = merged
        if (addedCount > 0) {
          taggedItemsInput = ""
          taggedItemsStatusText = null
          taggedItemsStatusError = false
        }
        if (addedCount == 0) {
          taggedItemsStatusText =
            when {
              taggedItems.size >= MAX_POST_TAGGED_ITEMS -> "You can add up to $MAX_POST_TAGGED_ITEMS items."
              result.items.isNotEmpty() -> "That item is already added."
              result.rejected.isNotEmpty() -> result.rejected.first().message.ifBlank { "That link could not be used." }
              else -> "That link could not be used."
            }
          taggedItemsStatusError = true
        }
      }.onFailure { error ->
        taggedItemsStatusText = error.message ?: "Failed to resolve tagged items"
        taggedItemsStatusError = true
      }
      taggedItemsResolving = false
    }
  }

  fun submitPost() {
    if (ownerAddress.isBlank()) {
      onShowMessage("Sign in to create a post")
      return
    }
    val activity = hostActivity
    if (activity == null) {
      onShowMessage("Unable to access host activity for signing")
      return
    }
    if (!PostTxRepository.isConfigured()) {
      onShowMessage("Feed contract is not configured in this build")
      return
    }
    if (tempoAccount == null) {
      onShowMessage("No account available")
      return
    }
    val song = selectedSong

    posting = true
    val activePreviewPlayer = previewPlayer
    val activeSongPreviewPlayer = previewSongPlayer
    previewPlayer = null
    previewSongPlayer = null
    runCatching { activePreviewPlayer?.release() }
    runCatching { activeSongPreviewPlayer?.release() }

    scope.launch {
      val mixedVideoFile = PostAudioMixService.createExportFile(context)
      val mixedVideoUri =
        runCatching {
          PostAudioMixService.exportMix(
            context = context,
            videoUri = videoUri,
            songTrackId = song?.trackId,
            config = PostAudioMixConfig(
              includeOriginalAudio = includeOriginalAudio,
              includeAddedSound = song != null,
              originalVolume = originalAudioVolume,
              addedVolume = addedSoundVolume,
              songOffsetMs = songOffsetMs,
              videoDurationMs = videoDurationMs,
            ),
            outputFile = mixedVideoFile,
          )
        }.getOrElse { error ->
          posting = false
          onShowMessage("Failed to prepare mixed video: ${error.message ?: "unknown error"}")
          runCatching { mixedVideoFile.delete() }
          return@launch
        }

      val loadedSession =
        SessionKeyManager.load(context)?.takeIf {
          SessionKeyManager.isValid(it, ownerAddress = tempoAccount.address) &&
            it.keyAuthorization?.isNotEmpty() == true
        }
      val result =
        PostTxRepository.createPost(
          context = context,
          activity = activity,
          account = tempoAccount,
          ownerAddress = ownerAddress,
          songTrackId = song?.trackId,
          songStoryIpId = song?.songStoryIpId,
          videoUri = mixedVideoUri,
          captionText = captionText,
          taggedItems = taggedItems,
          previewAtMs = previewAtMs,
          sessionKey = loadedSession,
        )
      runCatching { mixedVideoFile.delete() }

      posting = false
      if (result.success) {
        val txPreview = result.txHash?.take(10).orEmpty()
        val postPreview = result.postId?.take(10).orEmpty()
        onShowMessage("Post submitted tx=${txPreview}… post=${postPreview}…")
        onClose()
      } else {
        onShowMessage("Post failed: ${result.error ?: "unknown error"}")
      }
    }
  }

  when (stage) {
    PreviewStage.EDIT -> {
      PostPreviewEditStep(
        previewPlayer = previewPlayer,
        selectedSoundLabel = songLabel,
        posting = posting,
        canTrimSound = selectedSong != null,
        onBack = onBack,
        onSelectSound = { showSongPicker = true },
        onOpenTrim = { showTrimSheet = true },
        onOpenMix = { showAudioSheet = true },
        onNext = { stage = PreviewStage.DETAILS },
      )
    }

    PreviewStage.DETAILS -> {
      PostPreviewDetailsStep(
        previewPlayer = previewPlayer,
        captionText = captionText,
        taggedItemsInput = taggedItemsInput,
        taggedItems = taggedItems,
        taggedItemsResolving = taggedItemsResolving,
        taggedItemsStatusText = taggedItemsStatusText,
        taggedItemsStatusError = taggedItemsStatusError,
        posting = posting,
        postEnabled = tempoAccount != null,
        onBack = { stage = PreviewStage.EDIT },
        onEditCover = { showFramePicker = true },
        onCaptionChange = { next ->
          captionText = if (next.length <= 280) next else next.take(280)
        },
        onTaggedItemsInputChange = { taggedItemsInput = it },
        onAddTaggedItems = { resolveTaggedItems() },
        onRemoveTaggedItem = { item ->
          taggedItems = taggedItems.filterNot { existing -> taggedItemKey(existing) == taggedItemKey(item) }
          taggedItemsStatusText = null
          taggedItemsStatusError = false
        },
        onPost = { submitPost() },
      )
    }
  }

  if (showSongPicker) {
    SongPickerSheet(
      repository = DefaultSongPickerRepository,
      ownerAddress = ownerAddress,
      onSelectSong = {
        selectedSong = it
        showSongPicker = false
        restartPreviewFromBeginning = true
        showTrimSheet = true
      },
      onDismiss = { showSongPicker = false },
    )
  }

  if (showTrimSheet) {
    val song = selectedSong
    if (song != null) {
      PostSoundTrimSheet(
        selectedSong = song,
        videoDurationMs = videoDurationMs,
        songOffsetMs = songOffsetMs,
        onDismiss = {
          restartPreviewFromBeginning = true
          showTrimSheet = false
        },
        onSongOffsetChange = { nextOffsetMs -> songOffsetMs = nextOffsetMs },
      )
    } else {
      showTrimSheet = false
    }
  }

  if (showAudioSheet) {
    PostAudioMixSheet(
      selectedSongLabel = songLabel,
      includeOriginalAudio = includeOriginalAudio,
      originalAudioVolume = originalAudioVolume,
      addedSoundVolume = addedSoundVolume,
      onDismiss = { showAudioSheet = false },
      onIncludeOriginalAudioChange = { includeOriginalAudio = it },
      onOriginalAudioVolumeChange = { originalAudioVolume = it.coerceIn(0f, 1f) },
      onAddedSoundVolumeChange = { addedSoundVolume = it.coerceIn(0f, 1f) },
      onAddSound = {
        showAudioSheet = false
        showSongPicker = true
      },
    )
  }
}

@Composable
private fun PostPreviewEditStep(
  previewPlayer: Player?,
  selectedSoundLabel: String?,
  posting: Boolean,
  canTrimSound: Boolean,
  onBack: () -> Unit,
  onSelectSound: () -> Unit,
  onOpenTrim: () -> Unit,
  onOpenMix: () -> Unit,
  onNext: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(Color.Black),
  ) {
    PostPreviewPlayer(
      previewPlayer = previewPlayer,
      modifier = Modifier.fillMaxSize(),
      resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors =
                listOf(
                  Color.Black.copy(alpha = 0.34f),
                  Color.Transparent,
                  Color.Black.copy(alpha = 0.7f),
                ),
            ),
          ),
    )

    Row(
      modifier =
        Modifier
          .align(Alignment.TopStart)
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PirateIconButton(
        onClick = onBack,
        modifier =
          Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.34f)),
      ) {
        Icon(
          imageVector = PhosphorIcons.Regular.ArrowLeft,
          contentDescription = "Back",
          tint = Color.White,
        )
      }

      PreviewTopActionButton(
        label = "Next",
        enabled = !posting,
        onClick = onNext,
      )
    }

    if (!selectedSoundLabel.isNullOrBlank()) {
      Column(
        modifier =
          Modifier
            .align(Alignment.BottomStart)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 92.dp, bottom = 16.dp),
      ) {
        Text(
          text = selectedSoundLabel,
          color = Color.White.copy(alpha = 0.76f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth(),
          style =
            MaterialTheme.typography.bodySmall.copy(
              fontSize = 16.sp,
              shadow = androidx.compose.ui.graphics.Shadow(
                color = Color.Black.copy(alpha = 0.65f),
                offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                blurRadius = 3f,
              ),
            ),
        )
      }
    }

    Column(
      modifier =
        Modifier
          .align(Alignment.CenterEnd)
          .padding(end = 18.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      PreviewRailTool(
        icon = PhosphorIcons.Regular.MusicNote,
        label = "Sound",
        enabled = !posting,
        onClick = onSelectSound,
      )
      PreviewRailTool(
        icon = PhosphorIcons.Regular.Scissors,
        label = "Trim",
        enabled = !posting && canTrimSound,
        onClick = onOpenTrim,
      )
      PreviewRailTool(
        icon = PhosphorIcons.Regular.SlidersHorizontal,
        label = "Mix",
        enabled = !posting,
        onClick = onOpenMix,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostPreviewDetailsStep(
  previewPlayer: Player?,
  captionText: String,
  taggedItemsInput: String,
  taggedItems: List<PostTaggedItem>,
  taggedItemsResolving: Boolean,
  taggedItemsStatusText: String?,
  taggedItemsStatusError: Boolean,
  posting: Boolean,
  postEnabled: Boolean,
  onBack: () -> Unit,
  onEditCover: () -> Unit,
  onCaptionChange: (String) -> Unit,
  onTaggedItemsInputChange: (String) -> Unit,
  onAddTaggedItems: () -> Unit,
  onRemoveTaggedItem: (PostTaggedItem) -> Unit,
  onPost: () -> Unit,
) {
  val focusManager = LocalFocusManager.current

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("New Post") },
        navigationIcon = {
          PirateIconButton(onClick = onBack) {
            Icon(
              imageVector = PhosphorIcons.Regular.ArrowLeft,
              contentDescription = "Back to editor",
            )
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
          ),
      )
    },
    bottomBar = {
      Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
        color = Color(0xFF111214),
        shadowElevation = 8.dp,
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
          PiratePrimaryButton(
            text = "Post",
            modifier = Modifier.fillMaxWidth(),
            enabled = !posting && !taggedItemsResolving && postEnabled,
            loading = posting,
            onClick = onPost,
          )
        }
      }
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .background(Color.Black)
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 12.dp)
          .imePadding(),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color(0xFF111214),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            modifier =
              Modifier
                .width(108.dp)
                .aspectRatio(9f / 16f)
                .clip(MaterialTheme.shapes.large)
                .background(Color.Black),
          ) {
            PostPreviewPlayer(
              previewPlayer = previewPlayer,
              modifier = Modifier.fillMaxSize(),
              resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
            )
          }

          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = "Thumbnail",
              color = Color.White,
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            PirateOutlinedButton(
              onClick = {
                focusManager.clearFocus(force = true)
                onEditCover()
              },
              modifier = Modifier.fillMaxWidth(),
              enabled = !posting,
            ) {
              Text("Choose Thumbnail")
            }
          }
        }
      }

      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color(0xFF111214),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Caption",
              color = Color.White,
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
              text = "${captionText.length}/280",
              color = Color.White.copy(alpha = 0.78f),
              style = MaterialTheme.typography.labelMedium,
            )
          }
          OutlinedTextField(
            value = captionText,
            onValueChange = onCaptionChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !posting,
            placeholder = { Text("Add a caption") },
            minLines = 3,
            maxLines = 5,
          )
        }
      }

      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color(0xFF111214),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Items in video",
              color = Color.White,
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
              text = "${taggedItems.size}/$MAX_POST_TAGGED_ITEMS",
              color = Color.White.copy(alpha = 0.78f),
              style = MaterialTheme.typography.labelMedium,
            )
          }
          ItemLinkInputRow(
            value = taggedItemsInput,
            placeholder = "Paste a TheRealReal or Vestiaire link",
            enabled = !posting && !taggedItemsResolving && taggedItems.size < MAX_POST_TAGGED_ITEMS,
            onValueChange = onTaggedItemsInputChange,
            onAdd = {
              focusManager.clearFocus(force = true)
              onAddTaggedItems()
            },
          )
          taggedItemsStatusText?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
              text = message,
              color = if (taggedItemsStatusError) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.74f),
              style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            )
          }
          if (taggedItems.isNotEmpty()) {
            Column(
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              taggedItems.forEach { item ->
                TaggedItemCard(
                  item = item,
                  enabled = !posting,
                  onRemove = { onRemoveTaggedItem(item) },
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ItemLinkInputRow(
  value: String,
  placeholder: String,
  enabled: Boolean,
  onValueChange: (String) -> Unit,
  onAdd: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.weight(1f),
      enabled = enabled,
      placeholder = {
        Text(
          text = placeholder,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      },
      singleLine = true,
      maxLines = 1,
    )
    PirateIconButton(
      onClick = onAdd,
      enabled = enabled,
      modifier =
        Modifier
          .size(42.dp)
          .clip(CircleShape)
          .background(Color.White.copy(alpha = if (enabled) 0.08f else 0.04f)),
    ) {
      Icon(
        imageVector = PhosphorIcons.Regular.Plus,
        contentDescription = "Add item link",
        tint = Color.White.copy(alpha = if (enabled) 0.96f else 0.42f),
      )
    }
  }
}

@Composable
private fun TaggedItemCard(
  item: PostTaggedItem,
  enabled: Boolean,
  onRemove: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
    color = Color.White.copy(alpha = 0.04f),
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(64.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
      ) {
        val imageModel = item.imageUrl ?: item.images.firstOrNull()
        if (!imageModel.isNullOrBlank()) {
          AsyncImage(
            model = imageModel,
            contentDescription = item.title,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Icon(
            imageVector = PhosphorIcons.Regular.Tag,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.72f),
          )
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          text = item.title,
          color = Color.White,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        val detailLine = listOfNotNull(item.brand, item.sizeLabel(), merchantLabel(item.merchant)).joinToString(" • ")
        if (detailLine.isNotBlank()) {
          Text(
            text = detailLine,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        Text(
          text = formatTaggedItemPrice(item),
          color = Color.White.copy(alpha = 0.9f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        )
      }

      PirateIconButton(
        onClick = onRemove,
        enabled = enabled,
        modifier =
          Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f)),
      ) {
        Icon(
          imageVector = PhosphorIcons.Regular.X,
          contentDescription = "Remove tagged item",
          tint = Color.White.copy(alpha = if (enabled) 0.92f else 0.44f),
        )
      }
    }
  }
}

private fun mergeTaggedItems(
  existing: List<PostTaggedItem>,
  incoming: List<PostTaggedItem>,
  maxItems: Int,
): List<PostTaggedItem> {
  val merged = LinkedHashMap<String, PostTaggedItem>(maxItems)
  existing.forEach { item ->
    if (merged.size < maxItems) {
      merged.putIfAbsent(taggedItemKey(item), item)
    }
  }
  incoming.forEach { item ->
    if (merged.size >= maxItems) return@forEach
    merged.putIfAbsent(taggedItemKey(item), item)
  }
  return merged.values.toList()
}

private fun taggedItemKey(item: PostTaggedItem): String =
  sequenceOf(item.canonicalUrl, item.normalizedUrl, item.requestedUrl, item.title)
    .map { it.trim().lowercase() }
    .firstOrNull { it.isNotBlank() }
    .orEmpty()

private fun PostTaggedItem.sizeLabel(): String? {
  val normalizedSize = size?.trim().orEmpty()
  if (normalizedSize.isBlank()) return null
  val normalizedSizeSystem = sizeSystem?.trim().orEmpty()
  return if (normalizedSizeSystem.isBlank()) normalizedSize else "$normalizedSize $normalizedSizeSystem"
}

private fun merchantLabel(raw: String): String =
  when (raw.trim().lowercase()) {
    "vestiaire" -> "Vestiaire"
    "therealreal" -> "The RealReal"
    else -> raw.trim().ifBlank { "Item" }
  }

private fun formatTaggedItemPrice(item: PostTaggedItem): String {
  val amount = item.price
  if (amount == null) return item.condition ?: item.category ?: "Price unavailable"
  return runCatching {
    val formatter = NumberFormat.getCurrencyInstance()
    item.currency?.trim()?.takeIf { it.isNotBlank() }?.let { formatter.currency = Currency.getInstance(it) }
    formatter.format(amount)
  }.getOrElse {
    item.currency?.trim()?.takeIf { it.isNotBlank() }?.let { "$it $amount" } ?: amount.toString()
  }
}

@Composable
private fun PreviewTopActionButton(
  label: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    modifier =
      Modifier.clickable(
        enabled = enabled,
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
      ),
    shape = MaterialTheme.shapes.medium,
    color = if (enabled) Color.White else Color.White.copy(alpha = 0.42f),
  ) {
    Text(
      text = label,
      color = Color.Black.copy(alpha = if (enabled) 1f else 0.55f),
      style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
      modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
    )
  }
}

@Composable
private fun PreviewRailTool(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Column(
    modifier = Modifier.width(58.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    PirateIconButton(
      onClick = onClick,
      enabled = enabled,
      modifier =
        Modifier
          .size(48.dp)
          .clip(CircleShape)
          .background(Color.Black.copy(alpha = if (enabled) 0.36f else 0.2f)),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        modifier = Modifier.size(34.dp),
        tint = Color.White.copy(alpha = if (enabled) 1f else 0.46f),
      )
    }
    Text(
      text = label,
      color = Color.White.copy(alpha = if (enabled) 0.88f else 0.46f),
      style = MaterialTheme.typography.labelSmall,
    )
  }
}

@Composable
private fun PostPreviewPlayer(
  previewPlayer: Player?,
  modifier: Modifier,
  resizeMode: Int,
) {
  if (previewPlayer == null) {
    Box(modifier = modifier.background(Color.Black))
    return
  }

  AndroidView(
    modifier = modifier,
    factory = { ctx ->
      PlayerView(ctx).apply {
        useController = false
        this.resizeMode = resizeMode
        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
      }
    },
    update = { view -> view.player = previewPlayer },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostSoundTrimSheet(
  selectedSong: SongPickerSong,
  videoDurationMs: Long?,
  songOffsetMs: Long,
  onDismiss: () -> Unit,
  onSongOffsetChange: (Long) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
  val songDurationMs = remember(selectedSong.trackId, selectedSong.durationSec) { resolveSoundTrimSongDurationMs(selectedSong) }
  val clipDurationMs = remember(songDurationMs, videoDurationMs) { resolveSoundTrimClipDurationMs(songDurationMs = songDurationMs, videoDurationMs = videoDurationMs) }
  val maxOffsetMs = remember(songDurationMs, clipDurationMs) { resolveSoundTrimMaxOffsetMs(songDurationMs = songDurationMs, clipDurationMs = clipDurationMs) }
  var draftSongOffsetMs by remember(selectedSong.trackId, songOffsetMs, maxOffsetMs) {
    mutableStateOf(songOffsetMs.coerceIn(0L, maxOffsetMs))
  }

  LaunchedEffect(sheetState) {
    runCatching { sheetState.partialExpand() }
  }

  ModalBottomSheet(
    onDismissRequest = {
      onSongOffsetChange(draftSongOffsetMs.coerceIn(0L, maxOffsetMs))
      onDismiss()
    },
    sheetState = sheetState,
    containerColor = Color(0xFF111214),
    contentColor = Color.White,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .heightIn(max = 116.dp)
          .navigationBarsPadding()
          .padding(horizontal = 16.dp, vertical = 2.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Trim",
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
          text = "${formatTrimTimestamp(draftSongOffsetMs)} - ${formatTrimTimestamp((draftSongOffsetMs + clipDurationMs).coerceAtMost(songDurationMs))}",
          color = Color.White.copy(alpha = 0.84f),
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
      }

      SoundTrimTimeline(
        songDurationMs = songDurationMs,
        clipDurationMs = clipDurationMs,
        songOffsetMs = draftSongOffsetMs,
        onSongOffsetChange = { nextOffsetMs ->
          draftSongOffsetMs = nextOffsetMs.coerceIn(0L, maxOffsetMs)
        },
        onSongOffsetCommit = { nextOffsetMs ->
          val committedOffsetMs = nextOffsetMs.coerceIn(0L, maxOffsetMs)
          draftSongOffsetMs = committedOffsetMs
          if (committedOffsetMs != songOffsetMs) onSongOffsetChange(committedOffsetMs)
        },
      )
    }
  }
}

@Composable
private fun SoundTrimTimeline(
  songDurationMs: Long,
  clipDurationMs: Long,
  songOffsetMs: Long,
  onSongOffsetChange: (Long) -> Unit,
  onSongOffsetCommit: (Long) -> Unit,
) {
  val safeSongDurationMs = songDurationMs.coerceAtLeast(1_000L)
  val safeClipDurationMs = clipDurationMs.coerceIn(1_000L, safeSongDurationMs)
  val maxOffsetMs = resolveSoundTrimMaxOffsetMs(songDurationMs = safeSongDurationMs, clipDurationMs = safeClipDurationMs)
  val normalizedOffsetMs = songOffsetMs.coerceIn(0L, maxOffsetMs)

  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = Color.White.copy(alpha = 0.05f),
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      BoxWithConstraints(
        modifier =
          Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp, max = 32.dp)
            .clip(MaterialTheme.shapes.small)
            .background(Color(0xFF16181C))
            .pointerInput(safeSongDurationMs, safeClipDurationMs, maxOffsetMs) {
              detectTapGestures { pressOffset ->
                if (maxOffsetMs == 0L) return@detectTapGestures
                val widthPx = size.width.toFloat().coerceAtLeast(1f)
                val centeredMs = ((pressOffset.x / widthPx).coerceIn(0f, 1f) * safeSongDurationMs.toFloat()).toLong()
                val nextOffsetMs = (centeredMs - (safeClipDurationMs / 2L)).coerceIn(0L, maxOffsetMs)
                onSongOffsetChange(nextOffsetMs)
                onSongOffsetCommit(nextOffsetMs)
              }
            }
            .pointerInput(safeSongDurationMs, safeClipDurationMs, maxOffsetMs) {
              var dragOffsetMs = normalizedOffsetMs
              detectHorizontalDragGestures(
                onDragStart = { dragOffsetMs = normalizedOffsetMs },
                onDragEnd = { onSongOffsetCommit(dragOffsetMs) },
                onDragCancel = { onSongOffsetCommit(dragOffsetMs) },
              ) { change, _ ->
                if (maxOffsetMs == 0L) return@detectHorizontalDragGestures
                val widthPx = size.width.toFloat().coerceAtLeast(1f)
                val centeredMs = ((change.position.x / widthPx).coerceIn(0f, 1f) * safeSongDurationMs.toFloat()).toLong()
                val nextOffsetMs = (centeredMs - (safeClipDurationMs / 2L)).coerceIn(0L, maxOffsetMs)
                dragOffsetMs = nextOffsetMs
                onSongOffsetChange(nextOffsetMs)
                change.consume()
              }
            },
      ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 6.dp)) {
          val totalWidth = size.width.coerceAtLeast(1f)
          val totalHeight = size.height.coerceAtLeast(1f)
          val barCount = 42
          val gap = totalWidth / (barCount * 4.5f)
          val barWidth = ((totalWidth - (gap * (barCount - 1))) / barCount).coerceAtLeast(4f)
          val actualWindowFraction = (safeClipDurationMs.toFloat() / safeSongDurationMs.toFloat()).coerceIn(0.02f, 1f)
          val centerFraction =
            ((normalizedOffsetMs + (safeClipDurationMs / 2L)).toFloat() / safeSongDurationMs.toFloat()).coerceIn(0f, 1f)
          val visibleWindowWidth = (totalWidth * actualWindowFraction).coerceAtLeast(52.dp.toPx()).coerceAtMost(totalWidth)
          val windowLeft = ((centerFraction * totalWidth) - (visibleWindowWidth / 2f)).coerceIn(0f, totalWidth - visibleWindowWidth)
          val windowRight = windowLeft + visibleWindowWidth

          drawRoundRect(
            color = Color.Black.copy(alpha = 0.28f),
            size = size,
          )

          repeat(barCount) { index ->
            val variation = ((index * 37) % 100) / 100f
            val barHeight = totalHeight * (0.22f + (variation * 0.56f))
            val left = index * (barWidth + gap)
            val top = (totalHeight - barHeight) / 2f
            val isInsideWindow = (left + barWidth) >= windowLeft && left <= windowRight
            drawRoundRect(
              color = if (isInsideWindow) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.22f),
              topLeft = androidx.compose.ui.geometry.Offset(left, top),
              size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
              cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f),
            )
          }

          drawRoundRect(
            color = Color(0xFFE9435A),
            topLeft = androidx.compose.ui.geometry.Offset(windowLeft, 0f),
            size = androidx.compose.ui.geometry.Size(visibleWindowWidth, totalHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx(), 10.dp.toPx()),
            style = Stroke(width = 2.dp.toPx()),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "0:00",
          color = Color.White.copy(alpha = 0.6f),
          style = MaterialTheme.typography.labelMedium,
        )
        Text(
          text = formatTrimTimestamp(safeSongDurationMs),
          color = Color.White.copy(alpha = 0.6f),
          style = MaterialTheme.typography.labelMedium,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostAudioMixSheet(
  selectedSongLabel: String?,
  includeOriginalAudio: Boolean,
  originalAudioVolume: Float,
  addedSoundVolume: Float,
  onDismiss: () -> Unit,
  onIncludeOriginalAudioChange: (Boolean) -> Unit,
  onOriginalAudioVolumeChange: (Float) -> Unit,
  onAddedSoundVolumeChange: (Float) -> Unit,
  onAddSound: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = Color(0xFF111214),
    contentColor = Color.White,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      Text(
        text = "Audio Mix",
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
      )
      Text(
        text = "Choose how much original sound and added sound you want in the final post.",
        color = Color.White.copy(alpha = 0.72f),
        style = MaterialTheme.typography.bodyMedium,
      )

      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color.White.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(18.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                text = "Original sound",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
              )
              Text(
                text = if (includeOriginalAudio) "${(originalAudioVolume * 100f).toInt()}% volume" else "Muted",
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.labelLarge,
              )
            }
            Switch(
              checked = includeOriginalAudio,
              onCheckedChange = onIncludeOriginalAudioChange,
            )
          }
          if (includeOriginalAudio) {
            Slider(
              value = originalAudioVolume,
              onValueChange = onOriginalAudioVolumeChange,
              valueRange = 0f..1f,
            )
          }
        }
      }

      if (selectedSongLabel != null) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.large,
          color = Color.White.copy(alpha = 0.05f),
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text = "Added sound",
                  style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                  text = selectedSongLabel,
                  color = Color.White.copy(alpha = 0.72f),
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                  style = MaterialTheme.typography.bodyMedium,
                )
              }
            }

            Text(
              text = "Volume ${(addedSoundVolume * 100f).toInt()}%",
              color = Color.White.copy(alpha = 0.72f),
              style = MaterialTheme.typography.labelLarge,
            )
            Slider(
              value = addedSoundVolume,
              onValueChange = onAddedSoundVolumeChange,
              valueRange = 0f..1f,
            )
          }
        }
      } else {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.large,
          color = Color.White.copy(alpha = 0.05f),
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = "No added sound",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
              text = "Your upload will use its original audio unless you add a track.",
              color = Color.White.copy(alpha = 0.72f),
              style = MaterialTheme.typography.bodyMedium,
            )
            PirateOutlinedButton(
              onClick = onAddSound,
            ) {
              Icon(
                imageVector = PhosphorIcons.Regular.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Add Sound")
            }
          }
        }
      }

    }
  }
}

private fun buildSongPreviewPlayer(
  context: android.content.Context,
  songTrackId: String?,
  songOffsetMs: Long,
  clipDurationMs: Long,
): ExoPlayer? {
  val previewUrl = resolveSongPreviewUrl(songTrackId) ?: return null
  val songStartMs = songOffsetMs.coerceAtLeast(0L)
  val safeClipDurationMs = clipDurationMs.coerceAtLeast(1_000L)
  val mediaItem =
    MediaItem.Builder()
      .setUri(previewUrl)
      .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(songStartMs)
          .setEndPositionMs(songStartMs + safeClipDurationMs)
          .build(),
      )
      .build()
  return ExoPlayer.Builder(context).build().apply {
    repeatMode = Player.REPEAT_MODE_ONE
    setMediaItem(mediaItem)
    prepare()
    playWhenReady = false
  }
}

private fun resolveSoundTrimSongDurationMs(song: SongPickerSong?): Long {
  val durationMs = song?.durationSec?.takeIf { it > 0 }?.toLong()?.times(1000L)
  // Post remixing currently uses the published preview asset, not the full song file.
  return (durationMs ?: 30_000L).coerceIn(1_000L, 30_000L)
}

private fun resolveSoundTrimClipDurationMs(
  songDurationMs: Long,
  videoDurationMs: Long?,
): Long {
  val safeSongDurationMs = songDurationMs.coerceAtLeast(1_000L)
  val safeVideoDurationMs = (videoDurationMs ?: 10_000L).coerceAtLeast(1_000L)
  return safeVideoDurationMs.coerceAtMost(safeSongDurationMs)
}

private fun resolveSoundTrimMaxOffsetMs(
  songDurationMs: Long,
  videoDurationMs: Long?,
): Long {
  val clipDurationMs = resolveSoundTrimClipDurationMs(songDurationMs = songDurationMs, videoDurationMs = videoDurationMs)
  return resolveSoundTrimMaxOffsetMs(songDurationMs = songDurationMs, clipDurationMs = clipDurationMs)
}

private fun resolveSoundTrimMaxOffsetMs(
  songDurationMs: Long,
  clipDurationMs: Long,
): Long = (songDurationMs.coerceAtLeast(1_000L) - clipDurationMs.coerceAtLeast(1_000L)).coerceAtLeast(0L)

private fun formatTrimTimestamp(ms: Long): String {
  val totalSeconds = (ms.coerceAtLeast(0L) / 1000L).toInt()
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return "%d:%02d".format(minutes, seconds)
}
