package pl.investor.app

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}

enum class Market { GPW, NC, US }

enum class ReportKind { CURRENT, QUARTERLY, HALF_YEARLY, ANNUAL, OTHER }

enum class ReportStatus {
    NEW,
    IRRELEVANT,
    CORRECTION_SKIPPED,
    EXTRACTED,
    SCORED,
    UNSCORED_NO_AI,
    UNSCORED_NO_DATA,
    FAILED,
    PENDING_ATTACHMENTS,
    PENDING_DATA,
    PENDING_AI,
    PUSH_PENDING,
    BACKFILLED,
}

enum class EventType { RESULTS }

@Serializable
data class AlertItem(
    val id: Long,
    val companyName: String,
    val market: Market,
    val eventType: EventType,
    @Serializable(with = InstantSerializer::class) val publishedAt: Instant,
    val score: Int? = null,
    val belowThreshold: Boolean,
    val summary: String,
    val status: ReportStatus,
)

@Serializable
data class AlertsResponse(val items: List<AlertItem>)

@Serializable
data class ComponentView(val label: String, val points: Double, val max: Double)

@Serializable
data class RevenuePoint(val label: String, val value: Double, val currency: String)

@Serializable
data class AlertDetail(
    val id: Long,
    val companyName: String,
    val market: Market,
    val reportNumber: String,
    val reportKind: ReportKind,
    @Serializable(with = InstantSerializer::class) val publishedAt: Instant,
    val score: Int? = null,
    val components: List<ComponentView>,
    val penalty: Double,
    val warnings: List<String>,
    val reason: String,
    val outlookQuote: String? = null,
    val revenueHistory: List<RevenuePoint>,
    val sourceUrl: String,
)

@Serializable
data class DeviceRequest(val token: String)
