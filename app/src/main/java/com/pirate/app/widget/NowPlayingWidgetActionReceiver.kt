package com.pirate.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives broadcast intents from widget button presses.
 * Delegates to a static PlayerController reference set by the app runtime.
 */
class NowPlayingWidgetActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    val player = playerRef ?: run {
      Log.w("WidgetAction", "No PlayerController registered")
      return
    }
    when (action) {
      "com.pirate.app.WIDGET_PLAY_PAUSE" -> player.togglePlayPause()
      "com.pirate.app.WIDGET_SKIP_NEXT" -> player.skipNext()
    }
  }

  companion object {
    @Volatile
    var playerRef: com.pirate.app.player.PlayerController? = null
  }
}
