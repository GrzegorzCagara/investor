package pl.investor.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class InvestorApiTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    @Test
    fun `requests go to the configured server with the token header`() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "connection.preferences_pb") }
        val settings = ConnectionSettings(store)
        settings.save(server.url("/").toString(), " secret ")
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[]}"""))
        server.enqueue(MockResponse().setResponseCode(204))

        val api = InvestorApi.create(settings)
        api.alerts()
        api.registerDevice(DeviceRequest("fcm-token"))

        val alertsRequest = server.takeRequest()
        assertEquals("/api/alerts?limit=100", alertsRequest.path)
        assertEquals("secret", alertsRequest.getHeader(InvestorApi.TOKEN_HEADER))
        val deviceRequest = server.takeRequest()
        assertEquals("POST", deviceRequest.method)
        assertEquals("""{"token":"fcm-token"}""", deviceRequest.body.readUtf8())
    }

    private fun api() = InvestorApi.create(server.url("/"), OkHttpClient())

    private fun enqueueJson(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    }

    @Test
    fun `detail from an older server parses without the new fields`() = runBlocking {
        enqueueJson("{$DETAIL_BASE}")

        val detail = api().alert(42)

        assertNull(detail.reaction)
        assertTrue(detail.consensus.isEmpty())
        assertTrue(detail.companyHistory.isEmpty())
        assertNull(detail.outlookQuote)
    }

    @Test
    fun `detail with explicit nulls and empty lists parses`() = runBlocking {
        enqueueJson(
            """{"id":42,"companyName":"ASBIS","market":"GPW","reportNumber":"12/2026","reportKind":"HALF_YEARLY",""" +
                """"publishedAt":"2026-08-05T22:20:21Z","score":null,"components":[],"penalty":0.0,"warnings":[],""" +
                """"reason":"Brak danych.","outlookQuote":null,"revenueHistory":[],"sourceUrl":"https://example.test/r",""" +
                """"reaction":null,"consensus":[],"companyHistory":[]}""",
        )

        val detail = api().alert(42)

        assertNull(detail.score)
        assertNull(detail.outlookQuote)
        assertNull(detail.reaction)
        assertTrue(detail.consensus.isEmpty())
        assertTrue(detail.companyHistory.isEmpty())
    }

    @Test
    fun `detail parses reaction, consensus and company history`() = runBlocking {
        enqueueJson(
            "{$DETAIL_BASE," +
                """"reaction":{"stockPct":0.4,"indexPct":1.0,"indexName":"WIG","volumeMultiple":2.5,"category":"FLAT"},""" +
                """"consensus":[{"label":"Przychody","actual":1696.7,"consensus":1686.3,"differencePct":0.6}],""" +
                """"companyHistory":[{"id":40,"companyName":"ASBIS","market":"GPW","eventType":"RESULTS",""" +
                """"publishedAt":"2026-05-14T15:30:00Z","score":82,"reactionPct":null,"reactionIndexPct":null,""" +
                """"excess5Pct":10.0,"excess20Pct":null,"excess60Pct":null}]}""",
        )

        val detail = api().alert(42)

        assertEquals(ReactionView(0.4, 1.0, "WIG", 2.5, Reaction.FLAT), detail.reaction)
        assertEquals(listOf(ConsensusRowView("Przychody", 1696.7, 1686.3, 0.6)), detail.consensus)
        assertEquals(
            OutcomeItem(40, "ASBIS", Market.GPW, EventType.RESULTS, Instant.parse("2026-05-14T15:30:00Z"), 82, excess5Pct = 10.0),
            detail.companyHistory.single(),
        )
    }

    @Test
    fun `ranking sends the market and parses items`() = runBlocking {
        enqueueJson(
            """{"items":[{"id":42,"companyName":"ASBIS","market":"GPW","eventType":"RESULTS","publishedAt":"2026-08-05T22:20:21Z",""" +
                """"score":83,"belowThreshold":false,"summary":"Przychody wzrosły o 91%.","status":"SCORED"}]}""",
        )
        enqueueJson("""{"items":[]}""")

        val all = api().ranking()
        val us = api().ranking(Market.US)

        assertEquals(83, all.items.single().score)
        assertTrue(us.items.isEmpty())
        assertEquals("/api/ranking", server.takeRequest().path)
        assertEquals("/api/ranking?market=US", server.takeRequest().path)
    }

    @Test
    fun `effectiveness parses buckets, reactions and items`() = runBlocking {
        enqueueJson(EffectivenessViewModelTest.EFFECTIVENESS_JSON)

        val response = api().effectiveness()

        assertEquals(listOf("50–59", "80+"), response.buckets.map { it.label })
        assertNull(response.buckets.first().horizons.first().avgExcessPct)
        assertEquals(10.0, response.buckets.last().horizons.first().avgExcessPct!!, 0.0)
        assertEquals(listOf(Reaction.NEGATIVE, Reaction.FLAT, Reaction.POSITIVE), response.byReaction.map { it.reaction })
        assertNull(response.items.first().excess60Pct)
        assertNull(response.items.last().reactionPct)
    }

    @Test
    fun `settings are read and written with the same shape`() = runBlocking {
        enqueueJson(SettingsViewModelTest.SETTINGS_JSON)
        enqueueJson(SettingsViewModelTest.SETTINGS_JSON)

        val settings = api().settings()
        api().updateSettings(settings)

        assertEquals(SettingsView(70, listOf(Market.NC), emptyList(), QuietHoursView(false, "22:00", "07:00")), settings)
        server.takeRequest()
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/settings", put.path)
        assertEquals(SettingsViewModelTest.SETTINGS_JSON, put.body.readUtf8())
    }

    @Test
    fun `status keeps unknown sources and pool maps`() = runBlocking {
        enqueueJson(SettingsViewModelTest.STATUS_JSON)

        val status = api().status()

        assertEquals(listOf("GPW_LISTING", "BANKIER_NEWS"), status.sources.map { it.source })
        assertNull(status.sources.first().lastFailureAt)
        assertEquals(Instant.parse("2026-10-31T09:00:00Z"), status.sources.last().lastFailureAt)
        assertEquals(mapOf("GPW" to 0.42, "US" to 0.0), status.aiSpentUsd)
        assertEquals(mapOf("GPW" to 1.90, "US" to 2.85), status.aiLimitUsd)
        assertNull(status.medianLatencySeconds7d)
    }

    companion object {
        private const val DETAIL_BASE =
            """"id":42,"companyName":"ASBIS","market":"GPW","reportNumber":"12/2026","reportKind":"HALF_YEARLY",""" +
                """"publishedAt":"2026-08-05T22:20:21Z","score":83,"sourceUrl":"https://example.test/r",""" +
                """"components":[{"label":"Dynamika","points":26.0,"max":30.0}],"penalty":0.0,"warnings":[],""" +
                """"reason":"Przychody wzrosły o 91%.","revenueHistory":[]"""
    }
}
