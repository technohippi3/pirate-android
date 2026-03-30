package sc.pirate.app.util

import sc.pirate.app.BuildConfig
import sc.pirate.app.PirateChainConfig

private fun configuredSubgraphUrl(readValue: () -> String): String? {
  return runCatching(readValue).getOrDefault("").trim().removeSuffix("/").takeIf { it.isNotBlank() }
}

private fun withFallback(configured: String?, defaultUrl: String): List<String> {
  return listOfNotNull(configured, defaultUrl).distinct()
}

fun tempoMusicSocialSubgraphUrls(): List<String> {
  return withFallback(
    configured = configuredSubgraphUrl { BuildConfig.SUBGRAPH_MUSIC_SOCIAL_URL },
    defaultUrl = PirateChainConfig.STORY_MUSIC_SOCIAL_SUBGRAPH_URL,
  )
}

fun tempoProfilesSubgraphUrls(): List<String> {
  return withFallback(
    configured = configuredSubgraphUrl { BuildConfig.SUBGRAPH_PROFILES_URL },
    defaultUrl = PirateChainConfig.BASE_PROFILES_SUBGRAPH_URL,
  )
}

fun tempoPlaylistsSubgraphUrls(): List<String> {
  return withFallback(
    configured = configuredSubgraphUrl { BuildConfig.SUBGRAPH_PLAYLISTS_URL },
    defaultUrl = PirateChainConfig.STORY_PLAYLISTS_SUBGRAPH_URL,
  )
}

fun tempoStudyProgressSubgraphUrl(): String {
  return withFallback(
    configured = configuredSubgraphUrl { BuildConfig.SUBGRAPH_STUDY_PROGRESS_URL },
    defaultUrl = PirateChainConfig.STORY_STUDY_PROGRESS_SUBGRAPH_URL,
  ).first()
}

fun tempoFeedSubgraphUrls(): List<String> {
  return withFallback(
    configured = configuredSubgraphUrl { BuildConfig.SUBGRAPH_FEED_URL },
    defaultUrl = PirateChainConfig.STORY_FEED_SUBGRAPH_URL,
  )
}
