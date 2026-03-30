package sc.pirate.app

object PirateChainConfig {
  const val BASE_SEPOLIA_CHAIN_ID = 84_532L
  const val BASE_SEPOLIA_RPC_URL = "https://base-sepolia-rpc.publicnode.com"
  const val BASE_SEPOLIA_EXPLORER_URL = "https://sepolia.basescan.org"
  const val BASE_SEPOLIA_REGISTRY_V2 = "0xB65f7DAD7278ce2b9c14De2b68a3dBc8964F208c"
  const val BASE_SEPOLIA_RECORDS_V1 = "0x65f1Df08DB7ce0E2DB89BCA5169dcC7A5d3A0fCE"
  const val BASE_SEPOLIA_PROFILE_V2 = "0xead9af7446be41d0ada10028b62b72c2136ce03d"

  const val STORY_AENEID_CHAIN_ID = 1_315L
  const val STORY_AENEID_RPC_URL = "https://aeneid.storyrpc.io"
  const val STORY_AENEID_EXPLORER_URL = "https://aeneid.storyscan.io"

  private const val GOLDSKY_SUBGRAPH_BASE_URL =
    "https://api.goldsky.com/api/public/project_cmjjtjqpvtip401u87vcp20wd/subgraphs"

  const val STORY_MUSIC_SOCIAL_SUBGRAPH_URL =
    "$GOLDSKY_SUBGRAPH_BASE_URL/music-social-story-aeneid/20260330-175305/gn"
  const val BASE_PROFILES_SUBGRAPH_URL =
    "$GOLDSKY_SUBGRAPH_BASE_URL/profiles-base-sepolia/20260328-185319/gn"
  const val STORY_PLAYLISTS_SUBGRAPH_URL =
    "$GOLDSKY_SUBGRAPH_BASE_URL/playlist-feed-story-aeneid/20260329-001500/gn"
  const val STORY_STUDY_PROGRESS_SUBGRAPH_URL =
    "$GOLDSKY_SUBGRAPH_BASE_URL/study-progress-story-aeneid/20260328-194600/gn"
  const val STORY_FEED_SUBGRAPH_URL =
    "$GOLDSKY_SUBGRAPH_BASE_URL/tiktok-feed-story-aeneid/20260328-194600/gn"
}
