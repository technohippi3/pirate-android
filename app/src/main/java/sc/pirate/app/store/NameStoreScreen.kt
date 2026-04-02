package sc.pirate.app.store

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import sc.pirate.app.PirateChainConfig
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
  val tier: String,
  val isPremiumListing: Boolean = true,
  val premiumQuote: PremiumStoreQuote? = null,
)

@Composable
fun NameStoreScreen(
  isAuthenticated: Boolean,
  walletAddress: String?,
  onClose: () -> Unit,
  onShowMessage: (String) -> Unit,
) {
  val appContext = LocalContext.current.applicationContext
  val scope = rememberCoroutineScope()

  var selectedTld by remember { mutableStateOf("pirate") }
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
  val paymentSymbol = tokenSymbolForAddress(PirateChainConfig.BASE_SEPOLIA_USDC)
  val canBuy = isAuthenticated && !walletAddress.isNullOrBlank() && selectedName != null && !buying

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
      val priceDisplay = if (isListed) "${formatStoreTokenAmount(listing.price)} $paymentSymbol" else "Policy quote on buy"
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

  LaunchedEffect(isAuthenticated, walletAddress, selectedTld, refreshNonce) {
    if (!isAuthenticated || walletAddress.isNullOrBlank()) {
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
                price = "${formatStoreTokenAmount(quote.listing.price)} $paymentSymbol",
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
    hasAccount = !walletAddress.isNullOrBlank(),
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
    onBuy = buy@{
      val ownerAddress = walletAddress?.trim()?.takeIf { it.isNotEmpty() } ?: return@buy
      val name = selectedName ?: return@buy
      if (!canBuy) return@buy
      scope.launch {
        buying = true
        val buyResult =
          PremiumNameStoreApi.buy(
            context = appContext,
            ownerAddress = ownerAddress,
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
