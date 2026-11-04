package pl.investor.source.xtb

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class XtbAvailabilityJob(private val availability: XtbAvailability) {

    @Scheduled(initialDelay = FIRST_RUN_DELAY_MS, fixedDelay = MATCH_INTERVAL_MS)
    fun refresh() = availability.refreshAll()

    private companion object {
        const val FIRST_RUN_DELAY_MS = 180_000L
        const val MATCH_INTERVAL_MS = 3_600_000L
    }
}
