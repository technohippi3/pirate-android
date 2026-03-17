package com.pirate.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class WorkerAuthParsingTest {

  @Test
  fun parseNonceFromAuthResponse_returnsNonce() {
    val nonce = parseNonceFromAuthResponse("""{"nonce":"abc123"}""")
    assertEquals("abc123", nonce)
  }

  @Test
  fun parseNonceFromAuthResponse_missingNonce_throws() {
    try {
      parseNonceFromAuthResponse("""{"ok":true}""")
      fail("Expected IllegalStateException")
    } catch (error: IllegalStateException) {
      assertEquals("Missing nonce in response", error.message)
    }
  }

  @Test
  fun parseTokenFromVerifyResponse_returnsToken() {
    val token = parseTokenFromVerifyResponse("""{"token":"jwt-token"}""")
    assertEquals("jwt-token", token)
  }

  @Test
  fun parseTokenFromVerifyResponse_blankToken_throws() {
    try {
      parseTokenFromVerifyResponse("""{"token":"   "}""")
      fail("Expected IllegalStateException")
    } catch (error: IllegalStateException) {
      assertEquals("Missing token in response", error.message)
    }
  }
}
