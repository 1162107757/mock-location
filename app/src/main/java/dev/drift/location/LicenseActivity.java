package dev.drift.location;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Offline license activation page. No network request is made here. */
public final class LicenseActivity extends Activity {
    static final String EXTRA_OPEN_TRAJECTORY = "open_trajectory_after_activation";
    private static final int COLOR_BACKGROUND = Color.rgb(246, 241, 231);
    private static final int COLOR_CARD = Color.rgb(255, 255, 255);
    private static final int COLOR_INPUT = Color.rgb(244, 247, 245);
    private static final int COLOR_BORDER = Color.rgb(213, 220, 216);
    private static final int COLOR_TEXT = Color.rgb(30, 27, 25);
    private static final int COLOR_SUBTLE = Color.rgb(104, 108, 106);
    private static final int COLOR_ACCENT = Color.rgb(246, 168, 23);
    private static final int COLOR_TEAL = Color.rgb(42, 126, 136);
    private static final int COLOR_DANGER = Color.rgb(211, 83, 70);

    private EditText keyInput;
    private TextView statusTitle;
    private TextView statusDetail;
    private Button clearButton;
    private boolean openTrajectory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openTrajectory = getIntent().getBooleanExtra(EXTRA_OPEN_TRAJECTORY, false);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(buildPage());
        refreshState();
    }

    private View buildPage() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), dp(4), dp(14), dp(4));
        header.setBackground(rounded(COLOR_CARD, 18, COLOR_BORDER, 1));
        ImageButton back = new ImageButton(this);
        back.setElevation(0f);
        back.setStateListAnimator(null);
        back.setImageResource(R.drawable.ic_back_chevron);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setPadding(0, 0, 0, 0);
        back.setBackground(rounded(COLOR_INPUT, 12, COLOR_BORDER, 1));
        back.setContentDescription("返回首页");
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(42)));
        TextView title = label("卡密与授权", 20, COLOR_TEXT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        TextView tag = label("离线", 12, COLOR_TEAL, Typeface.BOLD);
        tag.setGravity(Gravity.CENTER);
        header.addView(tag, new LinearLayout.LayoutParams(dp(52), dp(44)));
        FrameLayout.LayoutParams headerParams = frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62), Gravity.TOP, 14, 14, 14, 0);
        root.addView(header, headerParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addBody(body);
        FrameLayout.LayoutParams scrollParams = frameParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP, 0, 76, 0, 0);
        root.addView(scroll, scrollParams);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = 0;
            int bottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            headerParams.topMargin = top + dp(14);
            scrollParams.topMargin = top + dp(76);
            scrollParams.bottomMargin = bottom + dp(12);
            header.setLayoutParams(headerParams);
            scroll.setLayoutParams(scrollParams);
            return insets;
        });
        return root;
    }

    private void addBody(LinearLayout body) {
        TextView eyebrow = label("本机授权", 12, COLOR_DANGER, Typeface.BOLD);
        body.addView(eyebrow, wrapParams(0));
        TextView headline = label("解锁 Mock Location 全部功能", 25, COLOR_TEXT, Typeface.BOLD);
        headline.setLineSpacing(dp(2), 1f);
        body.addView(headline, wrapParams(dp(6)));
        TextView intro = label("所有功能需要有效卡密；限时试用卡由授权方手动生成。", 14,
                COLOR_SUBTLE, Typeface.NORMAL);
        intro.setLineSpacing(dp(2), 1f);
        body.addView(intro, wrapParams(dp(8)));

        LinearLayout statusCard = card();
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusTitle = label("", 18, COLOR_TEXT, Typeface.BOLD);
        statusCard.addView(statusTitle);
        statusDetail = label("", 13, COLOR_SUBTLE, Typeface.NORMAL);
        statusDetail.setLineSpacing(dp(2), 1f);
        statusCard.addView(statusDetail, wrapParams(dp(6)));
        body.addView(statusCard, wrapParams(dp(16)));

        TextView deviceTitle = label("设备码", 13, COLOR_TEAL, Typeface.BOLD);
        body.addView(deviceTitle, wrapParams(dp(20)));
        LinearLayout deviceCard = card();
        TextView deviceCode = label(LicenseManager.getDeviceCode(this), 12, COLOR_TEXT, Typeface.BOLD);
        deviceCode.setTextIsSelectable(true);
        deviceCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        deviceCard.addView(deviceCode, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button copy = smallButton("复制");
        copy.setContentDescription("复制设备码");
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Mock Location 设备码", deviceCode.getText()));
            Toast.makeText(this, "设备码已复制", Toast.LENGTH_SHORT).show();
        });
        deviceCard.addView(copy, new LinearLayout.LayoutParams(dp(68), dp(42)));
        body.addView(deviceCard, wrapParams(dp(8)));
        TextView deviceHint = label("将完整设备码交给卡密生成方，用于生成设备绑定卡。", 12,
                COLOR_SUBTLE, Typeface.NORMAL);
        body.addView(deviceHint, wrapParams(dp(6)));

        TextView deviceKeyTitle = label("设备密钥指纹（推荐）", 13, COLOR_TEAL, Typeface.BOLD);
        body.addView(deviceKeyTitle, wrapParams(dp(20)));
        LinearLayout deviceKeyCard = card();
        deviceKeyCard.setOrientation(LinearLayout.HORIZONTAL);
        deviceKeyCard.setGravity(Gravity.CENTER_VERTICAL);
        TextView deviceKeyId = label(LicenseManager.getDeviceKeyId(this), 12,
                COLOR_TEXT, Typeface.BOLD);
        deviceKeyId.setTextIsSelectable(true);
        deviceKeyId.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        deviceKeyCard.addView(deviceKeyId, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button copyDeviceKey = smallButton("复制");
        copyDeviceKey.setContentDescription("复制设备密钥指纹");
        copyDeviceKey.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Mock Location 设备密钥指纹",
                    deviceKeyId.getText()));
            Toast.makeText(this, "设备密钥指纹已复制", Toast.LENGTH_SHORT).show();
        });
        deviceKeyCard.addView(copyDeviceKey, new LinearLayout.LayoutParams(dp(68), dp(42)));
        body.addView(deviceKeyCard, wrapParams(dp(8)));
        TextView deviceKeyHint = label("将设备码和设备密钥指纹一起交给发卡工具，绑定强度更高。", 12,
                COLOR_SUBTLE, Typeface.NORMAL);
        body.addView(deviceKeyHint, wrapParams(dp(6)));

        TextView inputTitle = label("输入卡密", 13, COLOR_TEAL, Typeface.BOLD);
        body.addView(inputTitle, wrapParams(dp(20)));
        keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setTextSize(13);
        keyInput.setTextColor(COLOR_TEXT);
        keyInput.setHintTextColor(Color.rgb(145, 145, 135));
        keyInput.setHint("ML2.xxxxx.xxxxx");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setPadding(dp(14), 0, dp(14), 0);
        keyInput.setBackground(rounded(COLOR_INPUT, 12, COLOR_BORDER, 1));
        body.addView(keyInput, fixedParams(dp(54), dp(8)));
        Button activate = primaryButton("激活卡密");
        activate.setContentDescription("激活卡密");
        activate.setOnClickListener(view -> activate());
        body.addView(activate, fixedParams(dp(52), dp(10)));

        clearButton = secondaryButton("清除本机授权");
        clearButton.setContentDescription("清除本机授权");
        clearButton.setOnClickListener(view -> {
            LicenseManager.clear(this);
            keyInput.setText("");
            refreshState();
            Toast.makeText(this, "本机授权已清除", Toast.LENGTH_SHORT).show();
        });
        body.addView(clearButton, fixedParams(dp(48), dp(8)));

        TextView note = label("卡密验证完全在本机完成，不会上传卡密或设备码。无服务端模式无法远程撤销已发出的卡密。",
                12, COLOR_SUBTLE, Typeface.NORMAL);
        note.setLineSpacing(dp(2), 1f);
        body.addView(note, wrapParams(dp(18)));
    }

    private void activate() {
        LicenseManager.Validation validation = LicenseManager.activate(this,
                keyInput == null ? "" : keyInput.getText().toString());
        refreshState(validation);
        if (!validation.valid) {
            Toast.makeText(this, validation.message, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "卡密激活成功", Toast.LENGTH_SHORT).show();
        if (openTrajectory) startActivity(new Intent(this, TrajectoryActivity.class));
        finish();
    }

    private void refreshState() {
        refreshState(LicenseManager.getAccess(this));
    }

    private void refreshState(LicenseManager.Validation validation) {
        refreshState(LicenseManager.getAccess(this));
    }

    private void refreshState(LicenseManager.Access access) {
        if (statusTitle == null || statusDetail == null) return;
        if (access != null && access.license != null && access.license.valid) {
            LicenseManager.Validation validation = access.license;
            statusTitle.setText((validation.trialCard ? "试用卡 · " : "已激活 · ")
                    + LicenseManager.formatExpiry(validation.expiresAtSeconds));
            statusTitle.setTextColor(COLOR_TEAL);
            statusDetail.setText("授权功能：" + LicenseManager.featureSummary(validation)
                    + "\n卡密编号：" + validation.licenseId);
            clearButton.setVisibility(View.VISIBLE);
        } else {
            statusTitle.setText("需要卡密才能使用");
            statusTitle.setTextColor(COLOR_DANGER);
            statusDetail.setText(access == null ? "授权状态不可用" : access.message
                    + "\n首页、地图选点、轨迹和模拟功能均已锁定。\n请向授权方申请卡密。");
            clearButton.setVisibility(View.GONE);
        }
    }

    private TextView label(String text, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(COLOR_TEXT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(rounded(COLOR_ACCENT, 16, Color.TRANSPARENT, 0));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = smallButton(text);
        button.setTextSize(14);
        return button;
    }

    private Button smallButton(String text) {
        Button button = new Button(this);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setText(text);
        button.setTextSize(12);
        button.setTextColor(COLOR_TEXT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackground(rounded(COLOR_CARD, 12, COLOR_BORDER, 1));
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(rounded(COLOR_CARD, 16, COLOR_BORDER, 0));
        // Flat card treatment: the page uses a crisp border and paper surfaces,
        // so elevation here creates an unintended shadow around device/status cards.
        card.setElevation(0f);
        card.setStateListAnimator(null);
        return card;
    }

    private GradientDrawable rounded(int fill, int radius, int stroke, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (width > 0) drawable.setStroke(dp(width), stroke);
        return drawable;
    }

    private FrameLayout.LayoutParams frameParams(int width, int height, int gravity,
                                                 int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.gravity = gravity;
        params.leftMargin = dp(left);
        params.topMargin = dp(top);
        params.rightMargin = dp(right);
        params.bottomMargin = dp(bottom);
        return params;
    }

    private LinearLayout.LayoutParams wrapParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        return params;
    }

    private LinearLayout.LayoutParams fixedParams(int height, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = dp(topMargin);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
