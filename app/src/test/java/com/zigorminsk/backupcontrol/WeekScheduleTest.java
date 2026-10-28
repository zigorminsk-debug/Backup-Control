package com.zigorminsk.backupcontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class WeekScheduleTest {
    private static final TimeZone MINSK = TimeZone.getTimeZone("Europe/Minsk");

    private static long instant(TimeZone zone, int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    @Test public void justBeforeControlIsStillPreviousCycle() {
        long before = instant(MINSK, 2026, 11, 2, 8, 59);
        assertEquals("2026-10-26", WeekSchedule.currentCycleKey(before, Calendar.MONDAY, 9, 0, MINSK));
        assertEquals(instant(MINSK, 2026, 11, 2, 9, 0),
                WeekSchedule.nextOccurrence(before, Calendar.MONDAY, 9, 0, MINSK));
    }

    @Test public void exactControlTimeStartsNewCycleAndNextIsAWeekLater() {
        long now = instant(MINSK, 2026, 11, 2, 9, 0);
        assertEquals("2026-11-02", WeekSchedule.currentCycleKey(now, Calendar.MONDAY, 9, 0, MINSK));
        assertEquals(instant(MINSK, 2026, 11, 9, 9, 0),
                WeekSchedule.nextOccurrence(now, Calendar.MONDAY, 9, 0, MINSK));
    }

    @Test public void nextWeekCrossesYearBoundary() {
        long now = instant(MINSK, 2026, 12, 31, 18, 0);
        assertEquals(instant(MINSK, 2027, 1, 4, 9, 0),
                WeekSchedule.nextOccurrence(now, Calendar.MONDAY, 9, 0, MINSK));
    }

    @Test public void weeklyWallTimeIsPreservedAcrossDaylightSaving() {
        TimeZone berlin = TimeZone.getTimeZone("Europe/Berlin");
        long now = instant(berlin, 2026, 3, 23, 10, 0);
        assertEquals(instant(berlin, 2026, 3, 30, 9, 0),
                WeekSchedule.nextOccurrence(now, Calendar.MONDAY, 9, 0, berlin));
    }

    @Test public void nonexistentDstTimeDoesNotShiftFollowingWeek() {
        TimeZone berlin = TimeZone.getTimeZone("Europe/Berlin");
        // 02:30 on March 29 is skipped; the following Sunday must still be 02:30.
        long afterJump = instant(berlin, 2026, 3, 29, 4, 0);
        assertEquals(instant(berlin, 2026, 4, 5, 2, 30),
                WeekSchedule.nextOccurrence(afterJump, Calendar.SUNDAY, 2, 30, berlin));
    }

    @Test public void completionOnControlDayReturnsNextWeekEvenBeforeNotification() {
        long mondayMorning = instant(MINSK, 2026, 11, 2, 8, 0);
        assertEquals("2026-11-09", WeekSchedule.nextControlDate(mondayMorning, Calendar.MONDAY, MINSK));
    }

    @Test public void completionBeforeControlDayReturnsOnThatDay() {
        long sundayEvening = instant(MINSK, 2026, 11, 1, 23, 59);
        assertEquals("2026-11-02", WeekSchedule.nextControlDate(sundayEvening, Calendar.MONDAY, MINSK));
        assertEquals("2026-11-02", WeekSchedule.localDateKey(
                instant(MINSK, 2026, 11, 2, 0, 0), MINSK));
    }

    @Test public void nextControlDayCrossesYearAndDaylightSaving() {
        assertEquals("2027-01-04", WeekSchedule.nextControlDate(
                instant(MINSK, 2026, 12, 31, 18, 0), Calendar.MONDAY, MINSK));
        TimeZone berlin = TimeZone.getTimeZone("Europe/Berlin");
        assertEquals("2026-03-29", WeekSchedule.nextControlDate(
                instant(berlin, 2026, 3, 28, 23, 30), Calendar.SUNDAY, berlin));
    }

    @Test public void rejectsInvalidSchedule() {
        assertThrows(IllegalArgumentException.class,
                () -> WeekSchedule.nextOccurrence(0, 0, 9, 0, MINSK));
        assertThrows(IllegalArgumentException.class,
                () -> WeekSchedule.nextOccurrence(0, Calendar.MONDAY, 24, 0, MINSK));
    }
}
