package com.pirate.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.pirate.app.MainActivity
import com.pirate.app.R

object WidgetKeys {
  val TITLE = stringPreferencesKey("title")
  val ARTIST = stringPreferencesKey("artist")
  val ARTWORK_URI = stringPreferencesKey("artworkUri")
  val IS_PLAYING = booleanPreferencesKey("isPlaying")
  val VERSION = intPreferencesKey("version")
}

class NowPlayingWidget : GlanceAppWidget() {

  companion object {
    /** Push new state into Glance's own DataStore and trigger re-render for all widget instances. */
    suspend fun pushState(
      context: Context,
      title: String,
      artist: String,
      artworkUri: String?,
      isPlaying: Boolean,
    ) {
      val glanceIds = getGlanceIds(context)
      for (id in glanceIds) {
        updateAppWidgetState(context, id) { prefs ->
          prefs[WidgetKeys.TITLE] = title
          prefs[WidgetKeys.ARTIST] = artist
          if (artworkUri != null) prefs[WidgetKeys.ARTWORK_URI] = artworkUri
          else prefs.remove(WidgetKeys.ARTWORK_URI)
          prefs[WidgetKeys.IS_PLAYING] = isPlaying
          prefs[WidgetKeys.VERSION] = (prefs[WidgetKeys.VERSION] ?: 0) + 1
        }
      }
      NowPlayingWidget().updateAll(context)
    }

    suspend fun clearState(context: Context) {
      val glanceIds = getGlanceIds(context)
      for (id in glanceIds) {
        updateAppWidgetState(context, id) { prefs ->
          prefs.clear()
        }
      }
      NowPlayingWidget().updateAll(context)
    }

    private suspend fun getGlanceIds(context: Context): List<GlanceId> {
      return GlanceAppWidgetManager(context).getGlanceIds(NowPlayingWidget::class.java)
    }

    private fun loadArtwork(context: Context, uriString: String): Bitmap? {
      return try {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
          BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 4 })
        }
      } catch (_: Exception) {
        try {
          java.net.URL(uriString).openStream().use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 4 })
          }
        } catch (_: Exception) { null }
      }
    }
  }

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    provideContent {
      val prefs = currentState<Preferences>()
      val title = prefs[WidgetKeys.TITLE] ?: ""
      val artist = prefs[WidgetKeys.ARTIST] ?: ""
      val isPlaying = prefs[WidgetKeys.IS_PLAYING] ?: false
      val artworkUri = prefs[WidgetKeys.ARTWORK_URI]

      // Note: bitmap loading inside composable isn't ideal but Glance
      // re-runs provideContent on each updateAll, so it works.
      val artworkBitmap = if (!artworkUri.isNullOrBlank()) loadArtwork(context, artworkUri) else null

      NowPlayingContent(
        title = title,
        artist = artist,
        isPlaying = isPlaying,
        artworkBitmap = artworkBitmap,
      )
    }
  }
}

@Composable
private fun NowPlayingContent(
  title: String,
  artist: String,
  isPlaying: Boolean,
  artworkBitmap: Bitmap?,
) {
  val hasTrack = title.isNotBlank()
  val white = ColorProvider(R.color.widget_text_white)
  val muted = ColorProvider(R.color.widget_text_muted)

  Box(
    modifier = GlanceModifier
      .fillMaxSize()
      .background(ImageProvider(R.drawable.widget_background))
      .clickable(actionStartActivity<MainActivity>()),
    contentAlignment = Alignment.Center,
  ) {
    if (!hasTrack) {
      Text(
        text = "No track playing",
        style = TextStyle(color = muted, fontSize = 16.sp),
      )
    } else {
      Row(
        modifier = GlanceModifier
          .fillMaxSize()
          .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = GlanceModifier
            .size(52.dp)
            .background(ImageProvider(R.drawable.widget_art_bg)),
          contentAlignment = Alignment.Center,
        ) {
          if (artworkBitmap != null) {
            Image(
              provider = ImageProvider(artworkBitmap),
              contentDescription = "Album art",
              modifier = GlanceModifier.size(52.dp),
              contentScale = ContentScale.Crop,
            )
          } else {
            Image(
              provider = ImageProvider(R.drawable.ic_music_note),
              contentDescription = "Album art",
              modifier = GlanceModifier.size(28.dp),
            )
          }
        }

        Spacer(modifier = GlanceModifier.width(12.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
          Text(
            text = title,
            style = TextStyle(color = white, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
          )
          Text(
            text = artist,
            style = TextStyle(color = muted, fontSize = 14.sp),
            maxLines = 1,
          )
        }

        Spacer(modifier = GlanceModifier.width(8.dp))

        Image(
          provider = ImageProvider(
            if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
          ),
          contentDescription = if (isPlaying) "Pause" else "Play",
          modifier = GlanceModifier
            .size(40.dp)
            .clickable(actionRunCallback<TogglePlayPauseAction>()),
        )

        Spacer(modifier = GlanceModifier.width(4.dp))

        Image(
          provider = ImageProvider(R.drawable.ic_widget_skip_next),
          contentDescription = "Skip next",
          modifier = GlanceModifier
            .size(40.dp)
            .clickable(actionRunCallback<SkipNextAction>()),
        )
      }
    }
  }
}

class TogglePlayPauseAction : ActionCallback {
  override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
    NowPlayingWidgetActionReceiver.playerRef?.togglePlayPause()
  }
}

class SkipNextAction : ActionCallback {
  override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
    NowPlayingWidgetActionReceiver.playerRef?.skipNext()
  }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
