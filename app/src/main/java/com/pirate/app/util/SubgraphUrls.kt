package com.pirate.app.util

import com.pirate.app.BuildConfig

private const val GOLDSKY_SUBGRAPH_BASE_URL =
  "https://api.goldsky.com/api/public/project_cmjjtjqpvtip401u87vcp20wd/subgraphs"

private const val DEFAULT_SUBGRAPH_MUSIC_SOCIAL_URL =
  "$GOLDSKY_SUBGRAPH_BASE_URL/music-social-tempo-launch/20260317-181500/gn"

private const val DEFAULT_SUBGRAPH_PROFILES_URL =
  "$GOLDSKY_SUBGRAPH_BASE_URL/profiles-tempo-launch/20260317-173310/gn"

private const val DEFAULT_SUBGRAPH_PLAYLISTS_URL =
  "$GOLDSKY_SUBGRAPH_BASE_URL/playlist-feed-tempo-launch/20260317-173310/gn"

private const val DEFAULT_SUBGRAPH_STUDY_PROGRESS_URL =
  "$GOLDSKY_SUBGRAPH_BASE_URL/study-progress-tempo-launch/20260317-181500/gn"

private const val DEFAULT_SUBGRAPH_FEED_URL =
  "$GOLDSKY_SUBGRAPH_BASE_URL/tiktok-feed-tempo-launch/20260317-181500/gn"

private fun configuredSubgraphUrl(readValue: () -> String): String? {
  return runCatching(readValue).getOrDefault("").trim().removeSuffix("/").takeIf { it.isNotBlank() }
}

private fun withFallback(configured: String?, defaultUrl: String): List<String> {
  return listOfNotNull(configured, defaultUrl).distinct()
}

fun tempoMusicSocialSubgraphUrls(): List<String> {
  return withFallback(
    configured = configuredSubgraphUrl { BuildConfig.SUBGRAPH_MUSIC_SOCIAL_URL },
    defaultUrl = DEFAULT_SUBGRAPH_MUSIC_SOCIAL_URL,
  )
}

fun tempoProfilesSubgraphUrls(): List<String> {
  return withFallback(
    configured = configuredSubgraphUrl { BuildConfig.SUBGRAPH_PROFILES_URL },
    defaultUrl = DEFAULT_SUBGRAPH_PROFILES_URL,
  )
}

fun tempoPlaylistsSubgraphUrls(): List<String> {
  return withFallback(
    configured = configuredSubgraphUrl { BuildConfig.SUBGRAPH_PLAYLISTS_URL },
    defaultUrl = DEFAULT_SUBGRAPH_PLAYLISTS_URL,
  )
}

fun tempoStudyProgressSubgraphUrl(): String {
  return withFallback(
    configured = configuredSubgraphUrl { BuildConfig.SUBGRAPH_STUDY_PROGRESS_URL },
    defaultUrl = DEFAULT_SUBGRAPH_STUDY_PROGRESS_URL,
  ).first()
}

fun tempoFeedSubgraphUrls(): List<String> {
  return withFallback(
    configured = configuredSubgraphUrl { BuildConfig.SUBGRAPH_FEED_URL },
    defaultUrl = DEFAULT_SUBGRAPH_FEED_URL,
  )
}
