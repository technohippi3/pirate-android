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
    minSdk = 28
    targetSdk = 36
    versionCode = 5
    versionName = "0.1.0-alpha.3"

    fun projectStringProperty(name: String): String? =
      (project.findProperty(name) as String?)
        ?.replace("\"", "\\\"")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    fun goldskySubgraphUrl(slug: String, version: String): String =
      "https://api.goldsky.com/api/public/project_cmjjtjqpvtip401u87vcp20wd/subgraphs/$slug/$version/gn"

    val subgraphMusicSocialUrl =
      projectStringProperty("SUBGRAPH_MUSIC_SOCIAL_URL")
        ?: goldskySubgraphUrl("music-social-story-aeneid", "20260330-175305")
    buildConfigField("String", "SUBGRAPH_MUSIC_SOCIAL_URL", "\"$subgraphMusicSocialUrl\"")

    val subgraphProfilesUrl =
      projectStringProperty("SUBGRAPH_PROFILES_URL")
        ?: goldskySubgraphUrl("profiles-base-sepolia", "20260328-185319")
    buildConfigField("String", "SUBGRAPH_PROFILES_URL", "\"$subgraphProfilesUrl\"")

    val subgraphPlaylistsUrl =
      projectStringProperty("SUBGRAPH_PLAYLISTS_URL")
        ?: goldskySubgraphUrl("playlist-feed-story-aeneid", "20260329-001500")
    buildConfigField("String", "SUBGRAPH_PLAYLISTS_URL", "\"$subgraphPlaylistsUrl\"")

    val subgraphStudyProgressUrl =
      projectStringProperty("SUBGRAPH_STUDY_PROGRESS_URL")
        ?: goldskySubgraphUrl("study-progress-story-aeneid", "20260328-194600")
    buildConfigField("String", "SUBGRAPH_STUDY_PROGRESS_URL", "\"$subgraphStudyProgressUrl\"")

    val subgraphFeedUrl =
      projectStringProperty("SUBGRAPH_FEED_URL")
        ?: goldskySubgraphUrl("tiktok-feed-story-aeneid", "20260328-194600")
    buildConfigField("String", "SUBGRAPH_FEED_URL", "\"$subgraphFeedUrl\"")

    val privyEnabled =
      (project.findProperty("PRIVY_ENABLED") as String?)
        ?.trim()
        ?.lowercase()
        ?.let { it == "true" || it == "1" }
        ?: true
    buildConfigField("boolean", "PRIVY_ENABLED", privyEnabled.toString())

    val privyAppId =
      projectStringProperty("PRIVY_APP_ID")
        ?: "cmnbdx9xk00ty0clapn2q8pdj"
    buildConfigField("String", "PRIVY_APP_ID", "\"$privyAppId\"")

    val privyAppClientId =
      projectStringProperty("PRIVY_APP_CLIENT_ID")
        ?: "client-WY6Xkpp2wLef8Y9cWBrZ1GhnmqAtnVh9YisfZ2dA3c7DW"
    buildConfigField("String", "PRIVY_APP_CLIENT_ID", "\"$privyAppClientId\"")

    val privyRedirectScheme =
      projectStringProperty("PRIVY_REDIRECT_SCHEME")
        ?: "pirate"
    buildConfigField("String", "PRIVY_REDIRECT_SCHEME", "\"$privyRedirectScheme\"")
    manifestPlaceholders["privyRedirectScheme"] = privyRedirectScheme

    val storyPublishCoordinator = projectStringProperty("STORY_PUBLISH_COORDINATOR")
      ?: "0xbeca8ec21492a3bbdda69f2952734597ad362d18"
    buildConfigField("String", "STORY_PUBLISH_COORDINATOR", "\"$storyPublishCoordinator\"")

    val storyCanonicalLyricsRegistry = projectStringProperty("STORY_CANONICAL_LYRICS_REGISTRY")
      ?: "0x1f52785d90b7126291dadc01f6f7a6ffc90cd09a"
    buildConfigField("String", "STORY_CANONICAL_LYRICS_REGISTRY", "\"$storyCanonicalLyricsRegistry\"")

    val storyTrackPresentationRegistry = projectStringProperty("STORY_TRACK_PRESENTATION_REGISTRY")
      ?: "0x3bfd7e6d5e1273cde8dcef0bebbe18a4a71b3601"
    buildConfigField("String", "STORY_TRACK_PRESENTATION_REGISTRY", "\"$storyTrackPresentationRegistry\"")

    val storyTrackPresentationDelegate = projectStringProperty("STORY_TRACK_PRESENTATION_DELEGATE")
      ?: "0x39839FB90820846e020EAdBFA9af626163274e30"
    buildConfigField("String", "STORY_TRACK_PRESENTATION_DELEGATE", "\"$storyTrackPresentationDelegate\"")

    val apiCoreUrl =
      projectStringProperty("API_CORE_URL")
        ?: "https://api.pirate.sc"
    buildConfigField("String", "API_CORE_URL", "\"$apiCoreUrl\"")

    val privyRelayUrl =
      projectStringProperty("PRIVY_RELAY_URL")
        ?: "https://pirate.sc/api/privy-relay"
    buildConfigField("String", "PRIVY_RELAY_URL", "\"$privyRelayUrl\"")

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
        ?: "https://psc.myfilebase.com/ipfs"
    buildConfigField("String", "IPFS_GATEWAY_URL", "\"$ipfsGatewayUrl\"")

    val arweaveGatewayUrl =
      projectStringProperty("ARWEAVE_GATEWAY_URL")
        ?: "https://ar-io.dev"
    buildConfigField("String", "ARWEAVE_GATEWAY_URL", "\"$arweaveGatewayUrl\"")

  }

  flavorDimensions += "distribution"

  productFlavors {
    create("standard") {
      dimension = "distribution"
    }
    create("fdroid") {
      dimension = "distribution"
    }
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
      signingConfig = signingConfigs.findByName("release")
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
  implementation("androidx.credentials:credentials:1.5.0")
  implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
  implementation("io.privy:privy-core:0.9.2")
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
  add("standardImplementation", "io.agora.rtc:full-sdk:4.5.1")

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
