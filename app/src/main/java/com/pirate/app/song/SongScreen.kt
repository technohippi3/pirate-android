package com.pirate.app.song

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.pirate.app.ui.PirateOutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pirate.app.music.MusicLibrary
import com.pirate.app.music.MusicTrack
import com.pirate.app.music.TrackPreviewHistoryStore
import com.pirate.app.music.resolvePlaybackCoverUrl
import com.pirate.app.music.resolveSongTrackId
import com.pirate.app.music.resolveTrackPreviewUrl
import com.pirate.app.player.PlayerController
import com.pirate.app.songpicker.DefaultSongPickerRepository
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val GENIUS_PACKAGE = "com.genius.android"

@Composable
fun SongScreen(
  trackId: String,
  initialTitle: String? = null,
  initialArtist: String? = null,
  player: PlayerController,
  isAuthenticated: Boolean,
  userAddress: String?,
  onBack: () -> Unit,
  onOpenArtist: (String) -> Unit,
  onOpenProfile: (String) -> Unit,
  onOpenPlayer: () -> Unit,
  onOpenLearn: (studySetRef: String?, trackId: String, language: String, version: Int, title: String?, artist: String?) -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var refreshKey by remember { mutableIntStateOf(0) }

  var loading by remember { mutableStateOf(true) }
  var loadError by remember { mutableStateOf<String?>(null) }
  var stats by remember { mutableStateOf<SongStats?>(null) }
  var listeners by remember { mutableStateOf<List<SongListenerRow>>(emptyList()) }
  var localTracks by remember(trackId) { mutableStateOf<List<MusicTrack>>(emptyList()) }
  var resolvedTrackId by remember(trackId) { mutableStateOf(trackId) }

  val learnerLanguage = remember { Locale.getDefault().language.ifBlank { "en" } }
  var studyStatusLoading by remember { mutableStateOf(true) }
  var studyStatus by remember { mutableStateOf<StudySetStatus?>(null) }
  var studyStatusRequested by remember { mutableStateOf(true) }
  var studyStatusTrackId by remember(trackId) { mutableStateOf<String?>(null) }
  var generateBusy by remember { mutableStateOf(false) }
  var generatedInSession by remember(trackId) { mutableStateOf(false) }
  var localCoverUri by remember(trackId) { mutableStateOf<String?>(null) }
  var annotationsInsufficient by remember(trackId) { mutableStateOf(false) }
  var annotationsLineCount by remember(trackId) { mutableStateOf<Int?>(null) }
  var annotationsMinLineCount by remember(trackId) { mutableIntStateOf(6) }
  var geniusAnnotateUrl by remember(trackId) { mutableStateOf<String?>(null) }
  var closing by remember { mutableStateOf(false) }
  val currentTrack by player.currentTrack.collectAsState()
  val playerIsPlaying by player.isPlaying.collectAsState()

  fun refresh() {
    refreshKey += 1
  }

  LaunchedEffect(trackId, initialTitle, initialArtist, refreshKey) {
    loading = true
    loadError = null

    var effectiveTrackId = trackId
    var statsResult = runCatching { SongArtistApi.fetchSongStats(trackId) }
    var resolvedStats = statsResult.getOrNull()

    if (resolvedStats == null && !initialTitle.isNullOrBlank() && !initialArtist.isNullOrBlank()) {
      val fuzzyResult = runCatching {
        SongArtistApi.resolveSongStatsByTitleArtist(
          title = initialTitle,
          artistName = initialArtist,
        )
      }
      val fuzzyResolved = fuzzyResult.getOrNull()
      if (fuzzyResolved != null) {
        effectiveTrackId = fuzzyResolved.trackId
        resolvedStats = fuzzyResolved
        statsResult = Result.success(fuzzyResolved)
        Log.d(
          "SongTrackIdDebug",
          "SongScreen fuzzy-resolved trackId='$effectiveTrackId' from title='$initialTitle' artist='$initialArtist' inputTrackId='$trackId'",
        )
      }
    }

    val listenersResult = runCatching { SongArtistApi.fetchSongTopListeners(effectiveTrackId, maxEntries = 40) }

    resolvedTrackId = effectiveTrackId
    stats = resolvedStats
    listeners = listenersResult.getOrElse { emptyList() }

    val hasFallbackSongIdentity = !initialTitle.isNullOrBlank() || !initialArtist.isNullOrBlank()
    loadError =
      when {
        statsResult.isFailure && listenersResult.isFailure -> {
          statsResult.exceptionOrNull()?.message
            ?: listenersResult.exceptionOrNull()?.message
            ?: "Failed to load song"
        }
        stats == null && !hasFallbackSongIdentity -> "Song not found"
        else -> null
      }

    loading = false
  }

  LaunchedEffect(resolvedTrackId, learnerLanguage, refreshKey, studyStatusRequested) {
    if (!studyStatusRequested) {
      studyStatusLoading = false
      studyStatus = null
      studyStatusTrackId = null
      return@LaunchedEffect
    }
    Log.d(
      "StudySetDebug",
      "status fetch start trackId='$resolvedTrackId' lang='$learnerLanguage' refreshKey=$refreshKey",
    )
    studyStatusLoading = true
    fun statusFromFailure(errorCode: String, error: Throwable): StudySetStatus {
      if (error is CancellationException) throw error
      return StudySetStatus(
        ready = false,
        studySetRef = null,
        studySetHash = null,
        errorCode = errorCode,
        error = error.message ?: "Status unavailable",
      )
    }
    val primaryStatus = runCatching {
      SongArtistApi.fetchStudySetStatus(trackId = resolvedTrackId, language = learnerLanguage)
    }.getOrElse { statusFromFailure("status_failed", it) }
    Log.d(
      "StudySetDebug",
      "status primary ready=${primaryStatus.ready} ref='${primaryStatus.studySetRef}' hash='${primaryStatus.studySetHash}' code='${primaryStatus.errorCode}' err='${primaryStatus.error}'",
    )
    val fallbackStatus =
      if (!primaryStatus.ready && !learnerLanguage.equals("en", ignoreCase = true)) {
        val fallback = runCatching {
          SongArtistApi.fetchStudySetStatus(trackId = resolvedTrackId, language = "en")
        }.getOrElse { statusFromFailure("status_failed_en", it) }
        Log.d(
          "StudySetDebug",
          "status en fallback ready=${fallback.ready} ref='${fallback.studySetRef}' hash='${fallback.studySetHash}' code='${fallback.errorCode}' err='${fallback.error}'",
        )
        if (fallback.ready) fallback else null
      } else {
        null
      }
    var effectiveStatus = fallbackStatus ?: primaryStatus
    var effectiveTrackId = resolvedTrackId

    val normalizedInputTrackId = normalizeBytes32(trackId)
    val normalizedResolvedTrackId = normalizeBytes32(resolvedTrackId)
    val shouldTryInputTrackId =
      !normalizedInputTrackId.isNullOrBlank() &&
        !normalizedInputTrackId.equals(normalizedResolvedTrackId, ignoreCase = true)
    if (!effectiveStatus.ready && shouldTryInputTrackId) {
      Log.d(
        "StudySetDebug",
        "status trying inputTrackId candidate trackId='$normalizedInputTrackId' (resolved='$resolvedTrackId')",
      )
      val inputPrimary = runCatching {
        SongArtistApi.fetchStudySetStatus(trackId = normalizedInputTrackId, language = learnerLanguage)
      }.getOrElse { statusFromFailure("status_failed_input", it) }
      Log.d(
        "StudySetDebug",
        "status input primary ready=${inputPrimary.ready} ref='${inputPrimary.studySetRef}' hash='${inputPrimary.studySetHash}' code='${inputPrimary.errorCode}' err='${inputPrimary.error}'",
      )

      val inputFallback =
        if (!inputPrimary.ready && !learnerLanguage.equals("en", ignoreCase = true)) {
          val fallback = runCatching {
            SongArtistApi.fetchStudySetStatus(trackId = normalizedInputTrackId, language = "en")
          }.getOrElse { statusFromFailure("status_failed_input_en", it) }
          Log.d(
            "StudySetDebug",
            "status input en fallback ready=${fallback.ready} ref='${fallback.studySetRef}' hash='${fallback.studySetHash}' code='${fallback.errorCode}' err='${fallback.error}'",
          )
          if (fallback.ready) fallback else null
        } else {
          null
        }

      val inputEffective = inputFallback ?: inputPrimary
      if (inputEffective.ready) {
        effectiveStatus = inputEffective
        effectiveTrackId = normalizedInputTrackId
      }
    }

    studyStatus = effectiveStatus
    studyStatusTrackId = if (effectiveStatus.ready) effectiveTrackId else null
    studyStatusLoading = false
    Log.d(
      "StudySetDebug",
      "status effective ready=${effectiveStatus.ready} ref='${effectiveStatus.studySetRef}' hash='${effectiveStatus.studySetHash}' code='${effectiveStatus.errorCode}' statusTrackId='$studyStatusTrackId'",
    )
  }

  val effectiveTitle = stats?.title?.ifBlank { null } ?: initialTitle?.ifBlank { null } ?: "Song"
  val effectiveArtist = stats?.artist?.ifBlank { null } ?: initialArtist?.ifBlank { null }

  LaunchedEffect(resolvedTrackId, effectiveTitle, effectiveArtist, stats?.album, refreshKey) {
    runCatching {
      val tracks = MusicLibrary.loadCachedTracks(context)
      localTracks = tracks
      val resolvedArtwork = resolveLocalSongArtworkUri(
        tracks = tracks,
        resolvedTrackId = resolvedTrackId,
        title = effectiveTitle,
        artist = effectiveArtist,
      )
      if (!resolvedArtwork.isNullOrBlank()) return@runCatching resolvedArtwork
      val artistName = effectiveArtist?.trim().orEmpty().ifBlank { null } ?: return@runCatching null
      SongArtistApi.fetchSongCoverFallback(
        title = effectiveTitle,
        artist = artistName,
        album = stats?.album,
      )
    }.onSuccess { resolvedArtwork ->
      Log.d(
        "SongCover",
        "fallback/local resolve result coverUrl='${resolvedArtwork ?: "<none>"}' title='$effectiveTitle' artist='${effectiveArtist.orEmpty()}' trackId='$resolvedTrackId'",
      )
      localCoverUri = resolvedArtwork
    }.onFailure {
      Log.w(
        "SongCover",
        "fallback/local resolve failed title='$effectiveTitle' artist='${effectiveArtist.orEmpty()}' trackId='$resolvedTrackId'",
      )
      localTracks = emptyList()
      localCoverUri = null
    }
  }

  fun openGeniusAnnotate() {
    val targetUrl =
      geniusAnnotateUrl
        ?: SongArtistApi.buildGeniusSongSearchUrl(
          title = effectiveTitle,
          artist = effectiveArtist,
        )
    val webIntent =
      Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
      }
    val geniusIntent = Intent(webIntent).setPackage(GENIUS_PACKAGE)
    runCatching { context.startActivity(geniusIntent) }
      .recoverCatching { context.startActivity(webIntent) }
      .onFailure {
        onShowMessage("Unable to open Genius. Visit $targetUrl")
      }
  }

  fun requestStudySetGeneration() {
    if (!isAuthenticated || userAddress.isNullOrBlank()) {
      onShowMessage("Sign in to generate exercises")
      return
    }
    studyStatusRequested = true
    scope.launch {
      generateBusy = true
      runCatching {
        SongArtistApi.generateStudySet(
          trackId = resolvedTrackId,
          language = learnerLanguage,
          userAddress = userAddress,
          title = effectiveTitle,
          artist = effectiveArtist,
          album = stats?.album,
        )
      }.onSuccess { result ->
        if (result.success) {
          annotationsInsufficient = false
          annotationsLineCount = null
          generatedInSession = true
          studyStatusRequested = true
          studyStatus =
            StudySetStatus(
              ready = true,
              studySetRef = result.studySetRef ?: studyStatus?.studySetRef,
              studySetHash = result.studySetHash ?: studyStatus?.studySetHash,
              errorCode = null,
              error = null,
            )
          onShowMessage(
            if (result.cached) "Study set already available"
            else "Study set generated",
          )
          Log.d(
            "LearnLaunch",
            "SongScreen generate success -> open learn ref='${result.studySetRef ?: studyStatus?.studySetRef}' trackId='$resolvedTrackId' lang='$learnerLanguage'",
          )
          onOpenLearn(
            result.studySetRef ?: studyStatus?.studySetRef,
            resolvedTrackId,
            learnerLanguage,
            2,
            effectiveTitle.takeIf { it.isNotBlank() },
            effectiveArtist?.takeIf { it.isNotBlank() },
          )
        } else if (
          result.errorCode.equals("insufficient_lyric_lines", ignoreCase = true) ||
          result.errorCode.equals("lyrics_not_found", ignoreCase = true) ||
          result.error?.contains("insufficient usable lyric lines", ignoreCase = true) == true ||
          result.error?.contains("no usable lyric lines found in genius referent fragments", ignoreCase = true) == true
        ) {
          annotationsInsufficient = true
          annotationsLineCount = result.lineCount ?: 0
          annotationsMinLineCount = result.minLineCount ?: annotationsMinLineCount
          geniusAnnotateUrl =
            geniusAnnotateUrl
              ?: SongArtistApi.buildGeniusSongSearchUrl(
                title = effectiveTitle,
                artist = effectiveArtist,
              )
        } else {
          onShowMessage(result.error ?: "Generation failed")
        }
        refresh()
      }.onFailure { err ->
        onShowMessage("Generate failed: ${err.message ?: "unknown error"}")
      }
      generateBusy = false
    }
  }

  fun handleSongPlaybackTap() {
    val currentMatchesSong =
      currentTrack?.let {
        matchesSongForPlayback(
          track = it,
          resolvedTrackId = resolvedTrackId,
          title = effectiveTitle,
          artist = effectiveArtist,
        )
      } == true

    if (currentMatchesSong) {
      player.togglePlayPause()
      if (!playerIsPlaying) {
        onOpenPlayer()
      }
      return
    }

    val playableTrack =
      resolvePlayableSongTrack(
        tracks = localTracks,
        resolvedTrackId = resolvedTrackId,
        title = effectiveTitle,
        artist = effectiveArtist,
      )
    if (playableTrack != null) {
      player.playTrack(playableTrack, listOf(playableTrack))
      onOpenPlayer()
      return
    }

    val previewUri = resolveTrackPreviewUrl(resolvedTrackId)
    if (previewUri.isNullOrBlank()) {
      onShowMessage("Track isn't available for playback on this device yet")
      return
    }

    val remoteCover =
      resolvePlaybackCoverUrl(stats?.coverCid)
        ?: localCoverUri
    val remoteTrack =
      MusicTrack(
        id = "release:$resolvedTrackId",
        canonicalTrackId = resolvedTrackId,
        title = effectiveTitle,
        artist = effectiveArtist ?: "Unknown Artist",
        album = stats?.album.orEmpty(),
        durationSec = stats?.durationSec ?: 0,
        uri = previewUri,
        filename = effectiveTitle,
        artworkUri = remoteCover,
        lyricsRef = stats?.lyricsRef,
        isCloudOnly = true,
        isPreviewOnly = true,
      )
    TrackPreviewHistoryStore.recordPreview(
      context = context,
      trackId = resolvedTrackId,
      title = effectiveTitle,
      artist = effectiveArtist ?: "Unknown Artist",
    )
    DefaultSongPickerRepository.invalidateSuggestedSongsCache()
    player.playTrack(remoteTrack, listOf(remoteTrack))
    onOpenPlayer()
  }

  val hasReadyExercises = generatedInSession || (studyStatus?.ready == true)
  val requiresAnnotationOnGenius = annotationsInsufficient && !hasReadyExercises

  LaunchedEffect(resolvedTrackId, effectiveTitle, effectiveArtist, hasReadyExercises, refreshKey) {
    val fallbackUrl =
      SongArtistApi.buildGeniusSongSearchUrl(
        title = effectiveTitle,
        artist = effectiveArtist,
      )
    geniusAnnotateUrl = fallbackUrl

    if (hasReadyExercises) {
      annotationsInsufficient = false
      annotationsLineCount = null
      return@LaunchedEffect
    }
    val artistName = effectiveArtist?.trim().orEmpty()
    if (effectiveTitle.isBlank() || artistName.isBlank()) {
      return@LaunchedEffect
    }
    val coverage = runCatching {
      SongArtistApi.fetchGeniusAnnotationCoverage(
        title = effectiveTitle,
        artist = artistName,
      )
    }.getOrNull() ?: return@LaunchedEffect

    annotationsInsufficient = coverage.insufficientLyricLines
    if (coverage.insufficientLyricLines) {
      annotationsLineCount = coverage.lineCount
      annotationsMinLineCount = coverage.minLineCount
    } else {
      annotationsLineCount = coverage.lineCount
      annotationsMinLineCount = coverage.minLineCount
    }
    geniusAnnotateUrl = coverage.geniusSongUrl ?: fallbackUrl
  }

  val statusCheckInFlight = studyStatusRequested && studyStatusLoading && !hasReadyExercises && !generateBusy
  val exerciseButtonLabel =
    when {
      requiresAnnotationOnGenius -> "Annotate"
      generateBusy -> "Generating..."
      statusCheckInFlight -> "Checking Exercises..."
      hasReadyExercises -> "Study"
      else -> "Generate Exercises"
    }
  val exerciseButtonEnabled = if (requiresAnnotationOnGenius) true else !generateBusy && !statusCheckInFlight
  val isCurrentSongPlaying =
    (currentTrack?.let {
      matchesSongForPlayback(
        track = it,
        resolvedTrackId = resolvedTrackId,
        title = effectiveTitle,
        artist = effectiveArtist,
      )
    } == true) && playerIsPlaying

  LaunchedEffect(hasReadyExercises, studyStatus, generatedInSession, resolvedTrackId, learnerLanguage, studyStatusTrackId) {
    Log.d(
      "StudySetDebug",
      "cta state trackId='$resolvedTrackId' statusTrackId='${studyStatusTrackId ?: "<none>"}' lang='$learnerLanguage' generatedInSession=$generatedInSession readyFromStatus=${studyStatus?.ready == true} hasReadyExercises=$hasReadyExercises ref='${studyStatus?.studySetRef}' code='${studyStatus?.errorCode}' err='${studyStatus?.error}'",
    )
  }

  var artistPickerOpen by remember { mutableStateOf(false) }

  if (loading) {
    SongScreenLoadingSkeleton()
    return
  }

  if (!loadError.isNullOrBlank()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(loadError ?: "Failed to load", color = MaterialTheme.colorScheme.error)
        PirateOutlinedButton(onClick = { refresh() }) { Text("Retry") }
      }
    }
    return
  }

  if (closing) {
    Box(
      modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    )
    LaunchedEffect(Unit) { onBack() }
    return
  }

  Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    SongTopSection(
      title = effectiveTitle,
      artist = effectiveArtist,
      coverCid = stats?.coverCid,
      localCoverUri = localCoverUri,
      scrobbleCountTotal = stats?.scrobbleCountTotal ?: 0L,
      onArtistClick = {
        val artistName = effectiveArtist.orEmpty()
        if (artistName.isBlank()) return@SongTopSection
        val artists = parseAllArtists(artistName)
        if (artists.size > 1) {
          artistPickerOpen = true
        } else {
          onOpenArtist(primaryArtist(artistName).ifBlank { artistName })
        }
      },
      onBack = { closing = true },
      onRefresh = { refresh() },
      isSongPlaying = isCurrentSongPlaying,
      onTogglePlayback = { handleSongPlaybackTap() },
    )

    Box(modifier = Modifier.weight(1f)) {
      if (requiresAnnotationOnGenius) {
        SongInsufficientAnnotationsPanel()
      } else {
        SongLeaderboardPanel(rows = listeners, onOpenProfile = onOpenProfile)
      }
    }

    SongExerciseFooterCta(
      label = exerciseButtonLabel,
      enabled = exerciseButtonEnabled,
      onClick = {
        if (requiresAnnotationOnGenius) {
          openGeniusAnnotate()
        } else if (studyStatusRequested && studyStatusLoading && !hasReadyExercises) {
          onShowMessage("Checking existing exercises...")
        } else if (hasReadyExercises) {
          Log.d(
            "LearnLaunch",
            "SongScreen CTA open learn ref='${studyStatus?.studySetRef}' trackId='${studyStatusTrackId ?: resolvedTrackId}' lang='$learnerLanguage'",
          )
          onOpenLearn(
            studyStatus?.studySetRef,
            studyStatusTrackId ?: resolvedTrackId,
            learnerLanguage,
            2,
            effectiveTitle.takeIf { it.isNotBlank() },
            effectiveArtist?.takeIf { it.isNotBlank() },
          )
        } else {
          requestStudySetGeneration()
        }
      },
    )
  }

  SongArtistPickerSheet(
    open = artistPickerOpen,
    effectiveArtist = effectiveArtist,
    onDismiss = { artistPickerOpen = false },
    onPickArtist = { artist ->
      artistPickerOpen = false
      onOpenArtist(artist)
    },
  )
}

