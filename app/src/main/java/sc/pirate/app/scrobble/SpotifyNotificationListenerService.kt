package sc.pirate.app.scrobble

import android.service.notification.NotificationListenerService
import android.util.Log

private const val TAG = "SpotifyNotifListener"

class SpotifyNotificationListenerService : NotificationListenerService() {
  override fun onListenerConnected() {
    super.onListenerConnected()
    Log.d(TAG, "Notification listener connected")
  }

  override fun onListenerDisconnected() {
    super.onListenerDisconnected()
    Log.d(TAG, "Notification listener disconnected")
  }
}
