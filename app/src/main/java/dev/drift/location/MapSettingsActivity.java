package dev.drift.location;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-page map provider settings. Keeps secrets masked and stores them in Android Keystore. */
public final class MapSettingsActivity extends Activity {
    private static final int COLOR_BACKGROUND = Color.rgb(239, 238, 227);
    private static final int COLOR_CARD = Color.rgb(255, 253, 245);
    private static final int COLOR_INPUT = Color.rgb(250, 245, 226);
    private static final int COLOR_BORDER = Color.rgb(7, 7, 6);
    private static final int COLOR_TEXT = Color.rgb(7, 7, 6);
    private static final int COLOR_SUBTLE = Color.rgb(76, 92, 94);
    private static final int COLOR_ACCENT = Color.rgb(250, 175, 20);
    private static final int COLOR_BLUE = Color.rgb(47, 152, 232);
    private static final int COLOR_ORANGE = Color.rgb(232, 74, 38);
    private static final int COLOR_TEAL = Color.rgb(65, 121, 140);
    private static final String UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/1162107757/mock-location/main/update.json";
    private static final int UPDATE_TIMEOUT_MS = 8_000;
    private static final int UPDATE_MAX_BYTES = 256 * 1024;
    public static final String EXTRA_REQUIRED = "required";

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private EditText keyField;
    private EditText securityCodeField;
    private TextView updateStatus;
    private boolean required;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        required = getIntent().getBooleanExtra(EXTRA_REQUIRED, false);
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
        setContentView(buildPage());
    }

    private View buildPage() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(14), dp(8));
        header.setBackground(rounded(COLOR_CARD, 18, COLOR_BORDER, 2));

        Button backButton = actionButton(required ? "退出" : "‹", COLOR_INPUT, COLOR_TEXT, 13);
        backButton.setContentDescription(required ? "退出地图设置" : "返回首页");
        backButton.setOnClickListener(view -> finishSettings());
        header.addView(backButton, new LinearLayout.LayoutParams(dp(required ? 58 : 48), dp(44)));

        TextView title = label("地图设置", 21, COLOR_TEXT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView headerTag = label("Mock", 12, COLOR_TEAL, Typeface.BOLD);
        headerTag.setGravity(Gravity.CENTER);
        header.addView(headerTag, new LinearLayout.LayoutParams(dp(52), dp(44)));

        FrameLayout.LayoutParams headerParams = frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62), Gravity.TOP, 14, 14, 14, 0);
        root.addView(header, headerParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(20), dp(18), dp(18));
        scrollView.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buildBody(body);

        FrameLayout.LayoutParams scrollParams = frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP, 0, 76, 0, 92);
        root.addView(scrollView, scrollParams);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(18), dp(10), dp(18), dp(12));
        bottomBar.setBackground(rounded(COLOR_CARD, 22, COLOR_BORDER, 2));
        Button saveButton = actionButton("保存设置", COLOR_ACCENT, COLOR_TEXT, 16);
        saveButton.setContentDescription("保存地图设置");
        saveButton.setOnClickListener(view -> saveConfiguration());
        bottomBar.addView(saveButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        FrameLayout.LayoutParams bottomParams = frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(78), Gravity.BOTTOM, 14, 0, 14, 14);
        root.addView(bottomBar, bottomParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
                topInset = systemBars.top;
                bottomInset = systemBars.bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            headerParams.topMargin = topInset + dp(14);
            scrollParams.topMargin = topInset + dp(76);
            bottomParams.bottomMargin = bottomInset + dp(14);
            scrollParams.bottomMargin = bottomInset + dp(94);
            header.setLayoutParams(headerParams);
            bottomBar.setLayoutParams(bottomParams);
            scrollView.setLayoutParams(scrollParams);
            return insets;
        });
        return root;
    }

    private void buildBody(LinearLayout body) {
        TextView eyebrow = label(required ? "首次配置" : "服务与版本", 12, COLOR_ORANGE, Typeface.BOLD);
        body.addView(eyebrow, wrapParams(0));

        TextView headline = label(required ? "先把地图服务接入 Mock Location" : "配置你的地图工作台",
                25, COLOR_TEXT, Typeface.BOLD);
        headline.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams headlineParams = wrapParams(dp(6));
        body.addView(headline, headlineParams);

        TextView intro = label(required
                        ? "填写高德 Web 端 JS API Key 后，地图和地点搜索才会开始工作。"
                        : "调整地图服务配置，查看使用说明和应用更新。",
                14, COLOR_SUBTLE, Typeface.NORMAL);
        intro.setLineSpacing(dp(2), 1f);
        body.addView(intro, wrapParams(dp(8)));

        LinearLayout providerCard = card();
        LinearLayout providerHeader = new LinearLayout(this);
        providerHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView providerBadge = label("A", 22, Color.WHITE, Typeface.BOLD);
        providerBadge.setGravity(Gravity.CENTER);
        providerBadge.setBackground(rounded(COLOR_BLUE, 14, COLOR_BORDER, 2));
        providerHeader.addView(providerBadge, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout providerTitles = new LinearLayout(this);
        providerTitles.setOrientation(LinearLayout.VERTICAL);
        TextView providerName = label("高德地图", 17, COLOR_TEXT, Typeface.BOLD);
        TextView providerType = label("Web 端 JS API · 地图与地点搜索", 12, COLOR_SUBTLE, Typeface.NORMAL);
        providerTitles.addView(providerName);
        providerTitles.addView(providerType, wrapParams(dp(3)));
        LinearLayout.LayoutParams providerTitleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        providerTitleParams.leftMargin = dp(12);
        providerHeader.addView(providerTitles, providerTitleParams);
        TextView configured = label(hasConfiguration() ? "已配置" : "待配置", 12,
                hasConfiguration() ? COLOR_TEAL : COLOR_ORANGE, Typeface.BOLD);
        configured.setGravity(Gravity.CENTER);
        configured.setPadding(dp(10), 0, dp(10), 0);
        configured.setBackground(rounded(hasConfiguration() ? Color.rgb(226, 242, 245)
                : Color.rgb(255, 238, 231), 12, COLOR_BORDER, 1));
        providerHeader.addView(configured, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        providerCard.addView(providerHeader);
        TextView providerHint = label("密钥仅用于本机地图请求，并使用 Android Keystore 加密保存。", 12,
                COLOR_SUBTLE, Typeface.NORMAL);
        providerHint.setLineSpacing(dp(2), 1f);
        providerCard.addView(providerHint, wrapParams(dp(12)));
        body.addView(providerCard, wrapParams(dp(18)));

        TextView configTitle = label("服务密钥", 13, COLOR_TEAL, Typeface.BOLD);
        body.addView(configTitle, wrapParams(0));
        keyField = secretField("Web 端 JS API Key");
        body.addView(keyField, fixedParams(dp(56), dp(8)));
        securityCodeField = secretField("JS API 安全密钥（jscode）");
        body.addView(securityCodeField, fixedParams(dp(56), dp(10)));
        TextView keyHint = label("已保存内容不会回显；输入新值即可替换当前配置。", 12,
                COLOR_SUBTLE, Typeface.NORMAL);
        body.addView(keyHint, wrapParams(dp(8)));

        TextView actionsTitle = label("快捷入口", 13, COLOR_TEAL, Typeface.BOLD);
        body.addView(actionsTitle, wrapParams(dp(20)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button consoleButton = utilityButton("申请 Key", "打开高德 Key 控制台", COLOR_ORANGE);
        consoleButton.setOnClickListener(view -> openWebPage(
                "https://console.amap.com/dev/key/app"));
        actions.addView(consoleButton, new LinearLayout.LayoutParams(0, dp(52), 1f));
        Button disclaimerButton = utilityButton("使用须知", "查看使用须知与免责说明", COLOR_TEAL);
        disclaimerButton.setOnClickListener(view -> showDisclaimerDialog());
        LinearLayout.LayoutParams disclaimerParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        disclaimerParams.leftMargin = dp(10);
        actions.addView(disclaimerButton, disclaimerParams);
        body.addView(actions, wrapParams(dp(8)));

        TextView updateTitle = label("关于与更新", 13, COLOR_TEAL, Typeface.BOLD);
        body.addView(updateTitle, wrapParams(dp(20)));
        LinearLayout updateCard = card();
        TextView version = label("Mock Location  ·  " + BuildConfig.VERSION_NAME,
                16, COLOR_TEXT, Typeface.BOLD);
        updateCard.addView(version);
        TextView compatibility = label("versionCode " + BuildConfig.VERSION_CODE
                        + "  ·  Android 7.1.2 - Android 16", 12,
                COLOR_SUBTLE, Typeface.NORMAL);
        updateCard.addView(compatibility, wrapParams(dp(4)));
        updateStatus = label("点击检查是否有新版本", 12, COLOR_SUBTLE, Typeface.NORMAL);
        updateStatus.setLineSpacing(dp(2), 1f);
        updateCard.addView(updateStatus, wrapParams(dp(12)));
        Button updateButton = actionButton("检查更新", Color.rgb(232, 243, 255), COLOR_TEXT, 13);
        updateButton.setContentDescription("检查应用更新");
        updateButton.setOnClickListener(view -> checkForUpdates(updateButton));
        updateCard.addView(updateButton, fixedParams(dp(46), dp(10)));
        body.addView(updateCard, wrapParams(dp(18)));
    }

    private boolean hasConfiguration() {
        return isValidKey(AmapKeyStore.getJsApiKey(this))
                && isValidSecurity(AmapKeyStore.getJsSecurityCode(this));
    }

    private EditText secretField(String hint) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setTextSize(15);
        field.setTextColor(COLOR_TEXT);
        field.setHintTextColor(COLOR_SUBTLE);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSelectAllOnFocus(true);
        field.setPadding(dp(16), 0, dp(16), 0);
        field.setBackground(rounded(COLOR_INPUT, 14, COLOR_BORDER, 2));
        return field;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(COLOR_CARD, 18, COLOR_BORDER, 2));
        return card;
    }

    private Button utilityButton(String text, String description, int accent) {
        Button button = actionButton(text, COLOR_CARD, COLOR_TEXT, 13);
        button.setContentDescription(description);
        button.setCompoundDrawablePadding(dp(4));
        button.setBackground(rounded(accent == COLOR_ORANGE ? Color.rgb(255, 238, 231)
                : Color.rgb(226, 242, 245), 14, COLOR_BORDER, 2));
        return button;
    }

    private Button actionButton(String text, int background, int foreground, float textSize) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(textSize);
        button.setTextColor(foreground);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(rounded(background, 14, COLOR_BORDER, 2));
        return button;
    }

    private void saveConfiguration() {
        String key = keyField == null ? "" : keyField.getText().toString().trim();
        String securityCode = securityCodeField == null ? "" : securityCodeField.getText().toString().trim();
        if (!key.matches("[0-9A-Za-z]{32}")) {
            keyField.setError("请输入完整的 32 位高德 Web 端 JS API Key");
            keyField.requestFocus();
            return;
        }
        if (!securityCode.matches("[0-9A-Za-z]{16,64}")) {
            securityCodeField.setError("请输入有效的 JS API 安全密钥");
            securityCodeField.requestFocus();
            return;
        }
        if (!AmapKeyStore.saveJsConfiguration(this, key, securityCode)) {
            Toast.makeText(this, "配置保存失败，请重试", Toast.LENGTH_LONG).show();
            return;
        }
        setResult(RESULT_OK);
        finish();
    }

    private void checkForUpdates(Button checkButton) {
        if (checkButton != null) {
            checkButton.setEnabled(false);
            checkButton.setText("检查中…");
        }
        if (updateStatus != null) updateStatus.setText("正在读取更新清单…");
        networkExecutor.execute(() -> {
            UpdateInfo update = fetchUpdateInfo();
            runOnUiThread(() -> {
                if (checkButton != null) {
                    checkButton.setEnabled(true);
                    checkButton.setText("检查更新");
                }
                if (update == null) {
                    if (updateStatus != null) updateStatus.setText("暂未获取到更新，请检查网络");
                    return;
                }
                if (update.versionCode <= BuildConfig.VERSION_CODE) {
                    if (updateStatus != null) updateStatus.setText("当前已是最新版本");
                    return;
                }
                if (updateStatus != null) updateStatus.setText("发现新版本：" + update.versionName);
                showUpdateDialog(update);
            });
        });
    }

    private UpdateInfo fetchUpdateInfo() {
        HttpURLConnection connection = null;
        try {
            String url = UPDATE_MANIFEST_URL + "?v=" + (System.currentTimeMillis() / 60_000L);
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(UPDATE_TIMEOUT_MS);
            connection.setReadTimeout(UPDATE_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
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
                int code = json.optInt("versionCode", -1);
                String name = json.optString("versionName", "").trim();
                String downloadUrl = json.optString("downloadUrl", "").trim();
                String notes = json.optString("releaseNotes", "").trim();
                return code >= 0 && !name.isEmpty()
                        ? new UpdateInfo(code, name, downloadUrl, notes) : null;
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void showUpdateDialog(UpdateInfo update) {
        String message = "发现新版本 " + update.versionName + "（versionCode "
                + update.versionCode + "）" + (update.releaseNotes.isEmpty()
                ? "" : "\n\n更新内容：\n" + update.releaseNotes);
        new AlertDialog.Builder(this)
                .setTitle("有新版本可用")
                .setMessage(message)
                .setNegativeButton("稍后", null)
                .setPositiveButton("打开下载页", (dialog, which) -> {
                    if (update.downloadUrl.isEmpty()) {
                        Toast.makeText(this, "更新清单未配置下载地址", Toast.LENGTH_LONG).show();
                    } else {
                        openWebPage(update.downloadUrl);
                    }
                }).show();
    }

    private void showDisclaimerDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), 0);
        content.setBackgroundColor(COLOR_CARD);

        TextView title = label("使用须知与免责说明", 20, COLOR_TEXT, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, dp(12));
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView message = label(MainActivity.DISCLAIMER_TEXT, 14, COLOR_TEXT, Typeface.NORMAL);
        message.setLineSpacing(dp(4), 1.02f);
        message.setPadding(0, 0, 0, dp(16));
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(COLOR_CARD);
        scrollView.addView(message, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(460)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setNegativeButton("关闭", null)
                .create();
        // Keep the title and close button fixed while the long notice scrolls independently.
        dialog.setOnShowListener(ignored -> {
            Button closeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (closeButton != null) closeButton.setTextColor(COLOR_TEAL);
            scrollView.post(() -> scrollView.scrollTo(0, 0));
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(COLOR_CARD));
                int width = getResources().getDisplayMetrics().widthPixels - dp(32);
                dialog.getWindow().setLayout(Math.max(dp(280), width),
                        WindowManager.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private void openWebPage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException exception) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void finishSettings() {
        if (required) {
            finishAffinity();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        finishSettings();
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private boolean isValidKey(String key) {
        return key != null && key.matches("[0-9A-Za-z]{32}");
    }

    private boolean isValidSecurity(String value) {
        return value != null && value.matches("[0-9A-Za-z]{16,64}");
    }

    private TextView label(String text, float sizeSp, int color, int style) {
        TextView value = new TextView(this);
        value.setText(text);
        value.setTextSize(sizeSp);
        value.setTextColor(color);
        value.setTypeface(Typeface.DEFAULT, style);
        return value;
    }

    private LinearLayout.LayoutParams wrapParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private LinearLayout.LayoutParams fixedParams(int height, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = topMargin;
        return params;
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
