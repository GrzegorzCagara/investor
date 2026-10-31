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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val server = MockWebServer()
    private val clock = Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC)
    private val putBodies = CopyOnWriteArrayList<String>()
    @Volatile
    private var putStatus = 200

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/settings" && request.method == "GET" -> json(SETTINGS_JSON)
                request.path == "/api/settings" && request.method == "PUT" -> {
                    val body = request.body.readUtf8()
                    putBodies += body
                    if (putStatus == 200) json(body) else MockResponse().setResponseCode(putStatus)
                }
                request.path == "/api/status" -> json(STATUS_JSON)
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

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun loadedViewModel(): SettingsViewModel {
        val viewModel = SettingsViewModel(InvestorApi.create(server.url("/"), OkHttpClient()), clock)
        viewModel.await { !it.loading && it.form != null && it.sources.isNotEmpty() }
        return viewModel
    }

    private fun SettingsViewModel.await(predicate: (SettingsUiState) -> Boolean): SettingsUiState =
        runBlocking { withTimeout(5_000) { state.first(predicate) } }

    @Test
    fun `loads settings, sources and AI usage`() {
        val state = loadedViewModel().state.value

        assertEquals(
            SettingsForm(
                threshold = "70",
                enabledMarkets = setOf(Market.GPW, Market.US),
                resultsEnabled = true,
                quietEnabled = false,
                quietFrom = "22:00",
                quietTo = "07:00",
            ),
            state.form,
        )
        assertEquals(
            listOf(
                SourceRow("Komunikaty GPW", "5 min temu", failing = false),
                SourceRow("Bankier – konsensus PAP", "3 godz. temu", failing = true),
            ),
            state.sources,
        )
        assertEquals(listOf(AiUsageRow("GPW/NC", "0,42 z 1,90 USD"), AiUsageRow("USA", "0,00 z 2,85 USD")), state.aiUsage)
        assertNull(state.statusError)
    }

    @Test
    fun `save sends the edited settings and confirms`() {
        val viewModel = loadedViewModel()
        viewModel.onThresholdChange("55")
        viewModel.onMarketChange(Market.NC, true)
        viewModel.onMarketChange(Market.US, false)
        viewModel.onResultsChange(false)
        viewModel.onQuietEnabledChange(true)
        viewModel.onQuietFromChange("23:00")
        viewModel.onQuietToChange("06:30")

        viewModel.save()
        val saved = viewModel.await { !it.saving && it.saveMessage != null }

        assertEquals(SettingsViewModel.SAVED, saved.saveMessage)
        assertEquals(
            SettingsView(55, listOf(Market.US), listOf(EventType.RESULTS), QuietHoursView(true, "23:00", "06:30")),
            InvestorApi.json.decodeFromString(SettingsView.serializer(), putBodies.single()),
        )
        assertTrue(putBodies.single().contains("\"disabledMarkets\":[\"US\"]"))
        assertEquals("55", saved.form?.threshold)
    }

    @Test
    fun `rejected save keeps the edited values and shows an error`() {
        putStatus = 400
        val viewModel = loadedViewModel()
        viewModel.onThresholdChange("60")
        viewModel.onQuietFromChange("21:15")

        viewModel.save()
        val failed = viewModel.await { !it.saving && it.saveError != null }

        assertEquals(SettingsViewModel.REJECTED, failed.saveError)
        assertNull(failed.saveMessage)
        assertEquals("60", failed.form?.threshold)
        assertEquals("21:15", failed.form?.quietFrom)
    }

    @Test
    fun `invalid threshold blocks saving`() {
        val viewModel = loadedViewModel()

        listOf("abc", "101", "-1", "").forEach { value ->
            viewModel.onThresholdChange(value)
            viewModel.save()

            val state = viewModel.state.value
            assertEquals(SettingsForm.THRESHOLD_ERROR, state.form?.thresholdError)
            assertEquals(SettingsViewModel.INVALID_FORM, state.saveError)
        }
        assertTrue(putBodies.isEmpty())
    }

    @Test
    fun `invalid quiet hour blocks saving`() {
        val viewModel = loadedViewModel()
        viewModel.onQuietToChange("25:00")

        viewModel.save()

        assertEquals(SettingsForm.TIME_ERROR, viewModel.state.value.form?.quietToError)
        assertEquals(SettingsViewModel.INVALID_FORM, viewModel.state.value.saveError)
        assertTrue(putBodies.isEmpty())
    }

    companion object {
        const val SETTINGS_JSON =
            """{"pushThreshold":70,"disabledMarkets":["NC"],"disabledEventTypes":[],"quietHours":{"enabled":false,"from":"22:00","to":"07:00"}}"""
        const val STATUS_JSON =
            """{"sources":[""" +
                """{"source":"GPW_LISTING","lastSuccessAt":"2026-10-31T09:55:00Z","lastFailureAt":null,"lastError":null,"outageNotified":false},""" +
                """{"source":"BANKIER_NEWS","lastSuccessAt":"2026-10-31T07:00:00Z","lastFailureAt":"2026-10-31T09:00:00Z","lastError":"timeout","outageNotified":false}""" +
                """],"aiSpentUsd":{"GPW":0.42,"US":0},"aiLimitUsd":{"GPW":1.90,"US":2.85},"medianLatencySeconds7d":null,"fcmConfigured":true}"""
    }
}
