package sc.pirate.app.auth.privy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivyRuntimeConfigTest {
  @Test
  fun disabledReason_acceptsCompleteConfig() {
    val config =
      PrivyRuntimeConfig(
        enabled = true,
        appId = "cmnbdx9xk00ty0clapn2q8pdj",
        appClientId = "client-WY6Xkpp2wLef8Y9cWBrZ1GhnmqAtnVh9YisfZ2dA3c7DW",
        redirectScheme = "pirate",
      )

    assertNull(config.disabledReason())
  }

  @Test
  fun disabledReason_requiresClientId() {
    val config =
      PrivyRuntimeConfig(
        enabled = true,
        appId = "cmnbdx9xk00ty0clapn2q8pdj",
        appClientId = "",
        redirectScheme = "pirate",
      )

    assertEquals(
      "Missing PRIVY_APP_CLIENT_ID.",
      config.disabledReason(),
    )
  }

  @Test
  fun disabledReason_requiresAppId() {
    val config =
      PrivyRuntimeConfig(
        enabled = true,
        appId = "",
        appClientId = "",
        redirectScheme = "pirate",
      )

    assertEquals("Missing PRIVY_APP_ID.", config.disabledReason())
  }
}
