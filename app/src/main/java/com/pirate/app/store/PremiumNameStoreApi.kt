package com.pirate.app.store

import androidx.fragment.app.FragmentActivity
import com.pirate.app.profile.TempoNameRecordsApi
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoClient
import com.pirate.app.tempo.TempoPasskeyManager
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function

data class PremiumStoreListing(
  val price: BigInteger,
  val durationSeconds: Long,
  val enabled: Boolean,
)

data class PremiumStoreQuote(
  val label: String,
  val tld: String,
  val parentNode: String,
  val listing: PremiumStoreListing,
)

data class PremiumStoreBuyResult(
  val success: Boolean,
  val buyTxHash: String? = null,
  val approvalTxHash: String? = null,
  val policy: String? = null,
  val quotePrice: BigInteger? = null,
  val error: String? = null,
)

object PremiumNameStoreApi {
  const val PREMIUM_NAME_STORE = "0x46Bcae2625157b562c974bb5a912dfcb811a234A"
  private const val DEFAULT_DURATION_SECONDS = 365L * 24L * 60L * 60L

  private const val MIN_GAS_LIMIT_APPROVE = 120_000L
  private const val MIN_GAS_LIMIT_BUY = 850_000L

  suspend fun quote(label: String, tld: String): PremiumStoreQuote = withContext(Dispatchers.IO) {
    val normalizedLabel = normalizeLabel(label)
    require(isValidLabel(normalizedLabel)) { "Invalid label format." }

    val normalizedTld = tld.trim().lowercase()
    val parentNode = TempoNameRecordsApi.parentNodeForTld(normalizedTld)
      ?: throw IllegalArgumentException("Unsupported TLD: .$normalizedTld")

    val listing = getListing(parentNode = parentNode, label = normalizedLabel)

    PremiumStoreQuote(
      label = normalizedLabel,
      tld = normalizedTld,
      parentNode = parentNode,
      listing = listing,
    )
  }

  suspend fun getAlphaUsdBalance(address: String): BigInteger = withContext(Dispatchers.IO) {
    TempoClient.getErc20BalanceRaw(address = address, token = TempoClient.ALPHA_USD)
  }

  suspend fun getAlphaUsdAllowance(ownerAddress: String, spender: String = PREMIUM_NAME_STORE): BigInteger = withContext(Dispatchers.IO) {
    val function =
      Function(
        "allowance",
        listOf(Address(ownerAddress), Address(spender)),
        emptyList(),
      )
    val callData = FunctionEncoder.encode(function)
    parseUint256(ethCall(TempoClient.ALPHA_USD, callData))
  }

  suspend fun buy(
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    label: String,
    tld: String,
    maxPrice: BigInteger? = null,
    rpId: String = account.rpId,
    sessionKey: SessionKeyManager.SessionKey? = null,
    preferSelfPay: Boolean = false,
  ): PremiumStoreBuyResult {
    val normalizedLabel = normalizeLabel(label)
    if (!isValidLabel(normalizedLabel)) {
      return PremiumStoreBuyResult(success = false, error = "Invalid label format.")
    }

    val normalizedTld = tld.trim().lowercase()
    TempoNameRecordsApi.parentNodeForTld(normalizedTld)
      ?: return PremiumStoreBuyResult(success = false, error = "Unsupported TLD: .$normalizedTld")

    return runCatching {
      val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
      if (chainId != TempoClient.CHAIN_ID) {
        throw IllegalStateException("Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})")
      }

      val permitPayload =
        withContext(Dispatchers.IO) {
          val challenge =
            if (normalizedLabel.length >= 6) {
              requestPowChallenge(
                label = normalizedLabel,
                tld = normalizedTld,
                wallet = account.address,
              )
            } else {
              null
            }
          requestPermit(
            label = normalizedLabel,
            tld = normalizedTld,
            wallet = account.address,
            recipient = account.address,
            durationSeconds = DEFAULT_DURATION_SECONDS,
            maxPrice = maxPrice,
            challenge = challenge,
          )
        }

      var approvalTxHash: String? = null
      if (permitPayload.requiredPrice > BigInteger.ZERO) {
        val allowance = withContext(Dispatchers.IO) {
          getAlphaUsdAllowance(ownerAddress = account.address, spender = permitPayload.txTo)
        }
        if (allowance < permitPayload.requiredPrice) {
          val approveCalldata = encodeApproveCall(spender = permitPayload.txTo, value = permitPayload.requiredPrice)
          approvalTxHash =
            submitCallWithFallback(
              activity = activity,
              account = account,
              to = TempoClient.ALPHA_USD,
              callData = approveCalldata,
              minimumGasLimit = MIN_GAS_LIMIT_APPROVE,
              rpId = rpId,
              sessionKey = sessionKey,
              preferSelfPay = preferSelfPay,
            )
        }
      }

      val buyTxHash =
        submitCallWithFallback(
          activity = activity,
          account = account,
          to = permitPayload.txTo,
          callData = permitPayload.txData,
          minimumGasLimit = MIN_GAS_LIMIT_BUY,
          rpId = rpId,
          sessionKey = sessionKey,
          preferSelfPay = preferSelfPay,
        )

      PremiumStoreBuyResult(
        success = true,
        buyTxHash = buyTxHash,
        approvalTxHash = approvalTxHash,
        policy = permitPayload.policy,
        quotePrice = permitPayload.requiredPrice,
      )
    }.getOrElse { err ->
      PremiumStoreBuyResult(success = false, error = err.message ?: "Premium name purchase failed.")
    }
  }

  fun isValidLabel(label: String): Boolean {
    val normalized = normalizeLabel(label)
    if (normalized.isBlank()) return false
    if (normalized.length > 63) return false
    if (normalized.first() == '-' || normalized.last() == '-') return false
    return normalized.all { ch ->
      ch in 'a'..'z' || ch in '0'..'9' || ch == '-'
    }
  }

  fun normalizeLabel(label: String): String = label.trim().lowercase()
}
