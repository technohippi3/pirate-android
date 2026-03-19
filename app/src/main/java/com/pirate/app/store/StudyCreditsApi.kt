package com.pirate.app.store

import androidx.fragment.app.FragmentActivity
import com.pirate.app.tempo.SessionKeyManager
import com.pirate.app.tempo.TempoClient
import com.pirate.app.tempo.TempoPasskeyManager
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
  const val STUDY_SET_REGISTRY = "0xB439853d4e8870A5f75277fa6b3c21C9B5Da089A"

  private const val MIN_GAS_LIMIT_APPROVE = 120_000L
  private const val MIN_GAS_LIMIT_BUY = 260_000L

  suspend fun quote(userAddress: String): StudyCreditsQuote =
    withContext(Dispatchers.IO) {
      val owner = userAddress.trim()
      val paymentToken = readPaymentToken()
      val walletCredits = readCredits(owner)
      val creditPrice = readCreditPrice()
      val tokenBalance = TempoClient.getErc20BalanceRaw(address = owner, token = paymentToken)
      val allowance = readAllowance(token = paymentToken, owner = owner, spender = STUDY_SET_REGISTRY)
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
    activity: FragmentActivity,
    account: TempoPasskeyManager.PasskeyAccount,
    creditCount: Int,
    rpId: String = account.rpId,
    sessionKey: SessionKeyManager.SessionKey? = null,
    preferSelfPay: Boolean = false,
  ): StudyCreditsBuyResult {
    if (creditCount <= 0) {
      return StudyCreditsBuyResult(success = false, error = "Credit count must be greater than zero.")
    }

    return runCatching {
      val chainId = withContext(Dispatchers.IO) { TempoClient.getChainId() }
      if (chainId != TempoClient.CHAIN_ID) {
        throw IllegalStateException("Wrong chain connected: $chainId (expected ${TempoClient.CHAIN_ID})")
      }

      val quote = withContext(Dispatchers.IO) { quote(account.address) }
      val creditCountBig = BigInteger.valueOf(creditCount.toLong())
      val totalCost = quote.creditPrice.multiply(creditCountBig)

      if (quote.tokenBalance < totalCost) {
        throw IllegalStateException("Insufficient payment token balance.")
      }

      var approvalTxHash: String? = null
      if (quote.allowance < totalCost) {
        val approveCalldata = encodeApproveCall(spender = quote.registry, value = totalCost)
        approvalTxHash =
          submitCallWithFallback(
            activity = activity,
            account = account,
            to = quote.paymentToken,
            callData = approveCalldata,
            minimumGasLimit = MIN_GAS_LIMIT_APPROVE,
            rpId = rpId,
            sessionKey = sessionKey,
            preferSelfPay = preferSelfPay,
          )
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
        submitCallWithFallback(
          activity = activity,
          account = account,
          to = quote.registry,
          callData = buyCalldata,
          minimumGasLimit = MIN_GAS_LIMIT_BUY,
          rpId = rpId,
          sessionKey = sessionKey,
          preferSelfPay = preferSelfPay,
        )

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
    val callData = FunctionEncoder.encode(function)
    return parseUint256(ethCall(STUDY_SET_REGISTRY, callData))
  }

  private fun readCreditPrice(): BigInteger {
    val function = Function("CREDIT_PRICE", emptyList(), emptyList())
    val callData = FunctionEncoder.encode(function)
    return parseUint256(ethCall(STUDY_SET_REGISTRY, callData))
  }

  private fun readPaymentToken(): String {
    val function = Function("paymentToken", emptyList(), emptyList())
    val callData = FunctionEncoder.encode(function)
    val result = ethCall(STUDY_SET_REGISTRY, callData)
    return parseAddressWord(result) ?: TempoClient.ALPHA_USD
  }

  private fun readAllowance(token: String, owner: String, spender: String): BigInteger {
    val function =
      Function(
        "allowance",
        listOf(Address(owner), Address(spender)),
        emptyList(),
      )
    val callData = FunctionEncoder.encode(function)
    return parseUint256(ethCall(token, callData))
  }

  private fun parseAddressWord(resultHex: String): String? {
    val clean = resultHex.removePrefix("0x").lowercase()
    if (clean.length < 64) return null
    val word = clean.takeLast(64)
    val raw = word.takeLast(40)
    if (raw.all { it == '0' }) return null
    return "0x$raw"
  }
}
