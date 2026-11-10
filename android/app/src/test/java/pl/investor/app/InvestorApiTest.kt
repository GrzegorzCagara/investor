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

    @Test
    fun `server path prefix is kept in requests`() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "connection.preferences_pb") }
        val settings = ConnectionSettings(store)
        settings.save(server.url("/investor").toString(), "secret")
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"items":[]}"""))

        InvestorApi.create(settings).alerts()

        assertEquals("/investor/api/alerts?limit=100", server.takeRequest().path)
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
        assertEquals(EventType.RESULTS, detail.eventType)
        assertNull(detail.news)
        assertNull(detail.listing)
        assertNull(detail.ytdChangePct)
        assertNull(detail.ordersYoyPct)
        assertTrue(detail.pastReactions.isEmpty())
        assertTrue(detail.signals.isEmpty())
        assertTrue(detail.context.isEmpty())
        assertTrue(detail.headlines.isEmpty())
    }

    @Test
    fun `detail parses context points and headlines`() = runBlocking {
        enqueueJson(
            "{$DETAIL_BASE," +
                """"context":[{"direction":"PLUS","text":"Zysk powyżej oczekiwań."},{"direction":"MINUS","text":"Przychody zależą od cen ropy."}],""" +
                """"headlines":[{"title":"Unimot zaskoczył wynikami","url":"https://www.bankier.pl/wiadomosc/unimot-1.html","publishedAt":"2026-11-05T19:15:00Z"}]}""",
        )

        val detail = api().alert(42)

        assertEquals(
            listOf(
                ContextPointView(ContextDirection.PLUS, "Zysk powyżej oczekiwań."),
                ContextPointView(ContextDirection.MINUS, "Przychody zależą od cen ropy."),
            ),
            detail.context,
        )
        assertEquals(
            listOf(HeadlineView("Unimot zaskoczył wynikami", "https://www.bankier.pl/wiadomosc/unimot-1.html", Instant.parse("2026-11-05T19:15:00Z"))),
            detail.headlines,
        )
    }

    @Test
    fun `swedish detail parses listing, context, signals and sourced consensus`() = runBlocking {
        enqueueJson(
            """{"id":90,"companyName":"Mips","market":"SE","reportNumber":"Nasdaq","reportKind":"QUARTERLY",""" +
                """"publishedAt":"2026-10-23T05:30:00Z","score":74,"components":[{"label":"Zapowiedzi i zamówienia","points":10.0,"max":10.0}],""" +
                """"penalty":0.0,"warnings":[],"reason":"Zysk operacyjny powyżej konsensusu.","outlookQuote":null,""" +
                """"revenueHistory":[{"label":"Q3 2026","value":215000.0,"currency":"SEK"}],"sourceUrl":"https://example.test/se",""" +
                """"reaction":{"stockPct":5.2,"indexPct":0.3,"indexName":"OMXSPI","volumeMultiple":null,"category":"POSITIVE"},""" +
                """"consensus":[{"label":"Sprzedaż vs konsensus FactSet (6 analityków)","actual":215,"consensus":216,"differencePct":-0.46,"currency":"SEK"}],""" +
                """"companyHistory":[],"eventType":"RESULTS","news":null,"listing":"Large Cap Stockholm","ytdChangePct":-66.0,""" +
                """"ordersYoyPct":25.0,"pastReactions":[{"date":"2026-07-16","pct":27.8},{"date":"2026-04-24","pct":-3.1}],""" +
                """"signals":["Odwrócenie trendu","Regularne wzrosty po raportach"]}""",
        )

        val detail = api().alert(90)

        assertEquals(Market.SE, detail.market)
        assertEquals("Nasdaq", detail.reportNumber)
        assertEquals("Large Cap Stockholm", detail.listing)
        assertEquals(-66.0, detail.ytdChangePct!!, 0.0)
        assertEquals(25.0, detail.ordersYoyPct!!, 0.0)
        assertEquals(listOf(PastReactionView("2026-07-16", 27.8), PastReactionView("2026-04-24", -3.1)), detail.pastReactions)
        assertEquals(listOf("Odwrócenie trendu", "Regularne wzrosty po raportach"), detail.signals)
        assertEquals(
            listOf(ConsensusRowView("Sprzedaż vs konsensus FactSet (6 analityków)", 215.0, 216.0, -0.46, "SEK")),
            detail.consensus,
        )
        assertEquals("SEK", detail.revenueHistory.single().currency)
        assertEquals("OMXSPI", detail.reaction?.indexName)
    }

    @Test
    fun `detail with null context fields parses`() = runBlocking {
        enqueueJson(
            "{$DETAIL_BASE," +
                """"listing":null,"ytdChangePct":null,"ordersYoyPct":null,"pastReactions":[],"signals":[]}""",
        )

        val detail = api().alert(42)

        assertNull(detail.listing)
        assertNull(detail.ytdChangePct)
        assertNull(detail.ordersYoyPct)
        assertTrue(detail.pastReactions.isEmpty())
        assertTrue(detail.signals.isEmpty())
    }

    @Test
    fun `news detail parses the event type and the assessment`() = runBlocking {
        enqueueJson(
            """{"id":77,"companyName":"Mirbud","market":"GPW","reportNumber":"31/2026","reportKind":"CURRENT",""" +
                """"publishedAt":"2026-11-02T08:15:00Z","score":78,"components":[],"penalty":0.0,"warnings":[],""" +
                """"reason":"Umowa na 18,4 mln PLN to blisko 12% rocznych przychodów.","outlookQuote":null,"revenueHistory":[],""" +
                """"sourceUrl":"https://example.test/n","reaction":null,"consensus":[],"companyHistory":[],"eventType":"NEWS",""" +
                """"news":{"categoryLabel":"Umowa","direction":"pozytywny","contractValue":"18,4 mln PLN","revenueSharePct":11.8}}""",
        )

        val detail = api().alert(77)

        assertEquals(EventType.NEWS, detail.eventType)
        assertEquals(NewsView("Umowa", "pozytywny", "18,4 mln PLN", 11.8), detail.news)
        assertTrue(detail.components.isEmpty())
        assertTrue(detail.revenueHistory.isEmpty())
    }

    @Test
    fun `news detail without a contract value parses`() = runBlocking {
        enqueueJson(
            "{$DETAIL_BASE," +
                """"eventType":"NEWS","news":{"categoryLabel":"Notowanie na innej giełdzie","direction":"neutralny",""" +
                """"contractValue":null,"revenueSharePct":null}}""",
        )

        val detail = api().alert(42)

        assertEquals(NewsView("Notowanie na innej giełdzie", "neutralny", null, null), detail.news)
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
        assertNull(detail.consensus.single().currency)
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
        assertEquals(EventType.RESULTS, all.items.single().eventType)
        assertTrue(us.items.isEmpty())
        assertEquals("/api/ranking", server.takeRequest().path)
        assertEquals("/api/ranking?market=US", server.takeRequest().path)
    }

    @Test
    fun `alerts parse news items`() = runBlocking {
        enqueueJson(
            """{"items":[{"id":77,"companyName":"Mirbud","market":"GPW","eventType":"NEWS","publishedAt":"2026-11-02T08:15:00Z",""" +
                """"score":78,"belowThreshold":false,"summary":"Umowa na 18,4 mln PLN.","status":"SCORED"}]}""",
        )

        val item = api().alerts().items.single()

        assertEquals(EventType.NEWS, item.eventType)
        assertEquals(78, item.score)
    }

    @Test
    fun `alerts ignore the XTB flag the server still sends`() = runBlocking {
        enqueueJson(
            """{"items":[{"id":77,"companyName":"Mirbud","market":"GPW","eventType":"NEWS","publishedAt":"2026-11-02T08:15:00Z",""" +
                """"score":78,"belowThreshold":false,"summary":"Umowa na 18,4 mln PLN.","status":"SCORED","availableOnXtb":true}]}""",
        )

        assertEquals(77L, api().alerts().items.single().id)
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
        assertTrue(response.bySignal.isEmpty())
    }

    @Test
    fun `effectiveness parses signal buckets`() = runBlocking {
        enqueueJson(EffectivenessViewModelTest.SIGNALS_JSON)

        val response = api().effectiveness()

        assertEquals(Signal.entries.toList(), response.bySignal.map { it.signal })
        val reversal = response.bySignal.first()
        assertEquals("Odwrócenie trendu", reversal.label)
        assertEquals(2, reversal.alerts)
        assertEquals(1.0, reversal.horizons.first().avgExcessPct!!, 0.0)
        assertNull(reversal.horizons.last().avgExcessPct)
    }

    @Test
    fun `ranking can be filtered to Sweden`() = runBlocking {
        enqueueJson(
            """{"items":[{"id":90,"companyName":"Mips","market":"SE","eventType":"RESULTS","publishedAt":"2026-10-23T05:30:00Z",""" +
                """"score":74,"belowThreshold":false,"summary":"Zysk powyżej konsensusu.","status":"SCORED"}]}""",
        )

        val se = api().ranking(Market.SE)

        assertEquals(Market.SE, se.items.single().market)
        assertEquals("/api/ranking?market=SE", server.takeRequest().path)
    }

    @Test
    fun `ranking can be filtered to Germany`() = runBlocking {
        enqueueJson(
            """{"items":[{"id":95,"companyName":"Masterflex","market":"DE","eventType":"RESULTS","publishedAt":"2026-11-04T06:30:00Z",""" +
                """"score":71,"belowThreshold":false,"summary":"Przychody wzrosły o 3%.","status":"SCORED","availableOnXtb":true}]}""",
        )

        val de = api().ranking(Market.DE)

        assertEquals(Market.DE, de.items.single().market)
        assertEquals("/api/ranking?market=DE", server.takeRequest().path)
    }

    @Test
    fun `german detail parses EQS report number and EUR amounts`() = runBlocking {
        enqueueJson(
            """{"id":95,"companyName":"Masterflex","market":"DE","reportNumber":"EQS","reportKind":"QUARTERLY",""" +
                """"publishedAt":"2026-11-04T06:30:00Z","score":71,"components":[],"penalty":0.0,"warnings":[],""" +
                """"reason":"Przychody wzrosły.","outlookQuote":null,""" +
                """"revenueHistory":[{"label":"Q3 2026","value":82.2,"currency":"EUR"}],"sourceUrl":"https://example.test/de",""" +
                """"reaction":null,"consensus":[],"companyHistory":[],"availableOnXtb":true}""",
        )

        val detail = api().alert(95)

        assertEquals(Market.DE, detail.market)
        assertEquals("EQS", detail.reportNumber)
        assertEquals("EUR", detail.revenueHistory.single().currency)
    }

    @Test
    fun `ranking can be filtered to the Netherlands`() = runBlocking {
        enqueueJson(
            """{"items":[{"id":96,"companyName":"ASML Holding","market":"NL","eventType":"RESULTS","publishedAt":"2026-10-14T05:00:00Z",""" +
                """"score":74,"belowThreshold":false,"summary":"Przychody wzrosły o 55%.","status":"SCORED"}]}""",
        )

        val nl = api().ranking(Market.NL)

        assertEquals(Market.NL, nl.items.single().market)
        assertEquals("/api/ranking?market=NL", server.takeRequest().path)
    }

    @Test
    fun `ranking can be filtered to Finland`() = runBlocking {
        enqueueJson(
            """{"items":[{"id":97,"companyName":"Raisio","market":"FI","eventType":"RESULTS","publishedAt":"2026-11-10T05:30:00Z",""" +
                """"score":58,"belowThreshold":true,"summary":"Sprzedaż wzrosła o 2%.","status":"SCORED"}]}""",
        )

        val fi = api().ranking(Market.FI)

        assertEquals(Market.FI, fi.items.single().market)
        assertEquals("/api/ranking?market=FI", server.takeRequest().path)
    }

    @Test
    fun `alerts parse the not on XTB status`() = runBlocking {
        enqueueJson(
            """{"items":[{"id":96,"companyName":"Uniper","market":"DE","eventType":"NEWS","publishedAt":"2026-11-04T07:00:00Z",""" +
                """"score":null,"belowThreshold":false,"summary":"","status":"SKIPPED_NOT_ON_XTB"}]}""",
        )

        assertEquals(ReportStatus.SKIPPED_NOT_ON_XTB, api().alerts().items.single().status)
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
        assertEquals(mapOf("GPW" to 0.42, "US" to 0.0, "NEWS" to 0.1), status.aiSpentUsd)
        assertEquals(mapOf("GPW" to 1.90, "US" to 2.85, "NEWS" to 1.00), status.aiLimitUsd)
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
