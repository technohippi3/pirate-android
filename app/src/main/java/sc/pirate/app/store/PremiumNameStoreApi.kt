package sc.pirate.app.store

import android.content.Context
import org.json.JSONObject
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.auth.privy.PrivyRelayClient
import sc.pirate.app.profile.PirateNameRecordsApi
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
  const val PREMIUM_NAME_STORE = PirateChainConfig.BASE_PREMIUM_NAME_STORE_V2
  private const val DEFAULT_DURATION_SECONDS = 365L * 24L * 60L * 60L
  private val ADDRESS_REGEX = Regex("^0x[a-fA-F0-9]{40}$")

  suspend fun quote(
    label: String,
    tld: String,
  ): PremiumStoreQuote = withContext(Dispatchers.IO) {
    val normalizedLabel = normalizeLabel(label)
    require(isValidLabel(normalizedLabel)) { "Invalid label format." }

    val normalizedTld = tld.trim().lowercase()
    val parentNode =
      PirateNameRecordsApi.parentNodeForTld(normalizedTld)
        ?: throw IllegalArgumentException("Unsupported TLD: .$normalizedTld")

    val listing = getListing(parentNode = parentNode, label = normalizedLabel)

    PremiumStoreQuote(
      label = normalizedLabel,
      tld = normalizedTld,
      parentNode = parentNode,
      listing = listing,
    )
  }

  suspend fun buy(
    context: Context,
    ownerAddress: String,
    label: String,
    tld: String,
    maxPrice: BigInteger? = null,
  ): PremiumStoreBuyResult {
    val normalizedOwner = normalizeAddress(ownerAddress)
      ?: return PremiumStoreBuyResult(success = false, error = "Wallet address is required.")
    val normalizedLabel = normalizeLabel(label)
    if (!isValidLabel(normalizedLabel)) {
      return PremiumStoreBuyResult(success = false, error = "Invalid label format.")
    }

    val normalizedTld = tld.trim().lowercase()
    PirateNameRecordsApi.parentNodeForTld(normalizedTld)
      ?: return PremiumStoreBuyResult(success = false, error = "Unsupported TLD: .$normalizedTld")

    return runCatching {
      val permitPayload =
        withContext(Dispatchers.IO) {
          val challenge =
            if (normalizedLabel.length >= 6) {
              requestPowChallenge(
                label = normalizedLabel,
                tld = normalizedTld,
                wallet = normalizedOwner,
              )
            } else {
              null
            }
          requestPermit(
            label = normalizedLabel,
            tld = normalizedTld,
            wallet = normalizedOwner,
            recipient = normalizedOwner,
            durationSeconds = DEFAULT_DURATION_SECONDS,
            maxPrice = maxPrice,
            challenge = challenge,
          )
        }

      var approvalTxHash: String? = null
      if (permitPayload.requiredPrice > BigInteger.ZERO) {
        val balance =
          readErc20BalanceRaw(
            address = normalizedOwner,
            token = permitPayload.paymentToken,
            rpcUrl = PirateChainConfig.BASE_SEPOLIA_RPC_URL,
          )
        if (balance < permitPayload.requiredPrice) {
          throw IllegalStateException("Insufficient payment token balance.")
        }
        val allowance =
          readErc20Allowance(
            token = permitPayload.paymentToken,
            owner = normalizedOwner,
            spender = permitPayload.txTo,
            rpcUrl = PirateChainConfig.BASE_SEPOLIA_RPC_URL,
          )
        if (allowance < permitPayload.requiredPrice) {
          val approveCalldata = encodeApproveCall(spender = permitPayload.txTo, value = permitPayload.requiredPrice)
          approvalTxHash =
            PrivyRelayClient.submitContractCall(
              context = context.applicationContext,
              chainId = PirateChainConfig.BASE_SEPOLIA_CHAIN_ID,
              to = permitPayload.paymentToken,
              data = approveCalldata,
              intentType = "pirate.name.approve",
              intentArgs =
                JSONObject()
                  .put("label", normalizedLabel)
                  .put("tld", normalizedTld)
                  .put("spender", permitPayload.txTo)
                  .put("amount", permitPayload.requiredPrice.toString()),
            )
          if (!awaitBaseReceipt(approvalTxHash)) {
            throw IllegalStateException("Approval reverted on-chain: $approvalTxHash")
          }
        }
      }

      val buyTxHash =
        PrivyRelayClient.submitContractCall(
          context = context.applicationContext,
          chainId = PirateChainConfig.BASE_SEPOLIA_CHAIN_ID,
          to = permitPayload.txTo,
          data = permitPayload.txData,
          intentType = "pirate.name.buy",
          intentArgs =
            JSONObject()
              .put("label", normalizedLabel)
              .put("tld", normalizedTld)
              .put("policy", permitPayload.policy)
              .put("paymentToken", permitPayload.paymentToken)
              .put("amount", permitPayload.requiredPrice.toString()),
        )
      if (!awaitBaseReceipt(buyTxHash)) {
        throw IllegalStateException("Premium name purchase reverted on-chain: $buyTxHash")
      }

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

  private fun normalizeAddress(raw: String): String? {
    val trimmed = raw.trim()
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    if (!ADDRESS_REGEX.matches(prefixed)) return null
    return "0x${prefixed.removePrefix("0x").removePrefix("0X")}".lowercase()
  }
}
