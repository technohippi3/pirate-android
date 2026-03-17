package com.pirate.app.music

import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.*

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import com.pirate.app.ui.PirateIconButton
import androidx.compose.material3.MaterialTheme
import com.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ── Step 1: Song ─────────────────────────────────────────────────

@Composable
internal fun SongStep(
  formData: SongPublishService.SongFormData,
  onFormChange: (SongPublishService.SongFormData) -> Unit,
  onPickAudio: () -> Unit,
  onClearAudio: () -> Unit,
  onPickVocals: () -> Unit,
  onClearVocals: () -> Unit,
  onPickInstrumental: () -> Unit,
  onClearInstrumental: () -> Unit,
  onPickCover: () -> Unit,
  getFileName: (Uri?) -> String?,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // Cover art — square preview or placeholder
    Box(
      modifier = Modifier
        .size(160.dp)
        .align(Alignment.CenterHorizontally)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .border(
          width = 2.dp,
          color = if (formData.coverUri != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
          shape = RoundedCornerShape(12.dp),
        )
        .clickable { onPickCover() },
      contentAlignment = Alignment.Center,
    ) {
      if (formData.coverUri != null) {
        coil.compose.AsyncImage(
          model = formData.coverUri,
          contentDescription = "Cover Art",
          modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
          contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
      } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(PhosphorIcons.Regular.Plus, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
          Spacer(modifier = Modifier.height(4.dp))
          Text("Cover Art", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text("Square image", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
      }
    }

    // Title
    OutlinedTextField(
      value = formData.title,
      onValueChange = { onFormChange(formData.copy(title = it)) },
      label = { Text("Song Title") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )

    // Genre dropdown
    DropdownField(
      label = "Genre",
      options = GENRE_OPTIONS,
      selectedValue = formData.genre,
      onSelect = { onFormChange(formData.copy(genre = it)) },
    )

    // Audio file picker (full mix)
    FilePickerButton(
      label = "Audio",
      fileName = getFileName(formData.audioUri),
      icon = { Icon(PhosphorIcons.Regular.MusicNotes, contentDescription = null, modifier = Modifier.size(24.dp)) },
      onClick = onPickAudio,
      onClear = onClearAudio,
    )

    FilePickerButton(
      label = "Vocals Stem",
      fileName = getFileName(formData.vocalsUri),
      icon = { Icon(PhosphorIcons.Regular.Microphone, contentDescription = null, modifier = Modifier.size(24.dp)) },
      onClick = onPickVocals,
      onClear = onClearVocals,
    )

    FilePickerButton(
      label = "Instrumental Stem",
      fileName = getFileName(formData.instrumentalUri),
      icon = { Icon(PhosphorIcons.Regular.Guitar, contentDescription = null, modifier = Modifier.size(24.dp)) },
      onClick = onPickInstrumental,
      onClear = onClearInstrumental,
    )

  }
}

// ── Step 2: Preview Clip ─────────────────────────────────────────────

@Composable
internal fun PreviewStep(
  formData: SongPublishService.SongFormData,
  onFormChange: (SongPublishService.SongFormData) -> Unit,
  onPickCanvas: () -> Unit,
  onClearCanvas: () -> Unit,
  getFileName: (Uri?) -> String?,
) {
  val audioUri = formData.audioUri
  val normalizedInitial =
    songPublishNormalizePreviewWindow(
      rawStartSec = formData.previewStartSec,
      rawEndSec = formData.previewEndSec,
      rawDurationSec = formData.trackDurationSec,
    )
  if (audioUri == null || normalizedInitial.durationSec <= 0f) {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        "Choose an audio file first.",
        style = MaterialTheme.typography.bodyLarge,
      )
    }
    return
  }

  val context = LocalContext.current
  val maxPreviewDuration = normalizedInitial.maxClipSec
  val minClipDuration = normalizedInitial.minClipSec
  var sliderValues by remember(formData.previewStartSec, formData.previewEndSec, formData.trackDurationSec) {
    mutableStateOf(normalizedInitial.startSec..normalizedInitial.endSec)
  }
  var previewPlayer by remember(audioUri) { mutableStateOf<ExoPlayer?>(null) }
  var previewPlaying by remember { mutableStateOf(false) }
  var playbackPositionSec by remember(sliderValues.start) { mutableStateOf(sliderValues.start) }

  DisposableEffect(audioUri) {
    val exo =
      ExoPlayer.Builder(context).build().apply {
        setMediaItem(MediaItem.fromUri(audioUri))
        repeatMode = Player.REPEAT_MODE_OFF
        playWhenReady = false
        addListener(
          object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
              previewPlaying = isPlaying
            }
          },
        )
        prepare()
        seekTo(songPublishSecondsToMs(sliderValues.start))
      }
    previewPlayer = exo
    playbackPositionSec = sliderValues.start

    onDispose {
      runCatching { exo.release() }
      if (previewPlayer === exo) previewPlayer = null
      previewPlaying = false
    }
  }

  LaunchedEffect(previewPlayer, sliderValues.start, sliderValues.endInclusive) {
    val exo = previewPlayer ?: return@LaunchedEffect
    val startMs = songPublishSecondsToMs(sliderValues.start)
    val endMs = songPublishSecondsToMs(sliderValues.endInclusive).coerceAtLeast(startMs + 1L)
    val currentMs = exo.currentPosition
    if (currentMs < startMs || currentMs >= endMs) {
      exo.seekTo(startMs)
      playbackPositionSec = sliderValues.start
    }
  }

  LaunchedEffect(previewPlayer, sliderValues.start, sliderValues.endInclusive) {
    val exo = previewPlayer ?: return@LaunchedEffect
    while (isActive) {
      val startMs = songPublishSecondsToMs(sliderValues.start)
      val endMs = songPublishSecondsToMs(sliderValues.endInclusive).coerceAtLeast(startMs + 1L)
      val currentMs = exo.currentPosition.coerceAtLeast(0L)
      if (exo.isPlaying && currentMs >= endMs) {
        exo.seekTo(startMs)
        exo.playWhenReady = true
      }
      val clampedMs = exo.currentPosition.coerceIn(startMs, endMs)
      playbackPositionSec = (clampedMs.toFloat() / 1000f).coerceIn(sliderValues.start, sliderValues.endInclusive)
      delay(80L)
    }
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
      "Set the start and end for your preview.",
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "Set start and end",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        val clipDuration = (sliderValues.endInclusive - sliderValues.start).coerceAtLeast(0f)
        Text(
          "${clipDuration.toInt()}s",
          style = MaterialTheme.typography.titleMedium,
          color = if (clipDuration > maxPreviewDuration) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
      }

      RangeSlider(
        value = sliderValues,
        onValueChange = { range ->
          val normalized =
            songPublishNormalizePreviewWindow(
              rawStartSec = range.start,
              rawEndSec = range.endInclusive,
              rawDurationSec = normalizedInitial.durationSec,
              minClipSec = minClipDuration,
              maxClipSec = maxPreviewDuration,
            )
          sliderValues = normalized.startSec..normalized.endSec
        },
        onValueChangeFinished = {
          previewPlayer?.let { exo ->
            val startMs = songPublishSecondsToMs(sliderValues.start)
            exo.playWhenReady = false
            exo.seekTo(startMs)
          }
          playbackPositionSec = sliderValues.start
          onFormChange(formData.copy(previewStartSec = sliderValues.start, previewEndSec = sliderValues.endInclusive))
        },
        valueRange = 0f..normalizedInitial.durationSec,
        steps = (normalizedInitial.durationSec.toInt() - 1).coerceAtLeast(0),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          Text("Start", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(formatTime(sliderValues.start), style = MaterialTheme.typography.bodyMedium)
        }
        Column(horizontalAlignment = Alignment.End) {
          Text("End", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(formatTime(sliderValues.endInclusive), style = MaterialTheme.typography.bodyMedium)
        }
      }

      Text(
        "Current: ${formatTime(playbackPositionSec)}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        PirateOutlinedButton(
          enabled = previewPlayer != null,
          onClick = {
            val exo = previewPlayer ?: return@PirateOutlinedButton
            val startMs = songPublishSecondsToMs(sliderValues.start)
            val endMs = songPublishSecondsToMs(sliderValues.endInclusive).coerceAtLeast(startMs + 1L)
            if (previewPlaying) {
              exo.playWhenReady = false
              return@PirateOutlinedButton
            }
            val currentMs = exo.currentPosition
            if (currentMs < startMs || currentMs >= endMs) {
              exo.seekTo(startMs)
            }
            exo.playWhenReady = true
          },
        ) {
          Text(if (previewPlaying) "Pause" else "Play")
        }
      }

      if (normalizedInitial.durationSec < 5f) {
        Text(
          "Tracks under 5 seconds use the full song as preview.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        "Canvas video (optional)",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Text(
        "Plays with this preview in the feed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (formData.canvasUri != null) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(PhosphorIcons.Regular.VideoCamera, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(getFileName(formData.canvasUri) ?: "video", style = MaterialTheme.typography.bodyLarge)
          }
          PirateIconButton(onClick = onClearCanvas) {
            Icon(PhosphorIcons.Regular.X, contentDescription = "Remove")
          }
        }
      } else {
        FilePickerButton(
          label = "Add Canvas Video (MP4/WebM)",
          fileName = null,
          icon = { Icon(PhosphorIcons.Regular.VideoCamera, contentDescription = null, modifier = Modifier.size(24.dp)) },
          onClick = onPickCanvas,
        )
      }
    }

  }
}

