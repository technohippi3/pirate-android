package sc.pirate.app.onboarding.steps

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationStepParsingTest {

  @Test
  fun parsePhotonSearchResults_validCityRow_returnsLocationResult() {
    val feature =
      JSONObject()
        .put(
          "properties",
          JSONObject()
            .put("type", "city")
            .put("name", "San Francisco")
            .put("state", "California")
            .put("country", "United States")
            .put("countrycode", "us"),
        )
        .put(
          "geometry",
          JSONObject().put("coordinates", JSONArray().put(-122.4194).put(37.7749)),
        )
    val payload = JSONObject().put("features", JSONArray().put(feature)).toString()

    val parsed = parsePhotonSearchResults(payload)

    assertEquals(1, parsed.size)
    assertEquals("San Francisco, CA, US", parsed[0].label)
    assertEquals(37.7749, parsed[0].lat, 0.0001)
    assertEquals(-122.4194, parsed[0].lng, 0.0001)
    assertEquals("us", parsed[0].countryCode)
  }

  @Test
  fun parsePhotonSearchResults_nonPlaceType_isSkipped() {
    val feature =
      JSONObject()
        .put("properties", JSONObject().put("type", "house").put("name", "Test"))
        .put("geometry", JSONObject().put("coordinates", JSONArray().put(10.0).put(20.0)))
    val payload = JSONObject().put("features", JSONArray().put(feature)).toString()

    val parsed = parsePhotonSearchResults(payload)
    assertTrue(parsed.isEmpty())
  }

  @Test
  fun parsePhotonSearchResults_missingCoordinates_isSkipped() {
    val feature =
      JSONObject()
        .put(
          "properties",
          JSONObject()
            .put("type", "city")
            .put("name", "Nowhere")
            .put("countrycode", "US"),
        )
        .put("geometry", JSONObject().put("coordinates", JSONArray()))
    val payload = JSONObject().put("features", JSONArray().put(feature)).toString()

    val parsed = parsePhotonSearchResults(payload)
    assertTrue(parsed.isEmpty())
  }
}
