package com.zigorminsk.backupcontrol;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** Data is kept locally. A weekly reset never removes an unresolved error reminder. */
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
                        .putString("items", encode(examples))
                        .putString("cycle", cycleKey(Calendar.MONDAY, 9, 0))
                        .commit();
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

    void setSchedule(int weekday, int hour, int minute) {
        synchronized (LOCK) {
            // Changing the schedule should not erase checks already made this week.
            prefs.edit().putInt("weekday", weekday).putInt("hour", hour).putInt("minute", minute)
                    .putString("cycle", cycleKey(weekday, hour, minute)).commit();
        }
    }

    /** Returns true only when the start of a new control cycle was crossed. */
    boolean syncCycle() {
        synchronized (LOCK) {
            String cycle = cycleKey(weekday(), hour(), minute());
            String previous = prefs.getString("cycle", cycle);
            // A clock moved backwards must not erase completed work a second time.
            if (cycle.compareTo(previous) <= 0) return false;
            List<BackupItem> items = load();
            for (BackupItem item : items) item.checked = false;
            prefs.edit().putString("items", encode(items)).putString("cycle", cycle).commit();
            return true;
        }
    }

    List<BackupItem> items() {
        synchronized (LOCK) { return load(); }
    }

    BackupItem item(int id) {
        synchronized (LOCK) {
            for (BackupItem item : load()) if (item.id == id) return item;
            return null;
        }
    }

    void add(String name) {
        synchronized (LOCK) {
            List<BackupItem> items = load();
            int id = prefs.getInt("nextId", 1);
            items.add(new BackupItem(id, name));
            prefs.edit().putInt("nextId", id + 1).putString("items", encode(items)).commit();
        }
    }

    void rename(int id, String name) {
        synchronized (LOCK) {
            List<BackupItem> items = load();
            for (BackupItem item : items) if (item.id == id) item.name = name;
            save(items);
        }
    }

    void setChecked(int id, boolean checked) {
        synchronized (LOCK) {
            List<BackupItem> items = load();
            for (BackupItem item : items) if (item.id == id) item.checked = checked;
            save(items);
        }
    }

    void remove(int id) {
        synchronized (LOCK) {
            List<BackupItem> items = load();
            for (int i = items.size() - 1; i >= 0; i--) {
                if (items.get(i).id == id) items.remove(i);
            }
            save(items);
        }
    }

    void setReminder(int id, long at, String note) {
        synchronized (LOCK) {
            List<BackupItem> items = load();
            for (BackupItem item : items) {
                if (item.id == id) {
                    item.reminderAt = at;
                    item.reminderNote = note;
                    item.reminderDelivered = false;
                }
            }
            save(items);
        }
    }

    void clearReminder(int id) {
        setReminder(id, 0, "");
    }

    void markReminderDelivered(int id, long expectedTime) {
        synchronized (LOCK) {
            List<BackupItem> items = load();
            for (BackupItem item : items) {
                if (item.id == id && item.reminderAt == expectedTime) item.reminderDelivered = true;
            }
            save(items);
        }
    }

    private String cycleKey(int weekday, int hour, int minute) {
        return WeekSchedule.currentCycleKey(System.currentTimeMillis(), weekday, hour, minute,
                TimeZone.getDefault());
    }

    private List<BackupItem> load() {
        List<BackupItem> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < array.length(); i++) items.add(BackupItem.fromJson(array.getJSONObject(i)));
        } catch (JSONException e) {
            throw new IllegalStateException("Cannot read backup checklist", e);
        }
        return items;
    }

    private void save(List<BackupItem> items) {
        prefs.edit().putString("items", encode(items)).commit();
    }

    private static String encode(List<BackupItem> items) {
        JSONArray array = new JSONArray();
        try {
            for (BackupItem item : items) array.put(item.toJson());
        } catch (JSONException e) {
            throw new IllegalStateException("Cannot save backup checklist", e);
        }
        return array.toString();
    }
}
