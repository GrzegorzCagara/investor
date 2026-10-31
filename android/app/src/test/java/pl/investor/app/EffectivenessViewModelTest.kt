package pl.investor.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class EffectivenessViewModelTest {

    private val server = MockWebServer()
    private val clock = Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun viewModel() = EffectivenessViewModel(InvestorApi.create(server.url("/"), OkHttpClient()), clock)

    private fun EffectivenessViewModel.awaitLoaded(): EffectivenessUiState =
        runBlocking { withTimeout(5_000) { state.first { !it.loading } } }

    @Test
    fun `response is mapped to table rows and items`() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(EFFECTIVENESS_JSON))

        val loaded = viewModel().awaitLoaded()

        assertNull(loaded.error)
        assertEquals(
            listOf(
                StatsGroup(
                    "Wyniki",
                    listOf(
                        StatsRow("50–59", 1, listOf(UNKNOWN, UNKNOWN, UNKNOWN)),
                        StatsRow("80+", 1, listOf(HorizonCell("+10,0%", "(1)"), UNKNOWN, UNKNOWN)),
                    ),
                ),
            ),
            loaded.scoreGroups,
        )
        assertEquals(
            listOf(
                StatsRow("Spadek w dniu raportu", 1, listOf(HorizonCell("−5,0%", "(1)"), UNKNOWN, UNKNOWN)),
                StatsRow("Płasko", 1, listOf(UNKNOWN, UNKNOWN, UNKNOWN)),
                StatsRow("Wzrost", 0, listOf(UNKNOWN, UNKNOWN, UNKNOWN)),
            ),
            loaded.reactionRows,
        )
        assertEquals(listOf(43L, 40L), loaded.items.map { it.id })
        assertEquals(-3.0, loaded.items.first().reactionPct!!, 0.0)
        assertNull(loaded.items.first().excess20Pct)
        assertNull(loaded.items.last().reactionPct)
    }

    @Test
    fun `failed request exposes an error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(EffectivenessViewModel.LOAD_ERROR, viewModel().awaitLoaded().error)
    }

    companion object {
        private val UNKNOWN = HorizonCell("—", "")

        val EFFECTIVENESS_JSON = """
            {
              "buckets":[
                {"eventType":"RESULTS","label":"50–59","alerts":1,"horizons":[{"sessions":5,"count":0,"avgExcessPct":null},{"sessions":20,"count":0,"avgExcessPct":null},{"sessions":60,"count":0,"avgExcessPct":null}]},
                {"eventType":"RESULTS","label":"80+","alerts":1,"horizons":[{"sessions":5,"count":1,"avgExcessPct":10.0},{"sessions":20,"count":0,"avgExcessPct":null},{"sessions":60,"count":0,"avgExcessPct":null}]}
              ],
              "byReaction":[
                {"reaction":"NEGATIVE","alerts":1,"horizons":[{"sessions":5,"count":1,"avgExcessPct":-5.0},{"sessions":20,"count":0,"avgExcessPct":null},{"sessions":60,"count":0,"avgExcessPct":null}]},
                {"reaction":"FLAT","alerts":1,"horizons":[{"sessions":5,"count":0,"avgExcessPct":null},{"sessions":20,"count":0,"avgExcessPct":null},{"sessions":60,"count":0,"avgExcessPct":null}]},
                {"reaction":"POSITIVE","alerts":0,"horizons":[{"sessions":5,"count":0,"avgExcessPct":null},{"sessions":20,"count":0,"avgExcessPct":null},{"sessions":60,"count":0,"avgExcessPct":null}]}
              ],
              "items":[
                {"id":43,"companyName":"Beta","market":"GPW","eventType":"RESULTS","publishedAt":"2026-10-21T12:00:00Z","score":65,"reactionPct":-3.0,"reactionIndexPct":1.0,"excess5Pct":-5.0,"excess20Pct":null,"excess60Pct":null},
                {"id":40,"companyName":"Gamma","market":"NC","eventType":"RESULTS","publishedAt":"2026-10-01T12:00:00Z","score":55,"reactionPct":null,"reactionIndexPct":null,"excess5Pct":null,"excess20Pct":null,"excess60Pct":null}
              ]
            }
        """.trimIndent()
    }
}
