package sc.pirate.app.post

import android.content.ContentValues
import android.content.Context
import android.content.ActivityNotFoundException
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import sc.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import sc.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.ArrowLeft
import sc.pirate.app.BuildConfig
import sc.pirate.app.auth.PirateAuthUiState
import sc.pirate.app.identity.SelfVerificationGate
import sc.pirate.app.songpicker.DefaultSongPickerRepository
import sc.pirate.app.songpicker.SongPickerSheet
import sc.pirate.app.songpicker.SongPickerSong
import sc.pirate.app.ui.PiratePrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostComposerScreen(
  authState: PirateAuthUiState,
  isAuthenticated: Boolean,
  ownerAddress: String?,
  onSelfVerifiedChange: (Boolean) -> Unit = {},
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  if (!isAuthenticated || ownerAddress.isNullOrBlank()) {
    LaunchedEffect(Unit) {
      onShowMessage("Sign in to create a post")
      onClose()
    }
    return
  }

  SelfVerificationGate(
    userAddress = ownerAddress,
    cachedVerified = authState.selfVerified,
    onVerified = { onSelfVerifiedChange(true) },
  ) {
    PostComposerFormContent(
      ownerAddress = ownerAddress,
      onClose = onClose,
      onShowMessage = onShowMessage,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostComposerFormContent(
  ownerAddress: String,
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
  var pendingRecordedUri by remember { mutableStateOf<Uri?>(null) }
  var videoDurationMs by remember { mutableStateOf<Long?>(null) }
  var previewAtMs by remember { mutableStateOf(1_000L) }
  var selectedSong by remember { mutableStateOf<SongPickerSong?>(null) }
  var showSongPicker by remember { mutableStateOf(false) }
  var showFramePicker by remember { mutableStateOf(false) }
  var captionText by remember { mutableStateOf("") }
  var posting by remember { mutableStateOf(false) }
  var previewPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
  var previewPlaying by remember { mutableStateOf(false) }

  LaunchedEffect(ownerAddress) {
    DefaultSongPickerRepository.preloadSuggestedSongs(
      context = context,
      ownerAddress = ownerAddress,
      maxEntries = 24,
    )
  }

  val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) {
      selectedVideoUri = uri
      previewAtMs = 1_000L
    }
  }
  val videoRecorder = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
    val uri = pendingRecordedUri
    pendingRecordedUri = null
    if (ok && uri != null) {
      selectedVideoUri = uri
      previewAtMs = 1_000L
      return@rememberLauncherForActivityResult
    }
    if (uri != null) deleteUriQuietly(context = context, uri = uri)
  }

  LaunchedEffect(selectedVideoUri) {
    val uri = selectedVideoUri
    if (uri == null) {
      videoDurationMs = null
      previewAtMs = 1_000L
      return@LaunchedEffect
    }
    val durationMs = withContext(Dispatchers.IO) { readVideoDurationMs(context = context, uri = uri) }
    videoDurationMs = durationMs
    val maxPreviewMs = (durationMs ?: 30_000L).coerceAtLeast(1_000L)
    previewAtMs = previewAtMs.coerceIn(0L, maxPreviewMs)
  }

  DisposableEffect(selectedVideoUri) {
    val uri = selectedVideoUri
    if (uri == null) {
      previewPlaying = false
      onDispose { }
    } else {
      val exo =
        ExoPlayer.Builder(context).build().apply {
          setMediaItem(MediaItem.fromUri(uri))
          repeatMode = Player.REPEAT_MODE_ONE
          playWhenReady = true
          addListener(
            object : Player.Listener {
              override fun onIsPlayingChanged(isPlaying: Boolean) {
                previewPlaying = isPlaying
              }
            },
          )
          prepare()
        }
      previewPlayer = exo
      onDispose {
        runCatching { exo.release() }
        if (previewPlayer === exo) previewPlayer = null
      }
    }
  }

  if (showFramePicker) {
    val video = selectedVideoUri
    if (video == null) {
      showFramePicker = false
    } else {
      PostFramePickerScreen(
        videoUri = video,
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
  }

  val songLabel =
    selectedSong?.let { "${it.title} - ${it.artist}" } ?: "Original audio"

  fun submitRequestedPosts(postCount: Int) {
    if (!PostTxRepository.isConfigured()) {
      onShowMessage("Feed contract is not configured in this build")
      return
    }
    val video = selectedVideoUri
    val song = selectedSong
    if (video == null) {
      onShowMessage("Select or record a video first")
      return
    }

    val safeCount = postCount.coerceAtLeast(1)
    posting = true
    scope.launch {
      val results =
        submitPostBatch(
          count = safeCount,
          context = context,
          ownerAddress = ownerAddress,
          song = song,
          videoUri = video,
          basePreviewAtMs = previewAtMs,
          videoDurationMs = videoDurationMs,
          captionText = captionText,
        )

      posting = false
      if (results.isEmpty()) {
        onShowMessage("Post failed: unknown error")
        return@launch
      }

      val firstFailure = results.firstOrNull { !it.success }
      if (firstFailure != null) {
        val successCount = results.count { it.success }
        val error = firstFailure.error ?: "unknown error"
        if (successCount == 0) {
          onShowMessage("Post failed: $error")
        } else {
          onShowMessage("Posted $successCount/$safeCount. Next failed: $error")
        }
        return@launch
      }

      if (safeCount == 1) {
        val result = results.first()
        val txPreview = result.txHash?.take(10).orEmpty()
        val postPreview = result.postId?.take(10).orEmpty()
        onShowMessage("Post submitted tx=${txPreview}… post=${postPreview}…")
      } else {
        val postIds =
          results.mapIndexed { index, result ->
            "#${index + 1} ${result.postId?.take(10).orEmpty()}…"
          }.joinToString(" ")
        onShowMessage("${results.size} posts submitted $postIds")
      }
      onClose()
    }
  }

  Box(
    modifier = Modifier.fillMaxSize().background(Color.Black),
  ) {
    if (selectedVideoUri != null && previewPlayer != null) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          PlayerView(ctx).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
          }
        },
        update = { view -> view.player = previewPlayer },
      )
    } else {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "Upload or record a video",
          color = Color.White.copy(alpha = 0.9f),
          style = MaterialTheme.typography.titleMedium,
        )
      }
    }

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors = listOf(
                Color.Black.copy(alpha = 0.35f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.6f),
              ),
            ),
          ),
    )

    Row(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PirateIconButton(onClick = onClose) {
        Icon(
          imageVector = PhosphorIcons.Regular.ArrowLeft,
          contentDescription = "Previous screen",
          tint = Color.White,
        )
      }
      PirateOutlinedButton(
        onClick = { showSongPicker = true },
      ) {
        Text(
          text = songLabel,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(modifier = Modifier.size(44.dp))
    }

    Column(
      modifier =
        Modifier
          .align(Alignment.CenterEnd)
          .padding(end = 10.dp),
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      PirateOutlinedButton(
        enabled = selectedVideoUri != null,
        onClick = { showFramePicker = true },
      ) {
        Text("Cover")
      }
      Text(
        text = formatPreviewTime(previewAtMs),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
      )
      PirateOutlinedButton(
        enabled = previewPlayer != null,
        onClick = {
          val exo = previewPlayer ?: return@PirateOutlinedButton
          exo.playWhenReady = !previewPlaying
        },
      ) {
        Text(if (previewPlaying) "Pause" else "Play")
      }
    }

    Column(
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .navigationBarsPadding()
          .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      OutlinedTextField(
        value = captionText,
        onValueChange = { next ->
          captionText = if (next.length <= 280) next else next.take(280)
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !posting,
        label = { Text("Caption") },
        placeholder = { Text("Add a caption") },
        singleLine = false,
        minLines = 1,
        maxLines = 3,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        PirateOutlinedButton(
          modifier = Modifier.weight(1f),
          onClick = { videoPicker.launch("video/*") },
        ) {
          Text("Upload")
        }
        PirateOutlinedButton(
          modifier = Modifier.weight(1f),
          onClick = {
            val captureUri = createVideoCaptureUri(context = context)
            if (captureUri == null) {
              onShowMessage("Unable to open camera")
              return@PirateOutlinedButton
            }
            pendingRecordedUri = captureUri
            runCatching {
              videoRecorder.launch(captureUri)
            }.onFailure { error ->
              pendingRecordedUri = null
              deleteUriQuietly(context = context, uri = captureUri)
              val message =
                if (error is ActivityNotFoundException) {
                  "No camera app available on this device"
                } else {
                  "Unable to open camera"
                }
              onShowMessage(message)
            }
          },
        ) {
          Text("Record")
        }
        PiratePrimaryButton(
          text = if (posting) "Posting..." else "Post",
          modifier = Modifier.weight(1.25f),
          enabled = !posting,
          onClick = { submitRequestedPosts(postCount = 1) },
        )
      }

      if (BuildConfig.DEBUG) {
        PirateOutlinedButton(
          modifier = Modifier.fillMaxWidth(),
          enabled = !posting,
          onClick = { submitRequestedPosts(postCount = 2) },
        ) {
          Text(if (posting) "Posting..." else "Post Placeholder x2")
        }
      }
    }
  }

  if (showSongPicker) {
    SongPickerSheet(
      repository = DefaultSongPickerRepository,
      ownerAddress = ownerAddress,
      onSelectSong = { selectedSong = it },
      onDismiss = { showSongPicker = false },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostFramePickerScreen(
  videoUri: Uri,
  initialPreviewAtMs: Long,
  initialDurationMs: Long?,
  onBack: () -> Unit,
  onApply: (Long, Long?) -> Unit,
) {
  val context = LocalContext.current
  var player by remember { mutableStateOf<ExoPlayer?>(null) }
  var durationMs by remember(videoUri, initialDurationMs) { mutableStateOf((initialDurationMs ?: 30_000L).coerceAtLeast(1_000L)) }
  var selectedAtMs by remember(videoUri, initialPreviewAtMs) { mutableStateOf(initialPreviewAtMs.coerceAtLeast(0L)) }

  LaunchedEffect(videoUri) {
    val duration = withContext(Dispatchers.IO) { readVideoDurationMs(context = context, uri = videoUri) }
    if (duration != null && duration > 0L) {
      durationMs = duration
      selectedAtMs = selectedAtMs.coerceIn(0L, duration)
    }
  }

  DisposableEffect(videoUri) {
    val exo =
      ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(videoUri))
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
        addListener(
          object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
              val d = duration
              if (d != C.TIME_UNSET && d > 0L) durationMs = d
            }
          },
        )
        prepare()
        seekTo(selectedAtMs)
      }
    player = exo
    onDispose {
      runCatching { exo.release() }
      if (player === exo) player = null
    }
  }

  val safeDurationMs = durationMs.coerceAtLeast(1_000L)
  val clampedSelectedAtMs = selectedAtMs.coerceIn(0L, safeDurationMs)

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Thumbnail") },
        navigationIcon = {
          PirateIconButton(onClick = {
            player?.playWhenReady = false
            onBack()
          }) {
            Icon(PhosphorIcons.Regular.ArrowLeft, contentDescription = "Previous screen")
          }
        },
        colors =
          androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
          ),
      )
    },
  ) { padding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .background(Color.Black)
          .padding(padding)
          .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF111214)),
        contentAlignment = Alignment.Center,
      ) {
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { ctx ->
            PlayerView(ctx).apply {
              useController = false
              resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
              setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            }
          },
          update = { view -> view.player = player },
        )
      }
      Slider(
        modifier = Modifier.fillMaxWidth(),
        value = selectedAtMs.toFloat().coerceIn(0f, safeDurationMs.toFloat()),
        onValueChange = { value ->
          val ms = value.toLong().coerceIn(0L, safeDurationMs)
          selectedAtMs = ms
          player?.seekTo(ms)
          player?.playWhenReady = false
        },
        valueRange = 0f..safeDurationMs.toFloat(),
      )
      PiratePrimaryButton(
        text = "Select",
        modifier = Modifier.fillMaxWidth(),
        onClick = {
          val chosen = player?.currentPosition?.coerceIn(0L, safeDurationMs) ?: clampedSelectedAtMs
          player?.playWhenReady = false
          onApply(chosen, safeDurationMs)
        },
      )
    }
  }
}

