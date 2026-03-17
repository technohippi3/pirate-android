package com.pirate.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.pirate.app.theme.PirateTheme

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    AppLocaleManager.applyStoredLocale(this)
    super.onCreate(savedInstanceState)
    handleDeepLink(intent)
    setContent {
      PirateTheme { PirateApp(activity = this@MainActivity) }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleDeepLink(intent)
  }

  private fun handleDeepLink(intent: Intent?) {
    val uri = intent?.dataString ?: return
    if (uri.startsWith("heaven://self") || uri.startsWith("pirate://self")) {
      // Self.xyz verification callback — the polling loop in SelfVerificationGate
      // will detect the verified status automatically. Just log the return.
      Log.i(TAG, "Self.xyz callback received: $uri")
    }
  }
}
