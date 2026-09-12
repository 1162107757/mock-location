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

    private final Runnable locationTicker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                for (String provider : installedProviders) {
                    pushLocation(provider, accuracyFor(provider));
                }
                if (rootOnly) broadcastRootState(true);
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

        readCoordinates(intent);
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
                        ? "Root 固定位置已启动 · " + installedProviders.size() + " 个定位通道"
                        : "正在模拟位置 · " + installedProviders.size() + " 个定位通道");
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
        speed = 0f;
        bearing = 0f;
        rootOnly = preferences.getBoolean(LocationContract.KEY_ROOT_ONLY, false);

        if (intent != null) {
            latitude = intent.getDoubleExtra(LocationContract.EXTRA_LATITUDE, latitude);
            longitude = intent.getDoubleExtra(LocationContract.EXTRA_LONGITUDE, longitude);
            rootOnly = intent.getBooleanExtra(LocationContract.EXTRA_ROOT_ONLY, rootOnly);
        }

        preferences.edit()
                .putLong(LocationContract.KEY_LATITUDE, Double.doubleToRawLongBits(latitude))
                .putLong(LocationContract.KEY_LONGITUDE, Double.doubleToRawLongBits(longitude))
                .putFloat(LocationContract.KEY_SPEED, speed)
                .putFloat(LocationContract.KEY_BEARING, bearing)
                .putBoolean(LocationContract.KEY_ROOT_ONLY, rootOnly)
                .apply();
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
        running = false;
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
        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(rootOnly ? "Drift Location · Root 增强" : "Drift Location 正在运行")
                .setContentText(String.format(Locale.US, "固定位置 %.5f, %.5f", latitude, longitude))
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
                .putExtra(LocationContract.EXTRA_MESSAGE, message);
        sendBroadcast(state);
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
}
