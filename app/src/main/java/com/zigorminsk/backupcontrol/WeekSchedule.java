package com.zigorminsk.backupcontrol;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** Calendar-based weekly scheduling, so local wall time survives DST changes. */
public final class WeekSchedule {
    private WeekSchedule() { }

    public static long nextOccurrence(long now, int weekday, int hour, int minute, TimeZone zone) {
        Calendar candidate = candidateForThisWeek(now, weekday, hour, minute, zone);
        if (candidate.getTimeInMillis() <= now) {
            // A nonexistent local time may have been normalized (for example,
            // 02:30 -> 03:30 on the spring DST transition). Reapply the chosen
            // wall time on the following date rather than carrying 03:30 forward.
            candidate.set(Calendar.HOUR_OF_DAY, 12);
            candidate.add(Calendar.DAY_OF_MONTH, 7);
            candidate.set(Calendar.HOUR_OF_DAY, hour);
            candidate.set(Calendar.MINUTE, minute);
        }
        return candidate.getTimeInMillis();
    }

    public static String currentCycleKey(long now, int weekday, int hour, int minute, TimeZone zone) {
        Calendar candidate = candidateForThisWeek(now, weekday, hour, minute, zone);
        if (candidate.getTimeInMillis() > now) candidate.add(Calendar.DAY_OF_MONTH, -7);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", candidate.get(Calendar.YEAR),
                candidate.get(Calendar.MONTH) + 1, candidate.get(Calendar.DAY_OF_MONTH));
    }

    /** Local date of the next selected weekday, strictly after today's date. */
    public static String nextControlDate(long now, int weekday, TimeZone zone) {
        if (weekday < Calendar.SUNDAY || weekday > Calendar.SATURDAY) {
            throw new IllegalArgumentException("Invalid weekday");
        }
        Calendar date = Calendar.getInstance(zone, Locale.ROOT);
        date.setTimeInMillis(now);
        int days = (weekday - date.get(Calendar.DAY_OF_WEEK) + 7) % 7;
        if (days == 0) days = 7;
        date.set(Calendar.HOUR_OF_DAY, 12);
        date.set(Calendar.MINUTE, 0);
        date.set(Calendar.SECOND, 0);
        date.set(Calendar.MILLISECOND, 0);
        date.add(Calendar.DAY_OF_MONTH, days);
        return dateKey(date);
    }

    public static String localDateKey(long instant, TimeZone zone) {
        Calendar date = Calendar.getInstance(zone, Locale.ROOT);
        date.setTimeInMillis(instant);
        return dateKey(date);
    }

    public static boolean isControlDay(long instant, int weekday, TimeZone zone) {
        Calendar date = Calendar.getInstance(zone, Locale.ROOT);
        date.setTimeInMillis(instant);
        return date.get(Calendar.DAY_OF_WEEK) == weekday;
    }

    private static String dateKey(Calendar date) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", date.get(Calendar.YEAR),
                date.get(Calendar.MONTH) + 1, date.get(Calendar.DAY_OF_MONTH));
    }

    private static Calendar candidateForThisWeek(long now, int weekday, int hour, int minute, TimeZone zone) {
        if (weekday < Calendar.SUNDAY || weekday > Calendar.SATURDAY
                || hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("Invalid weekly schedule");
        }
        Calendar candidate = Calendar.getInstance(zone, Locale.ROOT);
        candidate.setTimeInMillis(now);
        int daysAhead = (weekday - candidate.get(Calendar.DAY_OF_WEEK) + 7) % 7;
        // Move to the date first: setting a nonexistent wall time on today's date
        // before moving to the desired day can otherwise shift the target hour.
        candidate.set(Calendar.HOUR_OF_DAY, 12);
        candidate.set(Calendar.MINUTE, 0);
        candidate.set(Calendar.SECOND, 0);
        candidate.set(Calendar.MILLISECOND, 0);
        candidate.add(Calendar.DAY_OF_MONTH, daysAhead);
        candidate.set(Calendar.HOUR_OF_DAY, hour);
        candidate.set(Calendar.MINUTE, minute);
        return candidate;
    }
}
