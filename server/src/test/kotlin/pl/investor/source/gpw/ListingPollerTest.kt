package pl.investor.source.gpw

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.report.ReportKind
import pl.investor.report.ReportRepository
import pl.investor.settings.SettingRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

class ListingPollerTest {

    private val client = mockk<EspiListingClient>()
    private val reports = mockk<ReportRepository>()
    private val settings = mockk<SettingRepository>(relaxed = true)
    private val received = mutableListOf<ListingEntry>()
    private val noon = Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC)
    private val halfPastMidnightWarsaw = Clock.fixed(Instant.parse("2026-09-20T22:30:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 9, 21)
    private val yesterday = LocalDate.of(2026, 9, 20)

    init {
        every { settings.find(any()) } returns null
    }

    private fun poller(clock: Clock) = ListingPoller(client, reports, settings, clock) { received += it }

    private fun entry(n: Int, minuteOffset: Long = n.toLong()) = ListingEntry(
        Market.GPW, "COMPANY $n", null, ReportKind.CURRENT, "$n/2026", "$n", "title $n",
        Instant.parse("2026-09-21T06:00:00Z").plusSeconds(minuteOffset * 60), "https://www.gpw.pl/komunikat?geru_id=$n",
    )

    private fun page(entries: List<ListingEntry>, rawItemCount: Int = entries.size) =
        ListingPage(entries, rawItemCount, emptyList())

    private fun knownNothing() = every { reports.existsBySourceRef(any(), any()) } returns false

    private fun knowEntry(known: ListingEntry) = every { reports.existsBySourceRef(known.market, known.sourceRef) } returns true

    @Test
    fun `poller pages until it meets a known report`() {
        val firstPage = (1..50).map { entry(it) }
        val known = entry(100)
        knownNothing()
        knowEntry(known)
        every { client.page(Market.GPW, today, 0) } returns page(firstPage)
        every { client.page(Market.GPW, today, 50) } returns page(listOf(entry(51), known, entry(52)))

        val count = poller(noon).poll(Market.GPW)

        assertEquals(52, count)
        assertEquals(52, received.size)
        assertEquals(false, received.contains(known))
        verify(exactly = 1) { client.page(Market.GPW, today, 0) }
        verify(exactly = 1) { client.page(Market.GPW, today, 50) }
        verify(exactly = 2) { client.page(any(), any(), any()) }
    }

    @Test
    fun `stops after a short page without asking for more`() {
        knownNothing()
        every { client.page(Market.GPW, today, 0) } returns page((1..3).map { entry(it) })

        assertEquals(3, poller(noon).poll(Market.GPW))

        verify(exactly = 1) { client.page(any(), any(), any()) }
    }

    @Test
    fun `between 00 00 and 01 00 poller also reads yesterday`() {
        knownNothing()
        every { client.page(Market.GPW, today, 0) } returns page(listOf(entry(1)))
        every { client.page(Market.GPW, yesterday, 0) } returns page(listOf(entry(2, minuteOffset = -10)))

        val count = poller(halfPastMidnightWarsaw).poll(Market.GPW)

        assertEquals(2, count)
        verify(exactly = 1) { client.page(Market.GPW, today, 0) }
        verify(exactly = 1) { client.page(Market.GPW, yesterday, 0) }
    }

    @Test
    fun `outside the midnight hour only today is read`() {
        knownNothing()
        every { client.page(any(), any(), any()) } returns page(emptyList())

        poller(noon).poll(Market.GPW)

        verify(exactly = 1) { client.page(any(), any(), any()) }
    }

    @Test
    fun `new entries go to the pipeline oldest first`() {
        knownNothing()
        every { client.page(Market.GPW, today, 0) } returns page(listOf(entry(3), entry(2), entry(1)))

        poller(noon).poll(Market.GPW)

        assertEquals(listOf("1/2026", "2/2026", "3/2026"), received.map { it.number })
    }

    @Test
    fun `known entries are not passed on`() {
        val known = entry(1)
        knownNothing()
        knowEntry(known)
        every { client.page(Market.GPW, today, 0) } returns page(listOf(entry(2), known))

        assertEquals(1, poller(noon).poll(Market.GPW))
        assertEquals(listOf("2/2026"), received.map { it.number })
    }

    @Test
    fun `periodic entries with the same company, kind and number but different source ref are both delivered`() {
        val first = entry(1).copy(kind = ReportKind.QUARTERLY, companyName = "SAME", number = "/2026", sourceRef = "100")
        val second = first.copy(sourceRef = "200", publishedAt = first.publishedAt.plusSeconds(60))
        knownNothing()
        every { client.page(Market.GPW, today, 0) } returns page(listOf(second, first))

        assertEquals(2, poller(noon).poll(Market.GPW))
        assertEquals(listOf("100", "200"), received.map { it.sourceRef })
    }

    @Test
    fun `a page of 50 raw items with one unparsed still triggers page 2`() {
        knownNothing()
        every { client.page(Market.GPW, today, 0) } returns ListingPage((1..49).map { entry(it) }, 50, listOf("broken"))
        every { client.page(Market.GPW, today, 50) } returns page(listOf(entry(60)))

        assertEquals(50, poller(noon).poll(Market.GPW))
        verify(exactly = 1) { client.page(Market.GPW, today, 50) }
    }

    @Test
    fun `a failing day is skipped and the other day is still delivered`() {
        knownNothing()
        every { client.page(Market.GPW, yesterday, 0) } throws IllegalStateException("boom")
        every { client.page(Market.GPW, today, 0) } returns page(listOf(entry(1)))

        assertEquals(1, poller(halfPastMidnightWarsaw).poll(Market.GPW))
        assertEquals(listOf("1/2026"), received.map { it.number })
        verify(exactly = 0) { settings.put(any(), any()) }
    }

    @Test
    fun `a day failing on its second page delivers nothing from that day`() {
        knownNothing()
        every { client.page(Market.GPW, today, 0) } returns page((1..50).map { entry(it) })
        every { client.page(Market.GPW, today, 50) } throws IllegalStateException("boom")

        assertEquals(0, poller(noon).poll(Market.GPW))
        assertEquals(0, received.size)
    }

    @Test
    fun `last polled two days ago requests three dates and stores today`() {
        knownNothing()
        every { settings.find("LAST_POLLED_DATE_GPW") } returns "2026-09-19"
        every { client.page(any(), any(), any()) } returns page(emptyList())

        poller(noon).poll(Market.GPW)

        listOf(19, 20, 21).forEach { verify(exactly = 1) { client.page(Market.GPW, LocalDate.of(2026, 9, it), 0) } }
        verify(exactly = 3) { client.page(any(), any(), any()) }
        verify { settings.put("LAST_POLLED_DATE_GPW", "2026-09-21") }
    }

    @Test
    fun `catch up is capped at seven days back`() {
        knownNothing()
        every { settings.find("LAST_POLLED_DATE_NC") } returns "2026-08-01"
        every { client.page(any(), any(), any()) } returns page(emptyList())

        poller(noon).poll(Market.NC)

        verify(exactly = 8) { client.page(Market.NC, any(), 0) }
        verify(exactly = 1) { client.page(Market.NC, LocalDate.of(2026, 9, 14), 0) }
        verify(exactly = 0) { client.page(Market.NC, LocalDate.of(2026, 9, 13), 0) }
    }

    @Test
    fun `checkpoint does not move past a failed day`() {
        every { settings.find("LAST_POLLED_DATE_GPW") } returns "2026-09-20"
        knownNothing()
        every { client.page(Market.GPW, yesterday, 0) } throws IllegalStateException("boom")
        every { client.page(Market.GPW, today, 0) } returns page(emptyList())

        poller(noon).poll(Market.GPW)

        verify(exactly = 0) { settings.put(any(), any()) }
    }
}
