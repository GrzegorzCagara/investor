package pl.investor.pipeline

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.report.Market
import pl.investor.report.ReportRepository
import pl.investor.settings.SettingRepository
import pl.investor.source.gpw.EspiListingClient
import pl.investor.source.gpw.ListingPoller
import java.time.Clock

@Configuration
class PollingConfig {

    @Bean
    fun listingPoller(
        client: EspiListingClient,
        reports: ReportRepository,
        settings: SettingRepository,
        clock: Clock,
        pipeline: ReportPipeline,
    ) = ListingPoller(client, reports, settings, clock, pipeline::process)
}

@Component
class ListingPolling(private val poller: ListingPoller) {

    private val log = LoggerFactory.getLogger(ListingPolling::class.java)

    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    fun pollAll() {
        listOf(Market.GPW, Market.NC).forEach { market ->
            runCatching { poller.poll(market) }
                .onFailure { log.error("Polling {} listing failed", market, it) }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 60_000L
    }
}
