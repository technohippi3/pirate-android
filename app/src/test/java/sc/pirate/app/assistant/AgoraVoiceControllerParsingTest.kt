package sc.pirate.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AgoraVoiceControllerParsingTest {

  @Test
  fun parseAgentStartResponse_validPayload_returnsParsedFields() {
    val parsed =
      parseAgentStartResponse(
        """{"session_id":"s1","channel":"voice-room","agora_token":"tok","user_uid":77}""",
      )

    assertEquals("s1", parsed.sessionId)
    assertEquals("voice-room", parsed.channel)
    assertEquals("tok", parsed.agoraToken)
    assertEquals(77, parsed.userUid)
  }

  @Test
  fun parseAgentStartResponse_missingChannel_throws() {
    try {
      parseAgentStartResponse("""{"session_id":"s1","agora_token":"tok","user_uid":77}""")
      fail("Expected IllegalStateException")
    } catch (error: IllegalStateException) {
      assertEquals("Missing channel in agent response", error.message)
    }
  }

  @Test
  fun parseAgentStartResponse_invalidUid_throws() {
    try {
      parseAgentStartResponse("""{"session_id":"s1","channel":"voice-room","agora_token":"tok","user_uid":-1}""")
      fail("Expected IllegalStateException")
    } catch (error: IllegalStateException) {
      assertEquals("Invalid user_uid in agent response", error.message)
    }
  }
}
