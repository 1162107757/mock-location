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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Independent route editor and playback screen. The existing home screen remains fixed-location only. */
public final class TrajectoryActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 71;
    private static final int COLOR_BACKGROUND = Color.rgb(239, 238, 227);
    private static final int COLOR_CARD = Color.rgb(255, 253, 245);
    private static final int COLOR_MUTED = Color.rgb(250, 245, 226);
    private static final int COLOR_BORDER = Color.rgb(7, 7, 6);
    private static final int COLOR_TEXT = Color.rgb(7, 7, 6);
    private static final int COLOR_SUBTLE = Color.rgb(76, 92, 94);
    private static final int COLOR_ACCENT = Color.rgb(250, 175, 20);
    private static final int COLOR_BLUE = Color.rgb(47, 152, 232);
    private static final int COLOR_TEAL = Color.rgb(65, 121, 140);
    private static final int COLOR_DANGER = Color.rgb(232, 74, 38);

    private final ArrayList<AmapWebMapView.RoutePoint> routePoints = new ArrayList<>();
    private final ArrayList<ArrayList<AmapWebMapView.RoutePoint>> drawnSegments = new ArrayList<>();
    private SharedPreferences preferences;
    private AmapWebMapView mapView;
    private TextView routeSummary;
    private TextView routeHint;
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
    private int selectionMode = 1;
    private float speedKmh = 5f;
    private boolean receiverRegistered;
    private boolean rootSetupInFlight;
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
        setContentView(buildInterface());
        registerTrajectoryReceiver();

        double latitude = readDoublePreference(LocationContract.KEY_LATITUDE, LocationContract.DEFAULT_LATITUDE);
        double longitude = readDoublePreference(LocationContract.KEY_LONGITUDE, LocationContract.DEFAULT_LONGITUDE);
        routePoints.add(new AmapWebMapView.RoutePoint(latitude, longitude));
        mapView.setOnMapTapListener(this::onMapTap);
        mapView.setOnDrawPathListener(this::onDrawPath);
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
        Button backButton = compactButton("‹", "返回首页");
        backButton.setTextSize(28);
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
        fitRouteButton.setOnClickListener(view -> mapView.fitRoute());
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
            topBar.setLayoutParams(topBarParams);
            panel.setLayoutParams(panelParams);
            drawingFocusBar.setLayoutParams(focusBarParams);
            return insets;
        });
        refreshPanelSections();
        return root;
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
                    COLOR_BORDER, 2));
        }
        if (customSpeedButton != null) {
            boolean active = customSpeedButton == selected;
            customSpeedButton.setTextColor(COLOR_TEXT);
            customSpeedButton.setBackground(rounded(active ? COLOR_ACCENT : COLOR_MUTED, 12,
                    COLOR_BORDER, 2));
        }
    }

    private void onMapTap(double latitude, double longitude) {
        if (running || drawingMode || drawingPicking) {
            if (running) {
            Toast.makeText(this, "轨迹运行中，请先停止后再编辑", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!drawnSegments.isEmpty()) drawnSegments.clear();
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
            } else {
                routeHint.setText(drawnSegments.isEmpty()
                        ? (editingExpanded ? "选择起点、终点或自绘；蓝色准星就是实际落点"
                        : "点击编辑路线选择起点、终点或自绘")
                        : (editingExpanded ? "已绘制 " + drawnSegments.size() + " 段；点击“自绘”继续"
                        : "已绘制 " + drawnSegments.size() + " 段；点击编辑路线继续"));
            }
        }
        setSelectionMode(selectionMode);
    }

    private void toggleDrawingMode() {
        if (running) {
            Toast.makeText(this, "轨迹运行中，请先停止后再编辑", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!drawingMode && !drawingPicking) {
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
        if (running) {
            Toast.makeText(this, "请先停止轨迹", Toast.LENGTH_SHORT).show();
            return;
        }
        disableDrawingMode();
        drawnSegments.clear();
        routePoints.clear();
        routePoints.add(new AmapWebMapView.RoutePoint(
                readDoublePreference(LocationContract.KEY_LATITUDE, LocationContract.DEFAULT_LATITUDE),
                readDoublePreference(LocationContract.KEY_LONGITUDE, LocationContract.DEFAULT_LONGITUDE)));
        refreshRoute();
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
        playbackStatus.setText("正在启动轨迹…");
        updatePlaybackButtons();
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
    protected void onDestroy() {
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

    private double readDoublePreference(String key, double fallback) {
        return Double.longBitsToDouble(preferences.getLong(key, Double.doubleToRawLongBits(fallback)));
    }

    private Button compactButton(String text, String description) {
        Button button = createActionButton(text, COLOR_MUTED, COLOR_TEXT);
        button.setContentDescription(description);
        button.setTextSize(13);
        button.setBackground(rounded(COLOR_MUTED, 12, COLOR_BORDER, 2));
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
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
}
