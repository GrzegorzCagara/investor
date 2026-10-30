package pl.investor.source.quotes

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.history.DailyQuote
import pl.investor.history.QuoteRepository
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

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
    fun runToday() = run(LocalDate.now(clock.withZone(WARSAW)))

    @Scheduled(cron = "0 30 21 * * MON-FRI", zone = "Europe/Warsaw")
    fun retryToday() = runToday()

    fun run(date: LocalDate) {
        saveFrom("GPW archive", date) { gpwArchive.day(date) }
        saveFrom("NewConnect daily", date) { ncDaily.day(date) }
        saveFrom("indices", date) { INDICES.mapNotNull { indexDaily.day(it, date) } }
    }

    fun backfill(from: LocalDate, to: LocalDate) {
        generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .filterNot { it.dayOfWeek in WEEKEND }
            .forEach(::run)
    }

    private fun saveFrom(name: String, date: LocalDate, load: () -> List<DailyQuote>) {
        try {
            load().forEach(quotes::save)
        } catch (e: Exception) {
            log.error("Daily quotes from {} for {} failed", name, date, e)
        }
    }

    private companion object {
        val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
        val INDICES = listOf("WIG", "NCINDEX")
        val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }
}
