package com.pirate.app.music

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Dimension
import coil.size.Size as CoilSize
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.X
import com.pirate.app.ui.PirateIconButton
import com.pirate.app.ui.PiratePrimaryButton
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SONG_PUBLISH_COVER_DECODE_MAX_SIZE = 2048
private const val SONG_PUBLISH_COVER_OUTPUT_MAX_SIZE = 1600
private const val SONG_PUBLISH_COVER_JPEG_QUALITY = 90
private const val SONG_PUBLISH_COVER_CACHE_DIR = "song_publish_covers"
private const val SONG_PUBLISH_CROP_FRAME_RATIO = 0.82f

internal data class SongPublishCoverCropSource(
  val bitmap: Bitmap,
) {
  val isSquare: Boolean
    get() = bitmap.width == bitmap.height
}

internal data class SongPublishCoverCropSelection(
  val displayedImageWidthPx: Float,
  val displayedImageHeightPx: Float,
  val cropLeftPx: Float,
  val cropTopPx: Float,
  val cropSizePx: Float,
)

private data class SongPublishCoverDisplayMetrics(
  val imageLeftPx: Float,
  val imageTopPx: Float,
  val imageWidthPx: Float,
  val imageHeightPx: Float,
)

internal suspend fun songPublishLoadCoverCropSource(
  context: Context,
  uri: Uri,
): SongPublishCoverCropSource =
  withContext(Dispatchers.IO) {
    val request =
      ImageRequest.Builder(context)
        .data(uri)
        .allowHardware(false)
        .size(
          CoilSize(
            Dimension.Pixels(SONG_PUBLISH_COVER_DECODE_MAX_SIZE),
            Dimension.Pixels(SONG_PUBLISH_COVER_DECODE_MAX_SIZE),
          ),
        )
        .build()
    val result = context.imageLoader.execute(request) as? SuccessResult
      ?: throw IllegalStateException("Could not decode cover image")
    val bitmap = result.drawable.toBitmap().copy(Bitmap.Config.ARGB_8888, true)
    if (bitmap.width <= 0 || bitmap.height <= 0) {
      throw IllegalStateException("Cover image has invalid dimensions")
    }
    SongPublishCoverCropSource(bitmap = bitmap)
  }

internal suspend fun songPublishWriteSquareCoverBitmap(
  context: Context,
  bitmap: Bitmap,
): Uri =
  withContext(Dispatchers.IO) {
    val targetSize = min(min(bitmap.width, bitmap.height), SONG_PUBLISH_COVER_OUTPUT_MAX_SIZE).coerceAtLeast(1)
    val outputBitmap =
      if (bitmap.width != targetSize || bitmap.height != targetSize) {
        Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
      } else {
        bitmap
      }

    val dir = File(context.cacheDir, SONG_PUBLISH_COVER_CACHE_DIR)
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "cover-${UUID.randomUUID()}.jpg")
    file.outputStream().use { out ->
      val compressed = outputBitmap.compress(Bitmap.CompressFormat.JPEG, SONG_PUBLISH_COVER_JPEG_QUALITY, out)
      if (!compressed) {
        throw IllegalStateException("Could not save cropped cover")
      }
    }
    Uri.fromFile(file)
  }

internal suspend fun songPublishWriteCroppedCover(
  context: Context,
  source: SongPublishCoverCropSource,
  selection: SongPublishCoverCropSelection,
): Uri =
  withContext(Dispatchers.IO) {
    val bitmap = source.bitmap
    if (selection.displayedImageWidthPx <= 0f || selection.displayedImageHeightPx <= 0f || selection.cropSizePx <= 0f) {
      throw IllegalStateException("Cover crop is not ready")
    }

    val scaleX = bitmap.width / selection.displayedImageWidthPx
    val scaleY = bitmap.height / selection.displayedImageHeightPx
    val scale = min(scaleX, scaleY)

    val cropSizeSourcePx =
      (selection.cropSizePx * scale)
        .roundToInt()
        .coerceIn(1, min(bitmap.width, bitmap.height))
    val left =
      (selection.cropLeftPx * scaleX)
        .roundToInt()
        .coerceIn(0, bitmap.width - cropSizeSourcePx)
    val top =
      (selection.cropTopPx * scaleY)
        .roundToInt()
        .coerceIn(0, bitmap.height - cropSizeSourcePx)

    val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, cropSizeSourcePx, cropSizeSourcePx)
    songPublishWriteSquareCoverBitmap(context, croppedBitmap)
  }

