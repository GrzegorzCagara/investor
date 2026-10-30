package pl.investor.report

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
