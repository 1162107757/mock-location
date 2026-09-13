package dev.drift.location;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

public final class MockLocationService extends Service {
    private static final String CHANNEL_ID = "drift_mock_location";
    private static final int NOTIFICATION_ID = 1107;
    private static final long UPDATE_INTERVAL_MS = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private SharedPreferences preferences;
    private static final String FUSED_PROVIDER = "fused";

    private final Set<String> installedProviders = new LinkedHashSet<>();
    private boolean running;
    private double latitude;
    private double longitude;
    private float speed;
    private float bearing;
    private boolean rootOnly;
    private boolean trajectoryMode;
    private boolean trajectoryPaused;
    private boolean trajectoryLoop;
    private float trajectorySpeedKmh;
    private int trajectorySegmentIndex;
    private double trajectorySegmentMeters;
    private double trajectoryTotalMeters;
    private double trajectoryTravelledMeters;
    private long lastTrajectoryTickMs;
    private final ArrayList<RoutePoint> trajectoryPoints = new ArrayList<>();

    private final Runnable locationTicker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                boolean trajectoryCompleted = false;
                if (trajectoryMode && !trajectoryPaused) {
                    trajectoryCompleted = advanceTrajectory();
                } else if (trajectoryMode) {
                    speed = 0f;
                    persistLocationState();
                }
                for (String provider : installedProviders) {
                    pushLocation(provider, accuracyFor(provider));
                }
                if (rootOnly) broadcastRootState(true);
                if (trajectoryMode) broadcastTrajectoryState(true,
                        trajectoryCompleted ? "轨迹已完成" : "轨迹模拟中");
                if (trajectoryCompleted) {
                    stopMocking("轨迹已完成");
                    return;
                }
                handler.postDelayed(this, UPDATE_INTERVAL_MS);
            } catch (SecurityException exception) {
                stopMocking(permissionFailureMessage());
            } catch (RuntimeException exception) {
                stopMocking("模拟失败：" + safeMessage(exception));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        preferences = LocationContract.openPreferences(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? LocationContract.ACTION_START : intent.getAction();
        if (LocationContract.ACTION_STOP.equals(action)) {
            stopMocking("模拟已停止");
            return START_NOT_STICKY;
        }

        if (LocationContract.ACTION_TRAJECTORY_PAUSE.equals(action)) {
            if (running && trajectoryMode) {
                trajectoryPaused = true;
                speed = 0f;
                persistLocationState();
                updateNotification();
                broadcastTrajectoryState(true, "轨迹已暂停");
            }
            return START_STICKY;
        }

        if (LocationContract.ACTION_TRAJECTORY_RESUME.equals(action)) {
            if (running && trajectoryMode) {
                trajectoryPaused = false;
                speed = trajectorySpeedKmh / 3.6f;
                lastTrajectoryTickMs = SystemClock.elapsedRealtime();
                persistLocationState();
                updateNotification();
                broadcastTrajectoryState(true, "轨迹继续模拟");
            }
            return START_STICKY;
        }

        // A home-page map drag must not reset an active trajectory. The route engine
        // owns coordinates until the route is stopped or completed.
        if (LocationContract.ACTION_UPDATE.equals(action) && running && trajectoryMode
                && (intent == null || !intent.hasExtra(LocationContract.EXTRA_ROUTE_JSON))) {
            return START_STICKY;
        }

        readCoordinates(intent);
        if (intent != null && intent.hasExtra(LocationContract.EXTRA_ROUTE_JSON)) {
            configureTrajectory(
                    intent.getStringExtra(LocationContract.EXTRA_ROUTE_JSON),
                    intent.getFloatExtra(LocationContract.EXTRA_TRAJECTORY_SPEED_KMH, 5f),
                    intent.getBooleanExtra(LocationContract.EXTRA_TRAJECTORY_LOOP, false)
            );
        }
        if (LocationContract.ACTION_UPDATE.equals(action) && running) {
            updateNotification();
            if (rootOnly) broadcastRootState(true);
            return START_STICKY;
        }

        if (!running) {
            startForeground(NOTIFICATION_ID, buildNotification());
            try {
                installProvider(LocationManager.GPS_PROVIDER, false, true);
                installProvider(LocationManager.NETWORK_PROVIDER, true, false);
                installOptionalProvider(FUSED_PROVIDER, true, true);
                running = true;
                preferences.edit().putBoolean(LocationContract.KEY_RUNNING, true).apply();
                if (rootOnly) broadcastRootState(true);
                handler.removeCallbacks(locationTicker);
                handler.post(locationTicker);
                broadcastState(true, rootOnly
                        ? (trajectoryMode ? "Root 轨迹模拟已启动" : "Root 固定位置已启动 · " + installedProviders.size() + " 个定位通道")
                        : (trajectoryMode ? "轨迹模拟已启动" : "正在模拟位置 · " + installedProviders.size() + " 个定位通道"));
                if (trajectoryMode) broadcastTrajectoryState(true, "轨迹模拟中");
            } catch (SecurityException exception) {
                stopMocking(permissionFailureMessage());
            } catch (RuntimeException exception) {
                stopMocking("无法启动：" + safeMessage(exception));
            }
        }
        return START_STICKY;
    }

    private void readCoordinates(Intent intent) {
        long savedLatitude = preferences.getLong(
                LocationContract.KEY_LATITUDE,
                Double.doubleToRawLongBits(LocationContract.DEFAULT_LATITUDE)
        );
        long savedLongitude = preferences.getLong(
                LocationContract.KEY_LONGITUDE,
                Double.doubleToRawLongBits(LocationContract.DEFAULT_LONGITUDE)
        );
        latitude = Double.longBitsToDouble(savedLatitude);
        longitude = Double.longBitsToDouble(savedLongitude);
        speed = intent == null ? 0f : intent.getFloatExtra(LocationContract.EXTRA_SPEED, 0f);
        bearing = intent == null ? 0f : intent.getFloatExtra(LocationContract.EXTRA_BEARING, 0f);
        rootOnly = preferences.getBoolean(LocationContract.KEY_ROOT_ONLY, false);

        if (intent != null) {
            latitude = intent.getDoubleExtra(LocationContract.EXTRA_LATITUDE, latitude);
            longitude = intent.getDoubleExtra(LocationContract.EXTRA_LONGITUDE, longitude);
            rootOnly = intent.getBooleanExtra(LocationContract.EXTRA_ROOT_ONLY, rootOnly);
        }

        persistLocationState();
    }

    private void persistLocationState() {
        preferences.edit()
                .putLong(LocationContract.KEY_LATITUDE, Double.doubleToRawLongBits(latitude))
                .putLong(LocationContract.KEY_LONGITUDE, Double.doubleToRawLongBits(longitude))
                .putFloat(LocationContract.KEY_SPEED, speed)
                .putFloat(LocationContract.KEY_BEARING, bearing)
                .putBoolean(LocationContract.KEY_ROOT_ONLY, rootOnly)
                .apply();
    }

    private void configureTrajectory(String routeJson, float speedKmh, boolean loop) {
        ArrayList<RoutePoint> points = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(routeJson == null ? "[]" : routeJson);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                double pointLatitude = item.optDouble("latitude", Double.NaN);
                double pointLongitude = item.optDouble("longitude", Double.NaN);
                if (!Double.isFinite(pointLatitude) || !Double.isFinite(pointLongitude)) continue;
                if (pointLatitude < -90 || pointLatitude > 90 || pointLongitude < -180 || pointLongitude > 180) continue;
                points.add(new RoutePoint(pointLatitude, pointLongitude));
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException("轨迹数据无效");
        }
        if (points.size() < 2) throw new IllegalArgumentException("轨迹至少需要起点和终点");

        trajectoryPoints.clear();
        trajectoryPoints.addAll(points);
        trajectoryMode = true;
        trajectoryPaused = false;
        trajectoryLoop = loop;
        trajectorySpeedKmh = Math.max(0.1f, Math.min(speedKmh, 300f));
        trajectorySegmentIndex = 0;
        trajectorySegmentMeters = 0d;
        trajectoryTotalMeters = 0d;
        trajectoryTravelledMeters = 0d;
        for (int index = 0; index < trajectoryPoints.size() - 1; index++) {
            trajectoryTotalMeters += distanceMeters(trajectoryPoints.get(index), trajectoryPoints.get(index + 1));
        }
        RoutePoint first = trajectoryPoints.get(0);
        latitude = first.latitude;
        longitude = first.longitude;
        speed = trajectorySpeedKmh / 3.6f;
        bearing = bearingBetween(first, trajectoryPoints.get(1));
        lastTrajectoryTickMs = SystemClock.elapsedRealtime();
        persistLocationState();
    }

    private boolean advanceTrajectory() {
        if (trajectoryPoints.size() < 2) return true;
        long now = SystemClock.elapsedRealtime();
        double deltaSeconds = lastTrajectoryTickMs <= 0
                ? UPDATE_INTERVAL_MS / 1000d
                : Math.max(0.01d, Math.min(2.0d, (now - lastTrajectoryTickMs) / 1000d));
        lastTrajectoryTickMs = now;
        double remainingMeters = trajectorySpeedKmh / 3.6d * deltaSeconds;
        speed = trajectorySpeedKmh / 3.6f;

        while (remainingMeters > 0d) {
            if (trajectorySegmentIndex >= trajectoryPoints.size() - 1) {
                if (trajectoryLoop) {
                    trajectorySegmentIndex = 0;
                    trajectorySegmentMeters = 0d;
                    trajectoryTravelledMeters = 0d;
                    RoutePoint restart = trajectoryPoints.get(0);
                    latitude = restart.latitude;
                    longitude = restart.longitude;
                } else {
                    RoutePoint end = trajectoryPoints.get(trajectoryPoints.size() - 1);
                    latitude = end.latitude;
                    longitude = end.longitude;
                    speed = 0f;
                    bearing = 0f;
                    persistLocationState();
                    return true;
                }
            }
            RoutePoint from = trajectoryPoints.get(trajectorySegmentIndex);
            RoutePoint to = trajectoryPoints.get(trajectorySegmentIndex + 1);
            double segmentLength = distanceMeters(from, to);
            if (segmentLength < 0.1d) {
                trajectorySegmentIndex++;
                trajectorySegmentMeters = 0d;
                continue;
            }
            double segmentRemaining = segmentLength - trajectorySegmentMeters;
            double consumed = Math.min(segmentRemaining, remainingMeters);
            trajectorySegmentMeters += consumed;
            trajectoryTravelledMeters += consumed;
            double fraction = Math.max(0d, Math.min(1d, trajectorySegmentMeters / segmentLength));
            latitude = from.latitude + (to.latitude - from.latitude) * fraction;
            longitude = from.longitude + (to.longitude - from.longitude) * fraction;
            bearing = bearingBetween(from, to);
            remainingMeters -= consumed;
            if (trajectorySegmentMeters >= segmentLength - 0.001d) {
                trajectorySegmentIndex++;
                trajectorySegmentMeters = 0d;
            }
        }
        persistLocationState();
        return false;
    }

    private static double distanceMeters(RoutePoint first, RoutePoint second) {
        double lat1 = Math.toRadians(first.latitude);
        double lat2 = Math.toRadians(second.latitude);
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(second.longitude - first.longitude);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 6_371_000d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0d, 1d - a)));
    }

    private static float bearingBetween(RoutePoint first, RoutePoint second) {
        double lat1 = Math.toRadians(first.latitude);
        double lat2 = Math.toRadians(second.latitude);
        double deltaLon = Math.toRadians(second.longitude - first.longitude);
        double y = Math.sin(deltaLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2)
                - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
        return (float) ((Math.toDegrees(Math.atan2(y, x)) + 360d) % 360d);
    }

    @SuppressWarnings("deprecation")
    @SuppressLint({"MissingPermission", "WrongConstant"})
    // The service is started only after permissions; API 25 uses legacy Criteria constants.
    private void installProvider(String provider, boolean requiresNetwork, boolean requiresSatellite) {
        try {
            locationManager.removeTestProvider(provider);
        } catch (IllegalArgumentException ignored) {
            // The provider is still the system provider and has not been replaced yet.
        }
        locationManager.addTestProvider(
                provider,
                requiresNetwork,
                requiresSatellite,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
        );
        locationManager.setTestProviderEnabled(provider, true);
        installedProviders.add(provider);
    }

    private void installOptionalProvider(String provider, boolean requiresNetwork, boolean requiresSatellite) {
        try {
            installProvider(provider, requiresNetwork, requiresSatellite);
        } catch (SecurityException exception) {
            throw exception;
        } catch (RuntimeException ignored) {
            // Some Android builds do not expose the fused provider to test providers.
            // GPS and network still remain available on those devices.
        }
    }

    private float accuracyFor(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) return 3.0f;
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) return 12.0f;
        return 5.0f;
    }

    private void pushLocation(String provider, float accuracy) {
        Location location = new Location(provider);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAltitude(12.0);
        location.setAccuracy(accuracy);
        location.setSpeed(speed);
        location.setBearing(bearing);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            location.setSpeedAccuracyMetersPerSecond(0.2f);
            location.setBearingAccuracyDegrees(2.0f);
            location.setVerticalAccuracyMeters(3.0f);
        }
        locationManager.setTestProviderLocation(provider, location);
    }

    private void stopMocking(String message) {
        boolean wasTrajectory = trajectoryMode;
        running = false;
        trajectoryMode = false;
        trajectoryPaused = false;
        trajectoryPoints.clear();
        handler.removeCallbacks(locationTicker);
        if (!installedProviders.isEmpty()) {
            for (String provider : new LinkedHashSet<>(installedProviders)) {
                removeProvider(provider);
            }
            installedProviders.clear();
        }
        if (preferences != null) {
            preferences.edit().putBoolean(LocationContract.KEY_RUNNING, false).apply();
        }
        broadcastRootState(false);
        broadcastState(false, message);
        if (wasTrajectory) broadcastTrajectoryState(false, message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void removeProvider(String provider) {
        try {
            locationManager.setTestProviderEnabled(provider, false);
        } catch (RuntimeException ignored) {
        }
        try {
            locationManager.removeTestProvider(provider);
        } catch (RuntimeException ignored) {
        }
    }

    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "位置模拟",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("在位置模拟运行时显示状态");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, MockLocationService.class)
                .setAction(LocationContract.ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        String content = trajectoryMode
                ? String.format(Locale.US, "轨迹模拟 %.0f%% · %.1f km/h", trajectoryProgress() * 100d,
                trajectoryPaused ? 0d : trajectorySpeedKmh)
                : String.format(Locale.US, "固定位置 %.5f, %.5f", latitude, longitude);
        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(rootOnly ? "Drift Location · Root 增强" : "Drift Location 正在运行")
                .setContentText(content)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(R.drawable.ic_launcher, "停止", stopPendingIntent).build())
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void broadcastState(boolean isRunning, String message) {
        Intent state = new Intent(LocationContract.ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra(LocationContract.EXTRA_RUNNING, isRunning)
                .putExtra(LocationContract.EXTRA_MESSAGE, message)
                .putExtra(LocationContract.EXTRA_LATITUDE, latitude)
                .putExtra(LocationContract.EXTRA_LONGITUDE, longitude)
                .putExtra(LocationContract.EXTRA_SPEED, speed)
                .putExtra(LocationContract.EXTRA_BEARING, bearing);
        sendBroadcast(state);
    }

    private void broadcastTrajectoryState(boolean isRunning, String message) {
        Intent state = new Intent(LocationContract.ACTION_TRAJECTORY_STATE)
                .setPackage(getPackageName())
                .putExtra(LocationContract.EXTRA_RUNNING, isRunning)
                .putExtra(LocationContract.EXTRA_MESSAGE, message)
                .putExtra(LocationContract.EXTRA_TRAJECTORY, trajectoryMode)
                .putExtra(LocationContract.EXTRA_TRAJECTORY_PAUSED, trajectoryPaused)
                .putExtra(LocationContract.EXTRA_LATITUDE, latitude)
                .putExtra(LocationContract.EXTRA_LONGITUDE, longitude)
                .putExtra(LocationContract.EXTRA_SPEED, speed)
                .putExtra(LocationContract.EXTRA_BEARING, bearing)
                .putExtra(LocationContract.EXTRA_TRAJECTORY_SPEED_KMH,
                        trajectoryPaused ? 0f : trajectorySpeedKmh)
                .putExtra(LocationContract.EXTRA_PROGRESS, trajectoryProgress())
                .putExtra(LocationContract.EXTRA_ROUTE_DISTANCE, trajectoryTotalMeters)
                .putExtra(LocationContract.EXTRA_REMAINING_DISTANCE,
                        Math.max(0d, trajectoryTotalMeters - trajectoryTravelledMeters));
        sendBroadcast(state);
    }

    private double trajectoryProgress() {
        if (trajectoryTotalMeters <= 0d) return 0d;
        return Math.max(0d, Math.min(1d, trajectoryTravelledMeters / trajectoryTotalMeters));
    }

    private void broadcastRootState(boolean isRunning) {
        Intent state = new Intent(LocationContract.ACTION_ROOT_STATE)
                .putExtra(LocationContract.EXTRA_ROOT_TOKEN, LocationContract.ROOT_BROADCAST_TOKEN)
                .putExtra(LocationContract.EXTRA_RUNNING, isRunning)
                .putExtra(LocationContract.EXTRA_LATITUDE, latitude)
                .putExtra(LocationContract.EXTRA_LONGITUDE, longitude)
                .putExtra(LocationContract.EXTRA_SPEED, speed)
                .putExtra(LocationContract.EXTRA_BEARING, bearing);
        sendBroadcast(state);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    private String permissionFailureMessage() {
        return rootOnly
                ? "Root Mock Location 权限未生效，请重新授予 Root 权限"
                : "未获得模拟位置权限，请在开发者选项中选择 Drift Location";
    }

    @Override
    public void onDestroy() {
        if (running || !installedProviders.isEmpty()) {
            stopMocking("模拟服务已结束");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class RoutePoint {
        final double latitude;
        final double longitude;

        RoutePoint(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
