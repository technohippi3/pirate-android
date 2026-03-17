package com.pirate.app.store

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.pirate.app.tempo.TempoClient
import com.pirate.app.tempo.TempoPasskeyManager
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TIER1_LABELS = listOf("king", "queen", "god", "devil", "pirate", "heaven", "love", "moon", "star", "angel")
private val TIER3_LABELS = listOf("ace", "zen", "nova", "wolf", "punk", "dope", "fire", "gold", "jade", "ruby")

private const val TIER1_PRICE = "100.00"
private const val TIER3_PRICE = "25.00"

internal data class SelectableName(
  val label: String,
  val price: String,
  val tier: String, // "premium", "standard", or "policy"
  val isPremiumListing: Boolean = true,
  val premiumQuote: PremiumStoreQuote? = null,
)

private fun formatAUsd(rawAmount: BigInteger): String {
  return TempoClient.formatUnits(rawAmount = rawAmount, decimals = 6, maxFractionDigits = 2)
}

@Composable
fun NameStoreScreen(
  activity: FragmentActivity,
  isAuthenticated: Boolean,
  account: TempoPasskeyManager.PasskeyAccount?,
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()

  var selectedTld by remember { mutableStateOf("heaven") }
  var selectedName by remember { mutableStateOf<SelectableName?>(null) }

  var customInput by remember { mutableStateOf("") }
  var checkingCustom by remember { mutableStateOf(false) }
  var customResult by remember { mutableStateOf<SelectableName?>(null) }
  var customError by remember { mutableStateOf<String?>(null) }

  var availableNames by remember(selectedTld) { mutableStateOf<List<SelectableName>>(emptyList()) }
  var loadingNames by remember(selectedTld) { mutableStateOf(false) }

  var buying by remember { mutableStateOf(false) }
  var refreshNonce by remember { mutableIntStateOf(0) }

  val customNormalized = PremiumNameStoreApi.normalizeLabel(customInput)
  val customValid = PremiumNameStoreApi.isValidLabel(customNormalized)
  val canBuy = isAuthenticated && account != null && selectedName != null && !buying

  LaunchedEffect(customNormalized, selectedTld) {
    customResult = null
    customError = null
    if (!customValid || customNormalized.isBlank()) {
      checkingCustom = false
      return@LaunchedEffect
    }
    checkingCustom = true
    delay(400)

    val premiumQuote =
      runCatching {
        withContext(Dispatchers.IO) {
          PremiumNameStoreApi.quote(label = customNormalized, tld = selectedTld)
        }
      }.getOrNull()

    if (premiumQuote != null) {
      val listing = premiumQuote.listing
      val isListed = listing.enabled && listing.durationSeconds > 0L
      val priceDisplay = if (isListed) "${formatAUsd(listing.price)} αUSD" else "Policy quote on buy"
      val tier = if (isListed) "premium" else "policy"
      val name =
        SelectableName(
          label = customNormalized,
          price = priceDisplay,
          tier = tier,
          premiumQuote = premiumQuote,
        )
      customResult = name
      selectedName = name
    } else {
      customError = "Unable to check this name right now."
    }

    checkingCustom = false
  }

  LaunchedEffect(isAuthenticated, account?.address, selectedTld, refreshNonce) {
    if (!isAuthenticated || account == null) {
      availableNames = emptyList()
      loadingNames = false
      return@LaunchedEffect
    }

    loadingNames = true
    selectedName = null

    val allLabels = TIER1_LABELS.map { it to TIER1_PRICE } + TIER3_LABELS.map { it to TIER3_PRICE }
    val results = mutableListOf<SelectableName>()

    withContext(Dispatchers.IO) {
      for ((label, price) in allLabels) {
        runCatching {
          val quote = PremiumNameStoreApi.quote(label = label, tld = selectedTld)
          if (quote.listing.enabled && quote.listing.durationSeconds > 0L) {
            val tier = if (price == TIER1_PRICE) "premium" else "standard"
            results.add(
              SelectableName(
                label = label,
                price = "${formatAUsd(quote.listing.price)} αUSD",
                tier = tier,
                isPremiumListing = true,
                premiumQuote = quote,
              ),
            )
          }
        }
      }
    }

    availableNames = results
    loadingNames = false
  }

  NameStoreContent(
    isAuthenticated = isAuthenticated,
    hasAccount = account != null,
    selectedTld = selectedTld,
    customInput = customInput,
    checkingCustom = checkingCustom,
    customResult = customResult,
    customError = customError,
    customValid = customValid,
    customNormalized = customNormalized,
    loadingNames = loadingNames,
    availableNames = availableNames,
    selectedName = selectedName,
    buying = buying,
    canBuy = canBuy,
    onClose = onClose,
    onSelectTld = { tld ->
      selectedTld = tld
      customInput = ""
      selectedName = null
      customResult = null
      customError = null
    },
    onCustomInputChange = { value ->
      customInput = value.lowercase().filter { char -> char.isLetterOrDigit() || char == '-' }
      selectedName = null
      customResult = null
      customError = null
    },
    onSelectName = { name ->
      selectedName = name
      customInput = ""
      customResult = null
      customError = null
    },
    onBuy =
      buy@{
        val name = selectedName ?: return@buy
        val passkeyAccount = account ?: return@buy
        if (!canBuy) return@buy
        scope.launch {
          buying = true
          val buyResult =
            PremiumNameStoreApi.buy(
              activity = activity,
              account = passkeyAccount,
              label = name.label,
              tld = selectedTld,
              maxPrice =
                name.premiumQuote?.listing
                  ?.takeIf { it.enabled && it.durationSeconds > 0L }
                  ?.price,
            )
          buying = false
          if (!buyResult.success) {
            onShowMessage(buyResult.error ?: "Purchase failed.")
            return@launch
          }
          onShowMessage("Purchased ${name.label}.$selectedTld")
          selectedName = null
          customInput = ""
          customResult = null
          refreshNonce += 1
        }
      },
  )
}