private fun createVideoCaptureUri(context: Context): Uri? {
  val values =
    ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, "pirate-post-${System.currentTimeMillis()}.mp4")
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Pirate")
      }
    }
  return runCatching {
    context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
  }.getOrNull()
}

internal fun deleteUriQuietly(
  context: Context,
  uri: Uri,
) {
  runCatching {
    context.contentResolver.delete(uri, null, null)
  }
}

internal fun readVideoDurationMs(
  context: Context,
  uri: Uri,
): Long? {
  val retriever = MediaMetadataRetriever()
  return try {
    retriever.setDataSource(context, uri)
    retriever
      .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
      ?.toLongOrNull()
      ?.takeIf { it > 0L }
  } catch (_: Throwable) {
    null
  } finally {
    runCatching { retriever.release() }
  }
}

internal fun formatPreviewTime(ms: Long): String {
  return String.format(Locale.US, "%.1fs", ms.toDouble() / 1000.0)
}

private suspend fun submitPostBatch(
  count: Int,
  context: Context,
  ownerAddress: String,
  song: SongPickerSong?,
  videoUri: Uri,
  basePreviewAtMs: Long,
  videoDurationMs: Long?,
  captionText: String,
): List<PostCreateTxResult> {
  val safeCount = count.coerceAtLeast(1)
  val results = ArrayList<PostCreateTxResult>(safeCount)
  for (index in 0 until safeCount) {
    val result =
      PostTxRepository.createPost(
        context = context,
        ownerAddress = ownerAddress,
        songTrackId = song?.trackId,
        songStoryIpId = song?.songStoryIpId,
        videoUri = videoUri,
        captionText = captionText,
        previewAtMs = resolveBatchPreviewAtMs(basePreviewAtMs = basePreviewAtMs, index = index, videoDurationMs = videoDurationMs),
      )
    results += result
    if (!result.success) break
  }
  return results
}

private fun resolveBatchPreviewAtMs(
  basePreviewAtMs: Long,
  index: Int,
  videoDurationMs: Long?,
): Long {
  val maxPreviewAtMs = (videoDurationMs ?: (basePreviewAtMs + 2_400L)).coerceAtLeast(1_000L)
  val shifted = basePreviewAtMs + (index.toLong() * 1_200L)
  return shifted.coerceIn(0L, maxPreviewAtMs)
}