private fun formatTime(seconds: Float): String {
  val mins = (seconds / 60).toInt()
  val secs = (seconds % 60).toInt()
  return if (mins > 0) "${mins}:${secs.toString().padStart(2, '0')}" else "${secs}s"
}

private fun songPublishSecondsToMs(seconds: Float): Long =
  (seconds.coerceAtLeast(0f) * 1000f).toLong()

// ── Step 4: Details ──────────────────────────────────────────────

@Composable
internal fun DetailsStep(
  formData: SongPublishService.SongFormData,
  onFormChange: (SongPublishService.SongFormData) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    // Primary language
    DropdownField(
      label = "Primary Language",
      options = LANGUAGE_OPTIONS,
      selectedValue = formData.primaryLanguage,
      onSelect = { onFormChange(formData.copy(primaryLanguage = it)) },
    )

    // Secondary language
    DropdownField(
      label = "Secondary Language (optional)",
      options = SECONDARY_LANGUAGE_OPTIONS,
      selectedValue = formData.secondaryLanguage,
      onSelect = { onFormChange(formData.copy(secondaryLanguage = it)) },
    )

    // Lyrics
    OutlinedTextField(
      value = formData.lyrics,
      onValueChange = { onFormChange(formData.copy(lyrics = it)) },
      label = { Text("Lyrics") },
      modifier = Modifier.fillMaxWidth().height(200.dp),
      placeholder = { Text("Paste lyrics here...\n\n[Verse 1]\n...\n[Chorus]\n...") },
    )

  }
}

