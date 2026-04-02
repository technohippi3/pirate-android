package sc.pirate.app.music

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
                ownerAddress = "0x" + "1".repeat(40),
                title = "Jazzin Baby Blues",
                album = "",
            )
        val untrimmed =
            songPublishComputeMetaParts(
                ownerAddress = "0x" + "1".repeat(40),
                title = "  Jazzin Baby Blues  ",
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

    @Test
    fun requireFinalizePreparePayload_parsesPrepareResponseShape() {
        val response =
            SongPublishApiResponse(
                status = 200,
                body = "",
                json =
                    JSONObject(
                        """
                        {
                          "finalize": {
                            "trackId": "0x${"1".repeat(64)}",
                            "publishIdExpected": "0x${"2".repeat(64)}",
                            "payload": "0x${"3".repeat(64)}",
                            "coverRef": "ipfs://cover",
                            "datasetOwner": "0x${"4".repeat(40)}",
                            "algo": 1,
                            "visibility": 0,
                            "replaceIfActive": false,
                            "pieceCid": "ipfs://piece",
                            "contentId": "0x${"5".repeat(64)}",
                            "artifact": {
                              "ref": "ipfs://artifact",
                              "sha256": "0x${"6".repeat(64)}",
                              "bytes": 4096,
                              "encryptionAlgo": "aes_gcm_256"
                            },
                            "stems": [
                              {
                                "stemType": "instrumental",
                                "artifact": {
                                  "ref": "ipfs://stem",
                                  "sha256": "0x${"7".repeat(64)}",
                                  "bytes": 1024,
                                  "encryptionAlgo": "aes_gcm_256"
                                }
                              }
                            ],
                            "cdr": {
                              "dataKey": "AQIDBA",
                              "writeConditionAddr": "0x${"8".repeat(40)}",
                              "readConditionAddr": "0x${"8".repeat(40)}",
                              "writeConditionData": "0x1234",
                              "readConditionData": "0x5678",
                              "accessAuxData": "0x",
                              "writerAddress": "0x${"9".repeat(40)}"
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )

        val parsed = songPublishRequireFinalizePreparePayload("Prepare", response)

        assertEquals("ipfs://artifact", parsed.artifact.ref)
        assertEquals(1, parsed.stems.size)
        assertEquals("instrumental", parsed.stems.first().stemType)
        assertEquals("AQIDBA", parsed.cdr.dataKey)
        assertEquals("0x${"9".repeat(40)}", parsed.cdr.writerAddress)
    }

    @Test
    fun finalizeSubmitRequest_toJsonIncludesPreparedDeliveryAndVaultUuid() {
        val json =
            SongPublishFinalizeSubmitRequest(
                title = "Song",
                album = "",
                durationS = 180,
                signedTx = "0xabc123",
                contentId = "0x${"a".repeat(64)}",
                cdrVaultUuid = 88,
                pieceCid = "ipfs://piece",
                artifact =
                    SongPublishFinalizeArtifact(
                        ref = "ipfs://artifact",
                        sha256 = "0x${"b".repeat(64)}",
                        bytes = 4096,
                        encryptionAlgo = "aes_gcm_256",
                    ),
                stems =
                    listOf(
                        SongPublishFinalizeStem(
                            stemType = "vocals",
                            artifact =
                                SongPublishFinalizeArtifact(
                                    ref = "ipfs://stem",
                                    sha256 = "0x${"c".repeat(64)}",
                                    bytes = 1024,
                                    encryptionAlgo = "aes_gcm_256",
                                ),
                        ),
                    ),
                purchasePrice = "1000000",
                maxSupply = 25,
            ).toJson()

        assertEquals(88, json.getInt("cdrVaultUuid"))
        assertEquals("ipfs://artifact", json.getJSONObject("artifact").getString("ref"))
        assertEquals(1, json.getJSONArray("stems").length())
        assertEquals("1000000", json.getString("purchasePrice"))
        assertEquals(25, json.getInt("maxSupply"))
    }
}
