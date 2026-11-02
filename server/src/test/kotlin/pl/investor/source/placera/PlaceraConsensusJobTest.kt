package pl.investor.source.placera

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.source.Source
import pl.investor.source.SourceUnavailableException
import pl.investor.source.avanza.AvanzaClient
import pl.investor.source.avanza.AvanzaParser
import pl.investor.source.avanza.AvanzaSearchHit
import pl.investor.support.PostgresTestBase
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceraConsensusJobTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var previews: ConsensusPreviewRepository

    private val clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC)
    private val placera = mockk<PlaceraClient>()
    private val avanza = mockk<AvanzaClient>()
    private val job by lazy { PlaceraConsensusJob(placera, previews, avanza, clock) }

    private val mipsUrl = "https://www.placera.se/telegram/mips-mips-vantas-redovisa-58-miljoner-kronor-i-rorelseresultat-factset-20261019"
    private val brokenUrl = "https://www.placera.se/telegram/broken-vantas-redovisa-20261019"

    private fun fixture(path: String) = checkNotNull(javaClass.getResource("/fixtures/se/$path")).readText()

    @BeforeEach
    fun setUp() {
        jdbc.sql("TRUNCATE consensus_preview").update()
    }

    @Test
    fun `new previews are saved once and an unparseable one is skipped without blocking the others`() {
        every { placera.previewLinks() } returns listOf(brokenUrl, mipsUrl)
        every { placera.article(brokenUrl) } throws UnparseablePreviewException("No consensus table")
        every { placera.article(mipsUrl) } returns PlaceraParser.article(fixture("placera-mips-q3-2026.html"))

        job.refresh()
        job.refresh()

        val saved = assertNotNull(previews.forReport("SE0009216278", LocalDate.parse("2026-10-21"), Instant.parse("2026-10-21T05:30:00Z")))
        assertEquals("FactSet", saved.source)
        assertEquals(8, saved.pastReactions.size)
        assertEquals(clock.instant(), saved.fetchedAt)
        assertTrue(previews.exists(mipsUrl))
        verify(exactly = 1) { placera.article(mipsUrl) }
        verify(exactly = 1) { placera.article(brokenUrl) }
    }

    @Test
    fun `preview without an ISIN tag is resolved through Avanza`() {
        every { placera.previewLinks() } returns listOf(mipsUrl)
        every { placera.article(mipsUrl) } returns PlaceraParser.article(fixture("placera-mips-q3-2026.html")).copy(isin = null)
        every { avanza.findStock("Mips") } returns AvanzaSearchHit("740416", "Mips", "SE", "Stockholmsbörsen")
        every { avanza.stock("740416") } returns AvanzaParser.stock(fixture("avanza-stock-mips.json"))

        job.refresh()

        assertTrue(previews.exists(mipsUrl))
        assertNotNull(previews.latestBefore("SE0009216278", Instant.parse("2026-10-21T00:00:00Z")))
    }

    @Test
    fun `unavailable article is retried on the next run`() {
        every { placera.previewLinks() } returns listOf(mipsUrl)
        every { placera.article(mipsUrl) } throws SourceUnavailableException(Source.PLACERA, RuntimeException("503")) andThen
            PlaceraParser.article(fixture("placera-mips-q3-2026.html"))

        job.refresh()
        assertNull(previews.latestBefore("SE0009216278", Instant.parse("2026-10-21T00:00:00Z")))
        job.refresh()

        assertTrue(previews.exists(mipsUrl))
    }
}