// ── Step 5: License ───────────────────────────────────────────────

@Composable
internal fun LicenseStep(
  formData: SongPublishService.SongFormData,
  onFormChange: (SongPublishService.SongFormData) -> Unit,
) {
  val selectedLicense = formData.license
  val commercialHint =
    when (selectedLicense) {
      "commercial-use" -> "Commercial use is allowed. Remixes are not allowed."
      "commercial-remix" -> "Commercial use and remixes are allowed."
      else -> null
    }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
      "How can others use this song?",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
    )
    Text(
      "Choose whether people can use your song commercially and whether they can remix it.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LicenseOptionCard(
      selected = selectedLicense == "non-commercial",
      title = "Non-commercial only",
      description = "Fans can share and remix socially, but no one can monetize it.",
      onClick = { onFormChange(formData.copy(license = "non-commercial")) },
    )
    LicenseOptionCard(
      selected = selectedLicense == "commercial-use",
      title = "Commercial use (no remix)",
      description = "Others can monetize using your original song, but cannot release remixes.",
      onClick = { onFormChange(formData.copy(license = "commercial-use")) },
    )
    LicenseOptionCard(
      selected = selectedLicense == "commercial-remix",
      title = "Commercial use + remix",
      description = "Others can monetize and publish remixes of your song.",
      onClick = { onFormChange(formData.copy(license = "commercial-remix")) },
    )

    // Rev share (only for commercial licenses)
    if (selectedLicense != "non-commercial") {
      if (!commercialHint.isNullOrBlank()) {
        Text(
          commercialHint,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text("Revenue Share: ${formData.revShare}%", style = MaterialTheme.typography.bodyLarge)
      Slider(
        value = formData.revShare.toFloat(),
        onValueChange = { onFormChange(formData.copy(revShare = it.toInt())) },
        valueRange = 0f..100f,
        steps = 99,
      )
    }

  }
}

