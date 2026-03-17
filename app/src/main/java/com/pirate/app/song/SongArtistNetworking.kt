package com.pirate.app.song

import com.pirate.app.util.HttpClients
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType

internal val songArtistClient = HttpClients.Api
internal val studySetGenerateClient by lazy {
  songArtistClient
    .newBuilder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(150, TimeUnit.SECONDS)
    .build()
}
internal val songArtistJsonMediaType = "application/json; charset=utf-8".toMediaType()
