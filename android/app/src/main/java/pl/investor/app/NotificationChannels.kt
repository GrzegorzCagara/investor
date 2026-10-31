package pl.investor.app

object NotificationChannels {
    const val ALERTS = "alerty"
    const val QUIET = "alerty_ciche"

    fun resolve(requested: String?): String = requested?.takeIf { it == ALERTS || it == QUIET } ?: ALERTS
}