@Composable
private fun LicenseOptionCard(
  selected: Boolean,
  title: String,
  description: String,
  onClick: () -> Unit,
) {
  val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
  val backgroundColor =
    if (selected) {
      MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(backgroundColor)
      .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.Top,
  ) {
    RadioButton(selected = selected, onClick = onClick)
    Spacer(modifier = Modifier.width(8.dp))
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
      Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

// ── Step 6: Sales ─────────────────────────────────────────────────

@Composable
internal fun SalesStep(
  formData: SongPublishService.SongFormData,
  onFormChange: (SongPublishService.SongFormData) -> Unit,
) {
  val purchasePriceUnits = SongPublishService.purchasePriceUnitsOrNull(formData.purchasePriceUsd)
  val purchasePriceInvalid = formData.purchasePriceUsd.trim().isNotEmpty() && purchasePriceUnits == null
  val maxSupplyMissing = !formData.openEdition && formData.maxSupply.trim().isEmpty()
  val maxSupplyValue = formData.maxSupply.trim().toIntOrNull()?.takeIf { it in 1..1_000_000 }
  val maxSupplyInvalid = !formData.openEdition && (maxSupplyMissing || maxSupplyValue == null)

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    OutlinedTextField(
      value = formData.purchasePriceUsd,
      onValueChange = { onFormChange(formData.copy(purchasePriceUsd = it)) },
      label = { Text("Song Purchase Price (USD)") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
      isError = purchasePriceInvalid,
      supportingText = {
        if (purchasePriceUnits == null) {
          Text("Required")
        }
      },
    )

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { onFormChange(formData.copy(openEdition = !formData.openEdition)) },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(
        checked = formData.openEdition,
        onCheckedChange = { onFormChange(formData.copy(openEdition = it)) },
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        "Open edition (unlimited mints)",
        style = MaterialTheme.typography.bodyLarge,
      )
    }

    if (!formData.openEdition) {
      OutlinedTextField(
        value = formData.maxSupply,
        onValueChange = { onFormChange(formData.copy(maxSupply = it)) },
        label = { Text("Max Supply") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = maxSupplyInvalid,
        supportingText = {
          if (maxSupplyMissing) {
            Text("Max supply is required for capped editions")
          } else {
            Text("Range: 1 - 1000000")
          }
        },
      )
    }

  }
}

// ── Step 7: Finalize ──────────────────────────────────────────────

@Composable
internal fun FinalizeStep(
  formData: SongPublishService.SongFormData,
  onFormChange: (SongPublishService.SongFormData) -> Unit,
  getFileName: (Uri?) -> String?,
) {
  val purchasePriceUnits = SongPublishService.purchasePriceUnitsOrNull(formData.purchasePriceUsd)
  val maxSupplyValue = formData.maxSupply.trim().toIntOrNull()?.takeIf { it in 1..1_000_000 }
  val genreLabel = ALL_GENRE_OPTIONS.find { it.value == formData.genre }?.label ?: formData.genre
  val languageLabel =
    buildList {
      add(LANGUAGE_OPTIONS.find { it.value == formData.primaryLanguage }?.label ?: formData.primaryLanguage)
      if (formData.secondaryLanguage.isNotBlank()) {
        add(LANGUAGE_OPTIONS.find { it.value == formData.secondaryLanguage }?.label ?: formData.secondaryLanguage)
      }
    }.joinToString(" / ")
  val licenseLabel = LICENSE_OPTIONS.find { it.value == formData.license }?.label ?: formData.license
  val priceLabel = purchasePriceUnits?.let { formatUsdMax3(it) } ?: "—"
  val editionLabel =
    if (formData.openEdition) {
      "Open edition"
    } else {
      "${maxSupplyValue ?: "—"} copies"
    }
  val audioLabel = finalizeFileValue(getFileName(formData.audioUri))
  val vocalsLabel = finalizeFileValue(getFileName(formData.vocalsUri))
  val instrumentalLabel = finalizeFileValue(getFileName(formData.instrumentalUri))
  val canvasLabel = formData.canvasUri?.let { finalizeFileValue(getFileName(it)) } ?: "None"

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.Top,
    ) {
      if (formData.coverUri != null) {
        coil.compose.AsyncImage(
          model = formData.coverUri,
          contentDescription = "Cover Art",
          modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)),
          contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
      }
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = formData.title,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = formData.artist,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
        .padding(horizontal = 16.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (genreLabel.isNotBlank()) {
        FinalizeRow(label = "Genre", value = genreLabel)
      }
      FinalizeRow(label = "Language", value = languageLabel)
      FinalizeRow(label = "Audio", value = audioLabel)
      FinalizeRow(label = "Vocals", value = vocalsLabel)
      FinalizeRow(label = "Instrumental", value = instrumentalLabel)
      FinalizeRow(label = "Preview", value = "${formatTime(formData.previewStartSec)} - ${formatTime(formData.previewEndSec)}")
      FinalizeRow(label = "Lyrics", value = "Included")
      FinalizeRow(label = "Canvas", value = canvasLabel)
      FinalizeRow(label = "License", value = licenseLabel)
      if (formData.license != "non-commercial") {
        FinalizeRow(label = "Revenue Share", value = "${formData.revShare}%")
      }
      FinalizeRow(label = "Price", value = priceLabel)
      FinalizeRow(label = "Edition", value = editionLabel)
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        .clickable { onFormChange(formData.copy(attestation = !formData.attestation)) },
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(
        checked = formData.attestation,
        onCheckedChange = { onFormChange(formData.copy(attestation = it)) },
        modifier = Modifier.padding(start = 4.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        "I have the rights to publish this song.",
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(end = 16.dp, top = 14.dp, bottom = 14.dp),
      )
    }
  }
}

@Composable
private fun FinalizeRow(
  label: String,
  value: String,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(0.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(112.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

private fun finalizeFileValue(raw: String?): String {
  val value = raw?.trim().orEmpty().ifBlank { "—" }
  if (value.length <= 36) return value
  return value.take(20).trimEnd() + "…" + value.takeLast(12).trimStart()
}

private fun formatUsdMax3(rawUnits: String): String {
  val units = rawUnits.toBigIntegerOrNull() ?: return "—"
  val amount = BigDecimal(units).movePointLeft(6).setScale(3, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
  return "$$amount"
}
