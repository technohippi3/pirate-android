package com.pirate.app.player

import com.pirate.app.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerPresentationRepositoryTest {
    @Test
    fun extractCanvasVideoUrl_prefersCanonicalPirateCanvasRef() {
        val metadata = JSONObject(
            """
            {
              "pirate": {
                "media": {
                  "canvasRef": "ipfs://bafycanvascid"
                }
              },
              "animation_url": "https://example.com/fallback.mp4"
            }
            """.trimIndent(),
        )

        val resolved = PlayerPresentationRepository.extractCanvasVideoUrlForTesting(metadata)

        assertEquals("${BuildConfig.IPFS_GATEWAY_URL.trimEnd('/')}/bafycanvascid", resolved)
    }

    @Test
    fun extractCanvasVideoUrl_fallsBackToAnimationUrl() {
        val metadata = JSONObject(
            """
            {
              "animation_url": "https://example.com/canvas.mp4"
            }
            """.trimIndent(),
        )

        val resolved = PlayerPresentationRepository.extractCanvasVideoUrlForTesting(metadata)

        assertEquals("https://example.com/canvas.mp4", resolved)
    }

    @Test
    fun extractCanvasVideoUrl_returnsNullWhenNoVideoPresentationExists() {
        val metadata = JSONObject(
            """
            {
              "image": "https://example.com/cover.png"
            }
            """.trimIndent(),
        )

        val resolved = PlayerPresentationRepository.extractCanvasVideoUrlForTesting(metadata)

        assertNull(resolved)
    }
}
