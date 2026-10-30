package pl.investor.source

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import pl.investor.monitoring.SourceHealthTracker
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.Charset
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class PoliteHttpClient(
    private val clock: Clock,
    @Value("\${investor.contact-email}") contactEmail: String,
    private val tracker: SourceHealthTracker,
    timeout: Duration = DEFAULT_TIMEOUT,
) {

    private val restClient: RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(timeout).build()).apply {
                setReadTimeout(timeout)
            }
        )
        .defaultHeader("User-Agent", "investor-personal/1.0 ($contactEmail)")
        .build()

    private val lastRequestByHost = ConcurrentHashMap<String, Instant>()
    private val locksByHost = ConcurrentHashMap<String, ReentrantLock>()

    fun get(source: Source, url: String): String = execute(source, url) {
        restClient.get().uri(url).fetchText()
    }

    fun probe(source: Source, url: String): String? {
        waitForTurn(URI(url).hostWithPort())
        val body = try {
            restClient.get().uri(url).fetchText()
        } catch (e: Exception) {
            return null
        }
        tracker.recordSuccess(source, clock.instant())
        return body
    }

    fun reportFailure(source: Source, message: String) {
        tracker.recordFailure(source, clock.instant(), message)
    }

    fun postForm(source: Source, url: String, form: List<Pair<String, String>>): String = execute(source, url) {
        val body = LinkedMultiValueMap<String, String>().apply { form.forEach { (key, value) -> add(key, value) } }
        restClient.post().uri(url).body(body).fetchText()
    }

    fun getBytes(source: Source, url: String, headers: Map<String, String> = emptyMap()): ByteArray = execute(source, url) {
        fetchBytes(url, headers)
    }

    private fun RestClient.RequestHeadersSpec<*>.fetchText(): String = exchange { _, response ->
        when {
            response.statusCode.is5xxServerError ->
                throw HttpServerErrorException(response.statusCode, response.statusText)
            response.statusCode.isError ->
                throw HttpClientErrorException(response.statusCode, response.statusText)
        }
        response.body.readBytes().toString(declaredCharset(response.headers.getFirst("Content-Type")))
    }

    private fun declaredCharset(contentType: String?): Charset =
        contentType
            ?.let { CHARSET_PARAMETER.find(it)?.groupValues?.get(1) }
            ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
            ?: Charsets.UTF_8

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray =
        restClient.get().uri(url).headers { target -> headers.forEach(target::set) }.retrieve().body<ByteArray>() ?: ByteArray(0)

    private fun <T> execute(source: Source, url: String, request: () -> T): T {
        val result = fetchWithRetries(source, url, request)
        tracker.recordSuccess(source, clock.instant())
        return result
    }

    private fun <T> fetchWithRetries(source: Source, url: String, request: () -> T): T {
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) {
            waitForTurn(URI(url).hostWithPort())
            try {
                return request()
            } catch (e: Exception) {
                if (!e.isRetryable()) fail(source, e)
                lastError = e
            }
        }
        fail(source, checkNotNull(lastError))
    }

    private fun fail(source: Source, error: Exception): Nothing {
        tracker.recordFailure(source, clock.instant(), error.message ?: error.javaClass.simpleName)
        throw SourceUnavailableException(source, error)
    }

    private fun Exception.isRetryable() = this is HttpServerErrorException || this is ResourceAccessException

    private fun URI.hostWithPort() = "$host:$port"

    private fun waitForTurn(hostKey: String) {
        locksByHost.computeIfAbsent(hostKey) { ReentrantLock() }.withLock {
            lastRequestByHost[hostKey]?.let { last ->
                val wait = Duration.between(clock.instant(), last.plus(MIN_INTERVAL))
                if (wait.isPositive) Thread.sleep(wait)
            }
            lastRequestByHost[hostKey] = clock.instant()
        }
    }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)
        val MIN_INTERVAL: Duration = Duration.ofSeconds(1)
        const val MAX_ATTEMPTS = 3
        val CHARSET_PARAMETER = Regex("""charset\s*=\s*"?([^";\s]+)""", RegexOption.IGNORE_CASE)
    }
}
