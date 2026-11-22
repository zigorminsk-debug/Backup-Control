package com.zigorminsk.backupcontrol.domain.scheduler

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

object WeekdayScheduleCalculator {

    /**
     * Computes the next ZonedDateTime strictly after [now] that matches [hour]:[minute]
     * on one of [allowedDays] (defaults to Monday..Friday at 09:20).
     */
    fun nextScheduledRun(
        now: ZonedDateTime,
        hour: Int = 9,
        minute: Int = 20,
        allowedDays: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY
        )
    ): ZonedDateTime? {
        if (allowedDays.isEmpty()) return null

        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)

        for (offsetDays in 0L..7L) {
            val candidateDate = now.toLocalDate().plusDays(offsetDays)
            if (candidateDate.dayOfWeek !in allowedDays) continue

            val candidateDateTime = ZonedDateTime.of(
                candidateDate.year,
                candidateDate.monthValue,
                candidateDate.dayOfMonth,
                safeHour,
                safeMinute,
                0,
                0,
                now.zone
            )

            if (candidateDateTime.isAfter(now)) {
                return candidateDateTime
            }
        }
        return null
    }

    /**
     * Calculates full elapsed days without mail since [lastEmailMillis].
     * When [skipWeekends] is false, uses full 24-hour periods elapsed (or calendar days if older).
     * When [skipWeekends] is true, counts only Monday–Friday days between last email date and today.
     */
    fun calculateSilentDays(
        lastEmailMillis: Long,
        nowMillis: Long,
        skipWeekends: Boolean,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Int {
        if (lastEmailMillis <= 0L) return Int.MAX_VALUE
        if (nowMillis <= lastEmailMillis) return 0

        val lastZoned = Instant.ofEpochMilli(lastEmailMillis).atZone(zoneId)
        val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zoneId)

        if (!skipWeekends) {
            val hours = Duration.between(lastZoned, nowZoned).toHours()
            return (hours / 24L).toInt()
        }

        val startDate = lastZoned.toLocalDate()
        val endDate = nowZoned.toLocalDate()
        val totalDays = ChronoUnit.DAYS.between(startDate, endDate)
        if (totalDays <= 0L) return 0

        var workingDays = 0
        var cursor = startDate.plusDays(1)
        while (!cursor.isAfter(endDate)) {
            if (cursor.dayOfWeek != DayOfWeek.SATURDAY && cursor.dayOfWeek != DayOfWeek.SUNDAY) {
                workingDays++
            }
            cursor = cursor.plusDays(1)
        }
        // Subtract 1 if less than 24h has passed on the next weekday morning before backup window
        val totalHours = Duration.between(lastZoned, nowZoned).toHours()
        return if (totalHours < 24L) 0 else workingDays
    }
}
