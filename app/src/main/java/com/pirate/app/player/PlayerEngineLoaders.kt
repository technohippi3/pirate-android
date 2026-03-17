package com.pirate.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.pirate.app.music.MusicTrack

internal fun loadAndPlayMediaEngine(
  context: Context,
  track: MusicTrack,
  playWhenReady: Boolean,
  parsedUri: Uri,
  tag: String,
  mediaInfoNetworkBandwidth: Int,
  onSetMediaPlayer: (MediaPlayer) -> Unit,
  onSetIsPlaying: (Boolean) -> Unit,
  onSetDurationSec: (Float) -> Unit,
  onStartPlaybackService: () -> Unit,
  onSyncWidgetState: () -> Unit,
  onStartProgressUpdates: () -> Unit,
  onTrackEnded: () -> Unit,
  onTrackLoadFailed: () -> Unit,
) {
  val player = MediaPlayer()
  onSetMediaPlayer(player)

  player.setAudioAttributes(
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
      .build(),
  )

  player.setOnPreparedListener {
    val durationMs = runCatching { it.duration }.getOrDefault(0).coerceAtLeast(0)
    onSetDurationSec(durationMs / 1000f)

    if (playWhenReady) {
      runCatching { it.start() }
      onSetIsPlaying(true)
      onStartPlaybackService()
      onSyncWidgetState()
    }
    onStartProgressUpdates()
  }

  player.setOnCompletionListener {
    onSetIsPlaying(false)
    onTrackEnded()
  }

  player.setOnErrorListener { _, _, _ ->
    onSetIsPlaying(false)
    Log.w(tag, "MediaPlayer onError for track=${track.id} uri=${track.uri}")
    true
  }

  player.setOnInfoListener { _, what, extra ->
    when (what) {
      MediaPlayer.MEDIA_INFO_BUFFERING_START -> Log.d(tag, "buffering_start track=${track.id}")
      MediaPlayer.MEDIA_INFO_BUFFERING_END -> Log.d(tag, "buffering_end track=${track.id}")
      mediaInfoNetworkBandwidth -> Log.d(tag, "network_bandwidth track=${track.id} kbps=$extra")
    }
    false
  }

  try {
    player.setDataSource(context, parsedUri)
    player.prepareAsync()
  } catch (error: Throwable) {
    Log.e(tag, "Failed to load local track=${track.id} uri=${track.uri}", error)
    onTrackLoadFailed()
  }
}

internal fun loadAndPlayExoEngine(
  context: Context,
  track: MusicTrack,
  playWhenReady: Boolean,
  tag: String,
  onGetOrCreateExoCache: (Context) -> SimpleCache,
  onSetExoPlayer: (ExoPlayer) -> Unit,
  onSetIsPlaying: (Boolean) -> Unit,
  onSetDurationSec: (Float) -> Unit,
  onStartPlaybackService: () -> Unit,
  onSyncWidgetState: () -> Unit,
  onStartProgressUpdates: () -> Unit,
  onTrackEnded: () -> Unit,
  onTrackLoadFailed: () -> Unit,
) {
  val loadControl =
    DefaultLoadControl.Builder()
      .setBufferDurationsMs(
        15_000, // min buffer before/while playback
        60_000, // max buffer
        1_500, // buffer required for initial start
        5_000, // buffer required after rebuffer
      )
      .build()
  val player =
    ExoPlayer.Builder(context)
      .setLoadControl(loadControl)
      .build()
  onSetExoPlayer(player)

  var buffering = false
  player.addListener(
    object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        onSetIsPlaying(isPlaying)
        if (isPlaying) {
          onStartPlaybackService()
        }
        onSyncWidgetState()
      }

      override fun onPlaybackStateChanged(state: Int) {
        when (state) {
          Player.STATE_BUFFERING -> {
            if (!buffering) {
              buffering = true
              Log.d(tag, "buffering_start track=${track.id}")
            }
          }

          Player.STATE_READY -> {
            val durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            onSetDurationSec(durationMs / 1000f)
            if (buffering) {
              buffering = false
              Log.d(tag, "buffering_end track=${track.id}")
            }
            onStartProgressUpdates()
          }

          Player.STATE_ENDED -> {
            onSetIsPlaying(false)
            onTrackEnded()
          }
        }
      }

      override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        onSetIsPlaying(false)
        Log.e(tag, "ExoPlayer error track=${track.id} uri=${track.uri}", error)
      }
    },
  )

  try {
    val upstreamFactory =
      DefaultDataSource.Factory(
        context,
        DefaultHttpDataSource.Factory()
          .setAllowCrossProtocolRedirects(true)
          .setConnectTimeoutMs(15_000)
          .setReadTimeoutMs(30_000),
      )

    val mediaSource =
      runCatching {
        val cache = onGetOrCreateExoCache(context)
        val cacheFactory =
          CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        ProgressiveMediaSource.Factory(cacheFactory).createMediaSource(MediaItem.fromUri(track.uri))
      }.getOrElse {
        Log.w(tag, "Falling back to uncached Exo stream for ${track.id}: ${it.message}")
        ProgressiveMediaSource.Factory(upstreamFactory).createMediaSource(MediaItem.fromUri(track.uri))
      }

    player.setMediaSource(mediaSource)
    player.playWhenReady = playWhenReady
    player.prepare()
  } catch (error: Throwable) {
    Log.e(tag, "Failed to load remote track=${track.id} uri=${track.uri}", error)
    onTrackLoadFailed()
  }
}
