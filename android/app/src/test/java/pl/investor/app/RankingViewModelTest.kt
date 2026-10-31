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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class RankingViewModelTest {

    private val server = MockWebServer()
    private val clock = Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/ranking" -> json(item(42, "ASBIS", "GPW", 83), item(43, "Beta", "NC", 60))
                "/api/ranking?market=NC" -> json(item(43, "Beta", "NC", 60))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun item(id: Int, name: String, market: String, score: Int) =
        """{"id":$id,"companyName":"$name","market":"$market","eventType":"RESULTS","publishedAt":"2026-10-30T22:20:21Z",""" +
            """"score":$score,"belowThreshold":false,"summary":"Przychody wzrosły o 91%.","status":"SCORED"}"""

    private fun json(vararg items: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[${items.joinToString(",")}]}""")

    private fun RankingViewModel.await(predicate: (RankingUiState) -> Boolean): RankingUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    @Test
    fun `loads all markets first and reloads with the chosen market`() {
        val viewModel = RankingViewModel(InvestorApi.create(server.url("/"), OkHttpClient()), clock)

        val all = viewModel.await { !it.loading }
        assertEquals(listOf(42L, 43L), all.items.map { it.id })
        assertNull(all.market)

        viewModel.selectMarket(Market.NC)
        val nc = viewModel.await { !it.loading && it.market == Market.NC }

        assertEquals(listOf(43L), nc.items.map { it.id })
        assertNull(nc.error)
        assertEquals("/api/ranking", server.takeRequest().path)
        assertEquals("/api/ranking?market=NC", server.takeRequest().path)
    }

    @Test
    fun `failed request exposes an error`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }

        val loaded = RankingViewModel(InvestorApi.create(server.url("/"), OkHttpClient()), clock).await { !it.loading }

        assertEquals(RankingViewModel.LOAD_ERROR, loaded.error)
    }
}
