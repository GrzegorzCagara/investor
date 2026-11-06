package pl.investor.scoring

enum class ScoreComponent(val label: String, val max: Double) {
    SURPRISE("Niespodzianka", 30.0),
    GROWTH("Wzrost przychodów", 20.0),
    QUALITY("Jakość: zysk i marża", 15.0),
    SERIES("Seria mocnych raportów", 10.0),
    OUTLOOK("Zapowiedzi i zamówienia", 10.0),
    VALUATION("Wycena („czy już w cenie”)", 15.0),
    ;

    fun pointsOf(components: ScoreComponents): Double = when (this) {
        SURPRISE -> components.surprise
        GROWTH -> components.growth
        QUALITY -> components.quality
        SERIES -> components.series
        OUTLOOK -> components.outlook
        VALUATION -> components.valuation
    }
}
