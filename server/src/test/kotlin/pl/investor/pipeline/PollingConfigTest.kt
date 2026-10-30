package pl.investor.pipeline

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.source.gpw.ListingPoller

class PollingConfigTest {

    private val poller = mockk<ListingPoller>()

    @Test
    fun `polls GPW and NC`() {
        every { poller.poll(any()) } returns 0

        ListingPolling(poller).pollAll()

        verify(exactly = 1) { poller.poll(Market.GPW) }
        verify(exactly = 1) { poller.poll(Market.NC) }
    }

    @Test
    fun `failing GPW poll does not block the NC poll`() {
        every { poller.poll(Market.GPW) } throws IllegalStateException("gpw down")
        every { poller.poll(Market.NC) } returns 0

        ListingPolling(poller).pollAll()

        verify(exactly = 1) { poller.poll(Market.NC) }
    }
}
