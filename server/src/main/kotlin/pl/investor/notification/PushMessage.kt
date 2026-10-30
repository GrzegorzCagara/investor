package pl.investor.notification

import pl.investor.history.PeriodKind
import java.time.LocalDate

enum class PushKind { RESULTS, SOURCE_OUTAGE, AI_BUDGET, AI_AUTH }

data class PushMessage(
    val title: String,
    val body: String,
    val data: Map<String, String>,
    val kind: PushKind,
    val reportId: Long?,
    val companyId: Long? = null,
    val periodKind: PeriodKind? = null,
    val periodEnd: LocalDate? = null,
)
