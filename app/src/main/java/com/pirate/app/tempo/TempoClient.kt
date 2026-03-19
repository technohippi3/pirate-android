package com.pirate.app.tempo

import com.pirate.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

/**
 * Minimal Tempo testnet RPC client.
 */
object TempoClient {

    const val CHAIN_ID = 42431L
    const val RPC_URL = "https://rpc.moderato.tempo.xyz"
    const val SPONSOR_URL = "https://sponsor.moderato.tempo.xyz"
    const val EXPLORER_URL = "https://explore.tempo.xyz"
    const val BASE_SEPOLIA_CHAIN_ID = 84532L
    const val BASE_SEPOLIA_RPC_URL = "https://base-sepolia-rpc.publicnode.com"
    const val BASE_SEPOLIA_USDC = "0x036cbd53842c5426634e7929541ec2318f3dcf7e"
    // Update this after each SessionEscrow redeploy.
    const val SESSION_ESCROW_V1 = "0xF3B4DB746a874A2197ae21C857e1Ca692C6ED059"
    // StudyAttemptsV1 from contracts/tempo deployment.
    const val STUDY_ATTEMPTS_V1 = "0xd439F574b41264c14e6c3494B1fdBc85B02C623D"
    // StreakClaimV1 from contracts/tempo deployment.
    const val STREAK_CLAIM_V1 = "0xAfeb3842A34e84a9070AE4D75dbDB0f02fE77B5f"
    const val ALPHA_USD = "0x20c0000000000000000000000000000000000001"
    const val BETA_USD = "0x20c0000000000000000000000000000000000002"
    const val THETA_USD = "0x20c0000000000000000000000000000000000003"
    const val NONCE_PRECOMPILE = "0x4E4F4E4345000000000000000000000000000000"

    private const val TIP20_DECIMALS = 6
    private const val RPC_IO_MAX_ATTEMPTS = 3
    private const val RPC_IO_RETRY_DELAY_MS = 300L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json".toMediaType()

    sealed class RpcResult {
        data class Success(val result: Any?) : RpcResult()
        data class Error(val message: String) : RpcResult()
    }

    data class Eip1559Fees(
        val maxPriorityFeePerGas: Long,
        val maxFeePerGas: Long,
    )

    data class TransactionReceipt(
        val txHash: String,
        val statusHex: String,
    ) {
        val isSuccess: Boolean
            get() = statusHex.equals("0x1", ignoreCase = true)
    }

    data class SenderNonceTransaction(
        val hash: String,
        val blockNumberHex: String?,
        val maxPriorityFeePerGas: Long?,
        val maxFeePerGas: Long?,
        val gasPrice: Long?,
    ) {
        val isMined: Boolean
            get() = !blockNumberHex.isNullOrBlank()
    }

    data class Stablecoin(
        val symbol: String,
        val address: String,
        val decimals: Int = TIP20_DECIMALS,
    )

    data class StablecoinBalance(
        val token: Stablecoin,
        val rawAmount: BigInteger,
    )

    data class SongTermsResponse(
        val commercialUse: Boolean,
        val commercialRevSharePpm8: Int,
        val approvalMode: String,
        val approvalSlaSec: Int,
        val remixable: Boolean,
    )

    val SUPPORTED_STABLECOINS: List<Stablecoin> = listOf(
        Stablecoin(symbol = "AlphaUSD", address = ALPHA_USD),
        Stablecoin(symbol = "BetaUSD", address = BETA_USD),
        Stablecoin(symbol = "ThetaUSD", address = THETA_USD),
    )

    private suspend fun rpc(
        method: String,
        params: JSONArray = JSONArray(),
        url: String = RPC_URL,
    ): RpcResult {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)

