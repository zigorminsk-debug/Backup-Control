package com.zigorminsk.backupcontrol;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

/** Debug-only checks of persistence, migration, recurrence and undo on Android. */
public final class ArchiveVerificationInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Context context = getTargetContext();
        SharedPreferences prefs = context.getSharedPreferences("backup_control", Context.MODE_PRIVATE);
        Bundle result = new Bundle();
        try {
            prefs.edit().clear().commit();
            BackupStore store = new BackupStore(context);
            require(store.unnotifiedWeeklyCycle() == null, "new install must not alert early");
            store.add("Archive test", java.util.Arrays.asList("192.168.1.10", "192.168.1.11"));
            int id = store.items().get(store.items().size() - 1).id;
            long now = System.currentTimeMillis();
            store.setReminder(id, now + 60000, "Ошибка остаётся");
            store.setChecked(id, true);
            BackupItem item = store.item(id);
            ArchiveEntry entry = store.archive().get(0);
            require(item.checked && item.currentArchiveId == entry.id, "completion must hide the task");
            require(entry.itemId == id && entry.completedAt >= now && entry.completedAt <= System.currentTimeMillis(),
                    "archive must retain the real completion timestamp");
            require(entry.ips.size() == 2 && entry.ips.get(0).equals("192.168.1.10")
                            && store.item(id).ips.size() == 2,
                    "IP addresses must survive completion and be archived as a snapshot");
            require(item.availableOn.equals(WeekSchedule.nextControlDate(now, store.weekday(), TimeZone.getDefault())),
                    "completed task must wait for the next chosen day");
            store.setChecked(id, true);
            require(store.archive().size() == 1, "repeated checks must not duplicate history");
            require(store.undoCompletion(entry.id), "undo must restore the task");
            require(!store.item(id).checked && store.archive().isEmpty(), "undo must remove the mistaken record");
            require(store.item(id).reminderAt > 0, "undo must preserve the error reminder");

            store.setChecked(id, true);
            int newDay = store.weekday() == Calendar.TUESDAY ? Calendar.WEDNESDAY : Calendar.TUESDAY;
            store.setSchedule(newDay, 9, 0);
            item = store.item(id);
            require(item.checked && item.availableOn.equals(
                    WeekSchedule.nextControlDate(System.currentTimeMillis(), newDay, TimeZone.getDefault())),
                    "changing the weekday must reschedule archived tasks");
            require(store.unnotifiedWeeklyCycle() == null, "changing a schedule must not send an old alert");

            // Simulate arrival of the selected day, without changing the device clock.
            JSONArray items = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject json = items.getJSONObject(i);
                if (json.getInt("id") == id) json.put("availableOn",
                        WeekSchedule.localDateKey(System.currentTimeMillis(), TimeZone.getDefault()));
            }
            prefs.edit().putString("items", items.toString()).commit();
            require(store.syncCycle(), "task should return on its due day");
            require(!store.item(id).checked && store.item(id).reminderAt > 0 && store.archive().size() == 1,
                    "returning a task must keep its history and error reminder");
            require(!store.syncCycle(), "the same due day must not reset twice");

            prefs.edit().putString("lastWeeklyNotice", "2000-01-01").commit();
            String cycle = store.unnotifiedWeeklyCycle();
            require(cycle != null, "missed weekly reminder must remain pending");
            store.markWeeklyNotified(cycle);
            require(store.unnotifiedWeeklyCycle() == null, "weekly reminder must not be duplicated");

            // Old checked items had no timestamp. Preserve the completion and
            // explicitly represent the date as unknown rather than inventing it.
            JSONArray legacyItems = new JSONArray().put(new JSONObject().put("id", 1)
                    .put("name", "Старый пункт").put("checked", true)
                    .put("reminderAt", now + 60000).put("reminderNote", "Старая ошибка"));
            String oldCycle = WeekSchedule.currentCycleKey(System.currentTimeMillis(), Calendar.MONDAY,
                    9, 0, TimeZone.getDefault());
            prefs.edit().clear().putBoolean("initialized", true).putInt("nextId", 2)
                    .putInt("weekday", Calendar.MONDAY).putInt("hour", 9).putInt("minute", 0)
                    .putString("cycle", oldCycle).putString("items", legacyItems.toString()).commit();
            BackupStore migrated = new BackupStore(context);
            require(migrated.item(1).checked && migrated.archive().size() == 1
                            && migrated.archive().get(0).completedAt == 0,
                    "legacy checked item must migrate without a fake timestamp");
            require(migrated.item(1).reminderAt == now + 60000
                    && "Старая ошибка".equals(migrated.item(1).reminderNote),
                    "legacy error reminder must survive migration");
            require(new BackupStore(context).archive().size() == 1, "migration must run only once");

            prefs.edit().clear().putBoolean("initialized", true).putInt("nextId", 2)
                    .putInt("weekday", Calendar.MONDAY).putInt("hour", 9).putInt("minute", 0)
                    .putString("cycle", "2000-01-01").putString("items", legacyItems.toString()).commit();
            BackupStore overdueMigration = new BackupStore(context);
            require(!overdueMigration.item(1).checked && overdueMigration.archive().size() == 1
                    && overdueMigration.item(1).reminderAt == now + 60000,
                    "an old completed task must return without losing its history or reminder");
            require(overdueMigration.unnotifiedWeeklyCycle() != null,
                    "migration must preserve an undelivered weekly reminder");
            result.putString("archive_check", "PASS");
            prefs.edit().clear().commit();
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError e) {
            result.putString("archive_check", "FAIL: " + e);
            prefs.edit().clear().commit();
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
