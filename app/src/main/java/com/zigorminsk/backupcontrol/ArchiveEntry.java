package com.zigorminsk.backupcontrol;

import org.json.JSONException;
import org.json.JSONObject;

/** A completion is immutable history, even after its task becomes due again. */
final class ArchiveEntry {
    final long id;
    final int itemId;
    final String itemName;
    final long completedAt;

    ArchiveEntry(long id, int itemId, String itemName, long completedAt) {
        this.id = id;
        this.itemId = itemId;
        this.itemName = itemName;
        this.completedAt = completedAt;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("itemId", itemId)
                .put("itemName", itemName).put("completedAt", completedAt);
    }

    static ArchiveEntry fromJson(JSONObject json) throws JSONException {
        return new ArchiveEntry(json.getLong("id"), json.getInt("itemId"),
                json.getString("itemName"), json.optLong("completedAt", 0));
    }
}
