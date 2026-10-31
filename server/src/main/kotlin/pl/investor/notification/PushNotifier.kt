package pl.investor.notification

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Component
import pl.investor.settings.QuietHours
import java.time.Clock

@Component
class PushNotifier(
    private val firebase: FirebaseMessaging?,
    private val devices: DeviceRepository,
    private val pushLog: PushLogRepository,
    private val quietHours: QuietHours,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun send(message: PushMessage) {
        deliver(message)
        record(message)
    }

    private fun deliver(message: PushMessage) {
        if (firebase == null) throw PushDeliveryException(message.title, "Firebase is not configured")
        val tokens = devices.tokens()
        if (tokens.isEmpty()) throw PushDeliveryException(message.title, "no device is registered")
        val channel = currentChannel()
        val results = firebase.sendEach(tokens.map { message.toFirebase(it, channel) }).responses.zip(tokens)
        results.forEach { (result, token) -> handle(result.exception?.messagingErrorCode, token) }
        val failures = results.mapNotNull { (result, _) -> result.exception }
        if (failures.size == results.size) throw PushDeliveryException(message.title, "no device accepted it")
    }

    private fun record(message: PushMessage) {
        try {
            pushLog.save(message, clock.instant())
        } catch (e: DuplicateKeyException) {
            log.warn("Push already recorded for the same company and period: {}", message.title)
        }
    }

    private fun handle(error: MessagingErrorCode?, token: String) {
        when (error) {
            null -> Unit
            MessagingErrorCode.UNREGISTERED -> devices.remove(token)
            else -> log.warn("Push to a device failed: {}", error)
        }
    }

    private fun currentChannel(): String = if (quietHours.isActive(clock.instant())) QUIET_CHANNEL else NORMAL_CHANNEL

    private fun PushMessage.toFirebase(token: String, channel: String): Message =
        Message.builder()
            .setToken(token)
            .setNotification(Notification.builder().setTitle(title).setBody(body).build())
            .setAndroidConfig(AndroidConfig.builder().setNotification(AndroidNotification.builder().setChannelId(channel).build()).build())
            .putAllData(data)
            .build()

    private companion object {
        const val NORMAL_CHANNEL = "alerty"
        const val QUIET_CHANNEL = "alerty_ciche"
    }
}

class PushDeliveryException(title: String, reason: String) : RuntimeException("Push was not delivered, $reason: $title")
