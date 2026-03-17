package com.pirate.app.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class SongPublishServiceInternalsCallDataTest {
    @Test
    fun canonicalMetaParts_trimInputsBeforeHashing() {
        val trimmed =
            songPublishComputeMetaParts(
                title = "Jazzin Baby Blues",
                artist = "johnny.pirate",
                album = "",
            )
        val untrimmed =
            songPublishComputeMetaParts(
                title = "  Jazzin Baby Blues  ",
                artist = "  johnny.pirate  ",
                album = "   ",
            )

        assertEquals(3, trimmed.kind)
        assertEquals(trimmed.kind, untrimmed.kind)
        assertArrayEquals(trimmed.payload, untrimmed.payload)
        assertArrayEquals(trimmed.trackId, untrimmed.trackId)
    }

    @Test
    fun publishCallData_trimsMetadataBeforeEncoding() {
        val trimmed =
            songPublishEncodeCoordinatorPublishCallData(
                owner = "0x" + "1".repeat(40),
                title = "Jazzin Baby Blues",
                artist = "johnny.pirate",
                album = "",
                durationSec = 187,
                coverRef = "ipfs://QmCover",
                datasetOwner = "0x" + "1".repeat(40),
                pieceCid = "c1cd8f562c7aad2c37b815ce74f2a83335143ccca1f7ac5bc49563fcaf73b179",
                algo = 1,
                visibility = 0,
                replaceIfActive = false,
            )
        val untrimmed =
            songPublishEncodeCoordinatorPublishCallData(
                owner = "0x" + "1".repeat(40),
                title = "  Jazzin Baby Blues  ",
                artist = "  johnny.pirate  ",
                album = "   ",
                durationSec = 187,
                coverRef = "  ipfs://QmCover  ",
                datasetOwner = "0x" + "1".repeat(40),
                pieceCid = "  c1cd8f562c7aad2c37b815ce74f2a83335143ccca1f7ac5bc49563fcaf73b179  ",
                algo = 1,
                visibility = 0,
                replaceIfActive = false,
            )

        assertEquals(trimmed, untrimmed)
    }

    @Test
    fun setPublishDelegate_callDataUsesUint64Selector() {
        val callData = songPublishEncodeSetPublishDelegateCallData(
            publishId = "0x" + "1".repeat(64),
            delegateAddress = "0x" + "2".repeat(40),
            permissions = 3,
            expiresAtSec = 1_900_000_000L,
        )

        assertTrue(callData.startsWith("0x7b623dc8"))
        assertEquals(266, callData.length)
    }

    @Test
    fun parsePublishedTrackReadiness_readsCanonicalReadinessFields() {
        val readiness = songPublishParsePublishedTrackReadiness(
            JSONObject(
                """
                {
                  "trackId": "0x${"a".repeat(64)}",
                  "presentation": {
                    "status": "set",
                    "ready": true
                  },
                  "lyrics": {
                    "status": "processing",
                    "ready": false,
                    "manifestRef": null
                  }
                }
                """.trimIndent(),
            ),
        )

        assertNotNull(readiness)
        assertEquals("0x${"a".repeat(64)}", readiness?.trackId)
        assertEquals("set", readiness?.presentation?.status)
        assertTrue(readiness?.presentation?.ready == true)
        assertEquals("processing", readiness?.lyrics?.status)
        assertFalse(readiness?.lyrics?.ready == true)
    }
}
