package sc.pirate.app.scrobble

import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import sc.pirate.app.BuildConfig
import sc.pirate.app.auth.LegacySignerAccountStore
import sc.pirate.app.auth.PirateAuthUiState
import sc.pirate.app.tempo.SessionKeyManager
import sc.pirate.app.tempo.TempoPasskeyManager
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
  private val activity: FragmentActivity?,
  private val player: PlayerController,
  private val getAuthState: () -> PirateAuthUiState,
  private val onShowMessage: (String) -> Unit,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val submitMutex = Mutex()
  private var sessionKey: SessionKeyManager.SessionKey? = null

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
      val legacySignerAccount =
        LegacySignerAccountStore.loadAccount(
          context = appContext,
          ownerAddress = auth.activeAddress(),
        )

      if (legacySignerAccount == null) {
        Log.d(TAG, "Scrobble skipped: no Android signing path")
        if (BuildConfig.DEBUG) onShowMessage("Scrobble skipped (no Android signing path)")
        return
      }

      val input =
        TempoScrobbleInput(
          artist = scrobble.artist,
          title = scrobble.title,
          album = scrobble.album,
          durationSec = ((scrobble.durationMs ?: 0L) / 1000L).toInt().coerceAtLeast(0),
          playedAtSec = scrobble.playedAtSec.coerceAtLeast(0L),
        )

      val hostActivity = activity
      var usedPasskeyPath = false
      var result: TempoScrobbleSubmitResult

      val activeSession = ensureSessionKey(legacySignerAccount)
      if (activeSession == null) {
        if (hostActivity == null) {
          Log.w(TAG, "Scrobble paused: session key unavailable and no activity for passkey prompt")
          return
        }
        usedPasskeyPath = true
        result =
          TempoScrobbleApi.submitScrobbleWithPasskey(
            activity = hostActivity,
            account = legacySignerAccount,
            input = input,
          )
        if (!result.success && shouldRetryPasskeySubmission(result.error)) {
          Log.w(TAG, "Passkey scrobble was not confirmed; retrying once")
          result =
            TempoScrobbleApi.submitScrobbleWithPasskey(
              activity = hostActivity,
              account = legacySignerAccount,
              input = input,
            )
        }
      } else {
        result =
          TempoScrobbleApi.submitScrobble(
            account = legacySignerAccount,
            sessionKey = activeSession,
            input = input,
          )

        if (!result.success && shouldRefreshSessionKey(result.error) && hostActivity != null) {
          Log.w(TAG, "Scrobble session submit failed (${result.error}); refreshing session key and retrying once")
          hostActivity?.let { SessionKeyManager.clear(it) }
          sessionKey = null
          val refreshedSession = ensureSessionKey(legacySignerAccount)
          if (refreshedSession != null) {
            result =
              TempoScrobbleApi.submitScrobble(
                account = legacySignerAccount,
                sessionKey = refreshedSession,
                input = input,
              )
          }
        }

      }

      if (!result.success) {
        Log.w(TAG, "Scrobble submit failed: ${result.error}")
        if (BuildConfig.DEBUG) {
          onShowMessage("Scrobble failed: ${result.error ?: "unknown error"}")
        }
        return
      }

      val state = if (result.pendingConfirmation) "submitted-pending" else "confirmed"
      val mode =
        when {
          usedPasskeyPath -> "passkey"
          else -> "relay"
        }
      Log.d(
        TAG,
        "Scrobble $state mode=$mode tx=${result.txHash} trackId=${result.trackId} registerPath=${result.usedRegisterPath}",
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

  private suspend fun ensureSessionKey(
    account: TempoPasskeyManager.PasskeyAccount,
  ): SessionKeyManager.SessionKey? {
    val current = sessionKey
    if (
      SessionKeyManager.isValid(current, ownerAddress = account.address) &&
      current?.keyAuthorization?.isNotEmpty() == true
    ) {
      return current
    }
    sessionKey = null

    val hostActivity = activity
    if (hostActivity == null) {
      if (BuildConfig.DEBUG) {
        onShowMessage("Scrobble paused: missing activity for session key auth")
      }
      return null
    }

    val loaded = SessionKeyManager.load(hostActivity)
    if (
      SessionKeyManager.isValid(loaded, ownerAddress = account.address) &&
      loaded?.keyAuthorization?.isNotEmpty() == true
    ) {
      sessionKey = loaded
      return loaded
    }
    // Keep persisted session key when account mismatches; onboarding/account switching
    // can transiently present a different account and should not wipe a valid key.
    return null
  }

  private fun shouldRetryPasskeySubmission(error: String?): Boolean {
    if (error.isNullOrBlank()) return false
    val message = error.lowercase()
    return message.contains("dropped before inclusion") ||
      message.contains("not confirmed before expiry") ||
      message.contains("timed out waiting for transaction receipt") ||
      (message.contains("not found") && message.contains("transaction"))
  }

  private fun shouldRefreshSessionKey(error: String?): Boolean {
    if (error.isNullOrBlank()) return false
    val message = error.lowercase()
    return message.contains("reverted on-chain") ||
      message.contains("invalid signature") ||
      message.contains("sender signature") ||
      message.contains("key authorization") ||
      message.contains("key_authorization") ||
      message.contains("keychain") ||
      message.contains("unknown key") ||
      message.contains("unauthorized")
  }

}
