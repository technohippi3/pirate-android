package sc.pirate.app.profile

import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import sc.pirate.app.PirateChainConfig
import sc.pirate.app.tempo.P256Utils
import sc.pirate.app.util.HttpClients

internal data class BaseNameRegistrationQuote(
  val callData: String,
  val fullName: String,
  val label: String,
  val node: String,
  val priceWei: BigInteger,
  val tld: String,
)

internal object BaseNameRegistryApi {
  private const val MIN_DURATION_SECONDS = 365L * 24L * 60L * 60L
  private val jsonType = "application/json; charset=utf-8".toMediaType()

  suspend fun checkPirateNameAvailable(label: String): Boolean =
    withContext(Dispatchers.IO) {
      val normalizedLabel = normalizeLabel(label)
      if (normalizedLabel.isBlank()) return@withContext false
      val parentNode =
        TempoNameRecordsApi.parentNodeForTld("pirate")
          ?: error("Missing .pirate parent node.")

      val callData =
        FunctionEncoder.encode(
          Function(
            "available",
            listOf(
              Bytes32(P256Utils.hexToBytes(parentNode.removePrefix("0x"))),
              Utf8String(normalizedLabel),
            ),
            emptyList(),
          ),
        )
      parseBool(ethCall(PirateChainConfig.BASE_SEPOLIA_REGISTRY_V2, callData))
    }

  suspend fun quotePirateRegistration(
    label: String,
    durationSeconds: Long = MIN_DURATION_SECONDS,
  ): BaseNameRegistrationQuote =
    withContext(Dispatchers.IO) {
      val normalizedLabel = normalizeLabel(label)
      require(normalizedLabel.isNotBlank()) { "Name label is empty." }
      require(durationSeconds > 0L) { "Invalid registration duration." }

      val parentNode =
        TempoNameRecordsApi.parentNodeForTld("pirate")
          ?: error("Missing .pirate parent node.")

      val callData =
        FunctionEncoder.encode(
          Function(
            "register",
            listOf(
              Bytes32(P256Utils.hexToBytes(parentNode.removePrefix("0x"))),
              Utf8String(normalizedLabel),
              Uint256(BigInteger.valueOf(durationSeconds)),
            ),
            emptyList(),
          ),
        )

      val priceData =
        FunctionEncoder.encode(
          Function(
            "price",
            listOf(
              Bytes32(P256Utils.hexToBytes(parentNode.removePrefix("0x"))),
              Utf8String(normalizedLabel),
              Uint256(BigInteger.valueOf(durationSeconds)),
            ),
            emptyList(),
          ),
        )
      val priceWei =
        ethCall(PirateChainConfig.BASE_SEPOLIA_REGISTRY_V2, priceData)
          .removePrefix("0x")
          .ifBlank { "0" }
          .toBigIntegerOrNull(16)
          ?: BigInteger.ZERO
      val fullName = "$normalizedLabel.pirate"
      BaseNameRegistrationQuote(
        callData = callData,
        fullName = fullName,
        label = normalizedLabel,
        node = TempoNameRecordsApi.computeNode(fullName),
        priceWei = priceWei,
        tld = "pirate",
      )
    }

  private fun normalizeLabel(label: String): String {
    return label.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' }
  }

  private fun parseBool(result: String): Boolean {
    val clean = result.removePrefix("0x").ifBlank { "0" }
    return clean.toBigIntegerOrNull(16)?.let { it != BigInteger.ZERO } ?: false
  }

  private fun ethCall(to: String, data: String): String {
    val payload =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "eth_call")
        .put(
          "params",
          JSONArray()
            .put(JSONObject().put("to", to).put("data", data))
            .put("latest"),
        )

    val request =
      Request.Builder()
        .url(PirateChainConfig.BASE_SEPOLIA_RPC_URL)
        .post(payload.toString().toRequestBody(jsonType))
        .build()

    HttpClients.Api.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("RPC failed: ${response.code}")
      val body = JSONObject(response.body?.string().orEmpty())
      val error = body.optJSONObject("error")
      if (error != null) {
        throw IllegalStateException(error.optString("message", error.toString()))
      }
      return body.optString("result", "0x")
    }
  }
}
