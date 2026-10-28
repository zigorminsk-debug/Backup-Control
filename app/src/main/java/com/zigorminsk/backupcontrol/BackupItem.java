package com.zigorminsk.backupcontrol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class BackupItem {
    final int id;
    String name;
    /** IP addresses of the checked systems; a task may cover several machines. */
    List<String> ips = new ArrayList<>();
    boolean checked;
    long completedAt;
    long currentArchiveId;
    String availableOn;
    long reminderAt;
    String reminderNote;
    boolean reminderDelivered;

    BackupItem(int id, String name) {
        this.id = id;
        this.name = name;
        this.reminderNote = "";
        this.availableOn = "";
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        JSONArray addresses = new JSONArray();
        for (String ip : ips) addresses.put(ip);
        json.put("ips", addresses);
        json.put("checked", checked);
        json.put("completedAt", completedAt);
        json.put("currentArchiveId", currentArchiveId);
        json.put("availableOn", availableOn);
        json.put("reminderAt", reminderAt);
        json.put("reminderNote", reminderNote);
        json.put("reminderDelivered", reminderDelivered);
        return json;
    }

    static BackupItem fromJson(JSONObject json) throws JSONException {
        BackupItem item = new BackupItem(json.getInt("id"), json.getString("name"));
        item.ips = readIps(json.optJSONArray("ips"));
        item.checked = json.optBoolean("checked", false);
        item.completedAt = json.optLong("completedAt", 0);
        item.currentArchiveId = json.optLong("currentArchiveId", 0);
        item.availableOn = json.optString("availableOn", "");
        item.reminderAt = json.optLong("reminderAt", 0);
        item.reminderNote = json.optString("reminderNote", "");
        item.reminderDelivered = json.optBoolean("reminderDelivered", false);
        return item;
    }

    /** Items saved by older versions have no "ips" key and stay fully valid. */
    private static List<String> readIps(JSONArray array) {
        List<String> ips = new ArrayList<>();
        if (array == null) return ips;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty() && !ips.contains(value)) ips.add(value);
        }
        return ips;
    }
}
