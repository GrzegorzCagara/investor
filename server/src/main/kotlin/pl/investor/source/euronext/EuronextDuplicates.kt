package pl.investor.source.euronext

import pl.investor.pipeline.foldedTitle
import java.time.Duration
import java.time.Instant

data class PublishedRelease(val isin: String, val title: String, val publishedAt: Instant)

object EuronextDuplicates {

    val SAME_RELEASE_WINDOW: Duration = Duration.ofMinutes(60)
    private val LANGUAGE_PAIR_WINDOW: Duration = Duration.ofMinutes(5)
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val DUTCH_WORDS = Regex(
        "\\b(het|een|en|kwartaal|kwartaalcijfers|halfjaar|halfjaarcijfers|jaarcijfers|resultaten|" +
            "aandelen|eigen|inkoop|verkoop|kondigt|ontvangt|verwelkomt|publiceert|zegt|zeggen|sluiten|haar|voor|naar|bij|uit|" +
            "wordt|worden|heeft|derde|eerste|tweede|vierde|miljoen|miljard|jaar|nederlanders|transparantieverklaring)\\b",
    )
    private const val MIN_DUTCH_WORDS = 2

    fun isDutch(title: String): Boolean =
        DUTCH_WORDS.findAll(title.foldedTitle()).map { it.value }.distinct().count() >= MIN_DUTCH_WORDS

    fun isSameRelease(release: PublishedRelease, other: PublishedRelease): Boolean =
        release.isin == other.isin && release.within(other, SAME_RELEASE_WINDOW) && release.normalizedTitle() == other.normalizedTitle()

    fun isLanguagePair(release: PublishedRelease, other: PublishedRelease): Boolean =
        release.isin == other.isin && release.within(other, LANGUAGE_PAIR_WINDOW) && isDutch(release.title) != isDutch(other.title)

    private fun PublishedRelease.within(other: PublishedRelease, window: Duration): Boolean =
        Duration.between(publishedAt, other.publishedAt).abs() <= window

    private fun PublishedRelease.normalizedTitle(): String = title.foldedTitle().replace(NON_ALPHANUMERIC, "")
}
