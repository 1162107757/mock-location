package dev.drift.location;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

final class LocationContract {
    static final String PREFS = "drift_location";
    static final String KEY_LATITUDE = "latitude";
    static final String KEY_LONGITUDE = "longitude";
    static final String KEY_SPEED = "speed";
    static final String KEY_BEARING = "bearing";
    static final String KEY_RUNNING = "running";
    static final String KEY_ROOT_ONLY = "root_only";

    static final String ACTION_START = "dev.drift.location.action.START";
    static final String ACTION_UPDATE = "dev.drift.location.action.UPDATE";
    static final String ACTION_STOP = "dev.drift.location.action.STOP";
    static final String ACTION_STATE = "dev.drift.location.action.STATE";
    static final String ACTION_ROOT_STATE = "dev.drift.location.action.ROOT_STATE";
    static final String ACTION_TRAJECTORY_STATE = "dev.drift.location.action.TRAJECTORY_STATE";
    static final String ACTION_TRAJECTORY_PAUSE = "dev.drift.location.action.TRAJECTORY_PAUSE";
    static final String ACTION_TRAJECTORY_RESUME = "dev.drift.location.action.TRAJECTORY_RESUME";

    static final String EXTRA_LATITUDE = "extra_latitude";
    static final String EXTRA_LONGITUDE = "extra_longitude";
    static final String EXTRA_SPEED = "extra_speed";
    static final String EXTRA_BEARING = "extra_bearing";
    static final String EXTRA_RUNNING = "extra_running";
    static final String EXTRA_MESSAGE = "extra_message";
    static final String EXTRA_ROOT_ONLY = "extra_root_only";
    static final String EXTRA_ROOT_TOKEN = "extra_root_token";
    static final String EXTRA_ROUTE_JSON = "extra_route_json";
    static final String EXTRA_TRAJECTORY_SPEED_KMH = "extra_trajectory_speed_kmh";
    static final String EXTRA_TRAJECTORY_LOOP = "extra_trajectory_loop";
    static final String EXTRA_TRAJECTORY = "extra_trajectory";
    static final String EXTRA_TRAJECTORY_PAUSED = "extra_trajectory_paused";
    static final String EXTRA_PROGRESS = "extra_progress";
    static final String EXTRA_ROUTE_DISTANCE = "extra_route_distance";
    static final String EXTRA_REMAINING_DISTANCE = "extra_remaining_distance";

    static final String TARGET_WECHAT = "com.tencent.mm";
    static final String ROOT_BROADCAST_TOKEN = "drift-location-root-state-v1";

    static final double DEFAULT_LATITUDE = 31.230416;
    static final double DEFAULT_LONGITUDE = 121.473701;
    static final float DEFAULT_SPEED = 0f;

    @SuppressWarnings("deprecation")
    @SuppressLint("WorldReadableFiles") // LSPosed API 93+ safely redirects module preferences for hooked apps.
    static SharedPreferences openPreferences(Context context) {
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException ignored) {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    private LocationContract() {}
}
