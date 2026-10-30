package pl.investor.notification

import com.google.firebase.messaging.BatchResponse
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.SendResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import pl.investor.support.PostgresTestBase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PushNotifierTest : PostgresTestBase() {

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var devices: DeviceRepository
    @Autowired lateinit var pushLog: PushLogRepository

    private val clock = Clock.fixed(Instant.parse("2026-09-21T06:10:00Z"), ZoneOffset.UTC)
    private val message = PushMessage("ASBIS (GPW) · WYNIKI · ocena 83", "Przychody +91% r/r.", mapOf("alertId" to "1"), PushKind.AI_BUDGET, null)

    @BeforeEach
    fun clean() {
        jdbc.sql("DELETE FROM device").update()
        jdbc.sql("DELETE FROM push_log").update()
    }

    private fun response(error: MessagingErrorCode?): SendResponse = mockk {
        every { exception } returns error?.let { code -> mockk<FirebaseMessagingException> { every { messagingErrorCode } returns code } }
    }

    private fun batch(vararg responses: SendResponse): BatchResponse = mockk { every { this@mockk.responses } returns responses.toList() }

    private fun pushLogCount() = jdbc.sql("SELECT count(*) FROM push_log").query(Long::class.java).single()

    @Test
    fun `invalid FCM token is removed`() {
        devices.register("good", Instant.parse("2026-09-01T00:00:00Z"))
        devices.register("stale", Instant.parse("2026-09-02T00:00:00Z"))
        val firebase = mockk<FirebaseMessaging> {
            every { sendEach(any<List<Message>>()) } returns batch(response(null), response(MessagingErrorCode.UNREGISTERED))
        }

        PushNotifier(firebase, devices, pushLog, clock).send(message)

        assertEquals(listOf("good"), devices.tokens())
        assertEquals(1L, pushLogCount())
    }

    @Test
    fun `other FCM errors keep the token`() {
        devices.register("flaky", Instant.parse("2026-09-01T00:00:00Z"))
        val firebase = mockk<FirebaseMessaging> {
            every { sendEach(any<List<Message>>()) } returns batch(response(MessagingErrorCode.UNAVAILABLE))
        }

        assertFailsWith<PushDeliveryException> { PushNotifier(firebase, devices, pushLog, clock).send(message) }

        assertEquals(listOf("flaky"), devices.tokens())
        assertEquals(0L, pushLogCount())
    }

    @Test
    fun `push fails and is not logged when every token is unregistered`() {
        devices.register("stale", Instant.parse("2026-09-01T00:00:00Z"))
        val firebase = mockk<FirebaseMessaging> {
            every { sendEach(any<List<Message>>()) } returns batch(response(MessagingErrorCode.UNREGISTERED))
        }

        assertFailsWith<PushDeliveryException> { PushNotifier(firebase, devices, pushLog, clock).send(message) }

        assertEquals(emptyList(), devices.tokens())
        assertEquals(0L, pushLogCount())
    }

    @Test
    fun `push is not logged when FCM throws`() {
        devices.register("a", Instant.parse("2026-09-01T00:00:00Z"))
        val firebase = mockk<FirebaseMessaging> {
            every { sendEach(any<List<Message>>()) } throws IllegalStateException("fcm down")
        }

        assertFailsWith<IllegalStateException> { PushNotifier(firebase, devices, pushLog, clock).send(message) }

        assertEquals(0L, pushLogCount())
    }

    @Test
    fun `duplicate results push for the same company and period is swallowed`() {
        jdbc.sql("TRUNCATE company RESTART IDENTITY CASCADE").update()
        val companyId = jdbc.sql("INSERT INTO company (name, market) VALUES ('X', 'GPW') RETURNING id").query(Long::class.java).single()
        val results = message.copy(
            kind = PushKind.RESULTS, companyId = companyId,
            periodKind = pl.investor.history.PeriodKind.MONTH, periodEnd = java.time.LocalDate.parse("2026-08-31"),
        )
        devices.register("a", Instant.parse("2026-09-01T00:00:00Z"))
        val firebase = mockk<FirebaseMessaging> {
            every { sendEach(any<List<Message>>()) } returns batch(response(null))
        }
        val notifier = PushNotifier(firebase, devices, pushLog, clock)

        notifier.send(results)
        notifier.send(results)

        assertEquals(1L, pushLogCount())
    }

    @Test
    fun `push goes to every registered device and is logged with its kind`() {
        devices.register("a", Instant.parse("2026-09-01T00:00:00Z"))
        devices.register("b", Instant.parse("2026-09-02T00:00:00Z"))
        val firebase = mockk<FirebaseMessaging> {
            every { sendEach(any<List<Message>>()) } returns batch(response(null), response(null))
        }

        PushNotifier(firebase, devices, pushLog, clock).send(message)

        verify { firebase.sendEach(match<List<Message>> { it.size == 2 }) }
        assertEquals("AI_BUDGET", jdbc.sql("SELECT kind FROM push_log").query(String::class.java).single())
    }

    @Test
    fun `without Firebase the push fails and is not logged`() {
        devices.register("a", Instant.parse("2026-09-01T00:00:00Z"))

        assertFailsWith<PushDeliveryException> { PushNotifier(null, devices, pushLog, clock).send(message) }

        assertEquals(0L, pushLogCount())
        assertEquals(listOf("a"), devices.tokens())
    }

    @Test
    fun `without a registered device the push fails and is not logged`() {
        val firebase = mockk<FirebaseMessaging>()

        assertFailsWith<PushDeliveryException> { PushNotifier(firebase, devices, pushLog, clock).send(message) }

        assertEquals(0L, pushLogCount())
        verify(exactly = 0) { firebase.sendEach(any<List<Message>>()) }
    }
}
