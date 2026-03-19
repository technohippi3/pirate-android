package sc.pirate.app.music

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SongPublishDonationSearchTest {
  @Test
  fun parseDonationOrganizationSearchResponse_404_throwsUnavailableMessage() {
    val response = SongPublishApiResponse(
      status = 404,
      body = "",
      json = null,
    )

    try {
      SongPublishService.parseDonationOrganizationSearchResponse(response)
      fail("Expected 404 response to throw")
    } catch (error: IllegalStateException) {
      assertEquals("Charity search is not available on this API build yet", error.message)
    }
  }

  @Test
  fun parseDonationOrganizationSearchResponse_non404ErrorUsesApiErrorMessage() {
    val response = SongPublishApiResponse(
      status = 403,
      body = """{"error":"Self.xyz verification required for music upload/publish"}""",
      json = JSONObject("""{"error":"Self.xyz verification required for music upload/publish"}"""),
    )

    try {
      SongPublishService.parseDonationOrganizationSearchResponse(response)
      fail("Expected non-2xx response to throw")
    } catch (error: IllegalStateException) {
      assertEquals(
        "Charity search failed: Self.xyz verification required for music upload/publish",
        error.message,
      )
    }
  }

  @Test
  fun parseDonationOrganizationSearchResponse_filtersToCompliantBaseUsdcResults() {
    val response = SongPublishApiResponse(
      status = 200,
      body =
        """
        {
          "orgs": [
            {
              "provider": "endaoment",
              "id": "org_ok",
              "name": "Eligible Org",
              "ein": "123456789",
              "description": "",
              "logoUrl": "",
              "website": "",
              "isCompliant": true,
              "destinationChainId": 8453,
              "destinationToken": "usdc",
              "destinationRecipient": "0x1111111111111111111111111111111111111111"
            },
            {
              "provider": "endaoment",
              "id": "org_wrong_chain",
              "name": "Wrong Chain",
              "isCompliant": true,
              "destinationChainId": 1,
              "destinationToken": "USDC",
              "destinationRecipient": "0x2222222222222222222222222222222222222222"
            },
            {
              "provider": "endaoment",
              "id": "org_non_compliant",
              "name": "Non Compliant",
              "isCompliant": false,
              "destinationChainId": 8453,
              "destinationToken": "USDC",
              "destinationRecipient": "0x3333333333333333333333333333333333333333"
            }
          ]
        }
        """.trimIndent(),
      json =
        JSONObject(
          """
          {
            "orgs": [
              {
                "provider": "endaoment",
                "id": "org_ok",
                "name": "Eligible Org",
                "ein": "123456789",
                "description": "",
                "logoUrl": "",
                "website": "",
                "isCompliant": true,
                "destinationChainId": 8453,
                "destinationToken": "usdc",
                "destinationRecipient": "0x1111111111111111111111111111111111111111"
              },
              {
                "provider": "endaoment",
                "id": "org_wrong_chain",
                "name": "Wrong Chain",
                "isCompliant": true,
                "destinationChainId": 1,
                "destinationToken": "USDC",
                "destinationRecipient": "0x2222222222222222222222222222222222222222"
              },
              {
                "provider": "endaoment",
                "id": "org_non_compliant",
                "name": "Non Compliant",
                "isCompliant": false,
                "destinationChainId": 8453,
                "destinationToken": "USDC",
                "destinationRecipient": "0x3333333333333333333333333333333333333333"
              }
            ]
          }
          """.trimIndent(),
        ),
    )

    val results = SongPublishService.parseDonationOrganizationSearchResponse(response)

    assertEquals(1, results.size)
    assertEquals("endaoment", results[0].provider)
    assertEquals("org_ok", results[0].id)
    assertEquals("Eligible Org", results[0].name)
    assertEquals("123456789", results[0].ein)
    assertNull(results[0].description)
    assertNull(results[0].logoUrl)
    assertNull(results[0].website)
    assertEquals(8453, results[0].destinationChainId)
    assertEquals("USDC", results[0].destinationToken)
    assertEquals("0x1111111111111111111111111111111111111111", results[0].destinationRecipient)
  }
}
