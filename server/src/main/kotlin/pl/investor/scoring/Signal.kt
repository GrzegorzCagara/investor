package pl.investor.scoring

enum class Signal(val label: String) {
    TREND_REVERSAL("Odwrócenie trendu"),
    ORDERS_UP("Rosnące zamówienia"),
    LOW_EXPECTATIONS("Niskie oczekiwania"),
    CONSISTENT_REACTION("Regularne wzrosty po raportach"),
}
