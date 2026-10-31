package pl.investor.api

import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.investor.outcome.OutcomeJob
import pl.investor.pipeline.MonthlySalesBackfillJob
import pl.investor.source.quotes.DailyQuotesJob
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminControllerTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC)
    private val monthlySales = mockk<MonthlySalesBackfillJob>()
    private val dailyQuotes = mockk<DailyQuotesJob>()
    private val outcomes = mockk<OutcomeJob>(relaxed = true)
    private val controller = AdminController(monthlySales, dailyQuotes, outcomes, clock)
    private val mvc = MockMvcBuilders.standaloneSetup(controller).build()

    private fun awaitAccepted() {
        repeat(100) {
            if (mvc.perform(post("/api/admin/backfill")).andReturn().response.status == 202) return
            Thread.sleep(50)
        }
        error("guard was never released")
    }

    @Test
    fun `backfill runs both jobs and answers 202`() {
        val quotesRange = mutableListOf<Pair<LocalDate, LocalDate>>()
        val done = CountDownLatch(1)
        every { monthlySales.runAll(13) } returns 0
        every { dailyQuotes.backfill(any(), any()) } answers {
            quotesRange += firstArg<LocalDate>() to secondArg<LocalDate>()
            done.countDown()
        }

        mvc.perform(post("/api/admin/backfill")).andExpect(status().isAccepted)

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(LocalDate.of(2026, 5, 18) to LocalDate.of(2026, 10, 30)), quotesRange)
    }

    @Test
    fun `second call while running gives 409`() {
        val release = CountDownLatch(1)
        every { monthlySales.runAll(13) } answers {
            release.await(5, TimeUnit.SECONDS)
            0
        }
        every { dailyQuotes.backfill(any(), any()) } returns Unit

        mvc.perform(post("/api/admin/backfill")).andExpect(status().isAccepted)
        mvc.perform(post("/api/admin/backfill")).andExpect(status().isConflict)

        release.countDown()
        awaitAccepted()
    }

    @Test
    fun `failing run still runs quotes and releases the guard`() {
        val quotesRan = CountDownLatch(1)
        every { monthlySales.runAll(13) } throws IllegalStateException("boom")
        every { dailyQuotes.backfill(any(), any()) } answers { quotesRan.countDown() }

        mvc.perform(post("/api/admin/backfill")).andExpect(status().isAccepted)

        assertTrue(quotesRan.await(5, TimeUnit.SECONDS))
        awaitAccepted()
    }

    @Test
    fun `backfill recomputes all outcomes after the quotes even when quotes fail`() {
        val refreshed = CountDownLatch(1)
        every { monthlySales.runAll(13) } returns 0
        every { dailyQuotes.backfill(any(), any()) } throws IllegalStateException("boom")
        every { outcomes.refresh(recomputeAll = true) } answers { refreshed.countDown() }

        mvc.perform(post("/api/admin/backfill")).andExpect(status().isAccepted)

        assertTrue(refreshed.await(5, TimeUnit.SECONDS))
        verifyOrder {
            dailyQuotes.backfill(any(), any())
            outcomes.refresh(recomputeAll = true)
        }
        awaitAccepted()
    }
}
