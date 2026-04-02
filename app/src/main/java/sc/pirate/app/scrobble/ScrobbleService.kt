package sc.pirate.app.scrobble

import android.content.Context
import android.util.Log
import sc.pirate.app.BuildConfig
import sc.pirate.app.auth.PirateAuthUiState
import sc.pirate.app.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "PirateScrobble"
private const val SESSION_KEY = "local"
private const val SPOTIFY_SESSION_KEY = "spotify"
private val TICK_INTERVAL_MS = if (BuildConfig.DEBUG) 1_000L else 15_000L

class ScrobbleService(
  private val appContext: Context,
  private val player: PlayerController,
  private val getAuthState: () -> PirateAuthUiState,
  private val onShowMessage: (String) -> Unit,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val submitMutex = Mutex()

  private val engine =
    ScrobbleEngine(onScrobbleReady = { scrobble ->
      if (BuildConfig.DEBUG) {
        Log.d(
          TAG,
          "Scrobble ready: artist='${scrobble.artist}' title='${scrobble.title}' playedAt=${scrobble.playedAtSec}",
        )
        onShowMessage("Scrobble queued: ${scrobble.title}")
      }
      scope.launch { submit(scrobble) }
    })
  private val spotifyObserver =
    SpotifyMediaSessionObserver(
      context = appContext,
      onMetadata = { metadata -> engine.onMetadata(SPOTIFY_SESSION_KEY, metadata) },
      onPlayback = { playing -> engine.onPlayback(SPOTIFY_SESSION_KEY, playing) },
      onSessionGone = { engine.onSessionGone(SPOTIFY_SESSION_KEY) },
    )

  private var tickJob: Job? = null
  private var trackJob: Job? = null
  private var playbackJob: Job? = null

  fun start() {
    if (tickJob != null) return

    tickJob =
      scope.launch {
        while (true) {
          delay(TICK_INTERVAL_MS)
          spotifyObserver.refreshIfNeeded()
          engine.tick()
        }
      }

    trackJob =
      scope.launch {
        player.currentTrack.collectLatest { track ->
          if (BuildConfig.DEBUG) {
            Log.d(
              TAG,
              "Track update: ${track?.artist ?: "<none>"} - ${track?.title ?: "<none>"} durationSec=${track?.durationSec ?: 0}",
            )
          }
          if (track == null) {
            engine.onSessionGone(SESSION_KEY)
            return@collectLatest
          }
          engine.onMetadata(
            SESSION_KEY,
            TrackMetadata(
              artist = track.artist,
              title = track.title,
              album = track.album.ifBlank { null },
              durationMs = track.durationSec.takeIf { it > 0 }?.toLong()?.times(1000L),
              artworkUri = track.artworkUri,
              artworkFallbackUri = track.artworkFallbackUri,
            ),
          )
        }
      }

    playbackJob =
      scope.launch {
        player.isPlaying.collectLatest { playing ->
          if (BuildConfig.DEBUG) {
            Log.d(TAG, "Playback update: isPlaying=$playing")
          }
          engine.onPlayback(SESSION_KEY, playing)
        }
      }

    spotifyObserver.start()
  }

  fun stop() {
    spotifyObserver.stop()
    tickJob?.cancel()
    tickJob = null
    trackJob?.cancel()
    trackJob = null
    playbackJob?.cancel()
    playbackJob = null
    engine.onSessionGone(SESSION_KEY)
  }

  fun close() {
    stop()
    scope.coroutineContext.cancel()
  }

  private suspend fun submit(scrobble: ReadyScrobble) {
    submitMutex.withLock {
      val auth = getAuthState()
      if (auth.provider != PirateAuthUiState.AuthProvider.PRIVY) {
        Log.d(TAG, "Scrobble skipped: unsupported auth provider=${auth.provider}")
        if (BuildConfig.DEBUG) onShowMessage("Scrobble skipped (sign in again)")
        return
      }
      val input =
        ScrobbleInput(
          artist = scrobble.artist,
          title = scrobble.title,
          album = scrobble.album,
          durationSec = ((scrobble.durationMs ?: 0L) / 1000L).toInt().coerceAtLeast(0),
          playedAtSec = scrobble.playedAtSec.coerceAtLeast(0L),
        )
      val result =
        PrivyScrobbleClient.submitScrobble(
          context = appContext,
          authState = auth,
          input = input,
        )

      if (!result.success) {
        Log.w(TAG, "Scrobble submit failed: ${result.error}")
        if (BuildConfig.DEBUG) {
          onShowMessage("Scrobble failed: ${result.error ?: "unknown error"}")
        }
        return
      }

      val state = if (result.pendingConfirmation) "submitted-pending" else "confirmed"
      Log.d(
        TAG,
        "Scrobble $state mode=privy-api tx=${result.txHash} trackId=${result.trackId} registerPath=${result.usedRegisterPath}",
      )
      if (BuildConfig.DEBUG) {
        if (result.pendingConfirmation) {
          onShowMessage("Scrobble submitted (pending): ${input.title}")
        } else {
          onShowMessage("Scrobbled: ${input.title}")
        }
      }

      // Scrobble covers are intentionally disabled for cross-profile consistency.
    }
  }
}
