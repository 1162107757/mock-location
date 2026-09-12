package dev.drift.location;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LocationHistoryStore {
    private static final String PREFS = "drift_location_history";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 30;
    private static final double SAME_LOCATION_EPSILON = 0.00001d;

    private final SharedPreferences preferences;

    LocationHistoryStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Entry> getEntries() {
        String stored = preferences.getString(KEY_ENTRIES, "[]");
        if (stored == null || stored.isEmpty()) return Collections.emptyList();
        ArrayList<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(stored);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                double latitude = item.optDouble("latitude", Double.NaN);
                double longitude = item.optDouble("longitude", Double.NaN);
                if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) continue;
                entries.add(new Entry(
                        item.optLong("id", index),
                        item.optString("name", ""),
                        latitude,
                        longitude,
                        item.optLong("usedAt", 0L)
                ));
            }
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
        return entries;
    }

    void record(String name, double latitude, double longitude) {
        ArrayList<Entry> entries = new ArrayList<>(getEntries());
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            if (Math.abs(entry.latitude - latitude) <= SAME_LOCATION_EPSILON
                    && Math.abs(entry.longitude - longitude) <= SAME_LOCATION_EPSILON) {
                entries.remove(index);
            }
        }
        String cleanName = name == null ? "" : name.trim();
        long now = System.currentTimeMillis();
        entries.add(0, new Entry(now, cleanName, latitude, longitude, now));
        if (entries.size() > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size()).clear();
        }
        save(entries);
    }

    void delete(long id) {
        ArrayList<Entry> entries = new ArrayList<>(getEntries());
        for (int index = entries.size() - 1; index >= 0; index--) {
            if (entries.get(index).id == id) entries.remove(index);
        }
        save(entries);
    }

    void clear() {
        preferences.edit().remove(KEY_ENTRIES).apply();
    }

    private void save(List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", entry.id);
                item.put("name", entry.name);
                item.put("latitude", entry.latitude);
                item.put("longitude", entry.longitude);
                item.put("usedAt", entry.usedAt);
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        preferences.edit().putString(KEY_ENTRIES, array.toString()).apply();
    }

    static final class Entry {
        final long id;
        final String name;
        final double latitude;
        final double longitude;
        final long usedAt;

        Entry(long id, String name, double latitude, double longitude, long usedAt) {
            this.id = id;
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
            this.usedAt = usedAt;
        }
    }
}
