package com.zigorminsk.backupcontrol.ui

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {

    private val ruLocale = Locale("ru", "RU")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", ruLocale)
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", ruLocale)
    private val shortDateFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", ruLocale)

    fun formatTimestamp(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
        if (millis <= 0L) return "Никогда"
        val zoned = Instant.ofEpochMilli(millis).atZone(zoneId)
        val today = LocalDate.now(zoneId)
        return when (zoned.toLocalDate()) {
            today -> "Сегодня, ${zoned.format(timeFormatter)}"
            today.minusDays(1) -> "Вчера, ${zoned.format(timeFormatter)}"
            else -> zoned.format(dateTimeFormatter)
        }
    }

    fun formatNextRun(nextRun: ZonedDateTime?): String {
        if (nextRun == null) return "Расписание отключено"
        val dayShort = dayShortRu(nextRun.dayOfWeek)
        val today = LocalDate.now(nextRun.zone)
        return when (nextRun.toLocalDate()) {
            today -> "Сегодня в ${nextRun.format(timeFormatter)}"
            today.plusDays(1) -> "Завтра ($dayShort) в ${nextRun.format(timeFormatter)}"
            else -> "$dayShort, ${nextRun.format(shortDateFormatter)}"
        }
    }

    fun dayShortRu(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Пн"
        DayOfWeek.TUESDAY -> "Вт"
        DayOfWeek.WEDNESDAY -> "Ср"
        DayOfWeek.THURSDAY -> "Чт"
        DayOfWeek.FRIDAY -> "Пт"
        DayOfWeek.SATURDAY -> "Сб"
        DayOfWeek.SUNDAY -> "Вс"
    }

    fun formatScheduledDaysSummary(days: Set<DayOfWeek>): String {
        val weekdays = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY
        )
        return when {
            days.isEmpty() -> "Дни не выбраны"
            days == weekdays -> "Пн–Пт"
            days.size == 7 -> "Ежедневно"
            else -> DayOfWeek.entries.filter { it in days }.joinToString(", ") { dayShortRu(it) }
        }
    }
}
