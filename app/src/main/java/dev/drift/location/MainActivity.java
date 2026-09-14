package dev.drift.location;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
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
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String LOG_TAG = "DriftLocation";
    private static final int REQUEST_PERMISSIONS = 42;
    private static final int REQUEST_CURRENT_LOCATION = 43;
    private static final int REQUEST_MAP_SETTINGS = 44;
    private static final long LOCATION_TIMEOUT_MS = 12_000L;
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
    private static final int COLOR_DIALOG_SURFACE = Color.rgb(255, 253, 245);
    private static final int COLOR_DIALOG_BUTTON = Color.rgb(250, 245, 226);
    private static final int COLOR_DIALOG_BORDER = Color.rgb(7, 7, 6);
    private static final int COLOR_DIALOG_TEXT = Color.rgb(7, 7, 6);
    private static final int COLOR_DIALOG_SUBTLE = Color.rgb(76, 92, 94);
    private static final int MAP_UPDATE_DELAY_SECONDS = 3;
    private static final long MAP_UPDATE_DELAY_MS = MAP_UPDATE_DELAY_SECONDS * 1_000L;
    private static final String MAP_PREFERENCES = "amap_configuration";
    private static final String KEY_AMAP_PRIVACY_AGREED = "privacy_agreed";
    private static final String KEY_DISCLAIMER_AGREED = "disclaimer_v1_agreed";
    private static final String[] UPDATE_MANIFEST_URLS = {
            "https://raw.githubusercontent.com/1162107757/mock-location/main/update.json",
            "https://raw.githubusercontent.com/1162107757/mock-location/master/update.json"
    };
    private static final String UPDATE_CHECK_SCHEMA = "2";
    private static final int UPDATE_TIMEOUT_MS = 8_000;
    private static final int UPDATE_MAX_BYTES = 256 * 1024;

    static final String DISCLAIMER_TEXT =
            "请在使用本软件前仔细阅读本说明。点击“同意并继续”，表示你已经阅读、理解并同意以下内容。\n\n"
                    + "一、软件用途\n"
                    + "本软件仅用于 Android 应用开发、调试、自动化测试和个人设备测试，用于验证应用在不同位置、路线和定位状态下的功能表现。\n"
                    + "你应当仅在自己拥有或获得明确授权的设备、应用和测试环境中使用本软件。\n\n"
                    + "二、禁止用途\n"
                    + "不得使用本软件实施或协助实施欺诈、虚假打卡、虚假签到、伪造考勤、规避平台风控、绕过账号安全验证、影响游戏或竞赛公平性、冒充他人位置、伪造行踪、侵害他人合法权益，或其他违反法律法规、平台规则和服务协议的行为。\n\n"
                    + "三、Root 权限风险\n"
                    + "Root 模式可能需要授予系统级权限或修改系统定位配置，可能导致系统不稳定、应用闪退、设备重启、定位服务异常、数据丢失、设备无法启动、保修失效或安全软件报警。使用 Root 模式前请自行备份数据并确认能够承担相关风险。\n\n"
                    + "四、模拟位置限制\n"
                    + "本软件不能保证所有应用都能接受模拟位置。第三方应用可能同时使用 GPS、网络、Wi-Fi、基站、传感器、IP 地址或自有风控服务判断位置，因而可能出现位置不一致、获取失败、模拟位置被识别、服务拒绝、应用闪退或账号受限等情况。第三方应用的行为由其自身实现决定。\n\n"
                    + "五、位置数据\n"
                    + "本软件可能处理设备位置、地图选点、历史位置和模拟轨迹等数据。相关记录原则上保存在设备本地，但地图服务可能按照其服务协议处理地图请求、网络和设备信息。请勿在未经同意的情况下收集、保存、使用或传播他人的位置、住址、工作地点等敏感信息。\n\n"
                    + "六、第三方服务\n"
                    + "本软件使用第三方地图服务显示地图和搜索地点。地图数据、网络请求、服务可用性和坐标精度受第三方服务商影响，用户应自行查阅并遵守第三方服务协议和隐私政策。\n\n"
                    + "七、责任限制\n"
                    + "在法律允许的范围内，因用户违反法律法规或第三方规则、使用 Root 权限、操作不当、设备或系统异常、第三方应用或地图服务变化，以及使用模拟位置造成的账号、数据、财产或其他权益损失，由用户自行承担。因网络行为侵害他人民事权益的，用户可能依法承担相应责任。本说明不排除法律规定不得排除或限制的责任。\n\n"
                    + "八、用户确认\n"
                    + "我确认拥有当前设备及测试环境的合法使用权；我已阅读并理解本说明；我会遵守法律法规及第三方服务规则；我不会将本软件用于欺诈、作弊、侵权或其他违法用途；我自愿承担因使用模拟位置功能产生的相应风险。";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private SharedPreferences preferences;
    private SharedPreferences mapPreferences;
    private LocationHistoryStore historyStore;
    private AmapWebMapView mapView;
    private EditText searchInput;
    private TextView coordinateText;
    private TextView statusText;
    private Button startButton;
    private Button setupButton;
    private Button locateButton;
    private LocationManager locationManager;
    private LocationListener pendingLocationListener;
    private Runnable locationTimeout;
    private Location pendingBestLocation;
    private boolean running;
    private boolean rootOnly;
    private boolean rootGranted;
    private boolean rootCheckInFlight;
    private boolean rootProviderSetupInFlight;
    private boolean rootProviderAccessReady;
    private String selectedPlaceName = "";
    private double selectedPlaceLatitude = Double.NaN;
    private double selectedPlaceLongitude = Double.NaN;
    private String amapJsApiKey = "";
    private String amapJsSecurityCode = "";
    private boolean interfaceInitialized;
    private Runnable pendingMapCommitTask;
    private Runnable pendingMapCountdownTask;
    private double pendingMapLatitude;
    private double pendingMapLongitude;
    private double lastMapCenterLatitude = Double.NaN;
    private double lastMapCenterLongitude = Double.NaN;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!LocationContract.ACTION_STATE.equals(intent.getAction())) return;
            running = intent.getBooleanExtra(LocationContract.EXTRA_RUNNING, false);
            if (!running) cancelPendingMapLocationUpdate();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int systemUi = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                systemUi |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(systemUi);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        preferences = LocationContract.openPreferences(this);
        mapPreferences = getSharedPreferences(MAP_PREFERENCES, MODE_PRIVATE);
        historyStore = new LocationHistoryStore(this);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        running = preferences.getBoolean(LocationContract.KEY_RUNNING, false);
        rootOnly = preferences.getBoolean(LocationContract.KEY_ROOT_ONLY, false);
        rootGranted = false;

        beginAmapSetup(savedInstanceState);
    }

    private void beginAmapSetup(Bundle savedInstanceState) {
        if (!mapPreferences.getBoolean(KEY_DISCLAIMER_AGREED, false)) {
            showDisclaimerDialog(savedInstanceState);
            return;
        }
        if (mapPreferences.getBoolean(KEY_AMAP_PRIVACY_AGREED, false)) {
            continueAmapSetup(savedInstanceState);
            return;
        }
        AlertDialog privacyDialog = new AlertDialog.Builder(this)
                .setTitle("高德地图 JS API 服务说明")
                .setMessage("地图显示和地点搜索由高德开放平台 JS API 提供，并通过系统 WebView 加载。使用时，高德服务会按照其隐私权政策处理网络、设备及粗略位置信息。点击“同意并继续”后才会加载地图。")
                .setNegativeButton("退出", null)
                .setNeutralButton("查看高德隐私政策", null)
                .setPositiveButton("同意并继续", null)
                .create();
        privacyDialog.setOnShowListener(ignored -> {
            privacyDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                privacyDialog.dismiss();
                finish();
            });
            privacyDialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(view -> openAmapPrivacyPolicy());
            privacyDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    mapPreferences.edit().putBoolean(KEY_AMAP_PRIVACY_AGREED, true).apply();
                    privacyDialog.dismiss();
                    continueAmapSetup(savedInstanceState);
            });
        });
        privacyDialog.setOnCancelListener(dialog -> finish());
        privacyDialog.show();
    }

    private void showDisclaimerDialog(Bundle savedInstanceState) {
        showDisclaimerDialog(savedInstanceState, true);
    }

    private void showDisclaimerDialog(Bundle savedInstanceState, boolean initial) {
        TextView disclaimerText = label(DISCLAIMER_TEXT, 14, COLOR_DIALOG_TEXT, Typeface.NORMAL);
        disclaimerText.setLineSpacing(dp(4), 1f);
        disclaimerText.setPadding(dp(4), dp(4), dp(4), dp(16));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_DIALOG_SURFACE);
        scrollView.addView(disclaimerText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        CheckBox confirmBox = new CheckBox(this);
        confirmBox.setText("我已阅读并理解以上内容，同意承担相应使用责任");
        confirmBox.setTextSize(13);
        confirmBox.setTextColor(COLOR_DIALOG_SUBTLE);
        confirmBox.setEnabled(false);
        confirmBox.setContentDescription("阅读免责说明并确认");
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(2), dp(18), dp(2));
        content.setBackgroundColor(COLOR_DIALOG_SURFACE);
        content.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(390)));
        LinearLayout.LayoutParams checkboxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        checkboxParams.topMargin = dp(8);
        content.addView(confirmBox, checkboxParams);

        AlertDialog disclaimerDialog = new AlertDialog.Builder(this)
                .setTitle("使用须知与免责说明")
                .setView(content)
                .setNegativeButton(initial ? "不同意并退出" : "关闭", null)
                .setPositiveButton("同意并继续", null)
                .create();
        disclaimerDialog.setCancelable(!initial);
        disclaimerDialog.setCanceledOnTouchOutside(!initial);
        disclaimerDialog.setOnCancelListener(dialog -> {
            if (initial) finish();
        });
        disclaimerDialog.setOnShowListener(ignored -> {
            Button agreeButton = disclaimerDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button exitButton = disclaimerDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            agreeButton.setEnabled(false);
            agreeButton.setTextColor(COLOR_DIALOG_SUBTLE);
            exitButton.setTextColor(initial ? COLOR_DANGER : COLOR_DIALOG_SUBTLE);
            exitButton.setOnClickListener(view -> {
                disclaimerDialog.dismiss();
                if (initial) finish();
            });
            CompoundButton.OnCheckedChangeListener checkedListener = (button, checked) -> {
                boolean canContinue = checkedBoxAtEnd(scrollView) && checked;
                agreeButton.setEnabled(canContinue);
                agreeButton.setTextColor(canContinue ? COLOR_ACCENT : COLOR_DIALOG_SUBTLE);
            };
            confirmBox.setOnCheckedChangeListener(checkedListener);
            scrollView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (!checkedBoxAtEnd(scrollView)) return;
                confirmBox.setEnabled(true);
                confirmBox.setTextColor(COLOR_DIALOG_TEXT);
                checkedListener.onCheckedChanged(confirmBox, confirmBox.isChecked());
            });
            scrollView.post(() -> {
                if (checkedBoxAtEnd(scrollView)) {
                    confirmBox.setEnabled(true);
                    confirmBox.setTextColor(COLOR_DIALOG_TEXT);
                }
            });
            agreeButton.setOnClickListener(view -> {
                if (!checkedBoxAtEnd(scrollView) || !confirmBox.isChecked()) return;
                if (initial) mapPreferences.edit().putBoolean(KEY_DISCLAIMER_AGREED, true).apply();
                disclaimerDialog.dismiss();
                if (initial) beginAmapSetup(savedInstanceState);
            });
        });
        disclaimerDialog.show();
    }

    private boolean checkedBoxAtEnd(ScrollView scrollView) {
        if (scrollView == null || scrollView.getChildCount() == 0) return false;
        View child = scrollView.getChildAt(0);
        return child.getBottom() <= scrollView.getScrollY() + scrollView.getHeight() + dp(12);
    }

    private void continueAmapSetup(Bundle savedInstanceState) {
        amapJsApiKey = AmapKeyStore.getJsApiKey(this);
        amapJsSecurityCode = AmapKeyStore.getJsSecurityCode(this);
        if (amapJsApiKey.isEmpty() || amapJsSecurityCode.isEmpty()) {
            openMapSettings(true);
            return;
        }
        initializeInterface(savedInstanceState);
    }

    private void openMapSettings(boolean required) {
        Intent intent = new Intent(this, MapSettingsActivity.class)
                .putExtra(MapSettingsActivity.EXTRA_REQUIRED, required);
        startActivityForResult(intent, REQUEST_MAP_SETTINGS);
    }

    private void initializeInterface(Bundle savedInstanceState) {
        if (interfaceInitialized) return;
        interfaceInitialized = true;
        setContentView(buildInterface());
        mapView.onCreate(savedInstanceState);
        registerStateReceiver();

        double latitude = readDoublePreference(LocationContract.KEY_LATITUDE, LocationContract.DEFAULT_LATITUDE);
        double longitude = readDoublePreference(LocationContract.KEY_LONGITUDE, LocationContract.DEFAULT_LONGITUDE);
        // The initial center restores the last committed location; do not start the
        // three-second update countdown until the user actually moves the map.
        lastMapCenterLatitude = latitude;
        lastMapCenterLongitude = longitude;
        mapView.setCenter(latitude, longitude);
        renderState(running ? "正在模拟位置" : "准备就绪");
        checkRootAccess(false);
        scheduleAutomaticUpdateCheck();
    }

    private void showAmapKeyDialog(boolean required, Bundle savedInstanceState) {
        LinearLayout keyForm = new LinearLayout(this);
        keyForm.setOrientation(LinearLayout.VERTICAL);
        keyForm.setPadding(dp(20), dp(4), dp(20), dp(6));
        keyForm.setBackgroundColor(COLOR_DIALOG_SURFACE);

        TextView intro = label("地图显示和地点搜索由高德 Web 端 JS API 提供。", 14,
                COLOR_DIALOG_SUBTLE, Typeface.NORMAL);
        intro.setLineSpacing(dp(2), 1f);
        keyForm.addView(intro, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sectionTitle = label("服务配置", 12, COLOR_ACCENT, Typeface.BOLD);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionParams.topMargin = dp(18);
        keyForm.addView(sectionTitle, sectionParams);

        EditText keyField = createSettingsSecretField("32 位高德 Web 端（JS API）Key");
        LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        keyParams.topMargin = dp(8);
        keyForm.addView(keyField, keyParams);

        EditText securityCodeField = createSettingsSecretField("高德 JS API 安全密钥（jscode）");
        LinearLayout.LayoutParams securityParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        securityParams.topMargin = dp(10);
        keyForm.addView(securityCodeField, securityParams);

        TextView securityHint = label("已保存的密钥不会回显；如需更换，请重新输入。", 12,
                COLOR_DIALOG_SUBTLE, Typeface.NORMAL);
        LinearLayout.LayoutParams securityHintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        securityHintParams.topMargin = dp(8);
        keyForm.addView(securityHint, securityHintParams);

        if (!required) {
            TextView toolsTitle = label("其他", 12, COLOR_ACCENT, Typeface.BOLD);
            LinearLayout.LayoutParams toolsTitleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            toolsTitleParams.topMargin = dp(18);
            keyForm.addView(toolsTitle, toolsTitleParams);

            LinearLayout toolsRow = new LinearLayout(this);
            toolsRow.setOrientation(LinearLayout.HORIZONTAL);
            toolsRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams toolsRowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
            toolsRowParams.topMargin = dp(8);
            keyForm.addView(toolsRow, toolsRowParams);

            Button disclaimerButton = createSettingsUtilityButton("查看免责说明",
                    "查看使用须知与免责说明");
            LinearLayout.LayoutParams disclaimerParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            toolsRow.addView(disclaimerButton, disclaimerParams);
            disclaimerButton.setTag("disclaimer_review");

            Button aboutButton = createSettingsUtilityButton("关于与更新", "查看版本并检查更新");
            LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            aboutParams.leftMargin = dp(10);
            toolsRow.addView(aboutButton, aboutParams);
            aboutButton.setTag("about_review");
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(required ? "配置高德 JS API" : "高德地图设置")
                .setView(keyForm)
                .setNegativeButton(required ? "退出" : "取消", null)
                .setNeutralButton("申请 Key", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setCanceledOnTouchOutside(!required);
        dialog.setOnCancelListener(ignored -> {
            if (required) finish();
        });
        dialog.setOnShowListener(ignored -> {
            styleMapSettingsDialog(dialog, required);
            if (!required) {
                View disclaimerView = keyForm.findViewWithTag("disclaimer_review");
                if (disclaimerView != null) {
                    disclaimerView.setOnClickListener(view -> {
                        dialog.dismiss();
                        showDisclaimerDialog(null, false);
                    });
                }
                View aboutView = keyForm.findViewWithTag("about_review");
                if (aboutView != null) {
                    aboutView.setOnClickListener(view -> {
                        dialog.dismiss();
                        showAboutDialog();
                    });
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                dialog.dismiss();
                if (required) finish();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> openAmapKeyConsole());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String key = keyField.getText().toString().trim();
                String securityCode = securityCodeField.getText().toString().trim();
                if (!key.matches("[0-9A-Za-z]{32}")) {
                    keyField.setError("请输入完整的 32 位高德 Web端(JS API) Key");
                    return;
                }
                if (!securityCode.matches("[0-9A-Za-z]{16,64}")) {
                    securityCodeField.setError("请输入有效的 JS API 安全密钥");
                    return;
                }
                if (!AmapKeyStore.saveJsConfiguration(this, key, securityCode)) {
                    keyField.setError("配置保存失败，请重试");
                    return;
                }
                amapJsApiKey = key;
                amapJsSecurityCode = securityCode;
                dialog.dismiss();
                if (interfaceInitialized) {
                    recreate();
                } else {
                    initializeInterface(savedInstanceState);
                }
            });
        });
        dialog.show();
    }

    private EditText createSettingsSecretField(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setTextSize(15);
        field.setTextColor(COLOR_DIALOG_TEXT);
        field.setHintTextColor(COLOR_DIALOG_SUBTLE);
        field.setSelectAllOnFocus(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setPadding(dp(16), 0, dp(16), 0);
        field.setBackground(rounded(COLOR_DIALOG_BUTTON, 12,
                COLOR_DIALOG_BORDER, 1));
        return field;
    }

    private Button createSettingsUtilityButton(String text, String description) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(COLOR_DIALOG_TEXT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setContentDescription(description);
        button.setBackground(rounded(COLOR_DIALOG_BUTTON, 11,
                COLOR_DIALOG_BORDER, 1));
        return button;
    }

    private void styleMapSettingsDialog(AlertDialog dialog, boolean required) {
        if (dialog == null) return;
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(rounded(COLOR_DIALOG_SURFACE, 20,
                    Color.TRANSPARENT, 0));
            dialog.getWindow().setDimAmount(0.56f);
        }
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        styleDialogButton(negative, COLOR_DIALOG_SUBTLE, null);
        styleDialogButton(neutral, COLOR_DIALOG_TEXT,
                rounded(COLOR_DIALOG_BUTTON, 11, COLOR_DIALOG_BORDER, 2));
        styleDialogButton(positive, Color.WHITE,
                rounded(COLOR_ACCENT, 11, COLOR_DIALOG_BORDER, 2));
        if (required && negative != null) negative.setTextColor(COLOR_DANGER);
    }

    private void styleDialogButton(Button button, int textColor, GradientDrawable background) {
        if (button == null) return;
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(textColor);
        button.setMinHeight(dp(42));
        button.setMinWidth(dp(56));
        button.setPadding(dp(12), 0, dp(12), 0);
        if (background != null) button.setBackground(background);
    }

    private void openAmapPrivacyPolicy() {
        openWebPage("https://lbs.amap.com/pages/privacy/");
    }

    private void openAmapKeyConsole() {
        openWebPage("https://console.amap.com/dev/key/app");
    }

    private void openWebPage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException exception) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAboutDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(6), dp(22), dp(2));
        content.setBackgroundColor(COLOR_DIALOG_SURFACE);

        TextView versionText = label("Mock Location\n版本 " + BuildConfig.VERSION_NAME
                + "（versionCode " + BuildConfig.VERSION_CODE + "）\n适配 Android 7.1.2 - Android 16",
                15, COLOR_DIALOG_TEXT, Typeface.BOLD);
        versionText.setLineSpacing(dp(3), 1f);
        content.addView(versionText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView updateStatus = label("点击“检查更新”获取最新版本信息", 13,
                COLOR_DIALOG_SUBTLE, Typeface.NORMAL);
        updateStatus.setPadding(0, dp(14), 0, dp(4));
        content.addView(updateStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("关于与更新")
                .setView(content)
                .setNegativeButton("关闭", null)
                .setPositiveButton("检查更新", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> checkForUpdates(dialog, updateStatus)));
        dialog.show();
    }

    private void scheduleAutomaticUpdateCheck() {
        String checkSchema = mapPreferences.getString("update_check_schema", "");
        long lastCheck = UPDATE_CHECK_SCHEMA.equals(checkSchema)
                ? mapPreferences.getLong("last_update_check_at", 0L) : 0L;
        long now = System.currentTimeMillis();
        if (now - lastCheck < 24L * 60L * 60L * 1_000L) return;
        mapPreferences.edit()
                .putString("update_check_schema", UPDATE_CHECK_SCHEMA)
                .putLong("last_update_check_at", now)
                .apply();
        mainHandler.postDelayed(() -> performUpdateCheck(null, null, true), 1_500L);
    }

    private void checkForUpdates(AlertDialog dialog, TextView statusView) {
        if (statusView != null) statusView.setText("正在检查更新…");
        if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        performUpdateCheck(dialog, statusView, false);
    }

    private void performUpdateCheck(AlertDialog dialog, TextView statusView, boolean automatic) {
        networkExecutor.execute(() -> {
            UpdateInfo update = fetchUpdateInfo();
            mainHandler.post(() -> {
                if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if (update == null) {
                    if (!automatic && statusView != null) {
                        statusView.setText("暂未获取到更新，请检查网络或更新清单地址");
                    }
                    return;
                }
                if (update.versionCode <= BuildConfig.VERSION_CODE) {
                    if (!automatic && statusView != null) {
                        statusView.setText("当前已是最新版本（" + BuildConfig.VERSION_NAME + "）");
                    }
                    return;
                }
                if (statusView != null) {
                    statusView.setText("发现新版本：" + update.versionName);
                }
                if (!isFinishing()) showUpdateDialog(update);
            });
        });
    }

    private UpdateInfo fetchUpdateInfo() {
        UpdateInfo best = null;
        for (String manifestUrl : UPDATE_MANIFEST_URLS) {
            UpdateInfo candidate = fetchUpdateInfo(manifestUrl);
            if (candidate != null && (best == null || candidate.versionCode > best.versionCode)) {
                best = candidate;
            }
        }
        return best;
    }

    private UpdateInfo fetchUpdateInfo(String manifestUrl) {
        HttpURLConnection connection = null;
        try {
            String cacheBust = "v=" + (System.currentTimeMillis() / 60_000L);
            connection = (HttpURLConnection) new URL(manifestUrl + "?" + cacheBust).openConnection();
            connection.setConnectTimeout(UPDATE_TIMEOUT_MS);
            connection.setReadTimeout(UPDATE_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "MockLocation/" + BuildConfig.VERSION_NAME);
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) return null;
            try (InputStream stream = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line).append('\n');
                    if (body.length() > UPDATE_MAX_BYTES) return null;
                }
                JSONObject json = new JSONObject(body.toString());
                int versionCode = json.optInt("versionCode", -1);
                String versionName = json.optString("versionName", "").trim();
                String downloadUrl = json.optString("downloadUrl", "").trim();
                String notes = json.optString("releaseNotes",
                        json.optString("content", "")).trim();
                if (versionCode < 0 || versionName.isEmpty()) return null;
                return new UpdateInfo(versionCode, versionName, downloadUrl, notes);
            }
        } catch (Exception exception) {
            Log.w(LOG_TAG, "检查更新失败", exception);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void showUpdateDialog(UpdateInfo update) {
        StringBuilder message = new StringBuilder()
                .append("发现新版本 ").append(update.versionName)
                .append("（versionCode ").append(update.versionCode).append("）");
        if (!update.releaseNotes.isEmpty()) {
            message.append("\n\n更新内容：\n").append(update.releaseNotes);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("有新版本可用")
                .setMessage(message.toString())
                .setNegativeButton("稍后", null)
                .setPositiveButton("打开下载页", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    if (update.downloadUrl.isEmpty()) {
                        Toast.makeText(this, "更新清单未配置下载地址", Toast.LENGTH_LONG).show();
                        return;
                    }
                    openWebPage(update.downloadUrl);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showLocationDiagnostics() {
        TextView report = label(buildDiagnosticsText(), 14, COLOR_DIALOG_TEXT, Typeface.NORMAL);
        report.setLineSpacing(dp(3), 1f);
        report.setTextIsSelectable(true);
        report.setPadding(dp(4), dp(4), dp(4), dp(8));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_DIALOG_SURFACE);
        scrollView.addView(report, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("定位诊断")
                .setView(scrollView)
                .setNegativeButton("关闭", null)
                .setNeutralButton("系统定位设置", null)
                .setPositiveButton("刷新", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(view -> openSystemLocationSettings());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(view -> report.setText(buildDiagnosticsText()));
        });
        dialog.show();
    }

    private String buildDiagnosticsText() {
        StringBuilder report = new StringBuilder();
        report.append("应用\n")
                .append("版本：").append(BuildConfig.VERSION_NAME).append("\n")
                .append("包名：").append(getPackageName()).append("\n\n");
        report.append("系统\n")
                .append("Android：").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
                .append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n")
                .append("系统定位开关：").append(locationSwitchState()).append("\n\n");

        report.append("授权\n")
                .append("精确定位权限：").append(permissionState(Manifest.permission.ACCESS_FINE_LOCATION)).append("\n")
                .append("粗略定位权限：").append(permissionState(Manifest.permission.ACCESS_COARSE_LOCATION)).append("\n")
                .append("Mock Location AppOp：").append(mockLocationAppOpState()).append("\n")
                .append("Root 权限：").append(rootGranted ? "已获得" : "未检测到或尚未检测").append("\n")
                .append("Root 模式：").append(rootOnly ? "已启用" : "未启用").append("\n\n");

        report.append("定位源\n")
                .append("GPS：").append(providerState(LocationManager.GPS_PROVIDER)).append("\n")
                .append("网络：").append(providerState(LocationManager.NETWORK_PROVIDER)).append("\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            report.append("fused：").append(providerState("fused")).append("\n");
        } else {
            report.append("fused：Android 7 兼容模式已跳过\n");
        }
        report.append("被动：").append(providerState(LocationManager.PASSIVE_PROVIDER)).append("\n\n");

        report.append("应用状态\n")
                .append("模拟状态：").append(running ? "运行中" : "未运行").append("\n")
                .append("高德 JS API：")
                .append(!amapJsApiKey.isEmpty() && !amapJsSecurityCode.isEmpty() ? "已配置" : "未配置")
                .append("\n")
                .append("免责说明：")
                .append(mapPreferences.getBoolean(KEY_DISCLAIMER_AGREED, false) ? "已确认" : "未确认");
        return report.toString();
    }

    private String permissionState(String permission) {
        try {
            return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED ? "已授予" : "未授予";
        } catch (RuntimeException exception) {
            return "读取失败";
        }
    }

    private String locationSwitchState() {
        if (locationManager == null) return "服务不可用";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return locationManager.isLocationEnabled() ? "已开启" : "已关闭";
            }
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? "已开启" : "已关闭";
        } catch (RuntimeException exception) {
            return "读取失败";
        }
    }

    private String mockLocationAppOpState() {
        try {
            AppOpsManager manager = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (manager == null) return "服务不可用";
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(), getPackageName())
                    : manager.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(), getPackageName());
            if (mode == AppOpsManager.MODE_ALLOWED) return "已允许";
            if (mode == AppOpsManager.MODE_IGNORED) return "已忽略";
            if (mode == AppOpsManager.MODE_ERRORED) return "被拒绝";
            return "未设置";
        } catch (RuntimeException exception) {
            return "读取失败";
        }
    }

    @SuppressLint("MissingPermission")
    private String providerState(String provider) {
        if (locationManager == null) return "服务不可用";
        try {
            if (!locationManager.isProviderEnabled(provider)) return "关闭";
            Location last = locationManager.getLastKnownLocation(provider);
            if (last == null) return "开启 · 无缓存";
            return "开启 · " + (isMockLocation(last) ? "模拟" : "真实/未标记")
                    + " · " + formatLocationAge(last.getTime());
        } catch (RuntimeException exception) {
            return "不可用";
        }
    }

    private String formatLocationAge(long timestamp) {
        long age = System.currentTimeMillis() - timestamp;
        if (age < 0 || age < 1_000L) return "刚刚";
        if (age < 60_000L) return (age / 1_000L) + " 秒前";
        if (age < 3_600_000L) return (age / 60_000L) + " 分钟前";
        return (age / 3_600_000L) + " 小时前";
    }

    private void openSystemLocationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } catch (RuntimeException exception) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private View buildInterface() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACKGROUND);

        mapView = new AmapWebMapView(this, amapJsApiKey, amapJsSecurityCode);
        mapView.setOnCenterChangedListener((latitude, longitude) -> {
            boolean centerChanged = !Double.isFinite(lastMapCenterLatitude)
                    || Math.abs(latitude - lastMapCenterLatitude) > 0.000001d
                    || Math.abs(longitude - lastMapCenterLongitude) > 0.000001d;
            if (centerChanged) {
                lastMapCenterLatitude = latitude;
                lastMapCenterLongitude = longitude;
            }
            if (!matchesSelectedPlace(latitude, longitude)) selectedPlaceName = "";
            coordinateText.setText(formatCoordinate(latitude, longitude));
            if (centerChanged) scheduleMapLocationUpdate(latitude, longitude);
        });
        mapView.setOnSearchResultListener(new AmapWebMapView.OnSearchResultListener() {
            @Override
            public void onSearchResults(List<AmapWebMapView.SearchResult> results) {
                searchInput.setEnabled(true);
                searchInput.setHint("搜索城市、街道或地点");
                ArrayList<SearchResult> converted = new ArrayList<>();
                for (AmapWebMapView.SearchResult result : results) {
                    converted.add(new SearchResult(result.name, result.latitude, result.longitude));
                }
                showSearchResults(converted);
            }

            @Override
            public void onSearchFailed(String message) {
                searchInput.setEnabled(true);
                searchInput.setHint("搜索城市、街道或地点");
                Toast.makeText(MainActivity.this,
                        message == null || message.isEmpty() ? "高德搜索失败" : message,
                        Toast.LENGTH_LONG).show();
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
        zoomControls.setBackground(rounded(COLOR_CARD, 16, COLOR_BORDER, 2));
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
        bar.setBackground(rounded(COLOR_CARD, 20, COLOR_BORDER, 2));
        bar.setElevation(dp(5));

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

        Button searchButton = createActionButton("搜索", COLOR_ACCENT, COLOR_TEXT);
        searchButton.setContentDescription("搜索地点");
        searchButton.setOnClickListener(view -> searchLocation());
        bar.addView(searchButton, new LinearLayout.LayoutParams(dp(72), dp(44)));
        return bar;
    }

    private View buildControlPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(10), dp(18), dp(16));
        panel.setBackground(rounded(COLOR_CARD, 24, COLOR_BORDER, 2));
        panel.setElevation(dp(8));

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
        statusText.setContentDescription("查看定位诊断");
        statusText.setOnClickListener(view -> showLocationDiagnostics());
        locationRow.addView(statusText);
        panel.addView(locationRow);

        LinearLayout utilityRow = new LinearLayout(this);
        utilityRow.setOrientation(LinearLayout.HORIZONTAL);
        Button historyButton = createCompactButton("历史位置", "查看模拟过的位置");
        historyButton.setBackground(rounded(Color.rgb(255, 250, 230), 13, COLOR_BORDER, 2));
        historyButton.setOnClickListener(view -> showHistoryDialog());
        utilityRow.addView(historyButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button trajectoryButton = createCompactButton("轨迹模拟", "打开轨迹模拟功能");
        trajectoryButton.setBackground(rounded(Color.rgb(232, 243, 255), 13, COLOR_BORDER, 2));
        trajectoryButton.setOnClickListener(view -> {
            try {
                startActivity(new Intent(this, TrajectoryActivity.class));
            } catch (RuntimeException exception) {
                Toast.makeText(this, "无法打开轨迹模拟", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams trajectoryParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        trajectoryParams.leftMargin = dp(8);
        utilityRow.addView(trajectoryButton, trajectoryParams);
        Button mapSettingsButton = createCompactButton("地图设置", "配置高德地图 Key");
        mapSettingsButton.setBackground(rounded(Color.rgb(255, 238, 231), 13, COLOR_BORDER, 2));
        mapSettingsButton.setOnClickListener(view -> openMapSettings(false));
        LinearLayout.LayoutParams mapSettingsParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        mapSettingsParams.leftMargin = dp(8);
        utilityRow.addView(mapSettingsButton, mapSettingsParams);
        LinearLayout.LayoutParams utilityParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        utilityParams.topMargin = dp(12);
        panel.addView(utilityRow, utilityParams);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, dp(12), 0, 0);
        setupButton = createActionButton("Root 模式", COLOR_TEAL, Color.WHITE);
        setupButton.setOnClickListener(view -> showSetupOptions());
        LinearLayout.LayoutParams setupParams = new LinearLayout.LayoutParams(0, dp(52), 0.9f);
        setupParams.rightMargin = dp(10);
        actionRow.addView(setupButton, setupParams);

        startButton = createActionButton("开始模拟", COLOR_ACCENT, COLOR_TEXT);
        startButton.setOnClickListener(view -> {
            if (running) {
                cancelPendingMapLocationUpdate();
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
                    .setMessage("可以在开发者选项中选择 Mock Location，也可以在连接设备的电脑上执行 ADB 授权命令。授权后返回本应用即可开始。")
                    .setNegativeButton("稍后", null)
                    .setNeutralButton("ADB 无 Root", (dialog, which) -> showAdbAuthorizationDialog())
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
                .setMessage("Root 固定位置模式会自动授予 Mock Location 权限，并持续向 GPS、网络和 fused 通道提交同一个坐标，速度固定为 0。也可以使用 ADB 无 Root 授权，只需在连接设备的电脑上执行一条命令。")
                .setNegativeButton("取消", null)
                .setNeutralButton("ADB 无 Root", (dialog, which) -> showAdbAuthorizationDialog())
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

    private void showAdbAuthorizationDialog() {
        String packageName = getPackageName();
        String command = "adb shell appops set " + packageName + " android:mock_location allow";
        boolean authorized = isSelectedMockLocationApp();
        String message;
        if (authorized) {
            message = "当前应用已经获得系统模拟位置授权，可以直接点击“开始模拟”。\n\n如需在另一台设备授权，可执行：\n" + command;
        } else {
            message = "适用于 Android 7–16 的无 Root 授权方式：\n\n"
                    + "1. 打开设备 USB 调试，并让电脑上的 adb 识别设备。\n"
                    + "2. 在电脑终端执行：\n" + command + "\n"
                    + "3. 返回本应用后再次点击“开始模拟”。\n\n"
                    + "此方式只授予本应用 Mock Location AppOp，不会修改 Root 权限。";
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ADB 无 Root 授权")
                .setMessage(message)
                .setNegativeButton("关闭", null)
                .setNeutralButton("打开开发者设置", null)
                .setPositiveButton("复制命令", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> openDeveloperSettings());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Mock Location ADB 授权命令", command));
                    Toast.makeText(this, "命令已复制，请粘贴到电脑终端执行", Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show();
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
        try {
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
        } catch (RuntimeException exception) {
            Log.e(LOG_TAG, "开始获取当前位置失败", exception);
            finishLocationRequest();
            Toast.makeText(this, "无法获取当前位置，请确认系统定位已开启", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressLint("MissingPermission") // Called only after ACCESS_FINE_LOCATION is granted.
    private void locateCurrentPosition() {
        if (locationManager == null || locateButton == null || isFinishing()) {
            Toast.makeText(this, "系统定位服务不可用", Toast.LENGTH_LONG).show();
            return;
        }
        finishLocationRequest();
        locateButton.setEnabled(false);
        locateButton.setText("…");

        Location cached = bestLastKnownLocation();
        pendingBestLocation = cached;
        if (isRecentUsableLocation(cached)) {
            showDeviceLocation(cached);
            return;
        }

        locationTimeout = () -> {
            try {
                Location fallback = pendingBestLocation != null
                        ? pendingBestLocation : bestLastKnownLocation();
                finishLocationRequest();
                if (fallback != null) {
                    showDeviceLocation(fallback);
                } else {
                    Toast.makeText(this, "暂时无法获取当前位置，请确认系统定位已开启", Toast.LENGTH_LONG).show();
                }
            } catch (RuntimeException exception) {
                Log.e(LOG_TAG, "定位超时回退失败", exception);
                finishLocationRequest();
                Toast.makeText(this, "暂时无法获取当前位置，请确认系统定位已开启", Toast.LENGTH_LONG).show();
            }
        };
        // Keep listening for the full timeout when the cached fix is weak. In the
        // virtual device a GPS fix can take several seconds to arrive, while the
        // network provider often reports a 400–500 m estimate first.
        mainHandler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MS);

        try {
            pendingLocationListener = location -> {
                if (!isFinishing() && location != null && !isMockLocation(location)) {
                    if (isBetterLocation(location, pendingBestLocation)) {
                        pendingBestLocation = location;
                    }
                    // A good GPS fix is safe to apply immediately. Otherwise keep
                    // sampling briefly so a stale network/fused result cannot win.
                    if (isHighConfidenceLocation(location)) {
                        Location best = pendingBestLocation;
                        finishLocationRequest();
                        showDeviceLocation(best == null ? location : best);
                    }
                }
            };
            boolean registered = false;
            String[] providers = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused"}
                    : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
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
            if (pendingBestLocation != null) {
                Location fallback = pendingBestLocation;
                finishLocationRequest();
                showDeviceLocation(fallback);
            } else {
                finishLocationRequest();
                Toast.makeText(this, "无法获取当前位置，请确认系统定位已开启", Toast.LENGTH_LONG).show();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private Location bestLastKnownLocation() {
        Location best = null;
        String[] providers = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                "fused", LocationManager.PASSIVE_PROVIDER}
                : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER};
        for (String provider : providers) {
            try {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate == null || isMockLocation(candidate)) continue;
                if (isBetterLocation(candidate, best)) best = candidate;
            } catch (RuntimeException ignored) {
            }
        }
        return best;
    }

    private boolean isHighConfidenceLocation(Location location) {
        if (location == null || !Double.isFinite(location.getLatitude())
                || !Double.isFinite(location.getLongitude())) return false;
        float accuracy = location.getAccuracy();
        long age = Math.max(0L, System.currentTimeMillis() - location.getTime());
        return accuracy > 0f && accuracy <= 25f && age <= 30_000L;
    }

    private boolean isRecentUsableLocation(Location location) {
        if (location == null || !Double.isFinite(location.getLatitude())
                || !Double.isFinite(location.getLongitude())) return false;
        float accuracy = location.getAccuracy();
        long age = Math.max(0L, System.currentTimeMillis() - location.getTime());
        return accuracy > 0f && accuracy <= 100f && age <= 120_000L;
    }

    private boolean isBetterLocation(Location candidate, Location current) {
        if (candidate == null) return false;
        if (current == null) return true;
        long timeDelta = candidate.getTime() - current.getTime();
        if (timeDelta > 120_000L) return true;
        if (timeDelta < -120_000L) return false;
        float candidateAccuracy = candidate.getAccuracy() > 0f
                ? candidate.getAccuracy() : Float.MAX_VALUE;
        float currentAccuracy = current.getAccuracy() > 0f
                ? current.getAccuracy() : Float.MAX_VALUE;
        if (candidateAccuracy + 5f < currentAccuracy) return true;
        if (currentAccuracy + 5f < candidateAccuracy) return false;
        return timeDelta > 0L;
    }

    private void showDeviceLocation(Location location) {
        if (location == null || isFinishing()) return;
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90d || latitude > 90d || longitude < -180d || longitude > 180d) {
            Toast.makeText(this, "系统返回了无效的位置", Toast.LENGTH_LONG).show();
            return;
        }
        finishLocationRequest();
        selectedPlaceName = "设备当前位置";
        selectedPlaceLatitude = latitude;
        selectedPlaceLongitude = longitude;
        if (mapView == null) return;
        mapView.setCenter(latitude, longitude);
        mapView.zoomToAtLeast(16);
        Toast.makeText(this, "已定位到当前位置 · 精度约 " + Math.round(location.getAccuracy()) + " 米", Toast.LENGTH_SHORT).show();
    }

    private boolean isMockLocation(Location location) {
        try {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
                    && location.isFromMockProvider();
        } catch (RuntimeException exception) {
            Log.w(LOG_TAG, "读取位置来源标记失败，继续使用该位置", exception);
            return false;
        }
    }

    private void finishLocationRequest() {
        if (locationTimeout != null) {
            mainHandler.removeCallbacks(locationTimeout);
            locationTimeout = null;
        }
        if (pendingLocationListener != null) {
            try {
                if (locationManager != null) locationManager.removeUpdates(pendingLocationListener);
            } catch (RuntimeException ignored) {
            }
            pendingLocationListener = null;
        }
        pendingBestLocation = null;
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MAP_SETTINGS) return;
        String key = AmapKeyStore.getJsApiKey(this);
        String securityCode = AmapKeyStore.getJsSecurityCode(this);
        if (!key.isEmpty() && !securityCode.isEmpty()) {
            amapJsApiKey = key;
            amapJsSecurityCode = securityCode;
            if (interfaceInitialized) recreate();
            else initializeInterface(null);
        } else if (!interfaceInitialized) {
            finish();
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
        statusText.setTextColor(running ? COLOR_BLUE : COLOR_SUBTLE);
        statusText.setBackground(rounded(running ? Color.rgb(232, 244, 255) : COLOR_MUTED,
                14, running ? COLOR_BLUE : COLOR_BORDER, 2));
        startButton.setText(running ? "停止模拟" : "开始模拟");
        startButton.setTextColor(running ? Color.WHITE : COLOR_TEXT);
        startButton.setBackground(rounded(running ? COLOR_DANGER : COLOR_ACCENT, 15,
                COLOR_BORDER, 2));
        String setupLabel;
        if (rootOnly && rootGranted) {
            setupLabel = "Root 模式已配置";
        } else if (rootGranted) {
            setupLabel = "启用 Root 模式";
        } else if (isSelectedMockLocationApp()) {
            setupLabel = "系统位置已授权";
        } else {
            setupLabel = "授权设置";
        }
        setupButton.setText(setupLabel);
        if (message != null && (message.startsWith("无法") || message.startsWith("未获得") || message.startsWith("模拟失败"))) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void scheduleMapLocationUpdate(double latitude, double longitude) {
        if (!running) {
            cancelPendingMapLocationUpdate();
            writeCoordinatePreferences(latitude, longitude);
            return;
        }

        pendingMapLatitude = latitude;
        pendingMapLongitude = longitude;
        cancelPendingMapLocationUpdate();

        Runnable countdownTask = new Runnable() {
            private int secondsRemaining = MAP_UPDATE_DELAY_SECONDS;

            @Override
            public void run() {
                if (pendingMapCountdownTask != this || pendingMapCommitTask == null || !running) return;
                renderLocationUpdateCountdown(secondsRemaining);
                if (secondsRemaining > 1) {
                    secondsRemaining--;
                    mainHandler.postDelayed(this, 1_000L);
                }
            }
        };
        Runnable commitTask = new Runnable() {
            @Override
            public void run() {
                if (pendingMapCommitTask != this) return;
                pendingMapCommitTask = null;
                if (pendingMapCountdownTask == countdownTask) {
                    mainHandler.removeCallbacks(pendingMapCountdownTask);
                    pendingMapCountdownTask = null;
                }
                if (!running) return;
                writeCoordinatePreferences(pendingMapLatitude, pendingMapLongitude);
                sendServiceAction(LocationContract.ACTION_UPDATE);
                renderState(null);
            }
        };
        pendingMapCommitTask = commitTask;
        pendingMapCountdownTask = countdownTask;
        mainHandler.post(countdownTask);
        mainHandler.postDelayed(commitTask, MAP_UPDATE_DELAY_MS);
    }

    private void renderLocationUpdateCountdown(int secondsRemaining) {
        if (statusText == null) return;
        statusText.setText("● " + secondsRemaining + "秒后更新到此地址");
        statusText.setTextColor(COLOR_BLUE);
        statusText.setBackground(rounded(Color.rgb(232, 244, 255), 14, COLOR_BLUE, 2));
    }

    private void cancelPendingMapLocationUpdate() {
        if (pendingMapCommitTask != null) {
            mainHandler.removeCallbacks(pendingMapCommitTask);
            pendingMapCommitTask = null;
        }
        if (pendingMapCountdownTask != null) {
            mainHandler.removeCallbacks(pendingMapCountdownTask);
            pendingMapCountdownTask = null;
        }
    }

    private void showHistoryDialog() {
        List<LocationHistoryStore.Entry> entries = historyStore.getEntries();
        if (entries.isEmpty()) {
            Toast.makeText(this, "还没有模拟过的位置", Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_DIALOG_SURFACE);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(8), dp(12), dp(8));
        list.setBackgroundColor(COLOR_DIALOG_SURFACE);
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
            row.setBackground(rounded(COLOR_DIALOG_SURFACE, 12, COLOR_DIALOG_BORDER, 1));

            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            String title = entry.name.isEmpty() ? "固定位置" : entry.name;
            labels.addView(label(title, 14, COLOR_DIALOG_TEXT, Typeface.BOLD));
            String detail = String.format(
                    Locale.US,
                    "%.6f, %.6f  ·  %s",
                    entry.latitude,
                    entry.longitude,
                    timeFormat.format(new Date(entry.usedAt))
            );
            labels.addView(label(detail, 11, COLOR_DIALOG_SUBTLE, Typeface.NORMAL));
            row.addView(labels, new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
            ));

            Button useButton = createCompactButton("使用", "使用这个历史位置");
            useButton.setTextColor(COLOR_DIALOG_TEXT);
            useButton.setBackground(rounded(COLOR_DIALOG_BUTTON, 12, COLOR_DIALOG_BORDER, 1));
            useButton.setOnClickListener(view -> {
                selectHistoryEntry(entry);
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            });
            LinearLayout.LayoutParams useParams = new LinearLayout.LayoutParams(dp(60), dp(38));
            useParams.leftMargin = dp(8);
            row.addView(useButton, useParams);

            Button deleteButton = createCompactButton("删除", "删除这个历史位置");
            deleteButton.setTextColor(COLOR_DANGER);
            deleteButton.setBackground(rounded(Color.rgb(254, 242, 242), 12, Color.rgb(254, 202, 202), 1));
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
        mapView.search(query);
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
        if (mapView != null) mapView.onResume();
        if (setupButton != null) {
            renderState(null);
        }
    }

    @Override
    protected void onPause() {
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (mapView != null) mapView.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        cancelPendingMapLocationUpdate();
        finishLocationRequest();
        if (interfaceInitialized) unregisterReceiver(stateReceiver);
        if (mapView != null) mapView.onDestroy();
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
        button.setBackground(rounded(background, 15, COLOR_BORDER, 2));
        return button;
    }

    private Button createCompactButton(String text, String description) {
        Button button = createActionButton(text, COLOR_MUTED, COLOR_TEXT);
        button.setContentDescription(description);
        button.setBackground(rounded(COLOR_MUTED, 12, COLOR_BORDER, 2));
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

    private static final class UpdateInfo {
        final int versionCode;
        final String versionName;
        final String downloadUrl;
        final String releaseNotes;

        UpdateInfo(int versionCode, String versionName, String downloadUrl, String releaseNotes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.downloadUrl = downloadUrl;
            this.releaseNotes = releaseNotes;
        }
    }
}
