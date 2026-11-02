package pl.investor.pipeline

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.investor.report.Market
import pl.investor.source.gpw.ListingPoller
import pl.investor.source.nasdaq.NasdaqNordicPoller
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertTrue

class PollingConfigTest {

    private val poller = mockk<ListingPoller>()
    private val nasdaqPoller = mockk<NasdaqNordicPoller>()

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

    @Test
    fun `slow Nasdaq poll does not delay the GPW and NC polls`() {
        val nasdaqStarted = CountDownLatch(1)
        val releaseNasdaq = CountDownLatch(1)
        every { nasdaqPoller.poll() } answers {
            nasdaqStarted.countDown()
            releaseNasdaq.await(10, TimeUnit.SECONDS)
            0
        }
        every { poller.poll(any()) } returns 0
        val nasdaq = thread { NasdaqPolling(nasdaqPoller).poll() }
        assertTrue(nasdaqStarted.await(5, TimeUnit.SECONDS))

        ListingPolling(poller).pollAll()

        verify(exactly = 1) { poller.poll(Market.GPW) }
        verify(exactly = 1) { poller.poll(Market.NC) }
        releaseNasdaq.countDown()
        nasdaq.join()
    }

    @Test
    fun `failing Nasdaq poll is logged and swallowed`() {
        every { nasdaqPoller.poll() } throws IllegalStateException("nasdaq down")

        NasdaqPolling(nasdaqPoller).poll()

        verify(exactly = 1) { nasdaqPoller.poll() }
    }
}