private fun resolvePlayableSongTrack(
  tracks: List<MusicTrack>,
  resolvedTrackId: String,
  title: String,
  artist: String?,
): MusicTrack? {
  return tracks
    .asSequence()
    .filter { it.uri.isNotBlank() }
    .firstOrNull { track ->
      matchesSongForPlayback(
        track = track,
        resolvedTrackId = resolvedTrackId,
        title = title,
        artist = artist,
      )
    }
}

private fun matchesSongForPlayback(
  track: MusicTrack,
  resolvedTrackId: String,
  title: String,
  artist: String?,
): Boolean {
  val normalizedResolvedTrackId = normalizeBytes32(resolvedTrackId) ?: resolvedTrackId.trim().lowercase(Locale.US)
  val candidateTrackId = resolveSongTrackId(track)
  val normalizedCandidateTrackId = candidateTrackId?.let { normalizeBytes32(it) ?: it.trim().lowercase(Locale.US) }
  if (
    normalizedResolvedTrackId.isNotBlank() &&
    !normalizedCandidateTrackId.isNullOrBlank() &&
    normalizedResolvedTrackId == normalizedCandidateTrackId
  ) {
    return true
  }

  val targetTitleNorm = normalizeSongTitleForPlayback(title)
  if (targetTitleNorm.isBlank()) return false
  val candidateTitleNorm = normalizeSongTitleForPlayback(track.title)
  if (candidateTitleNorm.isBlank()) return false
  val titleMatch =
    candidateTitleNorm == targetTitleNorm ||
      candidateTitleNorm.contains(targetTitleNorm) ||
      targetTitleNorm.contains(candidateTitleNorm)
  if (!titleMatch) return false

  val targetArtistNorm = normalizeArtistName(artist.orEmpty())
  if (targetArtistNorm.isBlank()) return true
  val candidateArtistNorm = normalizeArtistName(track.artist)
  return (
    artistMatchesTarget(track.artist, targetArtistNorm) ||
      candidateArtistNorm.contains(targetArtistNorm) ||
      targetArtistNorm.contains(candidateArtistNorm)
    )
}

private fun normalizeSongTitleForPlayback(raw: String): String {
  return raw
    .trim()
    .lowercase(Locale.US)
    .replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), " ")
    .replace(Regex("\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b"), " ")
    .replace(Regex("[^a-z0-9]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
}
