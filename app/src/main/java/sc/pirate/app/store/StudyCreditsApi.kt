package sc.pirate.app.store

import android.content.Context
import org.json.JSONObject
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.auth.privy.PrivyRelayClient
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256

data class StudyCreditsQuote(
  val registry: String,
  val paymentToken: String,
  val walletCredits: BigInteger,
  val creditPrice: BigInteger,
  val tokenBalance: BigInteger,
  val allowance: BigInteger,
)

data class StudyCreditsBuyResult(
  val success: Boolean,
  val creditsPurchased: Int = 0,
  val totalCost: BigInteger = BigInteger.ZERO,
  val buyTxHash: String? = null,
  val approvalTxHash: String? = null,
  val error: String? = null,
)

object StudyCreditsApi {
  const val STUDY_SET_REGISTRY = PirateChainConfig.STORY_STUDY_SET_REGISTRY
  private val ADDRESS_REGEX = Regex("^0x[a-fA-F0-9]{40}$")

  suspend fun quote(userAddress: String): StudyCreditsQuote = withContext(Dispatchers.IO) {
    val owner = normalizeAddress(userAddress)
    val paymentToken = readPaymentToken()
    val walletCredits = readCredits(owner)
    val creditPrice = readCreditPrice()
    val tokenBalance = readErc20BalanceRaw(address = owner, token = paymentToken)
    val allowance = readErc20Allowance(token = paymentToken, owner = owner, spender = STUDY_SET_REGISTRY)
    StudyCreditsQuote(
      registry = STUDY_SET_REGISTRY,
      paymentToken = paymentToken,
      walletCredits = walletCredits,
      creditPrice = creditPrice,
      tokenBalance = tokenBalance,
      allowance = allowance,
    )
  }

  suspend fun buy(
    context: Context,
    ownerAddress: String,
    creditCount: Int,
  ): StudyCreditsBuyResult {
    if (creditCount <= 0) {
      return StudyCreditsBuyResult(success = false, error = "Credit count must be greater than zero.")
    }

    return runCatching {
      val owner = normalizeAddress(ownerAddress)
      val quote = withContext(Dispatchers.IO) { quote(owner) }
      val creditCountBig = BigInteger.valueOf(creditCount.toLong())
      val totalCost = quote.creditPrice.multiply(creditCountBig)

      if (quote.tokenBalance < totalCost) {
        throw IllegalStateException("Insufficient payment token balance.")
      }

      var approvalTxHash: String? = null
      if (quote.allowance < totalCost) {
        val approveCalldata = encodeApproveCall(spender = quote.registry, value = totalCost)
        approvalTxHash =
          PrivyRelayClient.submitContractCall(
            context = context.applicationContext,
            chainId = PirateChainConfig.STORY_AENEID_CHAIN_ID,
            to = quote.paymentToken,
            data = approveCalldata,
            intentType = "pirate.credits.approve",
            intentArgs =
              JSONObject()
                .put("registry", quote.registry)
                .put("creditCount", creditCount)
                .put("amount", totalCost.toString()),
          )
        if (!awaitStoryReceipt(approvalTxHash)) {
          throw IllegalStateException("Credits approval reverted on-chain: $approvalTxHash")
        }
      }

      val buyCalldata =
        FunctionEncoder.encode(
          Function(
            "buyCredits",
            listOf(Uint256(creditCountBig)),
            emptyList(),
          ),
        )
      val buyTxHash =
        PrivyRelayClient.submitContractCall(
          context = context.applicationContext,
          chainId = PirateChainConfig.STORY_AENEID_CHAIN_ID,
          to = quote.registry,
          data = buyCalldata,
          intentType = "pirate.credits.buy",
          intentArgs =
            JSONObject()
              .put("registry", quote.registry)
              .put("creditCount", creditCount)
              .put("amount", totalCost.toString()),
        )
      if (!awaitStoryReceipt(buyTxHash)) {
        throw IllegalStateException("Credits purchase reverted on-chain: $buyTxHash")
      }

      StudyCreditsBuyResult(
        success = true,
        creditsPurchased = creditCount,
        totalCost = totalCost,
        buyTxHash = buyTxHash,
        approvalTxHash = approvalTxHash,
      )
    }.getOrElse { err ->
      StudyCreditsBuyResult(success = false, error = err.message ?: "Credit purchase failed.")
    }
  }

  private fun readCredits(owner: String): BigInteger {
    val function =
      Function(
        "credits",
        listOf(Address(owner)),
        emptyList(),
      )
    return parseUint256(storyEthCall(STUDY_SET_REGISTRY, FunctionEncoder.encode(function)))
  }

  private fun readCreditPrice(): BigInteger {
    val function = Function("CREDIT_PRICE", emptyList(), emptyList())
    return parseUint256(storyEthCall(STUDY_SET_REGISTRY, FunctionEncoder.encode(function)))
  }

  private fun readPaymentToken(): String {
    val function = Function("paymentToken", emptyList(), emptyList())
    val result = storyEthCall(STUDY_SET_REGISTRY, FunctionEncoder.encode(function))
    return parseAddressWord(result) ?: PirateChainConfig.STORY_STABLE_TOKEN
  }

  private fun normalizeAddress(raw: String): String {
    val trimmed = raw.trim()
    val prefixed = if (trimmed.startsWith("0x", ignoreCase = true)) trimmed else "0x$trimmed"
    require(ADDRESS_REGEX.matches(prefixed)) { "Invalid wallet address." }
    return "0x${prefixed.removePrefix("0x").removePrefix("0X")}".lowercase()
  }
}
