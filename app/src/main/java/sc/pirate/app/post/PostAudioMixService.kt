package sc.pirate.app.post

import android.content.Context
import android.net.Uri
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer

data class PostAudioMixConfig(
  val includeOriginalAudio: Boolean,
  val includeAddedSound: Boolean,
  val originalVolume: Float,
  val addedVolume: Float,
  val songOffsetMs: Long,
  val videoDurationMs: Long? = null,
)

internal object PostAudioMixService {
  private const val DEFAULT_EXPORT_FILE_NAME = "post-mix-output.mp4"

  fun buildPreviewPlayer(
    context: Context,
    videoUri: Uri,
    songTrackId: String?,
    config: PostAudioMixConfig,
  ): Player {
    // On-device logs show CompositionPlayer hitting TIME_UNSET for local picker URIs during
    // preview setup, which drops us into a video-only fallback anyway. Use the stable video
    // player path directly for preview and keep composition-based mixing only for export.
    return buildVideoOnlyPreviewPlayer(
      context = context,
      videoUri = videoUri,
    )
  }

  suspend fun exportMix(
    context: Context,
    videoUri: Uri,
    songTrackId: String?,
    config: PostAudioMixConfig,
    outputFile: File,
  ): Uri {
    val composition =
      buildComposition(
        videoUri = videoUri,
        songTrackId = songTrackId,
        config = config,
        loopAddedSound = true,
      )
    outputFile.parentFile?.mkdirs()
    if (outputFile.exists()) {
      runCatching { outputFile.delete() }
    }

    return suspendCancellableCoroutine { continuation ->
      val listener =
        object : Transformer.Listener {
          override fun onCompleted(
            composition: Composition,
            exportResult: ExportResult,
          ) {
            if (!continuation.isActive) return
            continuation.resume(Uri.fromFile(outputFile))
          }

          override fun onError(
            composition: Composition,
            exportResult: ExportResult,
            exportException: ExportException,
          ) {
            if (!continuation.isActive) return
            continuation.resumeWithException(exportException)
          }
        }
      val transformer =
        Transformer.Builder(context)
          .addListener(listener)
          .build()

      continuation.invokeOnCancellation {
        runCatching { transformer.cancel() }
      }
      transformer.start(composition, outputFile.absolutePath)
    }
  }

  fun createExportFile(context: Context): File {
    val outputDir = File(context.cacheDir, "post-mix")
    if (!outputDir.exists()) {
      outputDir.mkdirs()
    }
    return File(outputDir, "${System.currentTimeMillis()}-$DEFAULT_EXPORT_FILE_NAME")
  }

  private fun buildComposition(
    videoUri: Uri,
    songTrackId: String?,
    config: PostAudioMixConfig,
    loopAddedSound: Boolean,
  ): Composition {
    val sequences = ArrayList<EditedMediaItemSequence>(2)
    val normalizedOriginalVolume = config.originalVolume.coerceIn(0f, 1f)
    val normalizedAddedVolume = config.addedVolume.coerceIn(0f, 1f)

    val videoEditedBuilder =
      EditedMediaItem.Builder(
        MediaItem.fromUri(videoUri),
      )
    if (!config.includeOriginalAudio || normalizedOriginalVolume <= 0f) {
      videoEditedBuilder.setRemoveAudio(true)
    } else {
      videoEditedBuilder.setEffects(buildVolumeEffects(normalizedOriginalVolume))
    }
    sequences += EditedMediaItemSequence(listOf(videoEditedBuilder.build()))

    val songUri = resolveSongUri(songTrackId)
    if (config.includeAddedSound && songUri != null && normalizedAddedVolume > 0f) {
      val songStartMs = config.songOffsetMs.coerceAtLeast(0L)
      val songClipBuilder =
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(songStartMs)
      config.videoDurationMs
        ?.takeIf { it > 0L }
        ?.let { durationMs ->
          songClipBuilder.setEndPositionMs((songStartMs + durationMs).coerceAtLeast(songStartMs + 1L))
        }
      val songMediaItem =
        MediaItem.Builder()
          .setUri(songUri)
          .setClippingConfiguration(songClipBuilder.build())
          .build()
      val songEdited =
        EditedMediaItem.Builder(songMediaItem)
          .setRemoveVideo(true)
          .setEffects(buildVolumeEffects(normalizedAddedVolume))
          .build()
      sequences += EditedMediaItemSequence(listOf(songEdited), loopAddedSound)
    }
    return Composition.Builder(sequences).build()
  }

  private fun buildVolumeEffects(volume: Float): Effects {
    val clamped = volume.coerceIn(0f, 1f)
    if (clamped >= 0.999f) return Effects.EMPTY
    val mixer =
      ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(clamped))
        putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(clamped))
      }
    return Effects(listOf(mixer), emptyList())
  }

  private fun resolveSongUri(trackId: String?): Uri? {
    val previewUrl = resolveSongPreviewUrl(trackId) ?: return null
    return Uri.parse(previewUrl)
  }

  private fun buildVideoOnlyPreviewPlayer(
    context: Context,
    videoUri: Uri,
  ): Player {
    return ExoPlayer.Builder(context).build().apply {
      repeatMode = Player.REPEAT_MODE_ONE
      setMediaItem(MediaItem.fromUri(videoUri))
      prepare()
      playWhenReady = true
    }
  }
}
