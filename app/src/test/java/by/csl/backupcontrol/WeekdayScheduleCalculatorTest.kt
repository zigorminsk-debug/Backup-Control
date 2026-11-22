package by.csl.backupcontrol

import by.csl.backupcontrol.domain.scheduler.WeekdayScheduleCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class WeekdayScheduleCalculatorTest {

    private val zone = ZoneId.of("Europe/Minsk")

    @Test
    fun `friday after 09_20 schedules next run on monday 09_20`() {
        // 2026-11-20 is Friday
        val fridayMorningAfter = ZonedDateTime.of(2026, 11, 20, 9, 21, 0, 0, zone)
        val next = WeekdayScheduleCalculator.nextScheduledRun(fridayMorningAfter)
        assertNotNull(next)
        assertEquals(DayOfWeek.MONDAY, next!!.dayOfWeek)
        assertEquals(2026, next.year)
        assertEquals(11, next.monthValue)
        assertEquals(23, next.dayOfMonth)
        assertEquals(9, next.hour)
        assertEquals(20, next.minute)
    }

    @Test
    fun `weekday before 09_20 schedules same day at 09_20`() {
        // 2026-11-23 is Monday
        val mondayEarly = ZonedDateTime.of(2026, 11, 23, 8, 5, 0, 0, zone)
        val next = WeekdayScheduleCalculator.nextScheduledRun(mondayEarly)
        assertNotNull(next)
        assertEquals(DayOfWeek.MONDAY, next!!.dayOfWeek)
        assertEquals(23, next.dayOfMonth)
        assertEquals(9, next.hour)
        assertEquals(20, next.minute)
    }

    @Test
    fun `sunday schedules monday at 09_20`() {
        // 2026-11-22 is Sunday
        val sunday = ZonedDateTime.of(2026, 11, 22, 17, 30, 0, 0, zone)
        val next = WeekdayScheduleCalculator.nextScheduledRun(sunday)
        assertNotNull(next)
        assertEquals(DayOfWeek.MONDAY, next!!.dayOfWeek)
        assertEquals(23, next.dayOfMonth)
        assertEquals(9, next.hour)
        assertEquals(20, next.minute)
    }
}
