package pl.investor.api

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.investor.outcome.OutcomeJob
import pl.investor.pipeline.MonthlySalesBackfillJob
import pl.investor.source.quotes.DailyQuotesJob
import pl.investor.support.toWarsawDate
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@RestController
@RequestMapping("/api/admin")
class AdminController(
    private val monthlySales: MonthlySalesBackfillJob,
    private val dailyQuotes: DailyQuotesJob,
    private val outcomes: OutcomeJob,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(AdminController::class.java)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "admin-backfill").apply { isDaemon = true } }

    @PostMapping("/backfill")
    fun backfill(): ResponseEntity<Void> = launch("Backfill") {
        runCatching { monthlySales.runAll(MONTHS) }.onFailure { log.error("Monthly sales backfill failed", it) }
        val today = clock.instant().toWarsawDate()
        runCatching { dailyQuotes.backfill(today.minusDays(QUOTE_DAYS), today) }.onFailure { log.error("Quotes backfill failed", it) }
        runCatching { outcomes.refresh(recomputeAll = true) }.onFailure { log.error("Outcomes refresh failed", it) }
    }

    @PostMapping("/backfill-quote-history")
    fun backfillQuoteHistory(): ResponseEntity<Void> = launch("Quote history backfill") {
        val today = clock.instant().toWarsawDate()
        runCatching { dailyQuotes.backfillMissing(today.minusDays(QUOTE_HISTORY_DAYS), today) }.onFailure { log.error("Quote history backfill failed", it) }
    }

    private fun launch(name: String, task: () -> Unit): ResponseEntity<Void> {
        if (!running.compareAndSet(false, true)) return ResponseEntity.status(HttpStatus.CONFLICT).build()
        executor.execute {
            val started = clock.instant()
            log.info("{} started", name)
            try {
                task()
            } finally {
                running.set(false)
                log.info("{} finished in {} min", name, Duration.between(started, clock.instant()).toMinutes())
            }
        }
        return ResponseEntity.accepted().build()
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    private companion object {
        const val MONTHS = 13
        const val QUOTE_DAYS = 165L
        const val QUOTE_HISTORY_DAYS = 915L
    }
}
