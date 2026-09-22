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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent route editor and playback screen. The existing home screen remains fixed-location only. */
public final class TrajectoryActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 71;
    private static final int COLOR_BACKGROUND = Color.rgb(246, 241, 231);
    private static final int COLOR_CARD = Color.rgb(255, 255, 255);
    private static final int COLOR_MUTED = Color.rgb(240, 243, 241);
    private static final int COLOR_BORDER = Color.rgb(213, 220, 216);
    private static final int COLOR_TEXT = Color.rgb(30, 27, 25);
    private static final int COLOR_SUBTLE = Color.rgb(104, 108, 106);
    private static final int COLOR_ACCENT = Color.rgb(246, 168, 23);
    private static final int COLOR_BLUE = Color.rgb(126, 177, 218);
    private static final int COLOR_TEAL = Color.rgb(42, 126, 136);
    private static final int COLOR_DANGER = Color.rgb(211, 83, 70);
    private static final String FAVORITE_ROUTES_PREFS = "trajectory_favorites";
    private static final String KEY_FAVORITE_ROUTES = "routes_json";
    private static final int MAX_FAVORITE_ROUTES = 30;
    private static final int MAX_FAVORITE_POINTS = 2_000;
    private static final int PAGE_HOME = 0;
    private static final int PAGE_EDITOR = 1;
    private static final int PAGE_DRAW = 2;
    private static final int PAGE_SETTINGS = 3;
    private static final int PAGE_RUNNING = 4;
    private static final int PAGE_FAVORITES = 5;

    private final ArrayList<AmapWebMapView.RoutePoint> routePoints = new ArrayList<>();
    private final ArrayList<ArrayList<AmapWebMapView.RoutePoint>> drawnSegments = new ArrayList<>();
    private final ArrayList<FavoriteRoute> favoriteRoutes = new ArrayList<>();
    private SharedPreferences preferences;
    private SharedPreferences favoritePreferences;
    private AmapWebMapView mapView;
    private TextView routeSummary;
    private TextView routeHint;
    private Button favoriteListButton;
    private TextView playbackStatus;
    private TextView speedText;
    private Button startButton;
    private Button pauseButton;
    private Button loopButton;
    private Button startPointButton;
    private Button endPointButton;
    private Button viaPointButton;
    private Button drawButton;
    private Button fitRouteButton;
    private Button undoSegmentButton;
    private Button editRouteButton;
    private Button playbackSettingsButton;
    private Button moreRouteButton;
    private TextView playbackSummary;
    private LinearLayout panel;
    private LinearLayout editSection;
    private LinearLayout advancedRouteRow;
    private LinearLayout playbackSection;
    private LinearLayout segmentTools;
    private LinearLayout favoriteTools;
    private Button saveFavoriteButton;
    private Button openFavoritesButton;
    private LinearLayout navigationTools;
    private Button planRouteButton;
    private LinearLayout drawingFocusBar;
    private TextView drawingFocusStatus;
    private Button drawingFocusAction;
    private Button drawingFocusExpandButton;
    private Button drawingFocusCollapseButton;
    private final ArrayList<Button> speedButtons = new ArrayList<>();
    private Button customSpeedButton;
    private boolean running;
    private boolean paused;
    private boolean loop;
    private boolean drawingMode;
    private boolean drawingPicking;
    private boolean editingExpanded;
    private boolean playbackExpanded;
    private boolean moreRouteExpanded;
    private boolean drawingFocusExpanded;
    private boolean planningRoute;
    private String routePlanMode = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable routePlanTimeout;
    private FrameLayout redesignedPages;
    private FrameLayout redesignedHomePage;
    private FrameLayout redesignedEditorPage;
    private FrameLayout redesignedDrawPage;
    private FrameLayout redesignedSettingsPage;
    private FrameLayout redesignedRunningPage;
    private FrameLayout redesignedFavoritesPage;
    private TextView redesignedRouteSummary;
    private TextView redesignedRouteMeta;
    private TextView redesignedHomeSummary;
    private TextView redesignedHomeMeta;
    private TextView redesignedHomeRecent;
    private TextView redesignedDrawStatus;
    private TextView redesignedRunningStatus;
    private TextView redesignedRunningMetrics;
    private TextView redesignedRunningProgress;
    private ProgressBar redesignedProgressBar;
    private TextView redesignedSettingsSpeed;
    private TextView redesignedSettingsSummary;
    private TextView redesignedSettingsDetail;
    private LinearLayout redesignedFavoriteList;
    private final ArrayList<Button> redesignedSpeedButtons = new ArrayList<>();
    private Button redesignedPlanButton;
    private Button redesignedDrawButton;
    private Button redesignedNextButton;
    private Button redesignedMoreButton;
    private Button redesignedSettingsStartButton;
    private Button redesignedPauseButton;
    private Button redesignedStopButton;
    private Button redesignedDrawUndoButton;
    private Button redesignedDrawConfirmButton;
    private Button redesignedDrawFinishButton;
    private Button redesignedLoopButton;
    private int redesignedPage = PAGE_EDITOR;
    private int selectionMode = 1;
    private float speedKmh = 5f;
    private boolean receiverRegistered;
    private boolean rootSetupInFlight;
    private double playbackProgress;
    private double playbackLatitude = Double.NaN;
    private double playbackLongitude = Double.NaN;
    private final ExecutorService rootExecutor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver trajectoryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (LocationContract.ACTION_TRAJECTORY_STATE.equals(action)) {
                running = intent.getBooleanExtra(LocationContract.EXTRA_RUNNING, false);
                paused = intent.getBooleanExtra(LocationContract.EXTRA_TRAJECTORY_PAUSED, false);
                double progress = intent.getDoubleExtra(LocationContract.EXTRA_PROGRESS, 0d);
                double latitude = intent.getDoubleExtra(LocationContract.EXTRA_LATITUDE, Double.NaN);
                double longitude = intent.getDoubleExtra(LocationContract.EXTRA_LONGITUDE, Double.NaN);
                playbackProgress = progress;
                playbackLatitude = latitude;
                playbackLongitude = longitude;
                String message = intent.getStringExtra(LocationContract.EXTRA_MESSAGE);
                if (running && Double.isFinite(latitude) && Double.isFinite(longitude)) {
                    playbackStatus.setText(String.format(Locale.US, "%s · %.0f%%\n%.6f, %.6f",
                            paused ? "已暂停" : "轨迹进行中", progress * 100d, latitude, longitude));
                } else {
                    playbackStatus.setText(message == null ? "未运行" : message);
                }
                updatePlaybackButtons();
                return;
            }
            if (LocationContract.ACTION_STATE.equals(action)
                    && !intent.getBooleanExtra(LocationContract.EXTRA_RUNNING, false)
                    && running) {
                running = false;
                paused = false;
                updatePlaybackButtons();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!LicenseManager.isTrajectoryUnlocked(this)) {
            startActivity(new Intent(this, LicenseActivity.class));
            finish();
            return;
        }
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int systemUi = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                systemUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(systemUi);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        preferences = LocationContract.openPreferences(this);
        favoritePreferences = getSharedPreferences(FAVORITE_ROUTES_PREFS, MODE_PRIVATE);
        loadFavoriteRoutes();
        setContentView(buildInterface());
        registerTrajectoryReceiver();

        double latitude = readDoublePreference(LocationContract.KEY_LATITUDE, LocationContract.DEFAULT_LATITUDE);
        double longitude = readDoublePreference(LocationContract.KEY_LONGITUDE, LocationContract.DEFAULT_LONGITUDE);
        routePoints.add(new AmapWebMapView.RoutePoint(latitude, longitude));
        mapView.setOnMapTapListener(this::onMapTap);
        mapView.setOnDrawPathListener(this::onDrawPath);
        mapView.setOnRoutePlanListener(new AmapWebMapView.OnRoutePlanListener() {
            @Override
            public void onRoutePlanned(String mode, List<AmapWebMapView.RoutePoint> points) {
                clearRoutePlanTimeout();
                handleRoutePlanned(mode, points);
            }

            @Override
            public void onRoutePlanFailed(String message) {
                clearRoutePlanTimeout();
                planningRoute = false;
                if (planRouteButton != null) planRouteButton.setText("生成导航线路");
                refreshPanelSections();
                Toast.makeText(TrajectoryActivity.this,
                        message == null ? "导航线路生成失败" : message, Toast.LENGTH_LONG).show();
            }
        });
        mapView.onCreate(savedInstanceState);
        mapView.setCenter(latitude, longitude);
        refreshRoute();
        updatePlaybackButtons();
    }

    private View buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACKGROUND);

        String key = AmapKeyStore.getJsApiKey(this);
        String securityCode = AmapKeyStore.getJsSecurityCode(this);
        mapView = new AmapWebMapView(this, key, securityCode);
        root.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(6), dp(4), dp(6), dp(4));
        topBar.setBackground(rounded(COLOR_CARD, 18, COLOR_BORDER, 2));
        ImageButton backButton = centeredBackButton("返回首页");
        backButton.setOnClickListener(view -> finish());
        topBar.addView(backButton, new LinearLayout.LayoutParams(dp(44), dp(42)));
        TextView title = label("轨迹模拟", 18, COLOR_TEXT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        topBar.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button clearButton = compactButton("清空", "清空轨迹点");
        clearButton.setOnClickListener(view -> clearRoute());
        topBar.addView(clearButton, new LinearLayout.LayoutParams(dp(58), dp(42)));
        FrameLayout.LayoutParams topBarParams = frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52),
                Gravity.TOP, 14, 14, 14, 0);
        root.addView(topBar, topBarParams);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(9), dp(18), dp(14));
        panel.setBackground(rounded(COLOR_CARD, 22, COLOR_BORDER, 2));
        panel.setElevation(dp(8));

        View handle = new View(this);
        handle.setBackground(rounded(COLOR_BORDER, 2, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(7);
        panel.addView(handle, handleParams);

        LinearLayout routeHeader = new LinearLayout(this);
        routeHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView routeTitle = label("路线", 12, COLOR_SUBTLE, Typeface.BOLD);
        routeHeader.addView(routeTitle, new LinearLayout.LayoutParams(dp(42), dp(22)));
        routeSummary = label("起点：当前位置\n点击地图设置终点", 14, COLOR_TEXT, Typeface.NORMAL);
        routeSummary.setLineSpacing(dp(2), 1f);
        routeSummary.setPadding(0, 0, 0, dp(2));
        routeHeader.addView(routeSummary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        favoriteListButton = compactButton(favoriteButtonText(), "打开收藏线路");
        favoriteListButton.setOnClickListener(view -> showFavoriteRoutesDialog());
        routeHeader.addView(favoriteListButton, new LinearLayout.LayoutParams(dp(76), dp(38)));
        panel.addView(routeHeader);

        routeHint = label("路线从当前位置开始，点击编辑路线设置终点", 11, COLOR_SUBTLE, Typeface.NORMAL);
        routeHint.setPadding(0, 0, 0, dp(8));
        panel.addView(routeHint);

        LinearLayout sectionToggleRow = new LinearLayout(this);
        sectionToggleRow.setOrientation(LinearLayout.HORIZONTAL);
        editRouteButton = compactButton("编辑路线", "展开路线编辑工具");
        editRouteButton.setOnClickListener(view -> {
            editingExpanded = !editingExpanded;
            if (editingExpanded) playbackExpanded = false;
            refreshRoute();
            refreshPanelSections();
        });
        sectionToggleRow.addView(editRouteButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        playbackSettingsButton = compactButton("播放设置", "展开播放速度和循环设置");
        playbackSettingsButton.setOnClickListener(view -> {
            playbackExpanded = !playbackExpanded;
            if (playbackExpanded) editingExpanded = false;
            refreshRoute();
            refreshPanelSections();
        });
        LinearLayout.LayoutParams playbackToggleParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        playbackToggleParams.leftMargin = dp(8);
        sectionToggleRow.addView(playbackSettingsButton, playbackToggleParams);
        panel.addView(sectionToggleRow);

        playbackSummary = label("速度 5.0 km/h  ·  到达终点自动停止", 11, COLOR_SUBTLE, Typeface.NORMAL);
        playbackSummary.setGravity(Gravity.CENTER_VERTICAL);
        playbackSummary.setPadding(dp(2), 0, 0, 0);
        LinearLayout.LayoutParams playbackSummaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        panel.addView(playbackSummary, playbackSummaryParams);

        editSection = new LinearLayout(this);
        editSection.setOrientation(LinearLayout.VERTICAL);
        editSection.setPadding(dp(10), dp(9), dp(10), dp(2));
        editSection.setBackground(rounded(COLOR_MUTED, 16, Color.TRANSPARENT, 0));
        LinearLayout editCaptionRow = new LinearLayout(this);
        editCaptionRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView editCaption = label("路线编辑", 12, COLOR_SUBTLE, Typeface.BOLD);
        editCaptionRow.addView(editCaption, new LinearLayout.LayoutParams(0, dp(24), 1f));
        drawingFocusCollapseButton = compactButton("专注绘制", "收起面板进入专注绘制");
        drawingFocusCollapseButton.setTextSize(11);
        drawingFocusCollapseButton.setOnClickListener(view -> {
            if (drawingMode || drawingPicking) {
                drawingFocusExpanded = false;
                refreshPanelSections();
            }
        });
        editCaptionRow.addView(drawingFocusCollapseButton, new LinearLayout.LayoutParams(dp(86), dp(28)));
        editSection.addView(editCaptionRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        LinearLayout primaryRouteRow = new LinearLayout(this);
        primaryRouteRow.setOrientation(LinearLayout.HORIZONTAL);
        startPointButton = compactButton("起点", "设置起点");
        endPointButton = compactButton("终点", "设置终点");
        viaPointButton = compactButton("途经点", "增加途经点");
        drawButton = compactButton("自绘", "确认起点后拖动地图绘制线路");
        startPointButton.setOnClickListener(view -> {
            disableDrawingMode();
            setSelectionMode(0);
        });
        endPointButton.setOnClickListener(view -> {
            disableDrawingMode();
            setSelectionMode(1);
        });
        viaPointButton.setOnClickListener(view -> {
            disableDrawingMode();
            setSelectionMode(2);
        });
        drawButton.setOnClickListener(view -> toggleDrawingMode());
        primaryRouteRow.addView(startPointButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams endParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        endParams.leftMargin = dp(8);
        primaryRouteRow.addView(endPointButton, endParams);
        editSection.addView(primaryRouteRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        moreRouteButton = compactButton("更多路线工具", "显示途经点、自绘和线路操作");
        moreRouteButton.setOnClickListener(view -> {
            moreRouteExpanded = !moreRouteExpanded;
            refreshPanelSections();
        });
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36));
        moreParams.topMargin = dp(7);
        editSection.addView(moreRouteButton, moreParams);

        advancedRouteRow = new LinearLayout(this);
        advancedRouteRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams viaParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        advancedRouteRow.addView(viaPointButton, viaParams);
        LinearLayout.LayoutParams drawParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        drawParams.leftMargin = dp(8);
        advancedRouteRow.addView(drawButton, drawParams);
        LinearLayout.LayoutParams advancedParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        advancedParams.topMargin = dp(7);
        editSection.addView(advancedRouteRow, advancedParams);

        segmentTools = new LinearLayout(this);
        segmentTools.setOrientation(LinearLayout.HORIZONTAL);
        fitRouteButton = compactButton("查看全线", "将地图缩放到完整线路");
        fitRouteButton.setOnClickListener(view -> {
            if (requireAccess()) mapView.fitRoute();
        });
        segmentTools.addView(fitRouteButton, new LinearLayout.LayoutParams(0, dp(38), 1f));
        undoSegmentButton = compactButton("撤销上一段", "撤销最近绘制的线路段");
        undoSegmentButton.setOnClickListener(view -> undoLastSegment());
        LinearLayout.LayoutParams undoParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        undoParams.leftMargin = dp(7);
        segmentTools.addView(undoSegmentButton, undoParams);
        LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        toolsParams.topMargin = dp(7);
        editSection.addView(segmentTools, toolsParams);

        favoriteTools = new LinearLayout(this);
        favoriteTools.setOrientation(LinearLayout.HORIZONTAL);
        saveFavoriteButton = compactButton("收藏线路", "收藏当前自绘线路");
        saveFavoriteButton.setOnClickListener(view -> saveCurrentRouteAsFavorite());
        favoriteTools.addView(saveFavoriteButton, new LinearLayout.LayoutParams(0, dp(38), 1f));
        openFavoritesButton = compactButton("我的收藏", "打开已收藏线路");
        openFavoritesButton.setOnClickListener(view -> showFavoriteRoutesDialog());
        LinearLayout.LayoutParams openFavoritesParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        openFavoritesParams.leftMargin = dp(7);
        favoriteTools.addView(openFavoritesButton, openFavoritesParams);
        LinearLayout.LayoutParams favoriteToolsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        favoriteToolsParams.topMargin = dp(7);
        editSection.addView(favoriteTools, favoriteToolsParams);

        navigationTools = new LinearLayout(this);
        navigationTools.setOrientation(LinearLayout.HORIZONTAL);
        planRouteButton = compactButton("生成导航线路", "使用起点和终点生成导航线路");
        planRouteButton.setOnClickListener(view -> showRoutePlannerDialog());
        navigationTools.addView(planRouteButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)));
        LinearLayout.LayoutParams navigationToolsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        navigationToolsParams.topMargin = dp(7);
        editSection.addView(navigationTools, navigationToolsParams);
        panel.addView(editSection, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        playbackSection = new LinearLayout(this);
        playbackSection.setOrientation(LinearLayout.VERTICAL);
        playbackSection.setPadding(dp(10), dp(9), dp(10), dp(2));
        playbackSection.setBackground(rounded(COLOR_MUTED, 16, Color.TRANSPARENT, 0));
        LinearLayout speedHeader = new LinearLayout(this);
        speedHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView speedCaption = label("播放速度", 12, COLOR_SUBTLE, Typeface.BOLD);
        speedHeader.addView(speedCaption, new LinearLayout.LayoutParams(0, dp(25), 1f));
        speedText = label("5.0 km/h", 13, COLOR_TEXT, Typeface.BOLD);
        speedText.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        speedHeader.addView(speedText, new LinearLayout.LayoutParams(dp(100), dp(25)));
        LinearLayout.LayoutParams speedHeaderParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(25));
        playbackSection.addView(speedHeader, speedHeaderParams);

        HorizontalScrollView speedScroll = new HorizontalScrollView(this);
        speedScroll.setHorizontalScrollBarEnabled(false);
        speedScroll.setClipToPadding(false);
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        addSpeedButton(speedRow, "步行", 5f);
        addSpeedButton(speedRow, "跑步", 10f);
        addSpeedButton(speedRow, "骑行", 20f);
        addSpeedButton(speedRow, "驾车", 60f);
        Button customSpeed = compactButton("自定义", "自定义速度");
        customSpeedButton = customSpeed;
        customSpeed.setOnClickListener(view -> showSpeedDialog());
        LinearLayout.LayoutParams customParams = new LinearLayout.LayoutParams(dp(74), dp(40));
        customParams.leftMargin = dp(6);
        speedRow.addView(customSpeed, customParams);
        speedScroll.addView(speedRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        LinearLayout.LayoutParams speedScrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        speedScrollParams.topMargin = dp(2);
        playbackSection.addView(speedScroll, speedScrollParams);
        selectSpeedButton(speedKmh == 5f ? speedButtons.get(0) : customSpeedButton);

        LinearLayout optionRow = new LinearLayout(this);
        optionRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView optionHint = label("到达终点后自动停止", 11, COLOR_SUBTLE, Typeface.NORMAL);
        optionRow.addView(optionHint, new LinearLayout.LayoutParams(0, dp(38), 1f));
        loopButton = compactButton("循环：关", "切换循环播放");
        loopButton.setOnClickListener(view -> {
            if (!requireAccess()) return;
            loop = !loop;
            loopButton.setText(loop ? "循环：开" : "循环：关");
            refreshPanelSections();
        });
        optionRow.addView(loopButton, new LinearLayout.LayoutParams(dp(94), dp(38)));
        LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        optionParams.topMargin = dp(5);
        playbackSection.addView(optionRow, optionParams);
        panel.addView(playbackSection, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        playbackStatus = label("未运行", 12, COLOR_SUBTLE, Typeface.NORMAL);
        playbackStatus.setGravity(Gravity.CENTER_VERTICAL);
        playbackStatus.setPadding(dp(12), 0, dp(12), 0);
        playbackStatus.setBackground(rounded(Color.rgb(232, 244, 255), 13, COLOR_BLUE, 2));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        statusParams.topMargin = dp(6);
        panel.addView(playbackStatus, statusParams);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        pauseButton = compactButton("暂停", "暂停轨迹");
        pauseButton.setOnClickListener(view -> togglePause());
        actionRow.addView(pauseButton, new LinearLayout.LayoutParams(0, dp(50), 0.72f));
        startButton = createActionButton("开始轨迹", COLOR_ACCENT, COLOR_TEXT);
        startButton.setOnClickListener(view -> {
            if (running) sendTrajectoryAction(LocationContract.ACTION_STOP);
            else startTrajectory();
        });
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, dp(50), 1.28f);
        startParams.leftMargin = dp(9);
        actionRow.addView(startButton, startParams);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        actionParams.topMargin = dp(9);
        panel.addView(actionRow, actionParams);

        FrameLayout.LayoutParams panelParams = frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM, 12, 0, 12, 12);
        root.addView(panel, panelParams);

        drawingFocusBar = new LinearLayout(this);
        drawingFocusBar.setOrientation(LinearLayout.HORIZONTAL);
        drawingFocusBar.setGravity(Gravity.CENTER_VERTICAL);
        drawingFocusBar.setPadding(dp(14), dp(7), dp(10), dp(7));
        drawingFocusBar.setBackground(rounded(COLOR_CARD, 18, COLOR_BORDER, 2));
        drawingFocusBar.setElevation(dp(8));
        drawingFocusStatus = label("自绘中", 12, COLOR_TEXT, Typeface.BOLD);
        drawingFocusStatus.setGravity(Gravity.CENTER_VERTICAL);
        drawingFocusBar.addView(drawingFocusStatus, new LinearLayout.LayoutParams(0, dp(42), 1f));
        drawingFocusAction = compactButton("结束绘制", "结束当前绘制状态");
        drawingFocusAction.setTextSize(12);
        drawingFocusAction.setOnClickListener(view -> toggleDrawingMode());
        drawingFocusBar.addView(drawingFocusAction, new LinearLayout.LayoutParams(dp(100), dp(42)));
        drawingFocusExpandButton = compactButton("展开", "展开完整路线面板");
        drawingFocusExpandButton.setTextSize(12);
        drawingFocusExpandButton.setOnClickListener(view -> {
            drawingFocusExpanded = true;
            refreshPanelSections();
        });
        LinearLayout.LayoutParams focusExpandParams = new LinearLayout.LayoutParams(dp(68), dp(42));
        focusExpandParams.leftMargin = dp(7);
        drawingFocusBar.addView(drawingFocusExpandButton, focusExpandParams);
        FrameLayout.LayoutParams focusBarParams = frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM, 24, 0, 24, 12);
        root.addView(drawingFocusBar, focusBarParams);
        topBar.setVisibility(View.GONE);
        panel.setVisibility(View.GONE);
        drawingFocusBar.setVisibility(View.GONE);
        buildRedesignedPages(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                topInset = insets.getInsets(WindowInsets.Type.systemBars()).top;
            } else {
                topInset = insets.getSystemWindowInsetTop();
            }
            topBarParams.topMargin = topInset + dp(14);
            panelParams.bottomMargin = bottomInset + dp(12);
            focusBarParams.bottomMargin = bottomInset + dp(12);
            if (redesignedPages != null) {
                redesignedPages.setPadding(0, topInset, 0, bottomInset);
            }
            topBar.setLayoutParams(topBarParams);
            panel.setLayoutParams(panelParams);
            drawingFocusBar.setLayoutParams(focusBarParams);
            return insets;
        });
        refreshPanelSections();
        return root;
    }

    /** The route editor is split into focused pages so the map remains visible while editing. */
    private void buildRedesignedPages(FrameLayout root) {
        redesignedPages = new FrameLayout(this);
        redesignedPages.setClickable(false);
        root.addView(redesignedPages, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        redesignedHomePage = new FrameLayout(this);
        redesignedEditorPage = new FrameLayout(this);
        redesignedDrawPage = new FrameLayout(this);
        redesignedSettingsPage = new FrameLayout(this);
        redesignedRunningPage = new FrameLayout(this);
        redesignedFavoritesPage = new FrameLayout(this);
        redesignedPages.addView(redesignedHomePage, fullPageParams());
        redesignedPages.addView(redesignedEditorPage, fullPageParams());
        redesignedPages.addView(redesignedDrawPage, fullPageParams());
        redesignedPages.addView(redesignedSettingsPage, fullPageParams());
        redesignedPages.addView(redesignedRunningPage, fullPageParams());
        redesignedPages.addView(redesignedFavoritesPage, fullPageParams());

        buildRedesignedHomePage();
        buildRedesignedEditorPage();
        buildRedesignedDrawPage();
        buildRedesignedSettingsPage();
        buildRedesignedRunningPage();
        buildRedesignedFavoritesPage();
        goToRedesignedPage(PAGE_HOME);
    }

    private FrameLayout.LayoutParams fullPageParams() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout redesignedToolbar(String titleText, View.OnClickListener backListener) {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(4), dp(8), dp(4));
        toolbar.setBackground(rounded(COLOR_CARD, 18, COLOR_BORDER, 1));
        ImageButton back = centeredBackButton("返回" + titleText);
        back.setOnClickListener(backListener);
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(42)));
        TextView title = label(titleText, 18, COLOR_TEXT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        View spacer = new View(this);
        toolbar.addView(spacer, new LinearLayout.LayoutParams(dp(44), dp(42)));
        return toolbar;
    }

    private LinearLayout redesignedCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(13), dp(18), dp(16));
        card.setBackground(rounded(COLOR_CARD, 24, COLOR_BORDER, 0));
        card.setElevation(dp(8));
        return card;
    }

    private Button redesignedPrimaryButton(String text, String description) {
        Button button = createActionButton(text, COLOR_ACCENT, COLOR_TEXT);
        button.setContentDescription(description);
        button.setMinHeight(dp(48));
        return button;
    }

    private Button redesignedSecondaryButton(String text, String description) {
        Button button = compactButton(text, description);
        button.setMinHeight(dp(46));
        return button;
    }

    private void buildRedesignedHomePage() {
        redesignedHomePage.setBackgroundColor(COLOR_BACKGROUND);
        redesignedHomePage.addView(redesignedToolbar("轨迹模拟", view -> finish()),
                frameParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), Gravity.TOP, 14, 14, 14, 0));
        LinearLayout card = redesignedCard();
        View handle = new View(this);
        handle.setBackground(rounded(COLOR_BORDER, 2, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(42), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(10);
        card.addView(handle, handleParams);
        TextView section = label("当前线路", 12, COLOR_SUBTLE, Typeface.BOLD);
        card.addView(section, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        redesignedHomeSummary = label("起点：当前位置\n终点：未设置", 18, COLOR_TEXT, Typeface.BOLD);
        redesignedHomeSummary.setLineSpacing(dp(2), 1f);
        card.addView(redesignedHomeSummary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));
        redesignedHomeMeta = label("准备新建一条线路", 12, COLOR_SUBTLE, Typeface.NORMAL);
        card.addView(redesignedHomeMeta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        Button newRoute = redesignedPrimaryButton("新建线路", "新建线路");
        newRoute.setOnClickListener(view -> {
            clearRoute();
            goToRedesignedPage(PAGE_EDITOR);
        });
        card.addView(newRoute, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        TextView recentLabel = label("最近线路", 12, COLOR_SUBTLE, Typeface.BOLD);
        LinearLayout.LayoutParams recentLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24));
        recentLabelParams.topMargin = dp(10);
        card.addView(recentLabel, recentLabelParams);
        redesignedHomeRecent = label("暂无最近线路\n完成路线后会显示在这里", 13, COLOR_SUBTLE, Typeface.NORMAL);
        redesignedHomeRecent.setLineSpacing(dp(2), 1f);
        card.addView(redesignedHomeRecent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        Button continueEdit = redesignedSecondaryButton("继续编辑", "继续编辑当前线路");
        continueEdit.setOnClickListener(view -> goToRedesignedPage(PAGE_EDITOR));
        Button favorites = redesignedSecondaryButton("我的收藏", "打开收藏线路");
        favorites.setOnClickListener(view -> goToRedesignedPage(PAGE_FAVORITES));
        quickRow.addView(continueEdit, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout.LayoutParams favoritesParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        favoritesParams.leftMargin = dp(8);
        quickRow.addView(favorites, favoritesParams);
        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        quickParams.topMargin = dp(9);
        card.addView(quickRow, quickParams);
        redesignedHomePage.addView(card, frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP, 14, 82, 14, 14));
    }

    private void buildRedesignedEditorPage() {
        redesignedEditorPage.addView(redesignedToolbar("路线编辑", view -> goToRedesignedPage(PAGE_HOME)),
                frameParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), Gravity.TOP, 14, 14, 14, 0));

        LinearLayout card = redesignedCard();
        card.setPadding(dp(16), dp(11), dp(16), dp(14));
        View handle = new View(this);
        handle.setBackground(rounded(COLOR_BORDER, 2, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(42), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(10);
        card.addView(handle, handleParams);

        redesignedRouteSummary = label("起点：当前位置\n终点：未设置", 15, COLOR_TEXT, Typeface.BOLD);
        redesignedRouteSummary.setLineSpacing(dp(2), 1f);
        card.addView(redesignedRouteSummary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        redesignedRouteMeta = label("选择起点和终点，或进入自绘线路", 12, COLOR_SUBTLE, Typeface.NORMAL);
        card.addView(redesignedRouteMeta, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(25)));

        LinearLayout pointRow = new LinearLayout(this);
        pointRow.setOrientation(LinearLayout.HORIZONTAL);
        Button startPoint = redesignedSecondaryButton("设置起点", "设置线路起点");
        startPoint.setOnClickListener(view -> {
            disableDrawingMode();
            setSelectionMode(0);
            Toast.makeText(this, "拖动地图后点击地图设置起点", Toast.LENGTH_SHORT).show();
        });
        Button endPoint = redesignedSecondaryButton("设置终点", "设置线路终点");
        endPoint.setOnClickListener(view -> {
            disableDrawingMode();
            setSelectionMode(1);
            Toast.makeText(this, "拖动地图后点击地图设置终点", Toast.LENGTH_SHORT).show();
        });
        pointRow.addView(startPoint, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams endPointParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        endPointParams.leftMargin = dp(8);
        pointRow.addView(endPoint, endPointParams);
        LinearLayout.LayoutParams pointRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        pointRowParams.topMargin = dp(4);
        card.addView(pointRow, pointRowParams);

        redesignedNextButton = redesignedPrimaryButton("下一步：播放设置", "路线准备完成，打开播放设置");
        redesignedNextButton.setOnClickListener(view -> goToRedesignedPage(PAGE_SETTINGS));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        nextParams.topMargin = dp(9);
        card.addView(redesignedNextButton, nextParams);

        LinearLayout routeActions = new LinearLayout(this);
        routeActions.setOrientation(LinearLayout.HORIZONTAL);
        redesignedDrawButton = redesignedSecondaryButton("自绘线路", "进入全屏自绘线路");
        redesignedDrawButton.setOnClickListener(view -> startRedesignedDrawing());
        redesignedPlanButton = redesignedSecondaryButton("生成导航线路", "使用起点和终点生成导航线路");
        redesignedPlanButton.setOnClickListener(view -> showRoutePlannerDialog());
        routeActions.addView(redesignedDrawButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams planParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        planParams.leftMargin = dp(8);
        routeActions.addView(redesignedPlanButton, planParams);
        LinearLayout.LayoutParams routeActionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        routeActionsParams.topMargin = dp(8);
        card.addView(routeActions, routeActionsParams);

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        Button via = redesignedSecondaryButton("途经点", "增加途经点");
        via.setOnClickListener(view -> {
            disableDrawingMode();
            setSelectionMode(2);
            Toast.makeText(this, "点击地图添加途经点", Toast.LENGTH_SHORT).show();
        });
        Button undo = redesignedSecondaryButton("撤销上一段", "撤销最近绘制的线路段");
        undo.setOnClickListener(view -> undoLastSegment());
        utilityRow.addView(via, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams undoParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        undoParams.leftMargin = dp(8);
        utilityRow.addView(undo, undoParams);
        LinearLayout.LayoutParams utilityParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        utilityParams.topMargin = dp(8);
        card.addView(utilityRow, utilityParams);

        LinearLayout footerRow = new LinearLayout(this);
        footerRow.setOrientation(LinearLayout.HORIZONTAL);
        Button save = redesignedSecondaryButton("收藏线路", "收藏当前线路");
        save.setOnClickListener(view -> saveCurrentRouteAsFavorite());
        Button more = redesignedSecondaryButton("更多", "打开更多路线工具");
        redesignedMoreButton = more;
        more.setOnClickListener(view -> showEditorMoreDialog());
        footerRow.addView(save, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        moreParams.leftMargin = dp(8);
        footerRow.addView(more, moreParams);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        footerParams.topMargin = dp(8);
        card.addView(footerRow, footerParams);

        redesignedEditorPage.addView(card, frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM, 14, 0, 14, 14));
    }

    private void buildRedesignedDrawPage() {
        redesignedDrawPage.addView(redesignedToolbar("自绘线路", view -> finishRedesignedDrawing()),
                frameParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), Gravity.TOP, 14, 14, 14, 0));
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(16), 0, dp(16), 0);
        status.setBackground(rounded(COLOR_CARD, 17, COLOR_BORDER, 2));
        redesignedDrawStatus = label("拖动地图，准星位置就是线路点", 12, COLOR_TEXT, Typeface.BOLD);
        redesignedDrawStatus.setGravity(Gravity.CENTER_VERTICAL);
        status.addView(redesignedDrawStatus, new LinearLayout.LayoutParams(0, dp(42), 1f));
        redesignedDrawPage.addView(status, frameParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                28, 82, 28, 0));

        LinearLayout bottom = redesignedCard();
        bottom.setPadding(dp(12), dp(9), dp(12), dp(12));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        redesignedDrawUndoButton = redesignedSecondaryButton("撤销上一段", "撤销最近绘制的线路段");
        redesignedDrawUndoButton.setOnClickListener(view -> undoLastSegment());
        redesignedDrawConfirmButton = redesignedSecondaryButton("继续绘制", "继续自绘线路");
        redesignedDrawConfirmButton.setOnClickListener(view -> {
            if (drawingPicking) {
                toggleDrawingMode();
            } else if (!drawingMode) {
                toggleDrawingMode();
            } else {
                Toast.makeText(this, "拖动地图继续绘制线路", Toast.LENGTH_SHORT).show();
            }
        });
        redesignedDrawFinishButton = redesignedPrimaryButton("完成绘制", "完成自绘线路");
        redesignedDrawFinishButton.setOnClickListener(view -> finishRedesignedDrawing());
        actions.addView(redesignedDrawUndoButton, new LinearLayout.LayoutParams(0, dp(46), 1f));
        LinearLayout.LayoutParams continueParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        continueParams.leftMargin = dp(7);
        actions.addView(redesignedDrawConfirmButton, continueParams);
        LinearLayout.LayoutParams drawFinishParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        drawFinishParams.leftMargin = dp(7);
        actions.addView(redesignedDrawFinishButton, drawFinishParams);
        bottom.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        redesignedDrawPage.addView(bottom, frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(82), Gravity.BOTTOM, 14, 0, 14, 14));
    }

    private void buildRedesignedSettingsPage() {
        redesignedSettingsPage.addView(redesignedToolbar("播放设置", view -> goToRedesignedPage(PAGE_EDITOR)),
                frameParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), Gravity.TOP, 14, 14, 14, 0));
        redesignedSettingsPage.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout card = redesignedCard();
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        TextView previewLabel = label("路线预览", 12, COLOR_SUBTLE, Typeface.BOLD);
        card.addView(previewLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(dp(14), dp(9), dp(14), dp(9));
        preview.setBackground(rounded(Color.rgb(234, 243, 255), 14, COLOR_BLUE, 1));
        redesignedSettingsSummary = label("起点 → 终点", 16, COLOR_TEXT, Typeface.BOLD);
        preview.addView(redesignedSettingsSummary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        redesignedSettingsDetail = label("距离待计算 · 预计用时待计算", 12, COLOR_SUBTLE, Typeface.NORMAL);
        preview.addView(redesignedSettingsDetail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        previewParams.topMargin = dp(3);
        card.addView(preview, previewParams);
        redesignedSettingsSpeed = label("5.0 km/h", 28, COLOR_TEXT, Typeface.BOLD);
        redesignedSettingsSpeed.setGravity(Gravity.CENTER);
        card.addView(redesignedSettingsSpeed, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        TextView speedLabel = label("模拟速度", 12, COLOR_SUBTLE, Typeface.BOLD);
        card.addView(speedLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        addRedesignedSpeedButton(speedRow, "5", 5f);
        addRedesignedSpeedButton(speedRow, "10", 10f);
        addRedesignedSpeedButton(speedRow, "20", 20f);
        addRedesignedSpeedButton(speedRow, "自定义", -1f);
        card.addView(speedRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        redesignedLoopButton = redesignedSecondaryButton("循环播放：关", "切换循环播放");
        redesignedLoopButton.setOnClickListener(view -> {
            if (!requireAccess()) return;
            loop = !loop;
            refreshRedesignedPages();
        });
        LinearLayout.LayoutParams loopParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        loopParams.topMargin = dp(10);
        card.addView(redesignedLoopButton, loopParams);
        redesignedSettingsStartButton = redesignedPrimaryButton("开始模拟", "开始轨迹模拟");
        redesignedSettingsStartButton.setOnClickListener(view -> startTrajectory());
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        startParams.topMargin = dp(12);
        card.addView(redesignedSettingsStartButton, startParams);
        redesignedSettingsPage.addView(card, frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP, 14, 82, 14, 14));
    }

    private void buildRedesignedRunningPage() {
        redesignedRunningPage.addView(redesignedToolbar("正在模拟", view -> {
            if (running) Toast.makeText(this, "请先停止轨迹", Toast.LENGTH_SHORT).show();
            else goToRedesignedPage(PAGE_EDITOR);
        }), frameParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), Gravity.TOP, 14, 14, 14, 0));
        // The running page is a map-first screen. Keep its root transparent so
        // the WebView remains visible behind the status toolbar and bottom card.
        LinearLayout card = redesignedCard();
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(rounded(Color.rgb(34, 181, 115), 20, Color.TRANSPARENT, 0));
        stateRow.addView(dot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        redesignedRunningStatus = label("进行中", 21, Color.rgb(22, 150, 91), Typeface.BOLD);
        redesignedRunningStatus.setPadding(dp(8), 0, 0, 0);
        stateRow.addView(redesignedRunningStatus, new LinearLayout.LayoutParams(0, dp(32), 1f));
        card.addView(stateRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        redesignedRunningMetrics = label("速度 --\n已用时间 -- · 剩余距离 --", 14, COLOR_TEXT, Typeface.BOLD);
        redesignedRunningMetrics.setLineSpacing(dp(2), 1f);
        card.addView(redesignedRunningMetrics, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        redesignedRunningProgress = label("0%", 12, COLOR_SUBTLE, Typeface.BOLD);
        redesignedRunningProgress.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        redesignedProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        redesignedProgressBar.setMax(1000);
        redesignedProgressBar.setProgress(0);
        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.addView(redesignedProgressBar, new LinearLayout.LayoutParams(0, dp(10), 1f));
        progressRow.addView(redesignedRunningProgress, new LinearLayout.LayoutParams(dp(44), dp(28)));
        LinearLayout.LayoutParams progressRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        progressRowParams.topMargin = dp(5);
        card.addView(progressRow, progressRowParams);
        redesignedPauseButton = redesignedSecondaryButton("暂停", "暂停轨迹");
        redesignedPauseButton.setOnClickListener(view -> togglePause());
        redesignedStopButton = redesignedPrimaryButton("停止模拟", "停止轨迹模拟");
        redesignedStopButton.setOnClickListener(view -> {
            sendTrajectoryAction(LocationContract.ACTION_STOP);
            goToRedesignedPage(PAGE_EDITOR);
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(redesignedPauseButton, new LinearLayout.LayoutParams(0, dp(50), 1f));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(50), 1f);
        stopParams.leftMargin = dp(8);
        actions.addView(redesignedStopButton, stopParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        actionsParams.topMargin = dp(12);
        card.addView(actions, actionsParams);
        redesignedRunningPage.addView(card, frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM, 14, 0, 14, 14));
    }

    private void buildRedesignedFavoritesPage() {
        redesignedFavoritesPage.addView(redesignedToolbar("我的收藏", view -> goToRedesignedPage(PAGE_EDITOR)),
                frameParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), Gravity.TOP, 14, 14, 14, 0));
        redesignedFavoritesPage.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout card = redesignedCard();
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView allTab = label("全部", 14, COLOR_TEXT, Typeface.BOLD);
        tabRow.addView(allTab, new LinearLayout.LayoutParams(0, dp(32), 1f));
        TextView drawTab = label("自绘线路", 13, COLOR_SUBTLE, Typeface.NORMAL);
        tabRow.addView(drawTab, new LinearLayout.LayoutParams(0, dp(32), 1f));
        TextView planTab = label("导航线路", 13, COLOR_SUBTLE, Typeface.NORMAL);
        tabRow.addView(planTab, new LinearLayout.LayoutParams(0, dp(32), 1f));
        card.addView(tabRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));
        redesignedFavoriteList = new LinearLayout(this);
        redesignedFavoriteList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(redesignedFavoriteList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Button newRoute = redesignedPrimaryButton("新建线路", "新建一条线路");
        newRoute.setOnClickListener(view -> {
            clearRoute();
            goToRedesignedPage(PAGE_EDITOR);
        });
        LinearLayout.LayoutParams newRouteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        newRouteParams.topMargin = dp(10);
        card.addView(newRoute, newRouteParams);
        redesignedFavoritesPage.addView(card, frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP, 14, 82, 14, 14));
    }

    private void addRedesignedSpeedButton(LinearLayout row, String text, float value) {
        Button button = redesignedSecondaryButton(text, text + " km/h");
        button.setTag(value);
        button.setTextSize(13);
        button.setOnClickListener(view -> {
            if (value < 0f) {
                showSpeedDialog();
            } else {
                speedKmh = value;
                refreshRedesignedPages();
            }
        });
        redesignedSpeedButtons.add(button);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        if (row.getChildCount() > 0) params.leftMargin = dp(6);
        row.addView(button, params);
    }

    private void startRedesignedDrawing() {
        if (!requireFeature(LicenseManager.FEATURE_TRAJECTORY, "轨迹模拟")) return;
        if (running) {
            Toast.makeText(this, "轨迹运行中，请先停止后再编辑", Toast.LENGTH_SHORT).show();
            return;
        }
        goToRedesignedPage(PAGE_DRAW);
        toggleDrawingMode();
    }

    private void finishRedesignedDrawing() {
        if (drawingMode) toggleDrawingMode();
        else if (drawingPicking) disableDrawingMode();
        goToRedesignedPage(PAGE_EDITOR);
    }

    private void goToRedesignedPage(int page) {
        if (page == PAGE_FAVORITES
                && !requireFeature(LicenseManager.FEATURE_FAVORITE, "线路收藏")) return;
        redesignedPage = page;
        if (redesignedEditorPage == null) return;
        // Keep the WebView measured at full size while pages cover it. AMap can
        // initialize only after it has a non-zero viewport (important on Android 7).
        mapView.setVisibility(View.VISIBLE);
        redesignedHomePage.setVisibility(page == PAGE_HOME ? View.VISIBLE : View.GONE);
        redesignedEditorPage.setVisibility(page == PAGE_EDITOR ? View.VISIBLE : View.GONE);
        redesignedDrawPage.setVisibility(page == PAGE_DRAW ? View.VISIBLE : View.GONE);
        redesignedSettingsPage.setVisibility(page == PAGE_SETTINGS ? View.VISIBLE : View.GONE);
        redesignedRunningPage.setVisibility(page == PAGE_RUNNING ? View.VISIBLE : View.GONE);
        redesignedFavoritesPage.setVisibility(page == PAGE_FAVORITES ? View.VISIBLE : View.GONE);
        if (page == PAGE_FAVORITES) refreshRedesignedFavoriteList();
        if (page == PAGE_DRAW) {
            mapView.setCenterCrosshairVisible(false);
            mapView.setRouteMarkersVisible(false);
        } else if (!drawingMode && !drawingPicking) {
            mapView.setCenterCrosshairVisible(true);
            mapView.setRouteMarkersVisible(true);
        }
        refreshRedesignedPages();
    }

    private void showEditorMoreDialog() {
        if (!requireAccess()) return;
        String[] actions = {"查看全线", "清空当前线路", "打开我的收藏"};
        new AlertDialog.Builder(this)
                .setTitle("更多路线工具")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        mapView.fitRoute();
                    } else if (which == 1) {
                        clearRoute();
                    } else {
                        goToRedesignedPage(PAGE_FAVORITES);
                    }
                })
                .show();
    }

    private void confirmDeleteFavoriteInline(FavoriteRoute favorite) {
        if (!requireAccess()) return;
        new AlertDialog.Builder(this)
                .setTitle("删除收藏")
                .setMessage("确定删除“" + favorite.name + "”？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    favoriteRoutes.remove(favorite);
                    persistFavoriteRoutes();
                    refreshRedesignedFavoriteList();
                    refreshPanelSections();
                })
                .show();
    }

    private void refreshRedesignedPages() {
        if (redesignedRouteSummary != null) {
            StringBuilder text = new StringBuilder();
            if (routePoints.isEmpty()) text.append("起点：未设置");
            else text.append("起点：").append(format(routePoints.get(0)));
            if (routePoints.size() > 1) text.append("\n终点：").append(format(routePoints.get(routePoints.size() - 1)));
            else text.append("\n终点：未设置");
            redesignedRouteSummary.setText(text.toString());
        }
        if (redesignedRouteMeta != null) {
            if (routePoints.size() < 2) {
                redesignedRouteMeta.setText("选择起点和终点，或进入自绘线路");
            } else {
                String mode = routePlanMode.isEmpty() ? "自定义线路" : routePlanMode + "导航线路";
                redesignedRouteMeta.setText(mode + " · " + formatDistance(routeDistanceMeters(routePoints))
                        + (routePoints.size() > 2 ? " · 途经点 " + (routePoints.size() - 2) + " 个" : ""));
            }
        }
        if (redesignedHomeSummary != null) {
            StringBuilder homeText = new StringBuilder();
            if (routePoints.isEmpty()) homeText.append("起点：未设置");
            else homeText.append("起点：").append(format(routePoints.get(0)));
            if (routePoints.size() > 1) homeText.append("\n终点：").append(format(routePoints.get(routePoints.size() - 1)));
            else homeText.append("\n终点：未设置");
            redesignedHomeSummary.setText(homeText.toString());
        }
        if (redesignedHomeMeta != null) {
            redesignedHomeMeta.setText(routePoints.size() >= 2
                    ? (routePlanMode.isEmpty() ? "线路已准备好 · " + formatDistance(routeDistanceMeters(routePoints))
                    : "已生成" + routePlanMode + "导航线路 · " + formatDistance(routeDistanceMeters(routePoints)))
                    : "准备新建一条线路");
        }
        if (redesignedHomeRecent != null) {
            if (favoriteRoutes.isEmpty()) {
                redesignedHomeRecent.setText("暂无最近线路\n完成路线后会显示在这里");
            } else {
                StringBuilder recent = new StringBuilder();
                int count = Math.min(3, favoriteRoutes.size());
                for (int index = 0; index < count; index++) {
                    if (index > 0) recent.append('\n');
                    FavoriteRoute favorite = favoriteRoutes.get(index);
                    recent.append(favorite.name).append("  ·  ")
                            .append(formatDistance(routeDistanceMeters(favorite.points)));
                }
                redesignedHomeRecent.setText(recent.toString());
            }
        }
        if (redesignedNextButton != null) {
            boolean ready = !running && !drawingMode && !drawingPicking && routePoints.size() >= 2;
            redesignedNextButton.setEnabled(ready);
            redesignedNextButton.setAlpha(ready ? 1f : 0.55f);
        }
        if (redesignedPlanButton != null) {
            boolean enabled = !running && !drawingMode && !drawingPicking
                    && !planningRoute && routePoints.size() >= 2;
            redesignedPlanButton.setEnabled(enabled);
            redesignedPlanButton.setAlpha(enabled ? 1f : 0.55f);
            if (!planningRoute) redesignedPlanButton.setText("生成导航线路");
        }
        if (redesignedSettingsSpeed != null) {
            redesignedSettingsSpeed.setText(String.format(Locale.US, "%.1f km/h", speedKmh));
        }
        if (redesignedSettingsSummary != null) {
            redesignedSettingsSummary.setText(routePoints.size() >= 2
                    ? (routePlanMode.isEmpty() ? "自定义线路" : routePlanMode + "导航线路")
                    : "尚未完成路线");
        }
        if (redesignedSettingsDetail != null) {
            double meters = routeDistanceMeters(routePoints);
            redesignedSettingsDetail.setText(routePoints.size() >= 2
                    ? formatDistance(meters) + " · 预计用时 " + estimateDuration(meters, speedKmh)
                    : "先返回路线编辑完成起点和终点");
        }
        if (redesignedSettingsStartButton != null) {
            boolean canStart = !running && routePoints.size() >= 2;
            redesignedSettingsStartButton.setEnabled(canStart);
            redesignedSettingsStartButton.setAlpha(canStart ? 1f : 0.55f);
        }
        if (redesignedLoopButton != null) {
            redesignedLoopButton.setText(loop ? "循环播放：开" : "循环播放：关");
        }
        for (Button button : redesignedSpeedButtons) {
            Object tag = button.getTag();
            boolean active = tag instanceof Float && ((Float) tag) >= 0f
                    && Math.abs(((Float) tag) - speedKmh) < 0.01f;
            button.setBackground(rounded(active ? COLOR_ACCENT : COLOR_MUTED, 12,
                    active ? Color.TRANSPARENT : COLOR_BORDER, active ? 0 : 1));
        }
        if (redesignedDrawStatus != null) {
            redesignedDrawStatus.setText(drawingPicking
                    ? "拖动地图调整起点 · 确认后开始绘制"
                    : "已绘制 " + drawnSegments.size() + " 段 · 拖动地图继续绘制");
        }
        if (redesignedDrawConfirmButton != null) {
            redesignedDrawConfirmButton.setText(drawingPicking ? "确认起点" : "继续绘制");
            redesignedDrawFinishButton.setEnabled(drawingMode || drawingPicking || !drawnSegments.isEmpty());
            redesignedDrawUndoButton.setEnabled(!drawnSegments.isEmpty());
            redesignedDrawUndoButton.setAlpha(drawnSegments.isEmpty() ? 0.55f : 1f);
        }
        if (redesignedRunningStatus != null) {
            redesignedRunningStatus.setText(running ? (paused ? "已暂停" : "进行中") : "未运行");
        }
        if (redesignedRunningMetrics != null) {
            double routeMeters = routeDistanceMeters(routePoints);
            double travelled = routeMeters * Math.max(0d, Math.min(1d, playbackProgress));
            redesignedRunningMetrics.setText(String.format(Locale.US,
                    "速度 %.1f km/h\n已用距离 %s · 剩余距离 %s",
                    speedKmh, formatDistance(travelled), formatDistance(Math.max(0d, routeMeters - travelled))));
        }
        if (redesignedRunningProgress != null) {
            redesignedRunningProgress.setText(String.format(Locale.US, "%.0f%%", playbackProgress * 100d));
        }
        if (redesignedProgressBar != null) {
            redesignedProgressBar.setProgress((int) Math.round(Math.max(0d, Math.min(1d, playbackProgress)) * 1000d));
        }
        if (redesignedPauseButton != null) {
            redesignedPauseButton.setEnabled(running);
            redesignedPauseButton.setText(paused ? "继续" : "暂停");
            redesignedPauseButton.setAlpha(running ? 1f : 0.55f);
        }
    }

    private void refreshRedesignedFavoriteList() {
        if (redesignedFavoriteList == null) return;
        redesignedFavoriteList.removeAllViews();
        if (favoriteRoutes.isEmpty()) {
            TextView empty = label("还没有收藏线路\n完成线路后可以在这里快速载入", 14, COLOR_SUBTLE, Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            redesignedFavoriteList.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
            return;
        }
        for (FavoriteRoute favorite : new ArrayList<>(favoriteRoutes)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(9), dp(8), dp(9));
            row.setBackground(rounded(COLOR_MUTED, 14, COLOR_BORDER, 1));
            TextView info = label(favorite.name + "\n"
                            + formatDistance(routeDistanceMeters(favorite.points)) + " · 自绘线路 · "
                            + android.text.format.DateFormat.format("yyyy/MM/dd", new Date(favorite.createdAt)),
                    13, COLOR_TEXT, Typeface.BOLD);
            info.setLineSpacing(dp(2), 1f);
            row.addView(info, new LinearLayout.LayoutParams(0, dp(58), 1f));
            Button load = compactButton("载入", "载入" + favorite.name);
            load.setOnClickListener(view -> {
                loadFavoriteRoute(favorite);
                goToRedesignedPage(PAGE_EDITOR);
            });
            row.addView(load, new LinearLayout.LayoutParams(dp(58), dp(40)));
            Button delete = compactButton("删除", "删除" + favorite.name);
            delete.setTextColor(COLOR_DANGER);
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(58), dp(40));
            deleteParams.leftMargin = dp(6);
            row.addView(delete, deleteParams);
            delete.setOnClickListener(view -> {
                confirmDeleteFavoriteInline(favorite);
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(7);
            redesignedFavoriteList.addView(row, rowParams);
        }
    }

    private void refreshPanelSections() {
        if (editRouteButton == null) return;
        boolean editing = editingExpanded && !running;
        boolean playback = playbackExpanded && !running && !drawingMode && !drawingPicking;
        editSection.setVisibility(editing ? View.VISIBLE : View.GONE);
        playbackSection.setVisibility(playback ? View.VISIBLE : View.GONE);
        advancedRouteRow.setVisibility(editing && moreRouteExpanded ? View.VISIBLE : View.GONE);
        if (segmentTools != null) {
            boolean showSegmentTools = editing && (routePoints.size() > 1 || !drawnSegments.isEmpty());
            segmentTools.setVisibility(showSegmentTools ? View.VISIBLE : View.GONE);
        }
        if (favoriteTools != null) {
            favoriteTools.setVisibility(editing ? View.VISIBLE : View.GONE);
        }
        if (navigationTools != null) {
            navigationTools.setVisibility(editing ? View.VISIBLE : View.GONE);
        }
        if (saveFavoriteButton != null) {
            boolean canSave = editing && !running && !drawingMode && !drawingPicking
                    && drawnSegments.size() > 0 && routePoints.size() > 1;
            saveFavoriteButton.setEnabled(canSave);
            saveFavoriteButton.setAlpha(canSave ? 1f : 0.55f);
        }
        if (planRouteButton != null) {
            boolean canPlan = editing && !running && !drawingMode && !drawingPicking
                    && !planningRoute && routePoints.size() >= 2;
            planRouteButton.setEnabled(canPlan);
            planRouteButton.setAlpha(canPlan ? 1f : 0.55f);
            if (!planningRoute) planRouteButton.setText("生成导航线路");
        }
        if (favoriteListButton != null) {
            favoriteListButton.setText(favoriteButtonText());
        }
        moreRouteButton.setText(moreRouteExpanded ? "收起更多工具" : "更多路线工具");
        editRouteButton.setText(editing ? "收起编辑" : "编辑路线");
        playbackSettingsButton.setText(playback ? "收起设置" : "播放设置");
        if (playbackSummary != null) {
            playbackSummary.setText(String.format(Locale.US, "速度 %.1f km/h  ·  到达终点%s",
                    speedKmh, loop ? "循环播放" : "自动停止"));
        }
        refreshDrawingFocusMode();
    }

    private void refreshDrawingFocusMode() {
        if (panel == null || drawingFocusBar == null) return;
        boolean drawing = drawingMode || drawingPicking;
        if (redesignedPages != null) {
            mapView.setCenterCrosshairVisible(!drawing);
            mapView.setRouteMarkersVisible(!drawing);
            refreshRedesignedPages();
            return;
        }
        boolean focus = drawing && !drawingFocusExpanded;
        if (mapView != null) {
            mapView.setCenterCrosshairVisible(!drawing);
            mapView.setRouteMarkersVisible(!drawing);
        }
        panel.setVisibility(focus ? View.GONE : View.VISIBLE);
        drawingFocusBar.setVisibility(focus ? View.VISIBLE : View.GONE);
        if (drawingFocusStatus != null) {
            drawingFocusStatus.setText(drawingPicking
                    ? "调整起点 · 拖动地图定位准星"
                    : "自绘中 · 已完成 " + drawnSegments.size() + " 段");
        }
        if (drawingFocusAction != null) {
            drawingFocusAction.setText(drawingPicking ? "确认起点" : "结束绘制");
            drawingFocusAction.setContentDescription(drawingPicking ? "确认自绘起点" : "结束当前绘制");
        }
        if (drawingFocusCollapseButton != null) {
            drawingFocusCollapseButton.setVisibility(drawing && drawingFocusExpanded
                    ? View.VISIBLE : View.GONE);
        }
    }

    private void addSpeedButton(LinearLayout row, String name, float value) {
        Button button = compactButton(name, name + "速度");
        button.setTag(value);
        speedButtons.add(button);
        button.setOnClickListener(view -> {
            speedKmh = value;
            speedText.setText(String.format(Locale.US, "%.1f km/h", speedKmh));
            selectSpeedButton(button);
            refreshPanelSections();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(68), dp(40));
        params.leftMargin = dp(5);
        row.addView(button, params);
    }

    private void selectSpeedButton(Button selected) {
        for (Button button : speedButtons) {
            boolean active = button == selected;
            button.setTextColor(COLOR_TEXT);
            button.setBackground(rounded(active ? COLOR_ACCENT : COLOR_MUTED, 12,
                    active ? Color.TRANSPARENT : COLOR_BORDER, active ? 0 : 1));
        }
        if (customSpeedButton != null) {
            boolean active = customSpeedButton == selected;
            customSpeedButton.setTextColor(COLOR_TEXT);
            customSpeedButton.setBackground(rounded(active ? COLOR_ACCENT : COLOR_MUTED, 12,
                    active ? Color.TRANSPARENT : COLOR_BORDER, active ? 0 : 1));
        }
    }

    private void onMapTap(double latitude, double longitude) {
        if (!LicenseManager.isTrajectoryUnlocked(this)) return;
        if (redesignedPage != PAGE_EDITOR) return;
        if (running || drawingMode || drawingPicking) {
            if (running) {
            Toast.makeText(this, "轨迹运行中，请先停止后再编辑", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!drawnSegments.isEmpty()) drawnSegments.clear();
        routePlanMode = "";
        AmapWebMapView.RoutePoint point = new AmapWebMapView.RoutePoint(latitude, longitude);
        if (selectionMode == 0) {
            if (routePoints.isEmpty()) routePoints.add(point);
            else routePoints.set(0, point);
            selectionMode = 1;
        } else if (selectionMode == 1) {
            if (routePoints.size() < 2) routePoints.add(point);
            else routePoints.set(routePoints.size() - 1, point);
        } else {
            int insertAt = Math.max(1, routePoints.size() - 1);
            routePoints.add(insertAt, point);
        }
        refreshRoute();
        updatePlaybackButtons();
    }

    private void refreshRoute() {
        if (routePoints.size() >= 1) mapView.setRoute(routePoints);
        else mapView.clearRoute();
        StringBuilder summary = new StringBuilder();
        if (routePoints.isEmpty()) summary.append("起点：未设置");
        else summary.append("起点：").append(format(routePoints.get(0)));
        if (routePoints.size() > 1) {
            summary.append("\n终点：").append(format(routePoints.get(routePoints.size() - 1)));
            if (routePoints.size() > 2) summary.append(" · 途经点 ").append(routePoints.size() - 2).append(" 个");
        } else summary.append("\n点击地图设置终点");
        routeSummary.setText(summary.toString());
        if (routeHint != null) {
            if (drawingPicking) {
                routeHint.setText("拖动地图调整起点；黄色准星中心就是起点");
            } else if (drawingMode) {
                routeHint.setText(drawnSegments.isEmpty()
                        ? "蓝色准星固定在中心；拖动地图绘制线路，松手完成一段"
                        : "已完成 " + drawnSegments.size() + " 段；继续拖动地图绘制，点击“结束绘制”完成");
            } else if (!routePlanMode.isEmpty()) {
                routeHint.setText("已生成" + routePlanMode + "导航线路，可直接开始轨迹");
            } else {
                routeHint.setText(drawnSegments.isEmpty()
                        ? (editingExpanded ? "选择起点、终点或自绘；蓝色准星就是实际落点"
                        : "点击编辑路线选择起点、终点或自绘")
                        : (editingExpanded ? "已绘制 " + drawnSegments.size() + " 段；点击“自绘”继续"
                        : "已绘制 " + drawnSegments.size() + " 段；点击编辑路线继续"));
            }
        }
        setSelectionMode(selectionMode);
        refreshRedesignedPages();
    }

    private void toggleDrawingMode() {
        if (!requireAccess()) return;
        if (running) {
            Toast.makeText(this, "轨迹运行中，请先停止后再编辑", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!drawingMode && !drawingPicking) {
            routePlanMode = "";
            editingExpanded = true;
            playbackExpanded = false;
            drawingFocusExpanded = false;
            drawingPicking = true;
            mapView.setDrawingEnabled(false);
            mapView.setDrawingPicking(true);
            drawButton.setText("确认起点");
            routeHint.setText("拖动地图调整起点；黄色准星中心就是起点");
            setSelectionMode(-1);
            Toast.makeText(this, "先拖动地图，让黄色准星停在想要的起点", Toast.LENGTH_SHORT).show();
        } else if (drawingPicking) {
            drawingPicking = false;
            drawingMode = true;
            mapView.setDrawingPicking(false);
            mapView.setDrawingEnabled(true);
            drawButton.setText("绘制中");
            routeHint.setText("蓝色准星固定在中心；拖动地图绘制线路，松手完成一段");
            setSelectionMode(-1);
            Toast.makeText(this, "起点已确认，拖动地图开始绘制", Toast.LENGTH_SHORT).show();
        } else {
            drawingMode = false;
            drawingFocusExpanded = false;
            mapView.setDrawingEnabled(false);
            drawButton.setText("自绘");
            refreshRoute();
        }
        updatePlaybackButtons();
    }

    private void disableDrawingMode() {
        if (!drawingMode && !drawingPicking) return;
        drawingMode = false;
        drawingPicking = false;
        drawingFocusExpanded = false;
        mapView.setDrawingPicking(false);
        mapView.setDrawingEnabled(false);
        if (drawButton != null) drawButton.setText("自绘");
        if (routeHint != null) {
            routeHint.setText(drawnSegments.isEmpty()
                    ? (editingExpanded ? "选择起点、终点或自绘；蓝色准星就是实际落点"
                    : "点击编辑路线选择起点、终点或自绘")
                    : (editingExpanded ? "已绘制 " + drawnSegments.size() + " 段；点击“自绘”继续"
                    : "已绘制 " + drawnSegments.size() + " 段；点击编辑路线继续"));
        }
        updatePlaybackButtons();
    }

    private void onDrawPath(List<AmapWebMapView.RoutePoint> points) {
        if (!LicenseManager.isTrajectoryUnlocked(this)) {
            disableDrawingMode();
            return;
        }
        if (running) return;
        if (points == null || points.size() < 2) {
            Toast.makeText(this, "自绘线路至少需要两个点", Toast.LENGTH_SHORT).show();
            refreshRoute();
            return;
        }
        ArrayList<AmapWebMapView.RoutePoint> segment = simplifyDrawnPath(points, 180);
        if (segment.size() < 2) {
            Toast.makeText(this, "自绘线路至少需要两个有效点", Toast.LENGTH_SHORT).show();
            refreshRoute();
            return;
        }
        drawnSegments.add(segment);
        rebuildDrawnRoute();
        selectionMode = 1;
        refreshRoute();
        updatePlaybackButtons();
        Toast.makeText(this, "已完成第 " + drawnSegments.size() + " 段，共 "
                        + routePoints.size() + " 个点；继续拖动地图绘制，点击“结束绘制”完成",
                Toast.LENGTH_SHORT).show();
    }

    private void rebuildDrawnRoute() {
        routePoints.clear();
        for (int segmentIndex = 0; segmentIndex < drawnSegments.size(); segmentIndex++) {
            ArrayList<AmapWebMapView.RoutePoint> segment = drawnSegments.get(segmentIndex);
            int startIndex = 0;
            if (segmentIndex > 0 && !routePoints.isEmpty()
                    && samePoint(routePoints.get(routePoints.size() - 1), segment.get(0))) {
                startIndex = 1;
            }
            for (int pointIndex = startIndex; pointIndex < segment.size(); pointIndex++) {
                routePoints.add(segment.get(pointIndex));
            }
        }
        if (routePoints.isEmpty()) {
            routePoints.add(new AmapWebMapView.RoutePoint(
                    readDoublePreference(LocationContract.KEY_LATITUDE, LocationContract.DEFAULT_LATITUDE),
                    readDoublePreference(LocationContract.KEY_LONGITUDE, LocationContract.DEFAULT_LONGITUDE)));
        }
    }

    private boolean samePoint(AmapWebMapView.RoutePoint first,
                              AmapWebMapView.RoutePoint second) {
        return first != null && second != null
                && Math.abs(first.latitude - second.latitude) < 1e-7d
                && Math.abs(first.longitude - second.longitude) < 1e-7d;
    }

    private void undoLastSegment() {
        if (!requireAccess()) return;
        if (running || drawingMode || drawingPicking) return;
        if (drawnSegments.isEmpty()) {
            Toast.makeText(this, "暂无线段可以撤销", Toast.LENGTH_SHORT).show();
            return;
        }
        drawnSegments.remove(drawnSegments.size() - 1);
        rebuildDrawnRoute();
        refreshRoute();
        updatePlaybackButtons();
        Toast.makeText(this, "已撤销上一段线路", Toast.LENGTH_SHORT).show();
    }

    private ArrayList<AmapWebMapView.RoutePoint> simplifyDrawnPath(
            List<AmapWebMapView.RoutePoint> source, int maximumPoints) {
        ArrayList<AmapWebMapView.RoutePoint> result = new ArrayList<>();
        if (source == null || source.isEmpty() || maximumPoints < 2) return result;
        if (source.size() <= maximumPoints) {
            result.addAll(source);
            return result;
        }
        double stride = (source.size() - 1d) / (maximumPoints - 1d);
        for (int index = 0; index < maximumPoints; index++) {
            int sourceIndex = (int) Math.round(index * stride);
            result.add(source.get(Math.min(source.size() - 1, sourceIndex)));
        }
        return result;
    }

    private void clearRoute() {
        if (!requireAccess()) return;
        if (running) {
            Toast.makeText(this, "请先停止轨迹", Toast.LENGTH_SHORT).show();
            return;
        }
        disableDrawingMode();
        routePlanMode = "";
        drawnSegments.clear();
        routePoints.clear();
        routePoints.add(new AmapWebMapView.RoutePoint(
                readDoublePreference(LocationContract.KEY_LATITUDE, LocationContract.DEFAULT_LATITUDE),
                readDoublePreference(LocationContract.KEY_LONGITUDE, LocationContract.DEFAULT_LONGITUDE)));
        refreshRoute();
    }

    private void showRoutePlannerDialog() {
        if (!requireFeature(LicenseManager.FEATURE_ROUTE, "导航线路")) return;
        if (running || drawingMode || drawingPicking || planningRoute) return;
        if (routePoints.size() < 2) {
            Toast.makeText(this, "请先设置起点和终点", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = {"驾车", "步行", "骑行"};
        int[] selected = {0};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择导航方式")
                .setSingleChoiceItems(labels, selected[0], (choiceDialog, which) -> selected[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始规划", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String mode = selected[0] == 1 ? "walking"
                            : (selected[0] == 2 ? "riding" : "driving");
                    dialog.dismiss();
                    requestRoutePlan(mode);
                }));
        dialog.show();
    }

    private void requestRoutePlan(String mode) {
        if (!requireFeature(LicenseManager.FEATURE_ROUTE, "导航线路")) return;
        if (routePoints.size() < 2) return;
        clearRoutePlanTimeout();
        planningRoute = true;
        planRouteButton.setText("正在规划…");
        routeHint.setText("正在生成" + routeModeLabel(mode) + "导航线路，请稍候");
        refreshPanelSections();
        routePlanTimeout = () -> {
            if (!planningRoute) return;
            planningRoute = false;
            if (planRouteButton != null) planRouteButton.setText("生成导航线路");
            refreshPanelSections();
            Toast.makeText(TrajectoryActivity.this,
                    "导航服务响应超时，请检查网络后重试", Toast.LENGTH_LONG).show();
        };
        mainHandler.postDelayed(routePlanTimeout, 15_000L);
        mapView.planRoute(mode, routePoints.get(0), routePoints.get(routePoints.size() - 1));
    }

    private void clearRoutePlanTimeout() {
        if (routePlanTimeout != null) {
            mainHandler.removeCallbacks(routePlanTimeout);
            routePlanTimeout = null;
        }
    }

    private void handleRoutePlanned(String mode, List<AmapWebMapView.RoutePoint> points) {
        planningRoute = false;
        ArrayList<AmapWebMapView.RoutePoint> planned = copyRoutePoints(
                points, MAX_FAVORITE_POINTS);
        if (planned.size() < 2) {
            if (planRouteButton != null) planRouteButton.setText("生成导航线路");
            refreshPanelSections();
            Toast.makeText(this, "高德没有返回可用的导航线路", Toast.LENGTH_LONG).show();
            return;
        }
        disableDrawingMode();
        routePlanMode = routeModeLabel(mode);
        routePoints.clear();
        routePoints.addAll(planned);
        drawnSegments.clear();
        selectionMode = 1;
        editingExpanded = true;
        playbackExpanded = false;
        moreRouteExpanded = false;
        refreshRoute();
        updatePlaybackButtons();
        mapView.fitRouteEndpoints();
        Toast.makeText(this, "已生成" + routePlanMode + "导航线路，共 "
                + routePoints.size() + " 个道路点", Toast.LENGTH_SHORT).show();
    }

    private String routeModeLabel(String mode) {
        if ("walking".equals(mode)) return "步行";
        if ("riding".equals(mode)) return "骑行";
        return "驾车";
    }

    private String favoriteButtonText() {
        return favoriteRoutes.isEmpty() ? "收藏" : "收藏 " + favoriteRoutes.size();
    }

    private void saveCurrentRouteAsFavorite() {
        if (!requireFeature(LicenseManager.FEATURE_FAVORITE, "线路收藏")) return;
        if (running || drawingMode || drawingPicking) {
            Toast.makeText(this, "请先结束自绘并停止轨迹", Toast.LENGTH_SHORT).show();
            return;
        }
        if (drawnSegments.isEmpty() || routePoints.size() < 2) {
            Toast.makeText(this, "请先完成一条自绘线路", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText nameField = new EditText(this);
        nameField.setSingleLine(true);
        nameField.setHint("例如：家到公司");
        nameField.setText("自绘线路 " + (favoriteRoutes.size() + 1));
        nameField.setSelectAllOnFocus(true);
        nameField.setTextSize(15);
        nameField.setTextColor(COLOR_TEXT);
        nameField.setHintTextColor(COLOR_SUBTLE);
        nameField.setPadding(dp(14), 0, dp(14), 0);
        nameField.setBackground(rounded(COLOR_CARD, 12, COLOR_BORDER, 1));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("收藏自绘线路")
                .setMessage("给这条线路起个容易识别的名称")
                .setView(nameField)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = nameField.getText().toString().trim();
                    if (name.isEmpty()) {
                        nameField.setError("请输入线路名称");
                        return;
                    }
                    ArrayList<AmapWebMapView.RoutePoint> points = copyRoutePoints(
                            routePoints, MAX_FAVORITE_POINTS);
                    if (points.size() < 2) {
                        Toast.makeText(this, "线路点不足，无法收藏", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    favoriteRoutes.add(0, new FavoriteRoute(name,
                            System.currentTimeMillis(), points));
                    while (favoriteRoutes.size() > MAX_FAVORITE_ROUTES) {
                        favoriteRoutes.remove(favoriteRoutes.size() - 1);
                    }
                    persistFavoriteRoutes();
                    refreshPanelSections();
                    dialog.dismiss();
                    Toast.makeText(this, "已收藏“" + name + "”", Toast.LENGTH_SHORT).show();
                }));
        dialog.show();
    }

    private ArrayList<AmapWebMapView.RoutePoint> copyRoutePoints(
            List<AmapWebMapView.RoutePoint> source, int maximumPoints) {
        if (source == null || source.isEmpty()) return new ArrayList<>();
        ArrayList<AmapWebMapView.RoutePoint> result = new ArrayList<>();
        if (source.size() <= maximumPoints) {
            for (AmapWebMapView.RoutePoint point : source) {
                if (point != null && Double.isFinite(point.latitude)
                        && Double.isFinite(point.longitude)) {
                    result.add(new AmapWebMapView.RoutePoint(point.latitude, point.longitude));
                }
            }
            return result;
        }
        ArrayList<AmapWebMapView.RoutePoint> sampled = simplifyDrawnPath(source, maximumPoints);
        for (AmapWebMapView.RoutePoint point : sampled) {
            if (point != null && Double.isFinite(point.latitude)
                    && Double.isFinite(point.longitude)) {
                result.add(new AmapWebMapView.RoutePoint(point.latitude, point.longitude));
            }
        }
        return result;
    }

    private void loadFavoriteRoutes() {
        favoriteRoutes.clear();
        if (favoritePreferences == null) return;
        String raw = favoritePreferences.getString(KEY_FAVORITE_ROUTES, "");
        if (raw.isEmpty()) return;
        try {
            JSONArray routes = new JSONArray(raw);
            for (int routeIndex = 0;
                 routeIndex < routes.length() && favoriteRoutes.size() < MAX_FAVORITE_ROUTES;
                 routeIndex++) {
                JSONObject route = routes.optJSONObject(routeIndex);
                if (route == null) continue;
                String name = route.optString("name", "").trim();
                JSONArray pointsJson = route.optJSONArray("points");
                if (name.isEmpty() || pointsJson == null) continue;
                ArrayList<AmapWebMapView.RoutePoint> points = new ArrayList<>();
                for (int pointIndex = 0;
                     pointIndex < pointsJson.length() && points.size() < MAX_FAVORITE_POINTS;
                     pointIndex++) {
                    JSONObject point = pointsJson.optJSONObject(pointIndex);
                    if (point == null) continue;
                    double latitude = point.optDouble("latitude", Double.NaN);
                    double longitude = point.optDouble("longitude", Double.NaN);
                    if (Double.isFinite(latitude) && Double.isFinite(longitude)) {
                        points.add(new AmapWebMapView.RoutePoint(latitude, longitude));
                    }
                }
                if (points.size() >= 2) {
                    favoriteRoutes.add(new FavoriteRoute(name,
                            route.optLong("createdAt", 0L), points));
                }
            }
        } catch (Exception exception) {
            favoriteRoutes.clear();
        }
    }

    private void persistFavoriteRoutes() {
        if (favoritePreferences == null) return;
        JSONArray routes = new JSONArray();
        for (FavoriteRoute favorite : favoriteRoutes) {
            JSONObject route = new JSONObject();
            JSONArray points = new JSONArray();
            try {
                route.put("name", favorite.name);
                route.put("createdAt", favorite.createdAt);
                for (AmapWebMapView.RoutePoint point : favorite.points) {
                    JSONObject pointJson = new JSONObject();
                    pointJson.put("latitude", point.latitude);
                    pointJson.put("longitude", point.longitude);
                    points.put(pointJson);
                }
                route.put("points", points);
                routes.put(route);
            } catch (Exception ignored) {
            }
        }
        favoritePreferences.edit().putString(KEY_FAVORITE_ROUTES, routes.toString()).apply();
    }

    private void showFavoriteRoutesDialog() {
        if (!requireFeature(LicenseManager.FEATURE_FAVORITE, "线路收藏")) return;
        if (favoriteRoutes.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("我的收藏")
                    .setMessage("还没有收藏线路。完成自绘后，在“编辑路线”中点击“收藏线路”即可保存。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(2), dp(2), dp(2), dp(2));
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("我的收藏（" + favoriteRoutes.size() + "）")
                .setView(scrollView)
                .setNegativeButton("关闭", null)
                .create();
        for (FavoriteRoute favorite : new ArrayList<>(favoriteRoutes)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(8), dp(8));
            row.setBackground(rounded(COLOR_MUTED, 14, COLOR_BORDER, 1));
            TextView info = label(favorite.name + "\n" + favorite.points.size() + " 个轨迹点",
                    13, COLOR_TEXT, Typeface.BOLD);
            info.setLineSpacing(dp(2), 1f);
            row.addView(info, new LinearLayout.LayoutParams(0, dp(54), 1f));
            Button loadButton = compactButton("载入", "载入" + favorite.name);
            loadButton.setTextSize(12);
            loadButton.setOnClickListener(view -> {
                dialog.dismiss();
                loadFavoriteRoute(favorite);
            });
            row.addView(loadButton, new LinearLayout.LayoutParams(dp(58), dp(38)));
            Button deleteButton = compactButton("删除", "删除" + favorite.name);
            deleteButton.setTextSize(12);
            deleteButton.setTextColor(COLOR_DANGER);
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(58), dp(38));
            deleteParams.leftMargin = dp(6);
            row.addView(deleteButton, deleteParams);
            deleteButton.setOnClickListener(view -> confirmDeleteFavorite(dialog, favorite));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(7);
            list.addView(row, rowParams);
        }
        dialog.show();
    }

    private void confirmDeleteFavorite(AlertDialog listDialog, FavoriteRoute favorite) {
        if (!requireFeature(LicenseManager.FEATURE_FAVORITE, "线路收藏")) return;
        new AlertDialog.Builder(this)
                .setTitle("删除收藏")
                .setMessage("确定删除“" + favorite.name + "”？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    favoriteRoutes.remove(favorite);
                    persistFavoriteRoutes();
                    refreshPanelSections();
                    listDialog.dismiss();
                    showFavoriteRoutesDialog();
                })
                .show();
    }

    private void loadFavoriteRoute(FavoriteRoute favorite) {
        if (!requireFeature(LicenseManager.FEATURE_FAVORITE, "线路收藏")) return;
        if (running) {
            Toast.makeText(this, "请先停止轨迹", Toast.LENGTH_SHORT).show();
            return;
        }
        disableDrawingMode();
        routePlanMode = "";
        routePoints.clear();
        ArrayList<AmapWebMapView.RoutePoint> points = copyRoutePoints(
                favorite.points, MAX_FAVORITE_POINTS);
        routePoints.addAll(points);
        drawnSegments.clear();
        if (points.size() >= 2) drawnSegments.add(new ArrayList<>(points));
        selectionMode = 1;
        editingExpanded = true;
        playbackExpanded = false;
        moreRouteExpanded = false;
        refreshRoute();
        updatePlaybackButtons();
        mapView.fitRoute();
        Toast.makeText(this, "已载入“" + favorite.name + "”", Toast.LENGTH_SHORT).show();
    }

    private void setSelectionMode(int mode) {
        selectionMode = mode;
        startPointButton.setTextColor(mode == 0 ? COLOR_TEXT : COLOR_TEXT);
        endPointButton.setTextColor(mode == 1 ? COLOR_TEXT : COLOR_TEXT);
        viaPointButton.setTextColor(mode == 2 ? COLOR_TEXT : COLOR_TEXT);
        startPointButton.setBackground(rounded(mode == 0 ? COLOR_ACCENT : COLOR_MUTED, 12,
                COLOR_BORDER, 2));
        endPointButton.setBackground(rounded(mode == 1 ? COLOR_ACCENT : COLOR_MUTED, 12,
                COLOR_BORDER, 2));
        viaPointButton.setBackground(rounded(mode == 2 ? COLOR_ACCENT : COLOR_MUTED, 12,
                COLOR_BORDER, 2));
        if (drawButton != null) {
            drawButton.setText(drawingPicking ? "确认起点"
                    : (drawingMode ? (drawnSegments.isEmpty() ? "绘制中" : "结束绘制") : "自绘"));
            drawButton.setTextColor(COLOR_TEXT);
            drawButton.setBackground(rounded((drawingMode || drawingPicking) ? COLOR_ACCENT : COLOR_MUTED, 12,
                    COLOR_BORDER, 2));
        }
    }

    private void startTrajectory() {
        if (!requireAccess()) return;
        if (routePoints.size() < 2) {
            Toast.makeText(this, "请先在地图上设置终点", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSIONS);
            return;
        }
        boolean rootOnly = preferences.getBoolean(LocationContract.KEY_ROOT_ONLY, false);
        if (!rootOnly && !isSelectedMockLocationApp()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要模拟位置授权")
                    .setMessage("请在开发者选项中选择 Mock Location，或使用首页的 ADB 授权。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("打开设置", (dialog, which) -> openDeveloperSettings())
                    .show();
            return;
        }
        if (rootOnly && !isSelectedMockLocationApp()) {
            configureRootAppOp();
            return;
        }
        launchTrajectory(rootOnly);
    }

    private void launchTrajectory(boolean rootOnly) {
        Intent intent = new Intent(this, MockLocationService.class)
                .setAction(LocationContract.ACTION_START)
                .putExtra(LocationContract.EXTRA_ROUTE_JSON, routeJson())
                .putExtra(LocationContract.EXTRA_TRAJECTORY_SPEED_KMH, speedKmh)
                .putExtra(LocationContract.EXTRA_TRAJECTORY_LOOP, loop)
                .putExtra(LocationContract.EXTRA_ROOT_ONLY, rootOnly)
                .putExtra(LocationContract.EXTRA_LATITUDE, routePoints.get(0).latitude)
                .putExtra(LocationContract.EXTRA_LONGITUDE, routePoints.get(0).longitude)
                .putExtra(LocationContract.EXTRA_SPEED, speedKmh / 3.6f)
                .putExtra(LocationContract.EXTRA_BEARING, 0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        running = true;
        paused = false;
        playbackProgress = 0d;
        playbackLatitude = routePoints.get(0).latitude;
        playbackLongitude = routePoints.get(0).longitude;
        playbackStatus.setText("正在启动轨迹…");
        updatePlaybackButtons();
        goToRedesignedPage(PAGE_RUNNING);
    }

    private boolean requireAccess() {
        LicenseManager.Access access = LicenseManager.getAccess(this);
        if (access.allowed) return true;
        startActivity(new Intent(this, LicenseActivity.class));
        Toast.makeText(this, access.message + "，请先激活卡密", Toast.LENGTH_LONG).show();
        return false;
    }

    private boolean requireFeature(String feature, String featureLabel) {
        LicenseManager.Access access = LicenseManager.getAccess(this);
        if (!access.allowed) {
            startActivity(new Intent(this, LicenseActivity.class));
            Toast.makeText(this, access.message + "，请先激活卡密", Toast.LENGTH_LONG).show();
            return false;
        }
        if (access.license != null && access.license.hasFeature(feature)) {
            return true;
        }
        Toast.makeText(this, "当前卡密未包含“" + featureLabel + "”功能", Toast.LENGTH_LONG).show();
        return false;
    }

    private void configureRootAppOp() {
        if (rootSetupInFlight) return;
        rootSetupInFlight = true;
        playbackStatus.setText("正在配置 Root 定位通道…");
        rootExecutor.execute(() -> {
            boolean success = false;
            String detail = "";
            try {
                String command = "appops set " + getPackageName()
                        + " android:mock_location allow || appops set " + getPackageName()
                        + " MOCK_LOCATION allow || appops set " + getPackageName() + " 58 allow";
                Process process = new ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true).start();
                StringBuilder output = new StringBuilder();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append(' ');
                success = process.waitFor() == 0;
                detail = output.toString().trim();
            } catch (Exception exception) {
                detail = exception.getClass().getSimpleName();
            }
            boolean configured = success;
            String error = detail;
            runOnUiThread(() -> {
                rootSetupInFlight = false;
                if (configured || isSelectedMockLocationApp()) {
                    launchTrajectory(true);
                } else {
                    playbackStatus.setText("Root 定位通道配置失败");
                    Toast.makeText(this, "Root 模拟位置权限配置失败：" + error,
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void togglePause() {
        if (!running) return;
        if (!requireAccess()) return;
        sendTrajectoryAction(paused ? LocationContract.ACTION_TRAJECTORY_RESUME
                : LocationContract.ACTION_TRAJECTORY_PAUSE);
    }

    private void sendTrajectoryAction(String action) {
        Intent intent = new Intent(this, MockLocationService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !LocationContract.ACTION_STOP.equals(action)) startForegroundService(intent);
        else startService(intent);
        if (LocationContract.ACTION_STOP.equals(action)) {
            running = false;
            paused = false;
            playbackStatus.setText("正在停止…");
            updatePlaybackButtons();
        }
    }

    private void updatePlaybackButtons() {
        if (startButton == null) return;
        startButton.setText(running ? "停止轨迹" : "开始轨迹");
        startButton.setTextColor(running ? Color.WHITE : COLOR_TEXT);
        startButton.setBackground(rounded(running ? COLOR_DANGER : COLOR_ACCENT, 15,
                COLOR_BORDER, 2));
        pauseButton.setEnabled(running);
        pauseButton.setText(paused ? "继续" : "暂停");
        pauseButton.setAlpha(running ? 1f : 0.55f);
        if (drawButton != null) {
            drawButton.setEnabled(!running);
            drawButton.setAlpha(running ? 0.55f : 1f);
        }
        if (fitRouteButton != null) {
            fitRouteButton.setEnabled(!running && !drawingMode && !drawingPicking);
            fitRouteButton.setAlpha(running || drawingMode || drawingPicking ? 0.55f : 1f);
        }
        if (undoSegmentButton != null) {
            boolean canUndo = !running && !drawingMode && !drawingPicking && !drawnSegments.isEmpty();
            undoSegmentButton.setEnabled(canUndo);
            undoSegmentButton.setAlpha(canUndo ? 1f : 0.55f);
        }
        refreshPanelSections();
    }

    private String routeJson() {
        JSONArray array = new JSONArray();
        for (AmapWebMapView.RoutePoint point : routePoints) {
            JSONObject item = new JSONObject();
            try {
                item.put("latitude", point.latitude);
                item.put("longitude", point.longitude);
                array.put(item);
            } catch (Exception ignored) {
            }
        }
        return array.toString();
    }

    private void showSpeedDialog() {
        if (!requireAccess()) return;
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        field.setHint("速度 km/h（0.1 - 300）");
        field.setText(String.format(Locale.US, "%.1f", speedKmh));
        field.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("自定义速度")
                .setView(field)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        float value = Float.parseFloat(field.getText().toString());
                        if (value < 0.1f || value > 300f) throw new NumberFormatException();
                        speedKmh = value;
                        speedText.setText(String.format(Locale.US, "%.1f km/h", speedKmh));
                        selectSpeedButton(customSpeedButton);
                        refreshPanelSections();
                    } catch (NumberFormatException exception) {
                        Toast.makeText(this, "速度范围为 0.1 - 300 km/h", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private boolean isSelectedMockLocationApp() {
        AppOpsManager manager = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(), getPackageName())
                : manager.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void openDeveloperSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (RuntimeException exception) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    @SuppressLint({"InlinedApi", "UnspecifiedRegisterReceiverFlag"})
    @SuppressWarnings("deprecation")
    private void registerTrajectoryReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(LocationContract.ACTION_TRAJECTORY_STATE);
        filter.addAction(LocationContract.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trajectoryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(trajectoryReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (redesignedPage == PAGE_RUNNING && running) {
            Toast.makeText(this, "请先停止轨迹", Toast.LENGTH_SHORT).show();
            return;
        }
        if (redesignedPage == PAGE_HOME) {
            super.onBackPressed();
        } else if (redesignedPage == PAGE_DRAW) {
            finishRedesignedDrawing();
        } else if (redesignedPage == PAGE_SETTINGS || redesignedPage == PAGE_FAVORITES
                || redesignedPage == PAGE_RUNNING) {
            goToRedesignedPage(PAGE_EDITOR);
        } else {
            goToRedesignedPage(PAGE_HOME);
        }
    }

    @Override
    protected void onDestroy() {
        clearRoutePlanTimeout();
        if (receiverRegistered) unregisterReceiver(trajectoryReceiver);
        if (mapView != null) mapView.onDestroy();
        rootExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS
                && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startTrajectory();
        }
    }

    private String format(AmapWebMapView.RoutePoint point) {
        return String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude);
    }

    private double routeDistanceMeters(List<AmapWebMapView.RoutePoint> points) {
        if (points == null || points.size() < 2) return 0d;
        double total = 0d;
        for (int index = 1; index < points.size(); index++) {
            AmapWebMapView.RoutePoint previous = points.get(index - 1);
            AmapWebMapView.RoutePoint current = points.get(index);
            if (previous == null || current == null) continue;
            double latitude = Math.toRadians((previous.latitude + current.latitude) / 2d);
            double dLat = Math.toRadians(current.latitude - previous.latitude);
            double dLng = Math.toRadians(current.longitude - previous.longitude);
            double a = Math.sin(dLat / 2d) * Math.sin(dLat / 2d)
                    + Math.cos(Math.toRadians(previous.latitude))
                    * Math.cos(Math.toRadians(current.latitude))
                    * Math.sin(dLng / 2d) * Math.sin(dLng / 2d);
            total += 6_371_000d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0d, 1d - a)));
        }
        return total;
    }

    private String formatDistance(double meters) {
        if (meters < 1000d) return String.format(Locale.US, "%.0f 米", Math.max(0d, meters));
        return String.format(Locale.US, "%.1f 公里", meters / 1000d);
    }

    private String estimateDuration(double meters, float kmh) {
        if (meters <= 0d || kmh <= 0f) return "--";
        long minutes = Math.max(1L, Math.round(meters / 1000d / kmh * 60d));
        if (minutes < 60L) return minutes + " 分钟";
        return (minutes / 60L) + " 小时 " + (minutes % 60L) + " 分钟";
    }

    private double readDoublePreference(String key, double fallback) {
        return Double.longBitsToDouble(preferences.getLong(key, Double.doubleToRawLongBits(fallback)));
    }

    private Button compactButton(String text, String description) {
        Button button = createActionButton(text, COLOR_CARD, COLOR_TEXT);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setContentDescription(description);
        button.setTextSize(13);
        button.setBackground(rounded(COLOR_CARD, 12, COLOR_BORDER, 1));
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private ImageButton centeredBackButton(String description) {
        ImageButton button = new ImageButton(this);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setImageResource(R.drawable.ic_back_chevron);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(rounded(COLOR_CARD, 12, COLOR_BORDER, 1));
        return button;
    }

    private Button createActionButton(String text, int background, int foreground) {
        Button button = new Button(this);
        button.setElevation(0f);
        button.setStateListAnimator(null);
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

    private TextView label(String text, float sizeSp, int color, int style) {
        TextView value = new TextView(this);
        value.setText(text);
        value.setTextSize(sizeSp);
        value.setTextColor(color);
        value.setTypeface(Typeface.DEFAULT, style);
        return value;
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, float radiusDp,
                                                                int strokeColor, int strokeWidthDp) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
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

    private static final class FavoriteRoute {
        final String name;
        final long createdAt;
        final ArrayList<AmapWebMapView.RoutePoint> points;

        FavoriteRoute(String name, long createdAt,
                      ArrayList<AmapWebMapView.RoutePoint> points) {
            this.name = name;
            this.createdAt = createdAt;
            this.points = points;
        }
    }
}
