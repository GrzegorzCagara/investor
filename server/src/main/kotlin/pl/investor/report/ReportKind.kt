package pl.investor.report

enum class ReportKind(val isPeriodic: Boolean) {
    CURRENT(isPeriodic = false),
    QUARTERLY(isPeriodic = true),
    HALF_YEARLY(isPeriodic = true),
    ANNUAL(isPeriodic = true),
    OTHER(isPeriodic = false),
    ;

    companion object {
        val periodic: List<ReportKind> = entries.filter { it.isPeriodic }
    }
}
