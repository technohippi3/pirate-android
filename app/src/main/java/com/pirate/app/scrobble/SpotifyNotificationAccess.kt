package com.pirate.app.scrobble

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

internal object SpotifyNotificationAccess {
  fun listenerComponent(context: Context): ComponentName =
    ComponentName(context, SpotifyNotificationListenerService::class.java)

  fun hasNotificationAccess(context: Context): Boolean {
    val expected = listenerComponent(context)
    val raw =
      Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
      ).orEmpty()
    if (raw.isBlank()) return false

    return raw
      .split(':')
      .asSequence()
      .mapNotNull { ComponentName.unflattenFromString(it) }
      .any { it.packageName == expected.packageName && it.className == expected.className }
  }

  fun openNotificationAccessSettings(activity: Activity) {
    runCatching {
      activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }.onFailure {
      runCatching {
        activity.startActivity(Intent(Settings.ACTION_SETTINGS))
      }
    }
  }
}
