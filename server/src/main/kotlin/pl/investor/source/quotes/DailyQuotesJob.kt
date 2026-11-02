package pl.investor.source.quotes

import pl.investor.support.WARSAW
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.history.QuoteRepository
import pl.investor.history.SESSION_CALENDAR
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate

@Component
class DailyQuotesJob(
    private val gpwArchive: GpwArchiveClient,
    private val ncDaily: NcDailyClient,
    private val indexDaily: IndexDailyClient,
    private val quotes: QuoteRepository,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(DailyQuotesJob::class.java)

    @Scheduled(cron = "0 30 18 * * MON-FRI", zone = "Europe/Warsaw")
    fun runToday() = run(today())

    @Scheduled(cron = "0 30 21 * * MON-FRI", zone = "Europe/Warsaw")
    fun retryToday() {
        val today = today()
        run(today)
        missingSessionsBefore(today).forEach(::run)
    }

    fun run(date: LocalDate) {
        saveFrom("GPW archive", date) { gpwArchive.day(date) }
        saveFrom("NewConnect daily", date) { ncDaily.day(date) }
        saveFrom("indices", date) { INDICES.mapNotNull { indexDaily.day(it, date) } }
    }

    fun backfill(from: LocalDate, to: LocalDate) {
        val days = weekdays(from, to).toList()
        log.info("Quotes backfill of {} weekdays from {} to {} started", days.size, from, to)
        days.forEach(::run)
        log.info("Quotes backfill finished, latest session {}", quotes.latestDate(SESSION_CALENDAR))
    }

    fun backfillMissing(from: LocalDate, to: LocalDate) {
        val recorded = quotes.between(SESSION_CALENDAR, from, to).map { it.date }.toSet()
        val days = weekdays(from, to).filterNot { it in recorded }.toList()
        log.info("Quotes history backfill of {} missing weekdays from {} to {} started", days.size, from, to)
        days.forEachIndexed { index, day ->
            run(day)
            if ((index + 1) % PROGRESS_EVERY == 0) log.info("Quotes history backfill: {} of {} weekdays done", index + 1, days.size)
        }
        log.info("Quotes history backfill finished")
    }

    private fun today(): LocalDate = LocalDate.now(clock.withZone(WARSAW))

    private fun missingSessionsBefore(today: LocalDate): List<LocalDate> {
        val from = today.minusDays(GAP_WINDOW_DAYS)
        val recorded = quotes.between(SESSION_CALENDAR, from, today).map { it.date }.toSet()
        return weekdays(from, today.minusDays(1)).filterNot { it in recorded }.toList()
    }

    private fun weekdays(from: LocalDate, to: LocalDate): Sequence<LocalDate> =
        generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .filterNot { it.dayOfWeek in WEEKEND }

    private fun saveFrom(name: String, date: LocalDate, load: () -> List<DailyQuote>) {
        try {
            load().forEach(quotes::save)
        } catch (e: Exception) {
            log.error("Daily quotes from {} for {} failed", name, date, e)
        }
    }

    private companion object {
        val INDICES = listOf("WIG", "NCINDEX")
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        const val GAP_WINDOW_DAYS = 14L
        const val PROGRESS_EVERY = 50
    }
}
