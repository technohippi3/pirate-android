package com.pirate.app.scrobble

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "SpotifyMediaObserver"
private const val SPOTIFY_PACKAGE = "com.spotify.music"

internal class SpotifyMediaSessionObserver(
  private val context: Context,
  private val onMetadata: (TrackMetadata) -> Unit,
  private val onPlayback: (Boolean) -> Unit,
  private val onSessionGone: () -> Unit,
) {
  private val mediaSessionManager = context.getSystemService(MediaSessionManager::class.java)
  private val listenerComponent = SpotifyNotificationAccess.listenerComponent(context)
  private val callbackHandler = Handler(Looper.getMainLooper())
  private var started = false
  private var listenerRegistered = false
  private var activeController: MediaController? = null

  private val activeSessionsListener =
    MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
      attachSpotifyController(controllers.orEmpty())
    }

  private val controllerCallback =
    object : MediaController.Callback() {
      override fun onMetadataChanged(metadata: MediaMetadata?) {
        publishMetadata(metadata)
      }

      override fun onPlaybackStateChanged(state: PlaybackState?) {
        onPlayback(isPlayingState(state))
      }

      override fun onSessionDestroyed() {
        detachController()
        onSessionGone()
      }
    }

  fun start() {
    if (started) return
    started = true

    if (mediaSessionManager == null) {
      onSessionGone()
      return
    }

    ensureListenerRegistered()
    refresh()
  }

  fun refresh() {
    if (!started || mediaSessionManager == null) return

    if (!SpotifyNotificationAccess.hasNotificationAccess(context)) {
      if (listenerRegistered) {
        runCatching { mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        listenerRegistered = false
      }
      detachController()
      onSessionGone()
      return
    }

    ensureListenerRegistered()

    runCatching {
      val activeSessions = mediaSessionManager.getActiveSessions(listenerComponent)
      attachSpotifyController(activeSessions.orEmpty())
    }.onFailure { error ->
      Log.w(TAG, "Unable to refresh Spotify media sessions: ${error.message}")
      detachController()
      onSessionGone()
    }
  }

  fun refreshIfNeeded() {
    if (!started || mediaSessionManager == null) return
    if (listenerRegistered) return
    refresh()
  }

  fun stop() {
    if (!started) return
    started = false

    runCatching {
      if (listenerRegistered) {
        mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
      }
    }
    listenerRegistered = false
    detachController()
    onSessionGone()
  }

  private fun ensureListenerRegistered() {
    if (listenerRegistered || mediaSessionManager == null) return
    if (!SpotifyNotificationAccess.hasNotificationAccess(context)) return

    runCatching {
      mediaSessionManager.addOnActiveSessionsChangedListener(activeSessionsListener, listenerComponent)
      listenerRegistered = true
    }.onFailure { error ->
      Log.w(TAG, "Unable to register Spotify active-sessions listener: ${error.message}")
      listenerRegistered = false
    }
  }

  private fun attachSpotifyController(controllers: List<MediaController>) {
    val spotifyController = controllers.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
    val current = activeController

    if (spotifyController == null) {
      if (current != null) {
        detachController()
        onSessionGone()
      }
      return
    }

    if (current?.sessionToken == spotifyController.sessionToken) {
      publishMetadata(spotifyController.metadata)
      onPlayback(isPlayingState(spotifyController.playbackState))
      return
    }

    detachController()
    activeController = spotifyController
    spotifyController.registerCallback(controllerCallback, callbackHandler)
    publishMetadata(spotifyController.metadata)
    onPlayback(isPlayingState(spotifyController.playbackState))
  }

  private fun detachController() {
    val controller = activeController ?: return
    runCatching { controller.unregisterCallback(controllerCallback) }
    activeController = null
  }

  private fun publishMetadata(metadata: MediaMetadata?) {
    val safeMetadata = metadata ?: return
    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
    val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
    if (artist.isBlank() || title.isBlank()) {
      onSessionGone()
      return
    }

    val album = safeMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty().ifBlank { null }
    val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L }
    val mediaId = safeMetadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.trim().orEmpty().ifBlank { null }
    val mediaUri = safeMetadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)?.trim().orEmpty().ifBlank { null }
    val artworkUri =
      safeMetadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.trim().orEmpty().ifBlank { null }
    val artworkFallbackUri =
      safeMetadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)?.trim().orEmpty().ifBlank { null }
    val isAdvertisement =
      safeMetadata.getLong(SPOTIFY_ADVERTISEMENT_METADATA_KEY) != 0L ||
        looksLikeSpotifyAdvertisement(
          artist = artist,
          title = title,
          album = album,
          mediaId = mediaId,
          mediaUri = mediaUri,
        )

    if (isAdvertisement) {
      Log.d(TAG, "Skipping Spotify advertisement metadata: artist='$artist' title='$title'")
    }

    onMetadata(
      TrackMetadata(
        artist = artist,
        title = title,
        album = album,
        durationMs = durationMs,
        artworkUri = artworkUri,
        artworkFallbackUri = artworkFallbackUri,
        isScrobblable = !isAdvertisement,
      ),
    )
  }

  private fun isPlayingState(state: PlaybackState?): Boolean =
    state?.state == PlaybackState.STATE_PLAYING
}
