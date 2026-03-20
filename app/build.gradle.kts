import java.util.Properties

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

val signingProps = Properties().apply {
  val propsFile = rootProject.file("signing.properties")
  if (propsFile.exists()) {
    propsFile.inputStream().use(::load)
  }
}

fun signingProp(name: String): String? =
  signingProps.getProperty(name)
    ?.trim()
    ?.takeIf { it.isNotBlank() }

android {
  namespace = "sc.pirate.app"
  compileSdk = 36

  signingConfigs {
    val storeFilePath = signingProp("storeFile")
    if (storeFilePath != null) {
      create("release") {
        storeFile = file(storeFilePath)
        storePassword = signingProp("storePassword")
        keyAlias = signingProp("keyAlias")
        keyPassword = signingProp("keyPassword")
      }
    }
  }

  defaultConfig {
    applicationId = "sc.pirate.app"
    minSdk = 24
    targetSdk = 36
    versionCode = 2
    versionName = "0.1.0-alpha.2"

    fun projectStringProperty(name: String): String? =
      (project.findProperty(name) as String?)
        ?.replace("\"", "\\\"")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    fun goldskySubgraphUrl(slug: String, version: String): String =
      "https://api.goldsky.com/api/public/project_cmjjtjqpvtip401u87vcp20wd/subgraphs/$slug/$version/gn"

    val subgraphMusicSocialUrl =
      projectStringProperty("SUBGRAPH_MUSIC_SOCIAL_URL")
        ?: goldskySubgraphUrl("music-social-tempo-launch", "20260317-181500")
    buildConfigField("String", "SUBGRAPH_MUSIC_SOCIAL_URL", "\"$subgraphMusicSocialUrl\"")

    val subgraphProfilesUrl =
      projectStringProperty("SUBGRAPH_PROFILES_URL")
        ?: goldskySubgraphUrl("profiles-tempo-launch", "20260317-173310")
    buildConfigField("String", "SUBGRAPH_PROFILES_URL", "\"$subgraphProfilesUrl\"")

    val subgraphPlaylistsUrl =
      projectStringProperty("SUBGRAPH_PLAYLISTS_URL")
        ?: goldskySubgraphUrl("playlist-feed-tempo-launch", "20260317-173310")
    buildConfigField("String", "SUBGRAPH_PLAYLISTS_URL", "\"$subgraphPlaylistsUrl\"")

    val subgraphStudyProgressUrl =
      projectStringProperty("SUBGRAPH_STUDY_PROGRESS_URL")
        ?: goldskySubgraphUrl("study-progress-tempo-launch", "20260317-181500")
    buildConfigField("String", "SUBGRAPH_STUDY_PROGRESS_URL", "\"$subgraphStudyProgressUrl\"")

    val subgraphFeedUrl =
      projectStringProperty("SUBGRAPH_FEED_URL")
        ?: goldskySubgraphUrl("tiktok-feed-tempo-launch", "20260317-181500")
    buildConfigField("String", "SUBGRAPH_FEED_URL", "\"$subgraphFeedUrl\"")

    val tempoFollowV1 = projectStringProperty("TEMPO_FOLLOW_V1")
      ?: "0xB65f7DAD7278ce2b9c14De2b68a3dBc8964F208c"
    buildConfigField("String", "TEMPO_FOLLOW_V1", "\"$tempoFollowV1\"")

    val tempoFeedV1 = projectStringProperty("TEMPO_FEED_V1")
      ?: "0x864d6f978fc45955585618e6b179ead35770244a"
    buildConfigField("String", "TEMPO_FEED_V1", "\"$tempoFeedV1\"")

    val tempoFeedV2 = projectStringProperty("TEMPO_FEED_V2")
      ?: "0x7208221F61463D7f430E5bCe9935F0c756D4818F"
    buildConfigField("String", "TEMPO_FEED_V2", "\"$tempoFeedV2\"")

    val tempoPublishCoordinator = projectStringProperty("TEMPO_PUBLISH_COORDINATOR")
      ?: "0xE41e8818E667e85C1697261a2c991c862b52de00"
    buildConfigField("String", "TEMPO_PUBLISH_COORDINATOR", "\"$tempoPublishCoordinator\"")

    val tempoCanonicalLyricsRegistry = projectStringProperty("TEMPO_CANONICAL_LYRICS_REGISTRY")
      ?: "0xEf17E6EA6Ddb92C8BAEf0920728e4a320e60c3d0"
    buildConfigField("String", "TEMPO_CANONICAL_LYRICS_REGISTRY", "\"$tempoCanonicalLyricsRegistry\"")

    val tempoTrackPresentationRegistry = projectStringProperty("TEMPO_TRACK_PRESENTATION_REGISTRY")
      ?: "0x77cF07239e859Dd0E91558F9B256453Df7F31E04"
    buildConfigField("String", "TEMPO_TRACK_PRESENTATION_REGISTRY", "\"$tempoTrackPresentationRegistry\"")

    val tempoTrackPresentationDelegate = projectStringProperty("TEMPO_TRACK_PRESENTATION_DELEGATE")
      ?: "0x39839FB90820846e020EAdBFA9af626163274e30"
    buildConfigField("String", "TEMPO_TRACK_PRESENTATION_DELEGATE", "\"$tempoTrackPresentationDelegate\"")

    val apiCoreUrl =
      projectStringProperty("API_CORE_URL")
        ?: "https://api.pirate.sc"
    buildConfigField("String", "API_CORE_URL", "\"$apiCoreUrl\"")

    val voiceAgentUrl =
      projectStringProperty("VOICE_AGENT_URL")
        ?: "https://chat.pirate.sc"
    buildConfigField("String", "VOICE_AGENT_URL", "\"$voiceAgentUrl\"")

    val voiceControlPlaneUrl =
      projectStringProperty("VOICE_CONTROL_PLANE_URL")
        ?: "https://voice.pirate.sc"
    buildConfigField("String", "VOICE_CONTROL_PLANE_URL", "\"$voiceControlPlaneUrl\"")

    val ipfsGatewayUrl =
      projectStringProperty("IPFS_GATEWAY_URL")
        ?: "https://pir.myfilebase.com/ipfs"
    buildConfigField("String", "IPFS_GATEWAY_URL", "\"$ipfsGatewayUrl\"")

    val arweaveGatewayUrl =
      projectStringProperty("ARWEAVE_GATEWAY_URL")
        ?: "https://ar-io.dev"
    buildConfigField("String", "ARWEAVE_GATEWAY_URL", "\"$arweaveGatewayUrl\"")

  }

  buildTypes {
    release {
      signingConfig = signingConfigs.findByName("release")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
    debug {
      isMinifyEnabled = false
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "/META-INF/DISCLAIMER"
    }
    jniLibs {
      useLegacyPackaging = true
    }
  }
}

dependencies {
  // Compose
  implementation(platform("androidx.compose:compose-bom:2026.02.00"))
  implementation("androidx.activity:activity-compose:1.12.4")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("com.adamglin:phosphor-icon:1.0.0")
  implementation("androidx.navigation:navigation-compose:2.9.7")
  implementation("androidx.fragment:fragment-ktx:1.8.9")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
  implementation("androidx.appcompat:appcompat:1.7.1")

  // Native passkeys
  implementation("androidx.credentials:credentials:1.2.2")
  implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
  implementation("com.upokecenter:cbor:4.5.3") {
    exclude(group = "com.github.peteroupc", module = "datautilities")
  }

  // HTTP + crypto helpers (for Lit auth service + WebAuthn authMethodId derivation)
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("org.bouncycastle:bcprov-jdk18on:1.83")
  // Ethereum ABI encoding/decoding for AA (ERC-4337) scrobble submits.
  implementation("org.web3j:abi:4.12.2")
  implementation("org.web3j:crypto:4.12.2")

  // Agora RTC (voice calls)
  implementation("io.agora.rtc:full-sdk:4.5.1")

  // XMTP messaging
  implementation("org.xmtp:android:4.9.0")

  // Image loading (album art, covers)
  implementation("io.coil-kt:coil-compose:2.6.0")
  implementation("io.coil-kt:coil-video:2.6.0")

  // Glance (home-screen widget)
  implementation("androidx.glance:glance-appwidget:1.1.1")
  implementation("androidx.datastore:datastore-preferences:1.1.4")

  // Media3 ExoPlayer for resilient HTTP streaming with cache
  implementation("androidx.media3:media3-exoplayer:1.4.1")
  implementation("androidx.media3:media3-ui:1.4.1")
  implementation("androidx.media3:media3-datasource:1.4.1")
  implementation("androidx.media3:media3-database:1.4.1")
  implementation("androidx.media3:media3-transformer:1.4.1")

  // CameraX capture (TikTok-style post flow)
  implementation("androidx.camera:camera-core:1.4.2")
  implementation("androidx.camera:camera-camera2:1.4.2")
  implementation("androidx.camera:camera-lifecycle:1.4.2")
  implementation("androidx.camera:camera-video:1.4.2")
  implementation("androidx.camera:camera-view:1.4.2")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")

  debugImplementation("androidx.compose.ui:ui-tooling")
}
