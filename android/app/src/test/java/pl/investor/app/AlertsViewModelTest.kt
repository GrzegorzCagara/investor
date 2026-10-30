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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class AlertsViewModelTest {

    private val server = MockWebServer()
    private val clock = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC)

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

    private fun viewModel() = AlertsViewModel(InvestorApi.create(server.url("/"), OkHttpClient()), clock)

    private fun item(id: Int, publishedAt: String, score: String, status: String = "SCORED") =
        """{"id":$id,"companyName":"Spolka$id","market":"GPW","eventType":"RESULTS","publishedAt":"$publishedAt",""" +
            """"score":$score,"belowThreshold":false,"summary":"Podsumowanie $id","status":"$status"}"""

    private fun enqueue(vararg items: String) {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[${items.joinToString(",")}]}"""),
        )
    }

    private fun AlertsViewModel.awaitLoaded(): AlertsUiState =
        runBlocking { withTimeout(5_000) { state.first { !it.loading } } }

    @Test
    fun `view model groups alerts into Dziś, Wczoraj and dates`() {
        enqueue(
            item(1, "2026-09-21T06:09:00Z", "84"),
            item(2, "2026-09-20T20:14:00Z", "77"),
            item(3, "2026-09-20T15:50:00Z", "35"),
            item(4, "2026-09-18T06:09:00Z", "null", "PENDING_AI"),
        )

        val loaded = viewModel().awaitLoaded()

        assertEquals(listOf("Dziś", "Wczoraj", "18.09"), loaded.sections.map { it.title })
        assertEquals(listOf(listOf(1L), listOf(2L, 3L), listOf(4L)), loaded.sections.map { section -> section.items.map { it.id } })
        assertNull(loaded.sections.last().items.single().score)
        assertNull(loaded.error)
    }

    @Test
    fun `failed request exposes an error`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val loaded = viewModel().awaitLoaded()

        assertEquals(AlertsViewModel.LOAD_ERROR, loaded.error)
        assertNotNull(loaded.error)
    }
}
