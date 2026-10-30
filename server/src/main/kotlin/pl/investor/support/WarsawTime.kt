package pl.investor.support

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

fun Instant.toWarsawDate(): LocalDate = atZone(WARSAW).toLocalDate()
