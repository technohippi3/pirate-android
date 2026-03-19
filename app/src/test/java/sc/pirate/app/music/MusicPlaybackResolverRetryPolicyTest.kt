package sc.pirate.app.music

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MusicPlaybackResolverRetryPolicyTest {
    private val noopLog: (String) -> Unit = {}

    @Test
    fun purchaseUnlockBudget_isTenSeconds() {
        assertEquals(10_000L, PURCHASE_UNLOCK_BUDGET_MS)
    }

    @Test
    fun fetchJsonFromUrlWithRetry_trimsLastDelayToBudgetAndStops() = runBlocking {
        var nowMs = 0L
        var fetchCalls = 0
        val delays = mutableListOf<Long>()

        val result = fetchJsonFromUrlWithRetry(
            url = "https://example.invalid/manifest",
            deadlineMs = 2_500L,
            fetchJson = {
                fetchCalls += 1
                null
            },
            delayMillis = { waitMs ->
                delays += waitMs
                nowMs += waitMs
            },
            nowMs = { nowMs },
            logInfo = noopLog,
            logWarn = noopLog,
        )

        assertNull(result)
        assertEquals(3, fetchCalls)
        assertEquals(listOf(1_000L, 1_000L, 500L), delays)
        assertEquals(2_500L, nowMs)
    }

    @Test
    fun fetchJsonFromUrlWithRetry_sharedDeadlineCarriesAcrossCalls() = runBlocking {
        var nowMs = 0L
        var firstCallFetches = 0
        var secondCallFetches = 0

        fetchJsonFromUrlWithRetry(
            url = "https://example.invalid/manifest",
            deadlineMs = 3_500L,
            fetchJson = {
                firstCallFetches += 1
                null
            },
            delayMillis = { waitMs -> nowMs += waitMs },
            nowMs = { nowMs },
            logInfo = noopLog,
            logWarn = noopLog,
        )

        fetchJsonFromUrlWithRetry(
            url = "https://example.invalid/envelope",
            deadlineMs = 3_500L,
            fetchJson = {
                secondCallFetches += 1
                null
            },
            delayMillis = { waitMs -> nowMs += waitMs },
            nowMs = { nowMs },
            logInfo = noopLog,
            logWarn = noopLog,
        )

        assertEquals(4, firstCallFetches)
        assertEquals(0, secondCallFetches)
        assertEquals(3_500L, nowMs)
    }

    @Test
    fun fetchJsonFromUrlWithRetry_returnsEarlyOnSuccess() = runBlocking {
        var nowMs = 0L
        var fetchCalls = 0
        val delays = mutableListOf<Long>()

        val result = fetchJsonFromUrlWithRetry(
            url = "https://example.invalid/envelope",
            deadlineMs = PURCHASE_UNLOCK_BUDGET_MS,
            fetchJson = {
                fetchCalls += 1
                if (fetchCalls < 3) null else JSONObject("""{"ok":true}""")
            },
            delayMillis = { waitMs ->
                delays += waitMs
                nowMs += waitMs
            },
            nowMs = { nowMs },
            logInfo = noopLog,
            logWarn = noopLog,
        )

        assertNotNull(result)
        assertEquals(3, fetchCalls)
        assertEquals(listOf(1_000L, 1_000L), delays)
    }
}
