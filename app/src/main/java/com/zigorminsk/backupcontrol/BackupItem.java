package com.zigorminsk.backupcontrol;

import org.json.JSONException;
import org.json.JSONObject;

final class BackupItem {
    final int id;
    String name;
    boolean checked;
    long reminderAt;
    String reminderNote;
    boolean reminderDelivered;

    BackupItem(int id, String name) {
        this.id = id;
        this.name = name;
        this.reminderNote = "";
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("checked", checked);
        json.put("reminderAt", reminderAt);
        json.put("reminderNote", reminderNote);
        json.put("reminderDelivered", reminderDelivered);
        return json;
    }

    static BackupItem fromJson(JSONObject json) throws JSONException {
        BackupItem item = new BackupItem(json.getInt("id"), json.getString("name"));
        item.checked = json.optBoolean("checked", false);
        item.reminderAt = json.optLong("reminderAt", 0);
        item.reminderNote = json.optString("reminderNote", "");
        item.reminderDelivered = json.optBoolean("reminderDelivered", false);
        return item;
    }
}
