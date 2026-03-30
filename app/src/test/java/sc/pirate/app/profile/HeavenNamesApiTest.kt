package sc.pirate.app.profile

import org.junit.Assert.assertEquals
import org.junit.Test

class HeavenNamesApiTest {
  @Test
  fun buildRegisterMessage_matchesServerFormat() {
    val message =
      HeavenNamesApi.buildRegisterMessage(
        label = "Alice",
        ownerAddress = "0xAbCdEf0123456789abcdef0123456789ABCDEF01",
        nonce = "nonce0123456789abcd",
        issuedAt = 1_710_000_000L,
        expiresAt = 1_710_000_120L,
        profileCid = "",
      )

    assertEquals(
      """
      heaven-registry:v1
      action=register
      tld=heaven
      label=alice
      wallet=0xabcdef0123456789abcdef0123456789abcdef01
      nonce=nonce0123456789abcd
      issued_at=1710000000
      expires_at=1710000120
      profile_cid=
      """.trimIndent(),
      message,
    )
  }
}