@Composable
internal fun SongPublishCoverCropScreen(
  source: SongPublishCoverCropSource,
  saving: Boolean,
  onCancel: () -> Unit,
  onConfirm: (SongPublishCoverCropSelection) -> Unit,
) {
  val density = LocalDensity.current
  var viewportSize by remember(source.bitmap) { mutableStateOf(IntSize.Zero) }
  var cropSizePx by remember(source.bitmap) { mutableFloatStateOf(0f) }
  var cropLeftPx by remember(source.bitmap) { mutableFloatStateOf(0f) }
  var cropTopPx by remember(source.bitmap) { mutableFloatStateOf(0f) }

  val metrics =
    remember(source.bitmap.width, source.bitmap.height, viewportSize) {
      songPublishCoverDisplayMetrics(
        bitmapWidth = source.bitmap.width,
        bitmapHeight = source.bitmap.height,
        viewportSize = viewportSize,
      )
    }

  LaunchedEffect(source.bitmap, metrics.imageWidthPx, metrics.imageHeightPx) {
    if (metrics.imageWidthPx <= 0f || metrics.imageHeightPx <= 0f) return@LaunchedEffect
    val targetCropSize = (min(metrics.imageWidthPx, metrics.imageHeightPx) * SONG_PUBLISH_CROP_FRAME_RATIO).coerceAtLeast(1f)
    cropSizePx = targetCropSize
    cropLeftPx = ((metrics.imageWidthPx - targetCropSize) / 2f).coerceAtLeast(0f)
    cropTopPx = ((metrics.imageHeightPx - targetCropSize) / 2f).coerceAtLeast(0f)
  }

  val imageWidthDp = with(density) { metrics.imageWidthPx.toDp() }
  val imageHeightDp = with(density) { metrics.imageHeightPx.toDp() }
  val frameLeftPx = metrics.imageLeftPx + cropLeftPx
  val frameTopPx = metrics.imageTopPx + cropTopPx
  val cropSizeDp = with(density) { cropSizePx.toDp() }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = Color(0xFF080B0F),
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PirateIconButton(
          onClick = onCancel,
          enabled = !saving,
        ) {
          androidx.compose.material3.Icon(
            PhosphorIcons.Regular.X,
            contentDescription = "Close cropper",
            tint = Color.White,
          )
        }
        Text(
          text = "Crop cover",
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium,
          color = Color.White,
          textAlign = TextAlign.Center,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(48.dp))
      }

      Text(
        text = "Drag the square to frame the artwork. The published cover will use exactly this crop.",
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.78f),
        textAlign = TextAlign.Center,
      )

      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 12.dp)
          .clip(RoundedCornerShape(24.dp))
          .background(Color(0xFF11161C))
          .onSizeChanged { viewportSize = it },
      ) {
        if (metrics.imageWidthPx > 0f && metrics.imageHeightPx > 0f) {
          Image(
            bitmap = source.bitmap.asImageBitmap(),
            contentDescription = "Cover crop image",
            contentScale = ContentScale.FillBounds,
            modifier =
              Modifier
                .size(width = imageWidthDp, height = imageHeightDp)
                .offset {
                  IntOffset(
                    x = metrics.imageLeftPx.roundToInt(),
                    y = metrics.imageTopPx.roundToInt(),
                  )
                },
          )

          Canvas(
            modifier =
              Modifier
                .fillMaxSize()
                .graphicsLayer {
                  compositingStrategy = CompositingStrategy.Offscreen
                },
          ) {
            drawRect(color = Color.Black.copy(alpha = 0.58f))
            if (cropSizePx > 0f) {
              val cropTopLeft = Offset(frameLeftPx, frameTopPx)
              val cropSize = Size(cropSizePx, cropSizePx)
              drawRect(
                color = Color.Transparent,
                topLeft = cropTopLeft,
                size = cropSize,
                blendMode = BlendMode.Clear,
              )
              drawRect(
                color = Color.White,
                topLeft = cropTopLeft,
                size = cropSize,
                style = Stroke(width = 2.dp.toPx()),
              )

              val third = cropSizePx / 3f
              for (index in 1..2) {
                val verticalX = frameLeftPx + third * index
                val horizontalY = frameTopPx + third * index
                drawLine(
                  color = Color.White.copy(alpha = 0.45f),
                  start = Offset(verticalX, frameTopPx),
                  end = Offset(verticalX, frameTopPx + cropSizePx),
                  strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                  color = Color.White.copy(alpha = 0.45f),
                  start = Offset(frameLeftPx, horizontalY),
                  end = Offset(frameLeftPx + cropSizePx, horizontalY),
                  strokeWidth = 1.dp.toPx(),
                )
              }
            }
          }

          Box(
            modifier =
              Modifier
                .offset {
                  IntOffset(
                    x = frameLeftPx.roundToInt(),
                    y = frameTopPx.roundToInt(),
                  )
                }
                .size(cropSizeDp)
                .border(2.dp, Color.Transparent)
                .pointerInput(metrics.imageWidthPx, metrics.imageHeightPx, cropSizePx, saving) {
                  detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (saving || cropSizePx <= 0f) return@detectDragGestures
                    cropLeftPx =
                      (cropLeftPx + dragAmount.x)
                        .coerceIn(0f, (metrics.imageWidthPx - cropSizePx).coerceAtLeast(0f))
                    cropTopPx =
                      (cropTopPx + dragAmount.y)
                        .coerceIn(0f, (metrics.imageHeightPx - cropSizePx).coerceAtLeast(0f))
                  }
                },
          )
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        PiratePrimaryButton(
          text = "Use crop",
          onClick = {
            onConfirm(
              SongPublishCoverCropSelection(
                displayedImageWidthPx = metrics.imageWidthPx,
                displayedImageHeightPx = metrics.imageHeightPx,
                cropLeftPx = cropLeftPx,
                cropTopPx = cropTopPx,
                cropSizePx = cropSizePx,
              ),
            )
          },
          modifier = Modifier.fillMaxWidth().height(52.dp),
          enabled = metrics.imageWidthPx > 0f && metrics.imageHeightPx > 0f && cropSizePx > 0f,
          loading = saving,
        )
      }
    }
  }
}

private fun songPublishCoverDisplayMetrics(
  bitmapWidth: Int,
  bitmapHeight: Int,
  viewportSize: IntSize,
): SongPublishCoverDisplayMetrics {
  val viewportWidth = viewportSize.width.toFloat()
  val viewportHeight = viewportSize.height.toFloat()
  if (bitmapWidth <= 0 || bitmapHeight <= 0 || viewportWidth <= 0f || viewportHeight <= 0f) {
    return SongPublishCoverDisplayMetrics(
      imageLeftPx = 0f,
      imageTopPx = 0f,
      imageWidthPx = 0f,
      imageHeightPx = 0f,
    )
  }

  val scale = min(viewportWidth / bitmapWidth.toFloat(), viewportHeight / bitmapHeight.toFloat())
  val imageWidthPx = bitmapWidth * scale
  val imageHeightPx = bitmapHeight * scale
  return SongPublishCoverDisplayMetrics(
    imageLeftPx = (viewportWidth - imageWidthPx) / 2f,
    imageTopPx = (viewportHeight - imageHeightPx) / 2f,
    imageWidthPx = imageWidthPx,
    imageHeightPx = imageHeightPx,
  )
}
