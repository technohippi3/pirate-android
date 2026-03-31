package sc.pirate.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class PiratePasskeyRpIdTest {
  @Test
  fun normalize_keepsBareHostname() {
    assertEquals("pirate.sc", PiratePasskeyRpId.normalize("pirate.sc"))
  }

  @Test
  fun normalize_extractsHostFromUrl() {
    assertEquals("pirate.sc", PiratePasskeyRpId.normalize("https://pirate.sc/login"))
  }

  @Test
  fun normalize_fallsBackForInvalidValue() {
    assertEquals("pirate.sc", PiratePasskeyRpId.normalize("https://"))
  }
}
