package sc.pirate.app.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfVerificationServiceParsingTest {

  @Test
  fun parseCreateSessionResponse_validPayload_returnsSuccess() {
    val result =
      parseCreateSessionResponse(
        """{"sessionId":"sid-1","deeplinkUrl":"self://verify","expiresAt":1735689600}""",
      )

    assertTrue(result is SelfVerificationService.SessionResult.Success)
    val success = result as SelfVerificationService.SessionResult.Success
    assertEquals("sid-1", success.sessionId)
    assertEquals("self://verify", success.deeplinkUrl)
    assertEquals(1735689600, success.expiresAt)
  }

  @Test
  fun parseCreateSessionResponse_missingFields_returnsError() {
    val result = parseCreateSessionResponse("""{"deeplinkUrl":"self://verify","expiresAt":1735689600}""")
    assertTrue(result is SelfVerificationService.SessionResult.Error)
    assertEquals(
      "Create session failed: missing sessionId",
      (result as SelfVerificationService.SessionResult.Error).message,
    )
  }

  @Test
  fun parsePollSessionResponse_validPayload_returnsSuccess() {
    val result = parsePollSessionResponse("""{"status":"pending","age":22,"nationality":"US"}""")
    assertTrue(result is SelfVerificationService.SessionStatus.Success)
    val success = result as SelfVerificationService.SessionStatus.Success
    assertEquals("pending", success.status)
    assertEquals(22, success.age)
    assertEquals("US", success.nationality)
  }

  @Test
  fun parsePollSessionResponse_missingStatus_returnsError() {
    val result = parsePollSessionResponse("""{"age":22}""")
    assertTrue(result is SelfVerificationService.SessionStatus.Error)
    assertEquals(
      "Poll failed: missing status",
      (result as SelfVerificationService.SessionStatus.Error).message,
    )
  }
}
