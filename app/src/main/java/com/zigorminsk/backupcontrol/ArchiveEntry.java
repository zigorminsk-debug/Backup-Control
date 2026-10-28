package com.zigorminsk.backupcontrol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A completion is immutable history, even after its task becomes due again. */
final class ArchiveEntry {
    final long id;
    final int itemId;
    final String itemName;
    final long completedAt;
    /** Addresses as they were when the check was done, not as they are today. */
    final List<String> ips;

    ArchiveEntry(long id, int itemId, String itemName, long completedAt, List<String> ips) {
        this.id = id;
        this.itemId = itemId;
        this.itemName = itemName;
        this.completedAt = completedAt;
        this.ips = ips == null ? new ArrayList<>() : new ArrayList<>(ips);
    }

    JSONObject toJson() throws JSONException {
        JSONArray addresses = new JSONArray();
        for (String ip : ips) addresses.put(ip);
        return new JSONObject().put("id", id).put("itemId", itemId)
                .put("itemName", itemName).put("completedAt", completedAt)
                .put("ips", addresses);
    }

    /** Entries written before addresses existed simply carry none. */
    static ArchiveEntry fromJson(JSONObject json) throws JSONException {
        List<String> ips = new ArrayList<>();
        JSONArray addresses = json.optJSONArray("ips");
        if (addresses != null) {
            for (int i = 0; i < addresses.length(); i++) {
                String value = addresses.optString(i, "").trim();
                if (!value.isEmpty()) ips.add(value);
            }
        }
        return new ArchiveEntry(json.getLong("id"), json.getInt("itemId"),
                json.getString("itemName"), json.optLong("completedAt", 0), ips);
    }
}
