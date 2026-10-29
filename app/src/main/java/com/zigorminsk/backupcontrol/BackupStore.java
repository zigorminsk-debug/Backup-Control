package com.zigorminsk.backupcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** Checklist and completion history are saved atomically in local preferences. */
final class BackupStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;

    BackupStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("backup_control", Context.MODE_PRIVATE);
        synchronized (LOCK) {
            if (!prefs.getBoolean("initialized", false)) {
                List<BackupItem> examples = new ArrayList<>();
                examples.add(new BackupItem(1, "Серверы и виртуальные машины"));
                examples.add(new BackupItem(2, "Базы данных"));
                examples.add(new BackupItem(3, "Файлы и документы"));
                prefs.edit()
                        .putBoolean("initialized", true)
                        .putInt("nextId", 4)
                        .putInt("weekday", Calendar.MONDAY)
                        .putInt("hour", 9)
                        .putInt("minute", 0)
                        .putString("items", encodeItems(examples))
                        .putString("archive", "[]")
                        .putLong("nextArchiveId", 1)
                        .putBoolean("archiveInitialized", true)
                        .putString("lastWeeklyNotice", cycleKey(Calendar.MONDAY, 9, 0))
                        .commit();
            } else if (!prefs.getBoolean("archiveInitialized", false)) {
                migrateExistingChecks();
            }
        }
    }

    int weekday() { return prefs.getInt("weekday", Calendar.MONDAY); }
    int hour() { return prefs.getInt("hour", 9); }
    int minute() { return prefs.getInt("minute", 0); }

    long nextControl() {
        return WeekSchedule.nextOccurrence(System.currentTimeMillis(), weekday(), hour(), minute(),
                TimeZone.getDefault());
    }

    long nextControlDayStart() {
        return WeekSchedule.nextOccurrence(System.currentTimeMillis(), weekday(), 0, 0,
                TimeZone.getDefault());
    }

    void setSchedule(int weekday, int hour, int minute) {
        synchronized (LOCK) {
            syncCycle();
            List<BackupItem> items = loadItems();
            long now = System.currentTimeMillis();
            TimeZone zone = TimeZone.getDefault();
            String today = WeekSchedule.localDateKey(now, zone);
            if (weekday != weekday()) {
                for (BackupItem item : items) {
                    if (!item.checked) continue;
                    // If the newly selected day is today and this was finished on
                    // an earlier day, it is already due; otherwise wait for the
                    // next selected day. Preserve the history in either case.
                    if (WeekSchedule.isControlDay(now, weekday, zone) && item.completedAt > 0
                            && WeekSchedule.localDateKey(item.completedAt, zone).compareTo(today) < 0) {
                        makeActive(item);
                    } else {
                        item.availableOn = WeekSchedule.nextControlDate(now, weekday, zone);
                    }
                }
            }
            prefs.edit().putInt("weekday", weekday).putInt("hour", hour).putInt("minute", minute)
                    .putString("items", encodeItems(items))
                    // A schedule edited after its time must not trigger a stale alert.
                    .putString("lastWeeklyNotice", cycleKey(weekday, hour, minute)).commit();
        }
    }

    /** Return archived tasks to the checklist from 00:00 on their selected day. */
    boolean syncCycle() {
        synchronized (LOCK) {
            String today = WeekSchedule.localDateKey(System.currentTimeMillis(), TimeZone.getDefault());
            List<BackupItem> items = loadItems();
            boolean changed = false;
            for (BackupItem item : items) {
                if (item.checked && !item.availableOn.isEmpty() && today.compareTo(item.availableOn) >= 0) {
                    makeActive(item);
                    changed = true;
                }
            }
            if (changed) saveItems(items);
            return changed;
        }
    }

    /** A cycle key is returned only after the configured notification time. */
    String unnotifiedWeeklyCycle() {
        synchronized (LOCK) {
            String cycle = cycleKey(weekday(), hour(), minute());
            String last = prefs.getString("lastWeeklyNotice", cycle);
            return cycle.compareTo(last) > 0 ? cycle : null;
        }
    }

    /** When the user postponed the reminder; 0 when nothing is postponed. */
    long snoozeUntil() {
        synchronized (LOCK) { return prefs.getLong("snoozeUntil", 0); }
    }

    void setSnooze(long until) {
        synchronized (LOCK) { prefs.edit().putLong("snoozeUntil", until).commit(); }
    }

    void clearSnooze() {
        synchronized (LOCK) { prefs.edit().remove("snoozeUntil").commit(); }
    }

    void markWeeklyNotified(String cycle) {
        synchronized (LOCK) {
            String last = prefs.getString("lastWeeklyNotice", "");
            if (cycle.compareTo(last) > 0) prefs.edit().putString("lastWeeklyNotice", cycle).commit();
        }
    }

    List<BackupItem> items() {
        synchronized (LOCK) { return loadItems(); }
    }

    List<ArchiveEntry> archive() {
        synchronized (LOCK) { return loadArchive(); }
    }

    BackupItem item(int id) {
        synchronized (LOCK) {
            for (BackupItem item : loadItems()) if (item.id == id) return item;
            return null;
        }
    }

    void add(String name, List<String> ips) {
        synchronized (LOCK) {
            List<BackupItem> items = loadItems();
            int id = prefs.getInt("nextId", 1);
            BackupItem item = new BackupItem(id, name);
            item.ips = new ArrayList<>(ips);
            items.add(item);
            prefs.edit().putInt("nextId", id + 1).putString("items", encodeItems(items)).commit();
        }
    }

    void rename(int id, String name, List<String> ips) {
        synchronized (LOCK) {
            List<BackupItem> items = loadItems();
            for (BackupItem item : items) {
                if (item.id != id) continue;
                item.name = name;
                item.ips = new ArrayList<>(ips);
            }
            saveItems(items);
        }
    }

    void setChecked(int id, boolean checked) {
        synchronized (LOCK) {
            syncCycle();
            List<BackupItem> items = loadItems();
            for (BackupItem item : items) {
                if (item.id != id || item.checked == checked) continue;
                if (!checked) {
                    makeActive(item);
                    saveItems(items);
                    return;
                }
                long now = System.currentTimeMillis();
                long archiveId = prefs.getLong("nextArchiveId", 1);
                item.checked = true;
                item.completedAt = now;
                item.currentArchiveId = archiveId;
                item.availableOn = WeekSchedule.nextControlDate(now, weekday(), TimeZone.getDefault());
                List<ArchiveEntry> entries = loadArchive();
                entries.add(new ArchiveEntry(archiveId, item.id, item.name, now, item.ips));
                prefs.edit().putString("items", encodeItems(items)).putString("archive", encodeArchive(entries))
                        .putLong("nextArchiveId", archiveId + 1).commit();
                return;
            }
        }
    }

    /** Undo only the currently archived completion, removing its mistaken record. */
    boolean undoCompletion(long archiveId) {
        synchronized (LOCK) {
            syncCycle();
            List<BackupItem> items = loadItems();
            for (BackupItem item : items) {
                if (item.checked && item.currentArchiveId == archiveId) {
                    makeActive(item);
                    List<ArchiveEntry> entries = loadArchive();
                    for (int i = entries.size() - 1; i >= 0; i--) {
                        if (entries.get(i).id == archiveId) entries.remove(i);
                    }
                    prefs.edit().putString("items", encodeItems(items))
                            .putString("archive", encodeArchive(entries)).commit();
                    return true;
                }
            }
            return false;
        }
    }

    void deleteArchiveEntry(long archiveId) {
        synchronized (LOCK) {
            // Do not orphan a currently archived task; the user can undo it instead.
            for (BackupItem item : loadItems()) {
                if (item.checked && item.currentArchiveId == archiveId) return;
            }
            List<ArchiveEntry> entries = loadArchive();
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).id == archiveId) entries.remove(i);
            }
            prefs.edit().putString("archive", encodeArchive(entries)).commit();
        }
    }

    void remove(int id) {
        synchronized (LOCK) {
            List<BackupItem> items = loadItems();
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).id == id) items.remove(i);
            }
            // Historical completion records are intentionally retained.
            saveItems(items);
        }
    }

    void setReminder(int id, long at, String note) {
        synchronized (LOCK) {
            List<BackupItem> items = loadItems();
            for (BackupItem item : items) {
                if (item.id == id) {
                    item.reminderAt = at;
                    item.reminderNote = note;
                    item.reminderDelivered = false;
                }
            }
            saveItems(items);
        }
    }

    void clearReminder(int id) { setReminder(id, 0, ""); }

    void markReminderDelivered(int id, long expectedTime) {
        synchronized (LOCK) {
            List<BackupItem> items = loadItems();
            for (BackupItem item : items) {
                if (item.id == id && item.reminderAt == expectedTime) item.reminderDelivered = true;
            }
            saveItems(items);
        }
    }

    private void migrateExistingChecks() {
        List<BackupItem> items = loadItems();
        List<ArchiveEntry> entries = new ArrayList<>();
        long nextId = 1;
        String oldCycle = prefs.getString("cycle", cycleKey(weekday(), hour(), minute()));
        boolean previousCyclePassed = cycleKey(weekday(), hour(), minute()).compareTo(oldCycle) > 0;
        long now = System.currentTimeMillis();
        for (BackupItem item : items) {
            if (!item.checked) continue;
            // Older versions did not store completion timestamps: never invent one.
            entries.add(new ArchiveEntry(nextId, item.id, item.name, 0, item.ips));
            if (previousCyclePassed) makeActive(item);
            else {
                item.currentArchiveId = nextId;
                item.availableOn = WeekSchedule.nextControlDate(now, weekday(), TimeZone.getDefault());
            }
            nextId++;
        }
        prefs.edit().putString("items", encodeItems(items)).putString("archive", encodeArchive(entries))
                .putLong("nextArchiveId", nextId).putBoolean("archiveInitialized", true)
                .putString("lastWeeklyNotice", oldCycle).commit();
    }

    private static void makeActive(BackupItem item) {
        item.checked = false;
        item.availableOn = "";
        item.currentArchiveId = 0;
    }

    private String cycleKey(int weekday, int hour, int minute) {
        return WeekSchedule.currentCycleKey(System.currentTimeMillis(), weekday, hour, minute,
                TimeZone.getDefault());
    }

    private List<BackupItem> loadItems() {
        String raw = prefs.getString("items", "[]");
        List<BackupItem> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) items.add(BackupItem.fromJson(array.getJSONObject(i)));
        } catch (JSONException e) {
            // Unreadable preferences must never crash the app on every launch:
            // that would lock the user out of their own data. Keep the damaged
            // payload for later recovery and continue with an empty checklist.
            recoverBroken("items", raw, e);
            return new ArrayList<>();
        }
        return items;
    }

    private List<ArchiveEntry> loadArchive() {
        String raw = prefs.getString("archive", "[]");
        List<ArchiveEntry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) entries.add(ArchiveEntry.fromJson(array.getJSONObject(i)));
        } catch (JSONException e) {
            recoverBroken("archive", raw, e);
            return new ArrayList<>();
        }
        return entries;
    }

    /** Preserve the last readable snapshot so damaged data is never silently lost. */
    private void recoverBroken(String key, String raw, JSONException cause) {
        Log.w("BackupControl", "Cannot read " + key + "; preserving a copy", cause);
        SharedPreferences.Editor editor = prefs.edit();
        if (raw != null && !raw.isEmpty()) {
            editor.putString(key + "_damaged_" + System.currentTimeMillis(), raw);
        }
        editor.remove(key).commit();
    }

    private void saveItems(List<BackupItem> items) {
        prefs.edit().putString("items", encodeItems(items)).commit();
    }

    private static String encodeItems(List<BackupItem> items) {
        JSONArray array = new JSONArray();
        try {
            for (BackupItem item : items) array.put(item.toJson());
        } catch (JSONException e) {
            throw new IllegalStateException("Cannot save backup checklist", e);
        }
        return array.toString();
    }

    private static String encodeArchive(List<ArchiveEntry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (ArchiveEntry entry : entries) array.put(entry.toJson());
        } catch (JSONException e) {
            throw new IllegalStateException("Cannot save completion archive", e);
        }
        return array.toString();
    }
}
