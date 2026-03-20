package sc.pirate.app.post

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.CameraRotate
import com.adamglin.phosphoricons.regular.Check
import com.adamglin.phosphoricons.regular.Lightning
import com.adamglin.phosphoricons.regular.MusicNote
import com.adamglin.phosphoricons.regular.Timer
import com.adamglin.phosphoricons.regular.Trash
import com.adamglin.phosphoricons.regular.Upload
import com.adamglin.phosphoricons.regular.X
import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import sc.pirate.app.ui.PirateIconButton
import sc.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import sc.pirate.app.songpicker.DefaultSongPickerRepository
import sc.pirate.app.songpicker.SongPickerSheet
import sc.pirate.app.songpicker.SongPickerSong
import sc.pirate.app.theme.PirateTokens
import sc.pirate.app.ui.PiratePrimaryButton
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_CAPTURE_DURATION_MS = 60_000L
private const val MIN_SEGMENT_DURATION_MS = 400L
private const val MUXER_WRITE_BUFFER_BYTES = 2 * 1024 * 1024

private data class CaptureSegment(
  val file: File,
  val durationMs: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostCaptureScreen(
  ownerAddress: String? = null,
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
  onVideoSelected: (Uri, SongPickerSong?) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()

  var selectedSong by remember { mutableStateOf<SongPickerSong?>(null) }
  var showSongPicker by remember { mutableStateOf(false) }
  var hasCameraPermission by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
  var hasAudioPermission by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
  var previewView by remember { mutableStateOf<PreviewView?>(null) }
  var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
  var activeRecording by remember { mutableStateOf<Recording?>(null) }
  var isRecording by remember { mutableStateOf(false) }
  var pendingSegmentFile by remember { mutableStateOf<File?>(null) }
  var recordingStartedAtMs by remember { mutableStateOf<Long?>(null) }
  var currentRecordingMaxMs by remember { mutableStateOf(MAX_CAPTURE_DURATION_MS) }
  var selectedSongAudioUrl by remember { mutableStateOf<String?>(null) }
  var songPreviewPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
  var preparingPreview by remember { mutableStateOf(false) }

  val segments = remember { mutableStateListOf<CaptureSegment>() }
  val totalDurationMs by remember { derivedStateOf { segments.sumOf { it.durationMs } } }
  val captureProgress by remember { derivedStateOf { (totalDurationMs.toFloat() / MAX_CAPTURE_DURATION_MS.toFloat()).coerceIn(0f, 1f) } }

  val videoPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
      if (uri != null) {
        songPreviewPlayer?.playWhenReady = false
        onVideoSelected(uri, null)
      }
    }

  val permissionsLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      hasCameraPermission = result[Manifest.permission.CAMERA] == true || hasPermission(context, Manifest.permission.CAMERA)
      hasAudioPermission = result[Manifest.permission.RECORD_AUDIO] == true || hasPermission(context, Manifest.permission.RECORD_AUDIO)
      if (!hasCameraPermission) {
        onShowMessage("Camera permission is required to record")
      }
    }

  LaunchedEffect(Unit) {
    if (!hasCameraPermission || !hasAudioPermission) {
      permissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
  }

  LaunchedEffect(ownerAddress) {
    DefaultSongPickerRepository.preloadSuggestedSongs(
      context = context,
      ownerAddress = ownerAddress,
      maxEntries = 24,
    )
  }

  DisposableEffect(hasCameraPermission, previewView, lifecycleOwner) {
    val view = previewView
    if (!hasCameraPermission || view == null) {
      onDispose { }
    } else {
      val providerFuture = ProcessCameraProvider.getInstance(context)
      val executor = ContextCompat.getMainExecutor(context)
      providerFuture.addListener(
        {
          val provider =
            runCatching { providerFuture.get() }.getOrElse {
              onShowMessage("Unable to access camera")
              return@addListener
            }
          val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
          val recorder =
            Recorder.Builder()
              .setQualitySelector(QualitySelector.from(Quality.HD))
              .build()
          val capture = VideoCapture.withOutput(recorder)
          runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            videoCapture = capture
          }.onFailure {
            videoCapture = null
            onShowMessage("Unable to start camera")
          }
        },
        executor,
      )
      onDispose {
        activeRecording?.stop()
        activeRecording?.close()
        activeRecording = null
        isRecording = false
        videoCapture = null
        runCatching { providerFuture.get().unbindAll() }
      }
    }
  }

  DisposableEffect(context) {
    val player = ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE }
    val listener =
      object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
          onShowMessage("Unable to play selected sound")
        }
      }
    player.addListener(listener)
    songPreviewPlayer = player
    onDispose {
      player.removeListener(listener)
      runCatching { player.release() }
      if (songPreviewPlayer === player) songPreviewPlayer = null
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      pendingSegmentFile?.let { file ->
        if (file.exists()) runCatching { file.delete() }
      }
      segments.forEach { segment ->
        if (segment.file.exists()) runCatching { segment.file.delete() }
      }
    }
  }

  LaunchedEffect(selectedSong?.trackId, songPreviewPlayer) {
    val player = songPreviewPlayer ?: return@LaunchedEffect
    val audioUrl = resolveSongAudioUrl(selectedSong)
    selectedSongAudioUrl = audioUrl
    if (audioUrl.isNullOrBlank()) {
      player.stop()
      player.clearMediaItems()
      if (selectedSong != null) onShowMessage("Selected song has no playable preview")
      return@LaunchedEffect
    }
    runCatching {
      player.setMediaItem(MediaItem.fromUri(audioUrl))
      player.prepare()
      player.seekTo(totalDurationMs)
      player.playWhenReady = true
    }.onFailure {
      player.stop()
      player.clearMediaItems()
      onShowMessage("Unable to play selected sound")
    }
  }

  fun startRecording() {
    if (isRecording || preparingPreview) return
    val capture = videoCapture
    if (capture == null) {
      onShowMessage("Camera is not ready")
      return
    }
    if (selectedSong == null) {
      onShowMessage("Choose a sound before recording")
      return
    }
    if (selectedSongAudioUrl.isNullOrBlank()) {
      onShowMessage("Selected sound has no playable preview")
      return
    }
    if (totalDurationMs >= MAX_CAPTURE_DURATION_MS) {
      onShowMessage("Max length reached. Tap Next")
      return
    }

    val remainingMs = (MAX_CAPTURE_DURATION_MS - totalDurationMs).coerceAtLeast(MIN_SEGMENT_DURATION_MS)
    val outputFile = createSegmentFile(context, segments.size)
    pendingSegmentFile = outputFile

    songPreviewPlayer?.let { player ->
      player.seekTo(totalDurationMs)
      player.playWhenReady = true
    }

    val outputOptions = FileOutputOptions.Builder(outputFile).build()
    val pending =
      capture.output.prepareRecording(context, outputOptions).let { prepared ->
        if (hasAudioPermission) prepared.withAudioEnabled() else prepared
      }

    activeRecording =
      runCatching {
        pending.start(ContextCompat.getMainExecutor(context)) { event ->
          when (event) {
            is VideoRecordEvent.Start -> {
              isRecording = true
              recordingStartedAtMs = SystemClock.elapsedRealtime()
              currentRecordingMaxMs = remainingMs
            }

            is VideoRecordEvent.Status -> {
              val elapsedMs = event.recordingStats.recordedDurationNanos / 1_000_000L
              if (elapsedMs >= currentRecordingMaxMs) {
                activeRecording?.stop()
              }
            }

            is VideoRecordEvent.Finalize -> {
              isRecording = false
              songPreviewPlayer?.playWhenReady = false
              activeRecording?.close()
              activeRecording = null

              val file = pendingSegmentFile
              pendingSegmentFile = null

              val measuredDurationMs =
                max(
                  event.recordingStats.recordedDurationNanos / 1_000_000L,
                  (SystemClock.elapsedRealtime() - (recordingStartedAtMs ?: SystemClock.elapsedRealtime())).coerceAtLeast(0L),
                )
              recordingStartedAtMs = null

              if (event.hasError()) {
                file?.let { failed -> if (failed.exists()) runCatching { failed.delete() } }
                onShowMessage("Recording failed")
                return@start
              }

              if (file == null || !file.exists()) {
                onShowMessage("Recording failed")
                return@start
              }

              val safeDurationMs = measuredDurationMs.coerceIn(0L, currentRecordingMaxMs)
              if (safeDurationMs < MIN_SEGMENT_DURATION_MS) {
                runCatching { file.delete() }
                onShowMessage("Clip too short")
                return@start
              }

              segments.add(CaptureSegment(file = file, durationMs = safeDurationMs))
              if (segments.sumOf { it.durationMs } >= MAX_CAPTURE_DURATION_MS) {
                onShowMessage("Max length reached. Tap Next")
              }
            }
          }
        }
      }.getOrElse {
        songPreviewPlayer?.playWhenReady = false
        pendingSegmentFile?.let { failed -> if (failed.exists()) runCatching { failed.delete() } }
        pendingSegmentFile = null
        onShowMessage("Unable to start recording")
        null
      }
  }

  fun stopRecording() {
    activeRecording?.stop()
    songPreviewPlayer?.playWhenReady = false
  }

  fun deleteLastSegment() {
    if (isRecording || preparingPreview || segments.isEmpty()) return
    val removed = segments.removeAt(segments.lastIndex)
    if (removed.file.exists()) runCatching { removed.file.delete() }
    songPreviewPlayer?.seekTo(segments.sumOf { it.durationMs })
  }

  fun goToPreview() {
    if (isRecording || preparingPreview) return
    if (segments.isEmpty()) {
      onShowMessage("Record at least one clip first")
      return
    }
    preparingPreview = true
    songPreviewPlayer?.playWhenReady = false

    val snapshot = segments.toList()
    scope.launch {
      val mergedUri = withContext(Dispatchers.IO) { mergeCaptureSegments(context = context, segments = snapshot) }
      preparingPreview = false
      if (mergedUri == null) {
        onShowMessage("Unable to prepare preview")
        return@launch
      }
      snapshot.forEach { segment -> if (segment.file.exists()) runCatching { segment.file.delete() } }
      segments.clear()
      onVideoSelected(mergedUri, selectedSong)
    }
  }

  val songLabel =
    selectedSong?.let { "${it.title} - ${it.artist}" } ?: "Add Sound"

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(Color.Black)
        .statusBarsPadding()
        .navigationBarsPadding(),
  ) {
    if (hasCameraPermission) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
          PreviewView(ctx).also { view ->
            view.scaleType = PreviewView.ScaleType.FILL_CENTER
            previewView = view
          }
        },
        update = { view ->
          if (previewView !== view) previewView = view
        },
      )
    } else {
      Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
      ) {
        Text("Allow camera access to record", color = Color.White.copy(alpha = 0.9f))
      }
    }

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors =
                listOf(
                  Color.Black.copy(alpha = 0.28f),
                  Color.Transparent,
                  Color.Black.copy(alpha = 0.62f),
                ),
            ),
          ),
    )

    PirateIconButton(
      onClick = onClose,
      modifier =
        Modifier
          .align(Alignment.TopStart)
          .padding(top = 14.dp, start = 14.dp)
          .size(46.dp)
          .clip(androidx.compose.foundation.shape.CircleShape)
          .background(PirateTokens.colors.bgOverlay.copy(alpha = 0.66f)),
    ) {
      Icon(
        imageVector = PhosphorIcons.Regular.X,
        contentDescription = "Close",
        tint = Color.White,
        modifier = Modifier.size(22.dp),
      )
    }

    PirateOutlinedButton(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .padding(top = 12.dp)
          .widthIn(max = 230.dp),
      shape = RoundedCornerShape(20.dp),
      colors =
        ButtonDefaults.outlinedButtonColors(
          containerColor = PirateTokens.colors.bgOverlay.copy(alpha = 0.78f),
          contentColor = PirateTokens.colors.textOnAccent,
        ),
      onClick = {
        if (isRecording || preparingPreview) {
          onShowMessage("Finish recording before changing sound")
        } else {
          showSongPicker = true
        }
      },
    ) {
      Icon(
        imageVector = PhosphorIcons.Regular.MusicNote,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.size(6.dp))
      Text(
        text = songLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    Column(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .padding(top = 112.dp, start = 18.dp, end = 18.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(Color.White.copy(alpha = 0.24f)),
      ) {
        Box(
          modifier =
            Modifier
              .fillMaxWidth(captureProgress)
              .height(4.dp)
              .clip(RoundedCornerShape(99.dp))
              .background(PirateTokens.colors.accentBrand),
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
      CaptureTool(icon = PhosphorIcons.Regular.CameraRotate, label = "Flip")
      CaptureTool(icon = PhosphorIcons.Regular.Timer, label = "Timer")
      CaptureTool(icon = PhosphorIcons.Regular.Lightning, label = "Flash")
    }

    Column(
      modifier =
        Modifier
          .align(Alignment.BottomCenter)
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (segments.isNotEmpty()) {
          PirateIconButton(
            onClick = { deleteLastSegment() },
            enabled = !isRecording && !preparingPreview,
            modifier =
              Modifier
                .size(52.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(PirateTokens.colors.bgOverlay.copy(alpha = 0.58f)),
          ) {
            Icon(
              imageVector = PhosphorIcons.Regular.Trash,
              contentDescription = "Delete last segment",
              tint = Color.White,
              modifier = Modifier.size(28.dp),
            )
          }
        } else {
          PirateIconButton(
            onClick = {
              if (isRecording || preparingPreview) return@PirateIconButton
              videoPicker.launch("video/*")
            },
            modifier =
              Modifier
                .size(52.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(PirateTokens.colors.bgOverlay.copy(alpha = 0.44f)),
            enabled = !isRecording && !preparingPreview,
          ) {
            Icon(
              imageVector = PhosphorIcons.Regular.Upload,
              contentDescription = "Upload",
              tint = Color.White,
              modifier = Modifier.size(30.dp),
            )
          }
        }

        RecordButton(
          isRecording = isRecording,
          enabled = hasCameraPermission && videoCapture != null && !preparingPreview && totalDurationMs < MAX_CAPTURE_DURATION_MS,
          onClick = {
            if (isRecording) stopRecording() else startRecording()
          },
        )

        if (segments.isNotEmpty()) {
          AdvanceButton(
            loading = preparingPreview,
            onClick = { goToPreview() },
            enabled = !isRecording && !preparingPreview,
          )
        } else {
          Spacer(modifier = Modifier.size(52.dp))
        }
      }

      if (!hasAudioPermission) {
        Text(
          text = "Microphone permission denied (video will be muted)",
          color = Color.White.copy(alpha = 0.82f),
        )
      }
    }
  }

  if (showSongPicker) {
    SongPickerSheet(
      repository = DefaultSongPickerRepository,
      ownerAddress = ownerAddress,
      onSelectSong = { song ->
        if (segments.isNotEmpty()) {
          segments.forEach { segment -> if (segment.file.exists()) runCatching { segment.file.delete() } }
          segments.clear()
          onShowMessage("Sound changed, clips reset")
        }
        selectedSong = song
        showSongPicker = false
      },
      onDismiss = { showSongPicker = false },
    )
  }
}

@Composable
private fun RecordButton(
  isRecording: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Box(
    modifier =
      Modifier
        .size(96.dp)
        .clip(androidx.compose.foundation.shape.CircleShape)
        .background(
          if (enabled) PirateTokens.colors.accentBrand else PirateTokens.colors.accentBrand.copy(alpha = 0.45f),
        )
        .pointerInput(enabled, isRecording) {
          detectTapGestures(
            onTap = {
              if (!enabled) return@detectTapGestures
              onClick()
            },
          )
        },
    contentAlignment = Alignment.Center,
  ) {
    if (isRecording) {
      Box(
        modifier =
          Modifier
            .size(44.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(PirateTokens.colors.textOnAccent),
      )
    }
  }
}

@Composable
private fun AdvanceButton(
  loading: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  PirateIconButton(
    onClick = onClick,
    enabled = enabled && !loading,
    modifier =
      Modifier
        .size(52.dp)
        .clip(androidx.compose.foundation.shape.CircleShape)
        .background(
          if (enabled) PirateTokens.colors.textOnAccent else PirateTokens.colors.textOnAccent.copy(alpha = 0.4f),
        ),
  ) {
    if (loading) {
      androidx.compose.material3.CircularProgressIndicator(
        modifier = Modifier.size(22.dp),
        color = PirateTokens.colors.bgPage,
        strokeWidth = 2.dp,
      )
    } else {
      Icon(
        imageVector = PhosphorIcons.Regular.Check,
        contentDescription = "Continue",
        tint = PirateTokens.colors.bgPage,
        modifier = Modifier.size(28.dp),
      )
    }
  }
}

@Composable
private fun CaptureTool(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
) {
  Column(
    modifier = Modifier.widthIn(min = 52.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    PirateIconButton(
      onClick = {},
      colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
      modifier =
        Modifier
          .size(48.dp)
          .clip(androidx.compose.foundation.shape.CircleShape)
          .background(PirateTokens.colors.bgOverlay.copy(alpha = 0.66f)),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        modifier = Modifier.size(34.dp),
        tint = Color.White,
      )
    }
    Text(
      text = label,
      color = Color.White.copy(alpha = 0.88f),
      style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
    )
  }
}

private fun createSegmentFile(
  context: Context,
  index: Int,
): File {
  val dir = File(context.cacheDir, "post-capture")
  if (!dir.exists()) {
    dir.mkdirs()
  }
  return File(dir, "segment-${System.currentTimeMillis()}-$index.mp4")
}

private fun mergeCaptureSegments(
  context: Context,
  segments: List<CaptureSegment>,
): Uri? {
  if (segments.isEmpty()) return null
  val outputUri = createMergedVideoUri(context) ?: return null
  return runCatching {
    context.contentResolver.openFileDescriptor(outputUri, "w")?.use { pfd ->
      if (segments.size == 1) {
        copyFileToDescriptor(source = segments.first().file, context = context, outputUri = outputUri, fileDescriptor = pfd.fileDescriptor)
      } else {
        mergeSegmentFiles(segments = segments, fileDescriptor = pfd.fileDescriptor)
      }
    } ?: throw IllegalStateException("Unable to create merged video")
    outputUri
  }.getOrElse {
    deleteUriQuietly(context = context, uri = outputUri)
    null
  }
}

private fun createMergedVideoUri(context: Context): Uri? {
  val values =
    ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, "pirate-post-${System.currentTimeMillis()}.mp4")
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Pirate")
      }
    }
  return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
}

private fun copyFileToDescriptor(
  source: File,
  context: Context,
  outputUri: Uri,
  fileDescriptor: java.io.FileDescriptor,
) {
  if (!source.exists()) {
    throw IllegalStateException("Segment file missing")
  }
  FileInputStream(source).use { input ->
    FileOutputStream(fileDescriptor).use { output ->
      val copied = input.copyTo(output)
      if (copied <= 0L) {
        throw IllegalStateException("Failed to copy segment")
      }
      output.flush()
    }
  }
  val contentValues = ContentValues().apply {
    put(MediaStore.Video.Media.SIZE, source.length())
    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
  }
  runCatching { context.contentResolver.update(outputUri, contentValues, null, null) }
}

private fun mergeSegmentFiles(
  segments: List<CaptureSegment>,
  fileDescriptor: java.io.FileDescriptor,
) {
  val firstExtractor = MediaExtractor()
  firstExtractor.setDataSource(segments.first().file.absolutePath)
  val firstVideoTrack = findTrackIndex(firstExtractor, "video/")
  if (firstVideoTrack < 0) {
    firstExtractor.release()
    throw IllegalStateException("No video track in first segment")
  }
  val firstAudioTrack = findTrackIndex(firstExtractor, "audio/")

  val muxer = MediaMuxer(fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  val muxVideoTrack = muxer.addTrack(firstExtractor.getTrackFormat(firstVideoTrack))
  val muxAudioTrack = if (firstAudioTrack >= 0) muxer.addTrack(firstExtractor.getTrackFormat(firstAudioTrack)) else -1
  firstExtractor.release()

  val buffer = ByteBuffer.allocate(MUXER_WRITE_BUFFER_BYTES)
  val bufferInfo = MediaCodec.BufferInfo()
  var offsetUs = 0L

  try {
    muxer.start()
    segments.forEach { segment ->
      val videoEndUs =
        copyTrackIntoMuxer(
          segmentFile = segment.file,
          trackMimePrefix = "video/",
          muxer = muxer,
          muxTrackIndex = muxVideoTrack,
          timeOffsetUs = offsetUs,
          buffer = buffer,
          bufferInfo = bufferInfo,
        )
      val audioEndUs =
        if (muxAudioTrack >= 0) {
          copyTrackIntoMuxer(
            segmentFile = segment.file,
            trackMimePrefix = "audio/",
            muxer = muxer,
            muxTrackIndex = muxAudioTrack,
            timeOffsetUs = offsetUs,
            buffer = buffer,
            bufferInfo = bufferInfo,
          )
        } else {
          0L
        }
      val measuredSpanUs = max(videoEndUs, audioEndUs)
      val estimatedSpanUs = segment.durationMs * 1_000L
      offsetUs += max(measuredSpanUs, estimatedSpanUs).coerceAtLeast(1_000L)
    }
    muxer.stop()
  } finally {
    runCatching { muxer.release() }
  }
}

private fun copyTrackIntoMuxer(
  segmentFile: File,
  trackMimePrefix: String,
  muxer: MediaMuxer,
  muxTrackIndex: Int,
  timeOffsetUs: Long,
  buffer: ByteBuffer,
  bufferInfo: MediaCodec.BufferInfo,
): Long {
  val extractor = MediaExtractor()
  extractor.setDataSource(segmentFile.absolutePath)
  val trackIndex = findTrackIndex(extractor, trackMimePrefix)
  if (trackIndex < 0) {
    extractor.release()
    return 0L
  }

  extractor.selectTrack(trackIndex)
  var maxPresentationTimeUs = 0L

  try {
    while (true) {
      buffer.clear()
      val sampleSize = extractor.readSampleData(buffer, 0)
      if (sampleSize < 0) break

      val rawSampleTimeUs = extractor.sampleTime
      val sampleTimeUs = if (rawSampleTimeUs < 0L) 0L else rawSampleTimeUs
      maxPresentationTimeUs = max(maxPresentationTimeUs, sampleTimeUs)

      bufferInfo.offset = 0
      bufferInfo.size = sampleSize
      bufferInfo.presentationTimeUs = sampleTimeUs + timeOffsetUs
      bufferInfo.flags = extractor.sampleFlags

      muxer.writeSampleData(muxTrackIndex, buffer, bufferInfo)
      extractor.advance()
    }
  } finally {
    extractor.release()
  }

  return maxPresentationTimeUs
}

private fun findTrackIndex(
  extractor: MediaExtractor,
  mimePrefix: String,
): Int {
  for (index in 0 until extractor.trackCount) {
    val format = extractor.getTrackFormat(index)
    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
    if (mime.startsWith(mimePrefix)) return index
  }
  return -1
}

private fun resolveSongAudioUrl(song: SongPickerSong?): String? {
  return resolveSongPreviewUrl(song?.trackId)
}

private fun hasPermission(
  context: Context,
  permission: String,
): Boolean {
  return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
