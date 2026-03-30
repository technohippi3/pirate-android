package sc.pirate.app.auth.privy

import android.content.Context
import io.privy.logging.PrivyLogLevel
import io.privy.sdk.Privy
import io.privy.sdk.PrivyConfig
import sc.pirate.app.BuildConfig

internal object PrivyClientStore {
  @Volatile
  private var currentKey: String? = null

  @Volatile
  private var currentClient: Privy? = null

  fun get(
    context: Context,
    config: PrivyRuntimeConfig,
  ): Privy {
    val appContext = context.applicationContext
    val key = "${config.appId}:${config.appClientId}"
    currentClient?.takeIf { currentKey == key }?.let { return it }

    return synchronized(this) {
      currentClient?.takeIf { currentKey == key }?.let { return@synchronized it }
      val client =
        Privy.init(
          context = appContext,
          config =
            PrivyConfig(
              appId = config.appId,
              appClientId = config.appClientId,
              logLevel =
                if (BuildConfig.DEBUG) {
                  PrivyLogLevel.DEBUG
                } else {
                  PrivyLogLevel.NONE
                },
            ),
        )
      currentKey = key
      currentClient = client
      client
    }
  }
}
