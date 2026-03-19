package sc.pirate.app.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import sc.pirate.app.MainActivity
import sc.pirate.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.URL

class PlaybackService : Service() {

  companion object {
    private const val TAG = "PlaybackService"
    private const val CHANNEL_ID = "now_playing"
    private const val NOTIFICATION_ID = 1

    const val ACTION_UPDATE = "sc.pirate.app.player.UPDATE"
    const val ACTION_STOP = "sc.pirate.app.player.STOP_SERVICE"

    @Volatile
    var playerRef: PlayerController? = null
  }

  private lateinit var mediaSession: MediaSession
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var cachedArtwork: Bitmap? = null
  private var cachedArtworkUri: String? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()

    mediaSession = MediaSession(this, "PiratePlayer")
    mediaSession.setCallback(object : MediaSession.Callback() {
      override fun onPlay() { playerRef?.togglePlayPause() }
      override fun onPause() { playerRef?.togglePlayPause() }
      override fun onSkipToNext() { playerRef?.skipNext() }
      override fun onSkipToPrevious() { playerRef?.skipPrevious() }
      override fun onSeekTo(pos: Long) { playerRef?.seekTo(pos / 1000f) }
      override fun onStop() {
        playerRef?.stop()
        stopSelf()
      }
    })
    mediaSession.isActive = true
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        mediaSession.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_UPDATE -> {
        updateSessionAndNotification()
      }
      else -> {
        // Initial start — post a minimal notification to satisfy foreground requirement
        startForeground(NOTIFICATION_ID, buildNotification(null, null, false, null))
        updateSessionAndNotification()
      }
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    mediaSession.isActive = false
    mediaSession.release()
    scope.cancel()
    super.onDestroy()
  }

  private fun updateSessionAndNotification() {
    val player = playerRef ?: return
    val track = player.currentTrack.value
    if (track == null) {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return
    }

    val isPlaying = player.isPlaying.value
    val progress = player.progress.value

    // Update playback state
    val stateBuilder = PlaybackState.Builder()
      .setActions(
        PlaybackState.ACTION_PLAY or
        PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or
        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
        PlaybackState.ACTION_SEEK_TO or
        PlaybackState.ACTION_STOP,
      )
      .setState(
        if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
        (progress.positionSec * 1000).toLong(),
        if (isPlaying) 1f else 0f,
      )
    mediaSession.setPlaybackState(stateBuilder.build())

    // Update metadata
    val metaBuilder = MediaMetadata.Builder()
      .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
      .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
      .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
      .putLong(MediaMetadata.METADATA_KEY_DURATION, (progress.durationSec * 1000).toLong())

    // Load artwork async if URI changed
    val artUri = track.artworkUri
    if (artUri != null && artUri == cachedArtworkUri && cachedArtwork != null) {
      metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cachedArtwork)
      mediaSession.setMetadata(metaBuilder.build())
      postNotification(track.title, track.artist, isPlaying, cachedArtwork)
    } else if (!artUri.isNullOrBlank()) {
      // Set metadata without art first, then update when art loads
      mediaSession.setMetadata(metaBuilder.build())
      postNotification(track.title, track.artist, isPlaying, null)
      scope.launch {
        val bmp = loadBitmap(artUri)
        cachedArtwork = bmp
        cachedArtworkUri = artUri
        if (bmp != null) {
          val updated = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, (progress.durationSec * 1000).toLong())
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
            .build()
          mediaSession.setMetadata(updated)
          postNotification(track.title, track.artist, isPlaying, bmp)
        }
      }
    } else {
      cachedArtwork = null
      cachedArtworkUri = null
      mediaSession.setMetadata(metaBuilder.build())
      postNotification(track.title, track.artist, isPlaying, null)
    }
  }

  private fun postNotification(title: String, artist: String, isPlaying: Boolean, artwork: Bitmap?) {
    val notification = buildNotification(title, artist, isPlaying, artwork)
    val nm = getSystemService(NotificationManager::class.java)
    nm.notify(NOTIFICATION_ID, notification)
  }

  private fun buildNotification(title: String?, artist: String?, isPlaying: Boolean, artwork: Bitmap?): Notification {
    val contentIntent = PendingIntent.getActivity(
      this, 0,
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
      },
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    val style = Notification.MediaStyle()
      .setMediaSession(mediaSession.sessionToken)
      .setShowActionsInCompactView(0, 1, 2)

    val builder = Notification.Builder(this, CHANNEL_ID)
      .setContentTitle(title ?: "Pirate")
      .setContentText(artist ?: "")
      .setSmallIcon(R.drawable.ic_music_note)
      .setLargeIcon(artwork)
      .setContentIntent(contentIntent)
      .setStyle(style)
      .setOngoing(isPlaying)
      .setVisibility(Notification.VISIBILITY_PUBLIC)

    // Previous
    builder.addAction(
      Notification.Action.Builder(
        android.R.drawable.ic_media_previous,
        "Previous",
        mediaAction(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
      ).build(),
    )

    // Play/Pause
    builder.addAction(
      Notification.Action.Builder(
        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        if (isPlaying) "Pause" else "Play",
        mediaAction(if (isPlaying) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY),
      ).build(),
    )

    // Next
    builder.addAction(
      Notification.Action.Builder(
        android.R.drawable.ic_media_next,
        "Next",
        mediaAction(PlaybackState.ACTION_SKIP_TO_NEXT),
      ).build(),
    )

    return builder.build()
  }

  private fun mediaAction(action: Long): PendingIntent {
    val keyCode = when (action) {
      PlaybackState.ACTION_PLAY -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
      PlaybackState.ACTION_PAUSE -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
      PlaybackState.ACTION_SKIP_TO_NEXT -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
      PlaybackState.ACTION_SKIP_TO_PREVIOUS -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
      else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    }
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
      putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
      setPackage(packageName)
    }
    return PendingIntent.getBroadcast(
      this, keyCode,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      "Now Playing",
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = "Shows current track with playback controls"
      setShowBadge(false)
    }
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  private fun loadBitmap(url: String): Bitmap? {
    return try {
      URL(url).openStream().use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to load artwork: ${e.message}")
      null
    }
  }
}
