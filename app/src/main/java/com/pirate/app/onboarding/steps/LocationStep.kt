package com.pirate.app.onboarding.steps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.util.Log
import com.pirate.app.R
import com.pirate.app.ui.PirateOutlinedTextField
import com.pirate.app.ui.PiratePrimaryButton
import java.net.URL
import java.net.URLEncoder

private const val TAG = "LocationStep"

data class LocationResult(
  val label: String,
  val lat: Double,
  val lng: Double,
  val countryCode: String?,
)

/** Abbreviate location label like the web app: "City, CC" or "City, ST, CC" for US/CA */
private fun abbreviateLocation(name: String?, state: String?, country: String?, countryCode: String?): String {
  val city = name ?: return ""
  val cc = countryCode?.uppercase() ?: country?.take(2)?.uppercase() ?: ""

  // US/CA: show state abbreviation
  if (cc == "US" || cc == "CA") {
    val stateAbbr = US_CA_STATE_ABBREVS[state] ?: state?.take(2)?.uppercase()
    return if (stateAbbr != null) "$city, $stateAbbr, $cc" else "$city, $cc"
  }
  return "$city, $cc"
}

/** Search Photon API for city-level locations */
suspend fun searchLocations(query: String): List<LocationResult> = withContext(Dispatchers.IO) {
  try {
    val encoded = URLEncoder.encode(query, "UTF-8")
    val url = "https://photon.komoot.io/api/?q=$encoded&limit=6&lang=en"
    val json = URL(url).readText()
    val results = parsePhotonSearchResults(json)
    val featureCount = JSONObject(json).optJSONArray("features")?.length() ?: 0
    Log.d(TAG, "Photon returned $featureCount features, ${results.size} after filter for '$query'")
    // Deduplicate by label
    results.distinctBy { it.label }
  } catch (e: Exception) {
    Log.e(TAG, "Photon search failed for '$query': ${e.message}", e)
    emptyList()
  }
}

internal fun parsePhotonSearchResults(rawJson: String): List<LocationResult> {
  val root = JSONObject(rawJson)
  val features = root.optJSONArray("features") ?: return emptyList()
  val results = mutableListOf<LocationResult>()
  for (i in 0 until features.length()) {
    val feature = features.optJSONObject(i) ?: continue
    val props = feature.optJSONObject("properties") ?: continue
    val type = props.optString("type", "")
    // Filter to place-level results (skip houses, streets, etc.)
    if (type !in listOf("city", "town", "village", "suburb", "district", "locality", "borough", "county", "state")) continue

    val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
    val lat = coords.optDouble(1, Double.NaN)
    val lng = coords.optDouble(0, Double.NaN)
    if (lat.isNaN() || lng.isNaN()) continue

    val label = abbreviateLocation(
      name = props.optString("name", "").takeIf { it.isNotBlank() },
      state = props.optString("state", "").takeIf { it.isNotBlank() },
      country = props.optString("country", "").takeIf { it.isNotBlank() },
      countryCode = props.optString("countrycode", "").takeIf { it.isNotBlank() },
    )
    if (label.isBlank()) continue

    results.add(
      LocationResult(
        label = label,
        lat = lat,
        lng = lng,
        countryCode = props.optString("countrycode", "").takeIf { it.isNotBlank() },
      ),
    )
  }
  return results
}

private val US_CA_STATE_ABBREVS = mapOf(
  "Alabama" to "AL", "Alaska" to "AK", "Arizona" to "AZ", "Arkansas" to "AR",
  "California" to "CA", "Colorado" to "CO", "Connecticut" to "CT", "Delaware" to "DE",
  "Florida" to "FL", "Georgia" to "GA", "Hawaii" to "HI", "Idaho" to "ID",
  "Illinois" to "IL", "Indiana" to "IN", "Iowa" to "IA", "Kansas" to "KS",
  "Kentucky" to "KY", "Louisiana" to "LA", "Maine" to "ME", "Maryland" to "MD",
  "Massachusetts" to "MA", "Michigan" to "MI", "Minnesota" to "MN", "Mississippi" to "MS",
  "Missouri" to "MO", "Montana" to "MT", "Nebraska" to "NE", "Nevada" to "NV",
  "New Hampshire" to "NH", "New Jersey" to "NJ", "New Mexico" to "NM", "New York" to "NY",
  "North Carolina" to "NC", "North Dakota" to "ND", "Ohio" to "OH", "Oklahoma" to "OK",
  "Oregon" to "OR", "Pennsylvania" to "PA", "Rhode Island" to "RI", "South Carolina" to "SC",
  "South Dakota" to "SD", "Tennessee" to "TN", "Texas" to "TX", "Utah" to "UT",
  "Vermont" to "VT", "Virginia" to "VA", "Washington" to "WA", "West Virginia" to "WV",
  "Wisconsin" to "WI", "Wyoming" to "WY",
  // Canadian provinces
  "Alberta" to "AB", "British Columbia" to "BC", "Manitoba" to "MB",
  "New Brunswick" to "NB", "Newfoundland and Labrador" to "NL", "Nova Scotia" to "NS",
  "Ontario" to "ON", "Prince Edward Island" to "PE", "Quebec" to "QC", "Saskatchewan" to "SK",
)

@Composable
fun LocationStep(
  submitting: Boolean,
  onContinue: (LocationResult) -> Unit,
) {
  val keyboardController = LocalSoftwareKeyboardController.current
  var query by remember { mutableStateOf("") }
  var selectedResult by remember { mutableStateOf<LocationResult?>(null) }
  var searching by remember { mutableStateOf(false) }
  val suggestions = remember { mutableStateListOf<LocationResult>() }

  val canContinue = selectedResult != null && !submitting

  // Debounced search
  LaunchedEffect(query) {
    // If user picked a suggestion, don't re-search
    if (query == selectedResult?.label) return@LaunchedEffect
    selectedResult = null
    suggestions.clear()
    if (query.length < 2) {
      searching = false
      return@LaunchedEffect
    }
    searching = true
    delay(300)
    try {
      val results = searchLocations(query)
      suggestions.clear()
      suggestions.addAll(results)
    } finally {
      searching = false
    }
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.Start,
  ) {
    Text(
      stringResource(R.string.onboarding_location_title),
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(32.dp))

    PirateOutlinedTextField(
      value = query,
      onValueChange = { query = it },
      modifier = Modifier.fillMaxWidth(),
      label = { Text(stringResource(R.string.onboarding_location_label)) },
      placeholder = { Text(stringResource(R.string.onboarding_location_placeholder)) },
      singleLine = true,
      trailingIcon = {
        if (searching) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
      },
    )

    // Suggestions dropdown
    if (suggestions.isNotEmpty() && selectedResult == null) {
      Spacer(Modifier.height(4.dp))
      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 240.dp),
      ) {
        items(suggestions, key = { it.label }) { result ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                query = result.label
                selectedResult = result
                suggestions.clear()
                keyboardController?.hide()
              }
              .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(result.label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
          }
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
          Text(
            stringResource(R.string.onboarding_location_openstreetmap_attribution),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(8.dp),
          )
        }
      }
    }

    Spacer(Modifier.weight(1f))

    PiratePrimaryButton(
      text = stringResource(R.string.common_continue),
      onClick = { selectedResult?.let { onContinue(it) } },
      enabled = canContinue,
      modifier = Modifier.fillMaxWidth().height(48.dp),
      loading = submitting,
    )

    Spacer(Modifier.height(32.dp))
  }
}
