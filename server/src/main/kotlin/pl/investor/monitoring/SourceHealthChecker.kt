package pl.investor.monitoring

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.notification.PushMessageFactory
import pl.investor.notification.PushNotifier
import pl.investor.report.Market
import pl.investor.source.Source
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class SourceHealthChecker(
    private val tracker: SourceHealthTracker,
    private val notifier: PushNotifier,
    private val factory: PushMessageFactory,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(SourceHealthChecker::class.java)

    @Scheduled(initialDelay = CHECK_INTERVAL_MS, fixedDelay = CHECK_INTERVAL_MS)
    fun check() {
        val now = clock.instant()
        tracker.snapshot()
            .filter { it.source in MONITORED.keys && it.isOutage(now) }
            .forEach { health ->
                val lastSuccessAt = checkNotNull(health.lastSuccessAt)
                if (tracker.markOutageNotifiedIfUnchanged(health.source, lastSuccessAt)) {
                    runCatching { notifier.send(factory.forSourceOutage(health.source, lastSuccessAt)) }
                        .onFailure { log.error("Sending outage push for {} failed", health.source, it) }
                }
            }
    }

    private fun SourceHealth.isOutage(now: Instant): Boolean =
        !outageNotified &&
            lastSuccessAt != null &&
            PublicationWindows.isOpen(now, MONITORED.getValue(source)) &&
            Duration.between(lastSuccessAt, now) >= OUTAGE_AFTER

    private companion object {
        const val CHECK_INTERVAL_MS = 300_000L
        val OUTAGE_AFTER: Duration = Duration.ofMinutes(30)
        val MONITORED = mapOf(Source.GPW_LISTING to Market.GPW, Source.NC_LISTING to Market.NC)
    }
}
