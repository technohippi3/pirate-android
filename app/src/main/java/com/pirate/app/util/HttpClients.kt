package com.pirate.app.util

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

object HttpClients {
  val Api: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .callTimeout(45, TimeUnit.SECONDS)
      .build()
  }
}
