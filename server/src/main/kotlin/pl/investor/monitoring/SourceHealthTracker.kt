package pl.investor.monitoring

import org.springframework.stereotype.Component
import pl.investor.source.Source
import java.time.Instant

data class SourceHealth(
    val source: Source,
    val lastSuccessAt: Instant?,
    val lastFailureAt: Instant?,
    val lastError: String?,
    val outageNotified: Boolean,
)

@Component
class SourceHealthTracker(private val repository: SourceHealthRepository) {

    fun recordSuccess(source: Source, at: Instant) = repository.upsertSuccess(source, at)

    fun recordFailure(source: Source, at: Instant, error: String) = repository.upsertFailure(source, at, error)

    fun markOutageNotified(source: Source) = repository.markOutageNotified(source)

    fun markOutageNotifiedIfUnchanged(source: Source, lastSuccessAt: Instant): Boolean =
        repository.markOutageNotifiedIfUnchanged(source, lastSuccessAt)

    fun snapshot(): List<SourceHealth> = repository.findAll()
}