        return withContext(Dispatchers.IO) {
            var lastIoError: String? = null
            for (attempt in 1..RPC_IO_MAX_ATTEMPTS) {
                val request = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(jsonType))
                    .build()
                try {
                    client.newCall(request).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext RpcResult.Error("RPC failed (${resp.code}): $text")
                        }
                        val json = JSONObject(text)
                        if (json.has("error")) {
                            val err = json.get("error")
                            return@withContext RpcResult.Error("RPC error: $err")
                        }
                        return@withContext RpcResult.Success(json.opt("result"))
                    }
                } catch (e: IOException) {
                    lastIoError = "RPC call failed: ${e.message ?: e::class.java.simpleName}"
                    if (attempt < RPC_IO_MAX_ATTEMPTS) {
                        delay(RPC_IO_RETRY_DELAY_MS * attempt)
                        continue
                    }
                    return@withContext RpcResult.Error(lastIoError)
                } catch (e: Exception) {
                    return@withContext RpcResult.Error("RPC call failed: ${e.message}")
                }
            }
            RpcResult.Error(lastIoError ?: "RPC call failed")
        }
    }

    /** Fund address with testnet stablecoins. */
    suspend fun fundAddress(address: String): String {
        val result = rpc("tempo_fundAddress", JSONArray().put(address))
        return when (result) {
            is RpcResult.Success -> result.result?.toString() ?: "funded"
            is RpcResult.Error -> throw TempoException("Fund address failed: ${result.message}")
        }
    }

    /** Get AlphaUSD balance (ERC-20 balanceOf via eth_call). */
    suspend fun getBalance(address: String): String {
        val raw = getTokenBalanceRaw(address = address, token = ALPHA_USD)
        return formatUnits(rawAmount = raw, decimals = TIP20_DECIMALS, maxFractionDigits = 2)
    }

    /** Get balances for all supported Tempo stablecoins. */
    suspend fun getStablecoinBalances(address: String): List<StablecoinBalance> {
        return SUPPORTED_STABLECOINS.map { stablecoin ->
            StablecoinBalance(
                token = stablecoin,
                rawAmount = getTokenBalanceRaw(address = address, token = stablecoin.address),
            )
        }
    }

    suspend fun getErc20BalanceRaw(
        address: String,
        token: String,
        rpcUrl: String = RPC_URL,
    ): BigInteger {
        return getTokenBalanceRaw(address = address, token = token, rpcUrl = rpcUrl)
    }

    fun formatUnits(
        rawAmount: BigInteger,
        decimals: Int = TIP20_DECIMALS,
        maxFractionDigits: Int = 2,
    ): String {
        if (rawAmount <= BigInteger.ZERO) return "0"
        val safeDecimals = decimals.coerceAtLeast(0)
        val safeMaxFractionDigits = maxFractionDigits.coerceAtLeast(0)
        val divisor = BigDecimal.TEN.pow(safeDecimals)
        val value = rawAmount.toBigDecimal().divide(divisor, safeDecimals, RoundingMode.DOWN)
        val scaled = value.setScale(safeMaxFractionDigits, RoundingMode.DOWN)
        return scaled.stripTrailingZeros().toPlainString()
    }

    /** Get transaction count (nonce) for address. */
    suspend fun getNonce(address: String, blockTag: String = "pending"): Long {
        val result = rpc("eth_getTransactionCount", JSONArray().put(address).put(blockTag))
        val hex = when (result) {
            is RpcResult.Success -> result.result?.toString()?.removePrefix("0x") ?: "0"
            is RpcResult.Error -> throw TempoException("Get nonce failed: ${result.message}")
        }
        return hex.toLong(16)
    }

    /**
     * Get 2D nonce for a specific nonce key.
     * For nonceKey 0, this is the standard protocol nonce from account state.
     * For nonceKey > 0, this queries the Nonce precompile getNonce(address,uint256).
     */
    suspend fun getNonceByKey(
        address: String,
        nonceKey: Long,
        blockTag: String = "latest",
    ): Long {
        if (nonceKey == 0L) return getNonce(address, blockTag = blockTag)
        require(nonceKey > 0L) { "nonceKey must be >= 0" }

        val selector = "89535803" // getNonce(address,uint256)
        val addressWord = encodeAddressWord(address)
        val nonceWord = encodeUintWord(nonceKey)
        val data = "0x$selector$addressWord$nonceWord"

        val callObj = JSONObject()
            .put("to", NONCE_PRECOMPILE)
            .put("data", data)
        val result = rpc("eth_call", JSONArray().put(callObj).put(blockTag))
        val hex = when (result) {
            is RpcResult.Success -> result.result?.toString()?.removePrefix("0x").orEmpty()
            is RpcResult.Error -> throw TempoException("Get nonce by key failed: ${result.message}")
        }
        val clean = hex.ifBlank { "0" }
        return clean.toLongOrNull(16) ?: 0L
    }

    /** Get current gas price. */
    suspend fun getGasPrice(): Long {
        val result = rpc("eth_gasPrice")
        val hex = when (result) {
            is RpcResult.Success -> result.result?.toString()?.removePrefix("0x") ?: "0"
            is RpcResult.Error -> throw TempoException("Get gas price failed: ${result.message}")
        }
        return hex.toLong(16)
    }

    /** Build buffered EIP-1559 fees from current gas price to avoid base-fee race failures. */
    suspend fun getSuggestedFees(): Eip1559Fees {
        val gasPrice = getGasPrice()
        val priority = (gasPrice / 5).coerceAtLeast(1_000_000L)
        val buffered = saturatingMul(gasPrice, 4)
        val minRequired = saturatingAdd(gasPrice, priority)
        val maxFee = maxOf(buffered, minRequired)
        return Eip1559Fees(
            maxPriorityFeePerGas = priority,
            maxFeePerGas = maxFee,
        )
    }

    /** Send raw signed transaction. */
    suspend fun sendRawTransaction(signedTxHex: String): String {
        val result = rpc("eth_sendRawTransaction", JSONArray().put(signedTxHex))
        return when (result) {
            is RpcResult.Success -> result.result?.toString() ?: throw TempoException("No tx hash returned")
            is RpcResult.Error -> throw TempoException("Send raw transaction failed: ${result.message}")
        }
    }

    /**
     * Request fee sponsorship from the public Moderato fee payer relay,
     * then broadcast the relay-signed transaction to chain RPC.
     */
    suspend fun sendSponsoredRawTransaction(
        signedTxHex: String,
        senderAddress: String,
        feePayerUrl: String = SPONSOR_URL,
    ): String {
        val txWithSenderHint = TempoTransaction.appendSenderHint(signedTxHex, senderAddress)
        val relaySigned = when (val result = rpc(
            method = "eth_signRawTransaction",
            params = JSONArray().put(txWithSenderHint),
            url = feePayerUrl,
        )) {
            is RpcResult.Success -> result.result?.toString()
                ?: throw TempoException("No relay signature returned")
            is RpcResult.Error -> {
                val directDiagnostic = runCatching {
                    rpc("eth_sendRawTransaction", JSONArray().put(signedTxHex), RPC_URL)
                    "direct send unexpectedly succeeded"
                }.exceptionOrNull()?.message ?: "direct send unexpectedly succeeded"
                throw TempoException(
                    "Fee sponsorship failed: ${result.message}; direct-send diagnostic: $directDiagnostic",
                )
            }
        }

        val txHashResult = rpc("eth_sendRawTransaction", JSONArray().put(relaySigned), RPC_URL)
        return when (txHashResult) {
            is RpcResult.Success -> txHashResult.result?.toString()
                ?: throw TempoException("No tx hash returned")
            is RpcResult.Error -> throw TempoException("Broadcast relay tx failed: ${txHashResult.message}")
        }
    }

    suspend fun getTransactionReceipt(txHash: String): TransactionReceipt? {
        val result = rpc("eth_getTransactionReceipt", JSONArray().put(txHash))
        val receipt = when (result) {
            is RpcResult.Success -> result.result as? JSONObject
            is RpcResult.Error -> return null
        } ?: return null
        return TransactionReceipt(
            txHash = receipt.optString("transactionHash", txHash),
            statusHex = receipt.optString("status", "0x0"),
        )
    }

    suspend fun hasTransaction(txHash: String): Boolean {
        val result = rpc("eth_getTransactionByHash", JSONArray().put(txHash))
        val tx = when (result) {
            is RpcResult.Success -> result.result as? JSONObject
            is RpcResult.Error -> return false
        } ?: return false
        val hash = tx.optString("hash", "").trim()
        return hash.equals(txHash, ignoreCase = true)
    }

    suspend fun getTransactionBySenderAndNonce(
        senderAddress: String,
        nonce: Long,
    ): SenderNonceTransaction? {
        val nonceHex = "0x" + nonce.toString(16)
        val result = rpc(
            "eth_getTransactionBySenderAndNonce",
            JSONArray().put(senderAddress).put(nonceHex),
        )
        val tx = when (result) {
            is RpcResult.Success -> result.result as? JSONObject
            is RpcResult.Error -> return null
        } ?: return null
        val hash = tx.optString("hash", "").trim()
        if (hash.isBlank()) return null
        val blockNumber = tx.optString("blockNumber", "").trim().ifBlank { null }
        val maxPriorityFeePerGas = parseHexLong(tx.optString("maxPriorityFeePerGas", ""))
        val maxFeePerGas = parseHexLong(tx.optString("maxFeePerGas", ""))
        val gasPrice = parseHexLong(tx.optString("gasPrice", ""))
        return SenderNonceTransaction(
            hash = hash,
            blockNumberHex = blockNumber,
            maxPriorityFeePerGas = maxPriorityFeePerGas,
            maxFeePerGas = maxFeePerGas,
            gasPrice = gasPrice,
        )
    }

    suspend fun waitForTransactionReceipt(
        txHash: String,
        timeoutMs: Long = 90_000L,
        pollMs: Long = 1_500L,
    ): TransactionReceipt {
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadlineMs) {
            val receipt = getTransactionReceipt(txHash)
            if (receipt != null) return receipt
            delay(pollMs)
        }
        throw TempoException("Timed out waiting for transaction receipt: $txHash")
    }

    /** Get chain ID to confirm connection. */
    suspend fun getChainId(): Long {
        val result = rpc("eth_chainId")
        val hex = when (result) {
            is RpcResult.Success -> result.result?.toString()?.removePrefix("0x") ?: "0"
            is RpcResult.Error -> throw TempoException("Get chain ID failed: ${result.message}")
        }
        return hex.toLong(16)
    }

    suspend fun fetchSongTermsBatch(trackIds: List<String>): Map<String, SongTermsResponse> {
        val normalizedTrackIds = trackIds
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(100)
            .toList()
        if (normalizedTrackIds.isEmpty()) return emptyMap()

        val apiBase = resolveApiCoreBaseUrlOrNull() ?: return emptyMap()
        return withContext(Dispatchers.IO) {
            runCatching {
                val out = linkedMapOf<String, SongTermsResponse>()
                for (chunk in normalizedTrackIds.chunked(100)) {
                    if (chunk.isEmpty()) continue
                    val trackIdsParam = chunk.joinToString(",")
                    val request = Request.Builder()
                        .url("$apiBase/api/music/terms?trackIds=$trackIdsParam")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IllegalStateException("Song terms request failed: ${response.code}")
                        }
                        val payload = JSONObject(response.body?.string().orEmpty())
                        val termsJson = payload.optJSONObject("terms") ?: JSONObject()
                        for (trackId in chunk) {
                            val row = termsJson.optJSONObject(trackId) ?: continue
                            out[trackId] = SongTermsResponse(
                                commercialUse = row.optBoolean("commercialUse", true),
                                commercialRevSharePpm8 = row.optInt("commercialRevSharePpm8", 0),
                                approvalMode = normalizeApprovalMode(row.optString("approvalMode", "auto")),
                                approvalSlaSec = row.optInt("approvalSlaSec", 259200),
                                remixable = row.optBoolean("remixable", false),
                            )
                        }
                    }
                }
                out
            }.getOrElse { emptyMap() }
        }
    }

    private fun saturatingAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    private fun saturatingMul(a: Long, factor: Int): Long =
        if (a > Long.MAX_VALUE / factor) Long.MAX_VALUE else a * factor

    private fun resolveApiCoreBaseUrlOrNull(): String? {
        val configured = BuildConfig.API_CORE_URL.trim().trimEnd('/')
        return if (configured.startsWith("https://") || configured.startsWith("http://")) configured else null
    }

    private fun normalizeApprovalMode(value: String): String {
        return if (value.trim().equals("gated", ignoreCase = true)) "gated" else "auto"
    }

    private fun parseHexLong(value: String): Long? {
        val clean = value.trim().removePrefix("0x").removePrefix("0X")
        if (clean.isBlank()) return null
        return clean.toLongOrNull(16)
    }

    private suspend fun getTokenBalanceRaw(
        address: String,
        token: String,
        rpcUrl: String = RPC_URL,
    ): BigInteger {
        // balanceOf(address) selector = 0x70a08231
        val cleanAddress = address.trim().removePrefix("0x").removePrefix("0X").lowercase()
        require(cleanAddress.length == 40 && cleanAddress.all { it in "0123456789abcdef" }) {
            "invalid address: $address"
        }
        val addrPadded = cleanAddress.padStart(64, '0')
        val data = "0x70a08231$addrPadded"

        val callObj = JSONObject()
            .put("to", token)
            .put("data", data)

        val result = rpc("eth_call", JSONArray().put(callObj).put("latest"), url = rpcUrl)
        val hex = when (result) {
            is RpcResult.Success -> result.result?.toString()?.removePrefix("0x")?.ifBlank { "0" } ?: "0"
            is RpcResult.Error -> throw TempoException("Get token balance failed: ${result.message}")
        }
        return hex.toBigIntegerOrNull(16) ?: BigInteger.ZERO
    }

    private fun encodeAddressWord(address: String): String {
        val clean = address.trim().removePrefix("0x").removePrefix("0X").lowercase()
        require(clean.length == 40 && clean.all { it in "0123456789abcdef" }) {
            "invalid address"
        }
        return clean.padStart(64, '0')
    }

    private fun encodeUintWord(value: Long): String {
        require(value >= 0L) { "uint value must be >= 0" }
        return value.toString(16).padStart(64, '0')
    }
}

class TempoException(message: String) : Exception(message)
