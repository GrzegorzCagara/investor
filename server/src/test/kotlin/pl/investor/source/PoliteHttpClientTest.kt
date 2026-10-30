package pl.investor.source

import io.mockk.mockk
import io.mockk.verify
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.investor.monitoring.SourceHealthTracker
import java.nio.charset.Charset
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PoliteHttpClientTest {

    private val server = MockWebServer()
    private val tracker = mockk<SourceHealthTracker>(relaxed = true)
    private val client = PoliteHttpClient(Clock.systemUTC(), "me@example.com", tracker)

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private fun url(path: String = "/") = server.url(path).toString()

    @Test
    fun `second request to the same host waits at least one second`() {
        server.enqueue(MockResponse.Builder().body("a").build())
        server.enqueue(MockResponse.Builder().body("b").build())

        val started = System.nanoTime()
        client.get(Source.GPW_LISTING, url())
        client.get(Source.GPW_LISTING, url())

        assertTrue((System.nanoTime() - started) / 1_000_000 >= 1000)
    }

    @Test
    fun `sends the contact user agent and decodes utf-8`() {
        server.enqueue(MockResponse.Builder().body("zażółć gęślą jaźń").addHeader("Content-Type", "text/html; charset=utf-8").build())

        val body = client.get(Source.GPW_LISTING, url())

        assertEquals("zażółć gęślą jaźń", body)
        assertEquals("investor-personal/1.0 (me@example.com)", server.takeRequest().headers["User-Agent"])
        verify { tracker.recordSuccess(Source.GPW_LISTING, any()) }
    }

    @Test
    fun `postForm sends url encoded form`() {
        server.enqueue(MockResponse.Builder().body("ok").build())

        client.postForm(Source.NC_LISTING, url(), listOf("a" to "1", "b" to "x y"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("a=1&b=x+y", request.body?.utf8())
    }

    @Test
    fun `getBytes returns raw bytes`() {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(byteArrayOf(1, 2, 3, -1))).build())

        assertEquals(listOf<Byte>(1, 2, 3, -1), client.getBytes(Source.GPW_ARCHIVE, url()).toList())
    }

    @Test
    fun `getBytes sends the given headers`() {
        server.enqueue(MockResponse.Builder().body("x").build())

        client.getBytes(Source.BANKIER, url(), mapOf("Referer" to "https://www.bankier.pl/wiadomosc/a.html"))

        assertEquals("https://www.bankier.pl/wiadomosc/a.html", server.takeRequest().headers["Referer"])
    }

    @Test
    fun `5xx is retried twice then throws SourceUnavailableException and records failure`() {
        repeat(3) { server.enqueue(MockResponse.Builder().code(503).build()) }

        val error = assertFailsWith<SourceUnavailableException> { client.get(Source.BANKIER, url()) }

        assertEquals(Source.BANKIER, error.source)
        assertEquals(3, server.requestCount)
        verify(exactly = 1) { tracker.recordFailure(Source.BANKIER, any(), any()) }
        verify(exactly = 0) { tracker.recordSuccess(any(), any()) }
    }

    @Test
    fun `5xx followed by success returns body`() {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().body("fine").build())

        assertEquals("fine", client.get(Source.BIZNESRADAR, url()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `request to another host does not wait for the rate limit of a busy host`() {
        MockWebServer().use { other ->
            other.start()
            server.enqueue(MockResponse.Builder().body("a").build())
            server.enqueue(MockResponse.Builder().body("a").build())
            other.enqueue(MockResponse.Builder().body("b").build())
            client.get(Source.GPW_LISTING, url())
            val busy = thread { client.get(Source.GPW_LISTING, url()) }
            Thread.sleep(200)

            val started = System.nanoTime()
            client.get(Source.NC_LISTING, other.url("/").toString())

            assertTrue((System.nanoTime() - started) / 1_000_000 < 500)
            busy.join()
        }
    }

    @Test
    fun `timeout is retried then throws SourceUnavailableException`() {
        val shortTimeoutClient = PoliteHttpClient(Clock.systemUTC(), "me@example.com", tracker, Duration.ofMillis(200))
        repeat(3) { server.enqueue(MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).body("late").build()) }

        assertFailsWith<SourceUnavailableException> { shortTimeoutClient.get(Source.GPW_ARCHIVE, url()) }

        assertEquals(3, server.requestCount)
        verify(exactly = 1) { tracker.recordFailure(Source.GPW_ARCHIVE, any(), any()) }
    }

    @Test
    fun `follows redirects`() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", url("/target")).build())
        server.enqueue(MockResponse.Builder().body("target body").build())

        assertEquals("target body", client.get(Source.GPW_LISTING, url("/start")))
    }

    private val polishText = "Zysk netto akcjonariuszy jednostki dominującej, sierpień"

    @Test
    fun `decodes utf-8 body when content type has no charset`() {
        val body = okio.Buffer().write(polishText.toByteArray(Charsets.UTF_8))
        server.enqueue(MockResponse.Builder().body(body).addHeader("Content-Type", "text/html").build())

        assertEquals(polishText, client.get(Source.GPW_LISTING, url()))
    }

    @Test
    fun `decodes body using the declared charset`() {
        val body = okio.Buffer().write(polishText.toByteArray(Charset.forName("windows-1250")))
        server.enqueue(MockResponse.Builder().body(body).addHeader("Content-Type", "text/html; charset=windows-1250").build())

        assertEquals(polishText, client.get(Source.GPW_LISTING, url()))
    }

    @Test
    fun `falls back to utf-8 when declared charset is unsupported`() {
        val body = okio.Buffer().write(polishText.toByteArray(Charsets.UTF_8))
        server.enqueue(MockResponse.Builder().body(body).addHeader("Content-Type", "text/html; charset=bogus").build())

        assertEquals(polishText, client.get(Source.GPW_LISTING, url()))
        verify(exactly = 1) { tracker.recordSuccess(Source.GPW_LISTING, any()) }
    }
}
