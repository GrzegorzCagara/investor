package pl.investor.pipeline

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.investor.report.Market
import pl.investor.report.ReportRepository
import pl.investor.settings.SettingRepository
import pl.investor.source.eqs.EqsListingClient
import pl.investor.source.eqs.EqsPoller
import pl.investor.source.gpw.EspiListingClient
import pl.investor.source.gpw.ListingPoller
import pl.investor.source.nasdaq.NasdaqNordicListingClient
import pl.investor.source.nasdaq.NasdaqNordicPoller
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

    @Bean
    fun nasdaqNordicPoller(
        client: NasdaqNordicListingClient,
        reports: ReportRepository,
        clock: Clock,
        pipeline: ReportPipeline,
    ) = NasdaqNordicPoller(client, reports, clock, pipeline::process)

    @Bean
    fun eqsPoller(
        client: EqsListingClient,
        reports: ReportRepository,
        clock: Clock,
        pipeline: ReportPipeline,
    ) = EqsPoller(client, reports, clock, pipeline::process)
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
}

@Component
class NasdaqPolling(private val poller: NasdaqNordicPoller) {

    private val log = LoggerFactory.getLogger(NasdaqPolling::class.java)

    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    fun poll() {
        runCatching { poller.poll() }
            .onFailure { log.error("Polling {} listing failed", Market.SE, it) }
    }
}

@Component
class EqsPolling(private val poller: EqsPoller) {

    private val log = LoggerFactory.getLogger(EqsPolling::class.java)

    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    fun poll() {
        runCatching { poller.poll() }
            .onFailure { log.error("Polling {} listing failed", Market.DE, it) }
    }
}

private const val POLL_INTERVAL_MS = 60_000L
