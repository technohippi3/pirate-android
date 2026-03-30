package sc.pirate.app.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongPublishDonationSearchTest {
  @Test
  fun buildDonationPolicy_returnsNull_whenDonationIsDisabled() {
    val formData =
      SongPublishService.SongFormData(
        donationEnabled = false,
        donationOrgId = "org_123",
        donationOrgName = "Eligible Org",
        donationRecipient = "0x1111111111111111111111111111111111111111",
        donationCompliant = true,
        donationSharePercent = 10,
      )

    val policy = SongPublishService.buildDonationPolicy(formData)

    assertNull(policy)
  }

  @Test
  fun buildDonationPolicy_returnsNull_whenDonationInputsAreInvalid() {
    val formData =
      SongPublishService.SongFormData(
        donationEnabled = true,
        donationOrgId = "org_123",
        donationOrgName = "Eligible Org",
        donationRecipient = "not-an-address",
        donationCompliant = true,
        donationSharePercent = 10,
      )

    val policy = SongPublishService.buildDonationPolicy(formData)

    assertNull(policy)
  }

  @Test
  fun buildDonationPolicy_returnsNull_whenOrgIsNotCompliant() {
    val formData =
      SongPublishService.SongFormData(
        donationEnabled = true,
        donationOrgId = "org_123",
        donationOrgName = "Eligible Org",
        donationRecipient = "0x1111111111111111111111111111111111111111",
        donationCompliant = false,
        donationSharePercent = 10,
      )

    val policy = SongPublishService.buildDonationPolicy(formData)

    assertNull(policy)
  }

  @Test
  fun buildDonationPolicy_buildsCompliantPayload_forFeaturedOrg() {
    val featuredOrg = SongPublishService.FEATURED_DONATION_ORGS.first()
    val formData =
      SongPublishService.SongFormData(
        donationEnabled = true,
        donationOrgId = featuredOrg.orgId,
        donationOrgName = featuredOrg.name,
        donationRecipient = featuredOrg.destinationRecipient,
        donationChainId = featuredOrg.destinationChainId,
        donationCompliant = true,
        donationSharePercent = 15,
      )

    val policy = SongPublishService.buildDonationPolicy(formData)

    assertNotNull(policy)
    assertEquals("endaoment", policy?.optString("provider"))
    assertEquals(featuredOrg.orgId, policy?.optString("orgId"))
    assertEquals(featuredOrg.name, policy?.optString("orgName"))
    assertTrue(policy?.optBoolean("isCompliant") == true)
    assertEquals(featuredOrg.destinationChainId, policy?.optInt("destinationChainId"))
    assertEquals("USDC", policy?.optString("destinationToken"))
    assertEquals(featuredOrg.destinationRecipient, policy?.optString("destinationRecipient"))
    assertEquals(15, policy?.optInt("sharePercent"))
  }
}
