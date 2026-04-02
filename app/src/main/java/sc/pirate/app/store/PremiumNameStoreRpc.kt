package sc.pirate.app.store

import java.math.BigInteger
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.utils.Numeric

internal fun getListing(
  parentNode: String,
  label: String,
): PremiumStoreListing {
  val function =
    Function(
      "quote",
      listOf(
        Bytes32(Numeric.hexStringToByteArray(parentNode)),
        Utf8String(label),
      ),
      emptyList(),
    )
  val callData = FunctionEncoder.encode(function)
  val result = baseEthCall(PremiumNameStoreApi.PREMIUM_NAME_STORE, callData)
  return parseListing(result)
}

private fun parseListing(resultHex: String): PremiumStoreListing {
  val clean = resultHex.removePrefix("0x").lowercase()
  if (clean.isBlank()) {
    return PremiumStoreListing(price = BigInteger.ZERO, durationSeconds = 0L, enabled = false)
  }

  val padded = clean.padStart(64 * 3, '0')
  if (padded.length < 64 * 3) {
    return PremiumStoreListing(price = BigInteger.ZERO, durationSeconds = 0L, enabled = false)
  }

  val price = padded.substring(0, 64).toBigIntegerOrNull(16) ?: BigInteger.ZERO
  val durationWord = padded.substring(64, 128).toBigIntegerOrNull(16) ?: BigInteger.ZERO
  val duration = durationWord.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
  val enabled = (padded.substring(128, 192).toBigIntegerOrNull(16) ?: BigInteger.ZERO) != BigInteger.ZERO
  return PremiumStoreListing(price = price, durationSeconds = duration, enabled = enabled)
}
