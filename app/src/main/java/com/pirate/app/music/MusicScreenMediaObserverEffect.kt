package com.pirate.app.music

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun MusicScreenMediaObserverEffect(
  context: Context,
  scope: CoroutineScope,
  hasPermission: Boolean,
  autoSyncJob: Job?,
  onSetAutoSyncJob: (Job?) -> Unit,
  onRunSilentScan: suspend () -> Unit,
) {
  DisposableEffect(hasPermission) {
    if (!hasPermission) {
      return@DisposableEffect onDispose {}
    }

    val observer =
      object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
          autoSyncJob?.cancel()
          onSetAutoSyncJob(
            scope.launch {
              delay(1200)
              onRunSilentScan()
            },
          )
        }
      }
    context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
    onDispose {
      runCatching { context.contentResolver.unregisterContentObserver(observer) }
      autoSyncJob?.cancel()
      onSetAutoSyncJob(null)
    }
  }
}
