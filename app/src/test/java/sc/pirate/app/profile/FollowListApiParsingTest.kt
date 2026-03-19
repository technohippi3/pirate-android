package sc.pirate.app.profile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FollowListApiParsingTest {

  @Test
  fun parseProfilesMap_parsesProfilesAndSkipsBlankIds() {
    val profiles =
      JSONArray()
        .put(JSONObject().put("id", "0xABC").put("displayName", "Alice").put("photoURI", "ipfs://1"))
        .put(JSONObject().put("id", "").put("displayName", "Nope").put("photoURI", "ipfs://2"))
    val payload = JSONObject().put("data", JSONObject().put("profiles", profiles))

    val parsed = parseProfilesMap(payload)

    assertEquals(1, parsed.size)
    assertEquals("Alice", parsed["0xabc"]?.first)
    assertEquals("ipfs://1", parsed["0xabc"]?.second)
  }
}
