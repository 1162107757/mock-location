package dev.drift.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 42;
    private static final int REQUEST_CURRENT_LOCATION = 43;
    private static final long LOCATION_TIMEOUT_MS = 12_000L;
    private static final int COLOR_BACKGROUND = Color.rgb(15, 23, 42);
    private static final int COLOR_CARD = Color.rgb(27, 35, 54);
    private static final int COLOR_MUTED = Color.rgb(39, 47, 66);
    private static final int COLOR_BORDER = Color.rgb(71, 85, 105);
    private static final int COLOR_TEXT = Color.rgb(248, 250, 252);
    private static final int COLOR_SUBTLE = Color.rgb(148, 163, 184);
    private static final int COLOR_ACCENT = Color.rgb(34, 197, 94);
    private static final int COLOR_DANGER = Color.rgb(239, 68, 68);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private SharedPreferences preferences;
    private LocationHistoryStore historyStore;
    private MapCanvasView mapView;
    private EditText searchInput;
    private TextView coordinateText;
    private TextView statusText;
    private Button startButton;
    private Button setupButton;
    private Button locateButton;
    private LocationManager locationManager;
    private LocationListener pendingLocationListener;
    private Runnable locationTimeout;
    private boolean running;
    private boolean rootOnly;
    private boolean rootGranted;
    private boolean rootCheckInFlight;
    private boolean rootProviderSetupInFlight;
    private boolean rootProviderAccessReady;
    private String selectedPlaceName = "";
    private double selectedPlaceLatitude = Double.NaN;
    private double selectedPlaceLongitude = Double.NaN;
    private long lastServiceUpdate;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!LocationContract.ACTION_STATE.equals(intent.getAction())) return;
            running = intent.getBooleanExtra(LocationContract.EXTRA_RUNNING, false);
            String message = intent.getStringExtra(LocationContract.EXTRA_MESSAGE);
            if (running && mapView != null) {
                historyStore.record(
                        historyNameForCurrentCenter(),
                        mapView.getCenterLatitude(),
                        mapView.getCenterLongitude()
                );
            }
            renderState(message == null ? (running ? "正在模拟位置" : "模拟已停止") : message);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        preferences = LocationContract.openPreferences(this);
        historyStore = new LocationHistoryStore(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        running = preferences.getBoolean(LocationContract.KEY_RUNNING, false);
        rootOnly = preferences.getBoolean(LocationContract.KEY_ROOT_ONLY, false);
        rootGranted = false;

        setContentView(buildInterface());
        registerStateReceiver();

        double latitude = readDoublePreference(LocationContract.KEY_LATITUDE, LocationContract.DEFAULT_LATITUDE);
        double longitude = readDoublePreference(LocationContract.KEY_LONGITUDE, LocationContract.DEFAULT_LONGITUDE);
        mapView.setCenter(latitude, longitude);
        renderState(running ? "正在模拟位置" : "准备就绪");
        checkRootAccess(false);
    }

    private View buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACKGROUND);

        mapView = new MapCanvasView(this);
        mapView.setOnCenterChangedListener((latitude, longitude) -> {
            if (!matchesSelectedPlace(latitude, longitude)) selectedPlaceName = "";
            coordinateText.setText(formatCoordinate(latitude, longitude));
            writeCoordinatePreferences(latitude, longitude);
            if (running && System.currentTimeMillis() - lastServiceUpdate > 120L) {
                sendServiceAction(LocationContract.ACTION_UPDATE);
                lastServiceUpdate = System.currentTimeMillis();
            }
        });
        root.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        View searchBar = buildSearchBar();
        FrameLayout.LayoutParams searchParams = frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56),
                Gravity.TOP,
                16, 14, 16, 0
        );
        root.addView(searchBar, searchParams);

        LinearLayout zoomControls = new LinearLayout(this);
        zoomControls.setOrientation(LinearLayout.VERTICAL);
        zoomControls.setPadding(dp(4), dp(4), dp(4), dp(4));
        zoomControls.setBackground(rounded(COLOR_CARD, 14, COLOR_BORDER, 1));
        Button zoomIn = createCompactButton("+", "放大地图");
        Button zoomOut = createCompactButton("−", "缩小地图");
        locateButton = createCompactButton("◎", "定位到设备当前位置");
        locateButton.setTextSize(21);
        zoomIn.setOnClickListener(view -> mapView.zoomIn());
        zoomOut.setOnClickListener(view -> mapView.zoomOut());
        locateButton.setOnClickListener(view -> beginLocateFlow());
        zoomControls.addView(zoomIn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        zoomControls.addView(zoomOut, new LinearLayout.LayoutParams(dp(44), dp(44)));
        zoomControls.addView(locateButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(zoomControls, frameParams(dp(52), dp(140), Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 16, 118));

        View controlPanel = buildControlPanel();
        FrameLayout.LayoutParams panelParams = frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
                12, 0, 12, 12
        );
        root.addView(controlPanel, panelParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
                topInset = systemBars.top;
                bottomInset = systemBars.bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            searchParams.topMargin = topInset + dp(14);
            panelParams.bottomMargin = bottomInset + dp(12);
            searchBar.setLayoutParams(searchParams);
            controlPanel.setLayoutParams(panelParams);
            return insets;
        });
        return root;
    }

    private View buildSearchBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(6), dp(6), dp(6));
        bar.setBackground(rounded(0xF21B2336, 18, COLOR_BORDER, 1));
        bar.setElevation(dp(8));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("搜索城市、街道或地点");
        searchInput.setHintTextColor(COLOR_SUBTLE);
        searchInput.setTextColor(COLOR_TEXT);
        searchInput.setTextSize(15);
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                searchLocation();
                return true;
            }
            return false;
        });
        bar.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        Button searchButton = createActionButton("搜索", COLOR_ACCENT, COLOR_BACKGROUND);
        searchButton.setContentDescription("搜索地点");
        searchButton.setOnClickListener(view -> searchLocation());
        bar.addView(searchButton, new LinearLayout.LayoutParams(dp(72), dp(44)));
        return bar;
    }

    private View buildControlPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(10), dp(18), dp(16));
        panel.setBackground(rounded(0xFA1B2336, 24, COLOR_BORDER, 1));
        panel.setElevation(dp(12));

        View handle = new View(this);
        handle.setBackground(rounded(COLOR_BORDER, 2, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(10);
        panel.addView(handle, handleParams);

        LinearLayout locationRow = new LinearLayout(this);
        locationRow.setGravity(Gravity.CENTER_VERTICAL);
        locationRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout locationLabels = new LinearLayout(this);
        locationLabels.setOrientation(LinearLayout.VERTICAL);
        TextView locationCaption = label("目标位置", 12, COLOR_SUBTLE, Typeface.NORMAL);
        coordinateText = label("", 17, COLOR_TEXT, Typeface.BOLD);
        coordinateText.setContentDescription("当前选中的经纬度，点击可手动输入");
        coordinateText.setPadding(0, dp(2), 0, dp(2));
        coordinateText.setOnClickListener(view -> showCoordinateDialog());
        locationLabels.addView(locationCaption);
        locationLabels.addView(coordinateText);
        locationRow.addView(locationLabels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        statusText = label("", 13, COLOR_SUBTLE, Typeface.BOLD);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(12), dp(8), dp(12), dp(8));
        locationRow.addView(statusText);
        panel.addView(locationRow);

        Button historyButton = createCompactButton("历史位置", "查看模拟过的位置");
        historyButton.setOnClickListener(view -> showHistoryDialog());
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
        );
        historyParams.topMargin = dp(12);
        panel.addView(historyButton, historyParams);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(12), 0, 0);
        setupButton = createActionButton("Root 模式", COLOR_MUTED, COLOR_TEXT);
        setupButton.setOnClickListener(view -> showSetupOptions());
        LinearLayout.LayoutParams setupParams = new LinearLayout.LayoutParams(0, dp(52), 0.9f);
        setupParams.rightMargin = dp(10);
        actionRow.addView(setupButton, setupParams);

        startButton = createActionButton("开始模拟", COLOR_ACCENT, COLOR_BACKGROUND);
        startButton.setOnClickListener(view -> {
            if (running) {
                sendServiceAction(LocationContract.ACTION_STOP);
            } else {
                beginStartFlow();
            }
        });
        actionRow.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1.1f));
        panel.addView(actionRow);
        return panel;
    }

    private void beginStartFlow() {
        if (rootGranted && !rootOnly) {
            rootOnly = true;
            preferences.edit().putBoolean(LocationContract.KEY_ROOT_ONLY, true).apply();
            renderState("已自动切换到 Root 模式");
        }
        if (rootOnly && !rootGranted) {
            Toast.makeText(this, "Root 模式需要先授予 Root 权限", Toast.LENGTH_LONG).show();
            requestRootAccess();
            return;
        }
        if (!rootOnly && !isSelectedMockLocationApp()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要完成一次系统设置")
                    .setMessage("请在开发者选项中找到“选择模拟位置信息应用”，然后选择 Drift Location。返回后即可开始。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("打开设置", (dialog, which) -> openDeveloperSettings())
                    .show();
            return;
        }
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        if (rootOnly && !rootProviderAccessReady && !isSelectedMockLocationApp()) {
            configureRootProviderAccess();
            return;
        }
        sendServiceAction(LocationContract.ACTION_START);
    }

    private void showSetupOptions() {
        String state = rootOnly && rootGranted ? "已启用" : (rootGranted ? "已授权" : "未授权");
        String action = !rootGranted ? "申请 Root 权限" : (rootOnly ? "关闭 Root 模式" : "启用 Root 模式");
        new AlertDialog.Builder(this)
                .setTitle("Root 增强 · " + state)
                .setMessage("Root 固定位置模式会自动授予 Mock Location 权限，并持续向 GPS、网络和 fused 通道提交同一个坐标，速度固定为 0。无需在开发者选项中手动选择应用。")
                .setNegativeButton("取消", null)
                .setNeutralButton("普通模式设置", (dialog, which) -> openDeveloperSettings())
                .setPositiveButton(action, (dialog, which) -> {
                    if (!rootGranted) {
                        requestRootAccess();
                        return;
                    }
                    if (running) {
                        Toast.makeText(this, "请先停止当前模拟", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    rootOnly = !rootOnly;
                    preferences.edit().putBoolean(LocationContract.KEY_ROOT_ONLY, rootOnly).apply();
                    renderState(rootOnly ? "Root 增强已启用" : "已切换到普通模式");
                })
                .show();
    }

    private void requestRootAccess() {
        if (rootCheckInFlight) {
            Toast.makeText(this, "正在等待 Root 授权结果…", Toast.LENGTH_SHORT).show();
            return;
        }
        checkRootAccess(true);
    }

    private void checkRootAccess(boolean showResult) {
        if (rootCheckInFlight) return;
        rootCheckInFlight = true;
        networkExecutor.execute(() -> {
            boolean granted = false;
            String output = "";
            try {
                Process process = new ProcessBuilder("su", "-c", "id")
                        .redirectErrorStream(true)
                        .start();
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                }
                int exitCode = process.waitFor();
                output = response.toString();
                granted = exitCode == 0 && (output.contains("uid=0") || output.contains("uid=root"));
            } catch (Exception exception) {
                output = exception.getClass().getSimpleName();
            }
            boolean hasRoot = granted;
            String rootOutput = output;
            mainHandler.post(() -> {
                rootCheckInFlight = false;
                rootGranted = hasRoot;
                rootProviderAccessReady = hasRoot && isSelectedMockLocationApp();
                if (showResult) {
                    if (hasRoot) {
                        if (!rootOnly) {
                            rootOnly = true;
                            preferences.edit().putBoolean(LocationContract.KEY_ROOT_ONLY, true).apply();
                        }
                        Toast.makeText(this, "Root 权限已获得，可以开始固定位置模拟", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "未获得 Root 权限（su 返回：" + rootOutput + "）", Toast.LENGTH_LONG).show();
                    }
                }
                renderState(hasRoot ? "Root 权限已就绪" : "未检测到 Root 权限");
            });
        });
    }

    private void configureRootProviderAccess() {
        if (rootProviderSetupInFlight) {
            Toast.makeText(this, "正在配置 Root 系统定位通道…", Toast.LENGTH_SHORT).show();
            return;
        }
        rootProviderSetupInFlight = true;
        renderState("正在配置 Root 系统定位通道…");
        networkExecutor.execute(() -> {
            boolean configured = false;
            String output = "";
            try {
                String packageName = getPackageName();
                String command = "appops set " + packageName
                        + " android:mock_location allow || appops set " + packageName
                        + " MOCK_LOCATION allow || appops set " + packageName + " 58 allow";
                Process process = new ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true)
                        .start();
                StringBuilder response = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line).append(' ');
                }
                configured = process.waitFor() == 0;
                output = response.toString().trim();
            } catch (Exception exception) {
                output = exception.getClass().getSimpleName();
            }
            boolean commandSucceeded = configured;
            String commandOutput = output;
            mainHandler.post(() -> {
                rootProviderSetupInFlight = false;
                rootProviderAccessReady = commandSucceeded;
                if (rootProviderAccessReady) {
                    renderState("Root 固定位置通道已配置");
                    beginStartFlow();
                } else {
                    String detail = commandOutput.isEmpty() ? "系统未确认权限" : commandOutput;
                    renderState("Root 系统定位通道配置失败");
                    Toast.makeText(this, "Root 模拟位置权限配置失败：" + detail,
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void beginLocateFlow() {
        if (running) {
            Toast.makeText(this, "当前位置正在被模拟；停止模拟后可获取真实位置", Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_CURRENT_LOCATION);
            return;
        }
        locateCurrentPosition();
    }

    @SuppressLint("MissingPermission") // Called only after ACCESS_FINE_LOCATION is granted.
    private void locateCurrentPosition() {
        finishLocationRequest();
        locateButton.setEnabled(false);
        locateButton.setText("…");

        Location cached = bestLastKnownLocation();
        if (cached != null && System.currentTimeMillis() - cached.getTime() < 120_000L) {
            showDeviceLocation(cached);
            return;
        }

        locationTimeout = () -> {
            Location fallback = bestLastKnownLocation();
            finishLocationRequest();
            if (fallback != null) {
                showDeviceLocation(fallback);
            } else {
                Toast.makeText(this, "暂时无法获取当前位置，请确认系统定位已开启", Toast.LENGTH_LONG).show();
            }
        };
        mainHandler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MS);

        try {
            pendingLocationListener = location -> {
                if (location != null && !location.isFromMockProvider()) {
                    finishLocationRequest();
                    showDeviceLocation(location);
                }
            };
            boolean registered = false;
            String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"};
            for (String provider : providers) {
                try {
                    if (!locationManager.isProviderEnabled(provider)) continue;
                    locationManager.requestLocationUpdates(provider, 0L, 0f, pendingLocationListener, Looper.getMainLooper());
                    registered = true;
                } catch (RuntimeException ignored) {
                }
            }
            if (!registered) {
                throw new IllegalStateException("没有可用的定位服务");
            }
        } catch (RuntimeException exception) {
            finishLocationRequest();
            if (cached != null) {
                showDeviceLocation(cached);
            } else {
                Toast.makeText(this, "无法获取当前位置，请确认系统定位已开启", Toast.LENGTH_LONG).show();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private Location bestLastKnownLocation() {
        Location best = null;
        String[] providers = {
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                "fused",
                LocationManager.PASSIVE_PROVIDER
        };
        for (String provider : providers) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate == null || candidate.isFromMockProvider()) continue;
                if (best == null || candidate.getTime() > best.getTime()) best = candidate;
            } catch (RuntimeException ignored) {
            }
        }
        return best;
    }

    private void showDeviceLocation(Location location) {
        finishLocationRequest();
        selectedPlaceName = "设备当前位置";
        selectedPlaceLatitude = location.getLatitude();
        selectedPlaceLongitude = location.getLongitude();
        mapView.setCenter(location.getLatitude(), location.getLongitude());
        mapView.zoomToAtLeast(16);
        Toast.makeText(this, "已定位到当前位置 · 精度约 " + Math.round(location.getAccuracy()) + " 米", Toast.LENGTH_SHORT).show();
    }

    private void finishLocationRequest() {
        if (locationTimeout != null) {
            mainHandler.removeCallbacks(locationTimeout);
            locationTimeout = null;
        }
        if (pendingLocationListener != null) {
            try {
                locationManager.removeUpdates(pendingLocationListener);
            } catch (RuntimeException ignored) {
            }
            pendingLocationListener = null;
        }
        if (locateButton != null) {
            locateButton.setEnabled(true);
            locateButton.setText("◎");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                beginStartFlow();
            } else {
                Toast.makeText(this, "需要位置权限才能启动 Android 位置前台服务", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_CURRENT_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locateCurrentPosition();
            } else {
                Toast.makeText(this, "需要位置权限才能获取当前位置", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, MockLocationService.class)
                .setAction(action)
                .putExtra(LocationContract.EXTRA_LATITUDE, mapView.getCenterLatitude())
                .putExtra(LocationContract.EXTRA_LONGITUDE, mapView.getCenterLongitude())
                .putExtra(LocationContract.EXTRA_SPEED, 0f)
                .putExtra(LocationContract.EXTRA_BEARING, 0f);
        intent.putExtra(LocationContract.EXTRA_ROOT_ONLY, rootOnly);
        if (LocationContract.ACTION_START.equals(action) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private boolean isSelectedMockLocationApp() {
        AppOpsManager manager = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        int mode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mode = manager.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(),
                    getPackageName()
            );
        } else {
            mode = manager.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(),
                    getPackageName()
            );
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void openDeveloperSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (RuntimeException exception) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void renderState(String message) {
        preferences.edit().putBoolean(LocationContract.KEY_RUNNING, running).apply();
        statusText.setText(running ? "● 运行中" : "● 未运行");
        statusText.setTextColor(running ? COLOR_ACCENT : COLOR_SUBTLE);
        statusText.setBackground(rounded(running ? 0x3322C55E : COLOR_MUTED, 14, running ? COLOR_ACCENT : COLOR_BORDER, 1));
        startButton.setText(running ? "停止模拟" : "开始模拟");
        startButton.setTextColor(running ? Color.WHITE : COLOR_BACKGROUND);
        startButton.setBackground(rounded(running ? COLOR_DANGER : COLOR_ACCENT, 15, Color.TRANSPARENT, 0));
        setupButton.setText(rootOnly && rootGranted ? "Root 模式已配置" : (rootGranted ? "启用 Root 模式" : "申请 Root"));
        if (message != null && (message.startsWith("无法") || message.startsWith("未获得") || message.startsWith("模拟失败"))) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void showHistoryDialog() {
        List<LocationHistoryStore.Entry> entries = historyStore.getEntries();
        if (entries.isEmpty()) {
            Toast.makeText(this, "还没有模拟过的位置", Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView scrollView = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        scrollView.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog[] dialogHolder = new AlertDialog[1];
        SimpleDateFormat timeFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (LocationHistoryStore.Entry entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(8), dp(8));
            row.setBackground(rounded(COLOR_MUTED, 12, COLOR_BORDER, 1));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            String title = entry.name.isEmpty() ? "固定位置" : entry.name;
            labels.addView(label(title, 14, COLOR_TEXT, Typeface.BOLD));
            String detail = String.format(
                    Locale.US,
                    "%.6f, %.6f  ·  %s",
                    entry.latitude,
                    entry.longitude,
                    timeFormat.format(new Date(entry.usedAt))
            );
            labels.addView(label(detail, 11, COLOR_SUBTLE, Typeface.NORMAL));
            row.addView(labels, new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            ));

            Button useButton = createCompactButton("使用", "使用这个历史位置");
            useButton.setOnClickListener(view -> {
                selectHistoryEntry(entry);
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            });
            LinearLayout.LayoutParams useParams = new LinearLayout.LayoutParams(dp(60), dp(38));
            useParams.leftMargin = dp(8);
            row.addView(useButton, useParams);

            Button deleteButton = createCompactButton("删除", "删除这个历史位置");
            deleteButton.setTextColor(COLOR_DANGER);
            deleteButton.setOnClickListener(view -> {
                historyStore.delete(entry.id);
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                showHistoryDialog();
            });
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(60), dp(38));
            deleteParams.leftMargin = dp(6);
            row.addView(deleteButton, deleteParams);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rowParams.bottomMargin = dp(8);
            list.addView(row, rowParams);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("历史位置")
                .setView(scrollView)
                .setNeutralButton("清空", null)
                .setNegativeButton("关闭", null)
                .create();
        dialogHolder[0] = dialog;
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> confirmClearHistory(dialog)));
        dialog.show();
    }

    private void selectHistoryEntry(LocationHistoryStore.Entry entry) {
        selectedPlaceName = entry.name;
        selectedPlaceLatitude = entry.latitude;
        selectedPlaceLongitude = entry.longitude;
        searchInput.setText(entry.name.isEmpty() ? "" : briefName(entry.name));
        mapView.setCenter(entry.latitude, entry.longitude);
        Toast.makeText(this, "已选择历史位置", Toast.LENGTH_SHORT).show();
    }

    private void confirmClearHistory(AlertDialog historyDialog) {
        new AlertDialog.Builder(this)
                .setTitle("清空历史位置？")
                .setMessage("这会删除全部位置记录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    historyStore.clear();
                    historyDialog.dismiss();
                    Toast.makeText(this, "历史位置已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private String historyNameForCurrentCenter() {
        return matchesSelectedPlace(mapView.getCenterLatitude(), mapView.getCenterLongitude())
                ? selectedPlaceName : "";
    }

    private boolean matchesSelectedPlace(double latitude, double longitude) {
        return !selectedPlaceName.isEmpty()
                && Math.abs(latitude - selectedPlaceLatitude) <= 0.00001d
                && Math.abs(longitude - selectedPlaceLongitude) <= 0.00001d;
    }

    private void searchLocation() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            searchInput.setError("请输入地点");
            return;
        }
        searchInput.setEnabled(false);
        searchInput.setHint("正在搜索…");
        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                URL url = new URL("https://photon.komoot.io/api/?q=" + encoded + "&limit=5&lang=zh");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("User-Agent", "DriftLocation/0.1 (Android location testing tool)");
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONArray json = new JSONObject(body.toString()).getJSONArray("features");
                List<SearchResult> results = new ArrayList<>();
                for (int index = 0; index < json.length(); index++) {
                    JSONObject item = json.getJSONObject(index);
                    JSONObject properties = item.getJSONObject("properties");
                    JSONArray coordinates = item.getJSONObject("geometry").getJSONArray("coordinates");
                    results.add(new SearchResult(
                            photonDisplayName(properties, query),
                            coordinates.getDouble(1),
                            coordinates.getDouble(0)
                    ));
                }
                mainHandler.post(() -> showSearchResults(results));
            } catch (Exception exception) {
                mainHandler.post(() -> Toast.makeText(this, "搜索失败，请检查网络或直接输入坐标", Toast.LENGTH_LONG).show());
            } finally {
                if (connection != null) connection.disconnect();
                mainHandler.post(() -> {
                    searchInput.setEnabled(true);
                    searchInput.setHint("搜索城市、街道或地点");
                });
            }
        });
    }

    private void showSearchResults(List<SearchResult> results) {
        if (results.isEmpty()) {
            Toast.makeText(this, "没有找到相关地点", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[results.size()];
        for (int index = 0; index < results.size(); index++) names[index] = results.get(index).name;
        new AlertDialog.Builder(this)
                .setTitle("选择地点")
                .setItems(names, (dialog, which) -> {
                    SearchResult result = results.get(which);
                    selectedPlaceName = result.name;
                    selectedPlaceLatitude = result.latitude;
                    selectedPlaceLongitude = result.longitude;
                    mapView.setCenter(result.latitude, result.longitude);
                    searchInput.setText(briefName(result.name));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCoordinateDialog() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        EditText latitudeField = coordinateField("纬度", mapView.getCenterLatitude());
        EditText longitudeField = coordinateField("经度", mapView.getCenterLongitude());
        fields.addView(latitudeField);
        fields.addView(longitudeField);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("输入经纬度")
                .setView(fields)
                .setNegativeButton("取消", null)
                .setPositiveButton("定位", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                double latitude = Double.parseDouble(latitudeField.getText().toString());
                double longitude = Double.parseDouble(longitudeField.getText().toString());
                if (latitude < -85.0511 || latitude > 85.0511 || longitude < -180 || longitude > 180) {
                    throw new NumberFormatException();
                }
                selectedPlaceName = "";
                mapView.setCenter(latitude, longitude);
                dialog.dismiss();
            } catch (NumberFormatException exception) {
                latitudeField.setError("纬度应在 -85.0511 到 85.0511 之间");
                longitudeField.setError("经度应在 -180 到 180 之间");
            }
        }));
        dialog.show();
    }

    private EditText coordinateField(String hint, double value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(String.format(Locale.US, "%.6f", value));
        field.setSelectAllOnFocus(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return field;
    }

    @SuppressLint({"InlinedApi", "UnspecifiedRegisterReceiverFlag"})
    // The API 25 overload is selected on old devices; the flagged overload is used on API 33+.
    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(LocationContract.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (setupButton != null) {
            setupButton.setText(rootOnly && rootGranted ? "Root 模式已配置" : (rootGranted ? "启用 Root 模式" : "申请 Root"));
        }
    }

    @Override
    protected void onDestroy() {
        finishLocationRequest();
        unregisterReceiver(stateReceiver);
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private Button createActionButton(String text, int background, int foreground) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(foreground);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        button.setMinWidth(dp(44));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(rounded(background, 15, Color.TRANSPARENT, 0));
        return button;
    }

    private Button createCompactButton(String text, String description) {
        Button button = createActionButton(text, COLOR_MUTED, COLOR_TEXT);
        button.setContentDescription(description);
        button.setBackground(rounded(COLOR_MUTED, 12, COLOR_BORDER, 1));
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private TextView label(String text, float sizeSp, int color, int style) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(sizeSp);
        label.setTextColor(color);
        label.setTypeface(Typeface.DEFAULT, style);
        return label;
    }

    private GradientDrawable rounded(int color, float radiusDp, int strokeColor, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeWidthDp > 0) drawable.setStroke(dp(strokeWidthDp), strokeColor);
        return drawable;
    }

    private FrameLayout.LayoutParams frameParams(int width, int height, int gravity,
                                                  int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, gravity);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatCoordinate(double latitude, double longitude) {
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
    }

    private String briefName(String name) {
        int comma = name.indexOf(',');
        return comma > 0 ? name.substring(0, comma) : name;
    }

    private static String photonDisplayName(JSONObject properties, String fallback) {
        String[] keys = {"name", "city", "state", "country"};
        StringBuilder result = new StringBuilder();
        for (String key : keys) {
            String value = properties.optString(key, "").trim();
            if (value.isEmpty() || result.toString().contains(value)) continue;
            if (result.length() > 0) result.append(", ");
            result.append(value);
        }
        return result.length() == 0 ? fallback : result.toString();
    }

    private void writeCoordinatePreferences(double latitude, double longitude) {
        preferences.edit()
                .putLong(LocationContract.KEY_LATITUDE, Double.doubleToRawLongBits(latitude))
                .putLong(LocationContract.KEY_LONGITUDE, Double.doubleToRawLongBits(longitude))
                .apply();
    }

    private double readDoublePreference(String key, double fallback) {
        long bits = preferences.getLong(key, Double.doubleToRawLongBits(fallback));
        return Double.longBitsToDouble(bits);
    }

    private static final class SearchResult {
        final String name;
        final double latitude;
        final double longitude;

        SearchResult(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }
}
