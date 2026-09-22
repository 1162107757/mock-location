package dev.drift.location.issuer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;
import java.util.UUID;

/** Local issuer companion. The private key is imported and encrypted, never bundled in the APK. */
public final class LicenseIssuerActivity extends Activity {
    private static final int REQUEST_PRIVATE_KEY = 91;
    private static final int COLOR_BACKGROUND = Color.rgb(246, 241, 231);
    private static final int COLOR_CARD = Color.rgb(255, 255, 255);
    private static final int COLOR_INPUT = Color.rgb(244, 247, 245);
    private static final int COLOR_BORDER = Color.rgb(213, 220, 216);
    private static final int COLOR_TEXT = Color.rgb(30, 27, 25);
    private static final int COLOR_SUBTLE = Color.rgb(104, 108, 106);
    private static final int COLOR_ACCENT = Color.rgb(246, 168, 23);
    private static final int COLOR_TEAL = Color.rgb(42, 126, 136);
    private static final int COLOR_DANGER = Color.rgb(211, 83, 70);
    private static final String TARGET_PACKAGE = "dev.drift.location";
    private static final String PRODUCT_ID = "mock-location";
    private static final String TOKEN_PREFIX = "ML2";
    private static final String PUBLIC_KEY_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEMylrVJ6jTNnqoENIsjdyGTKEg4lP5UKi40GY1i7ND9NaTWgESaZpNelPv9nBQ5TwKkmQXS7aE7hI5upwRN4JWg";
    private static final String PREFS = "issuer_store";
    private static final String PRIVATE_KEY_KEY = "encrypted_private_key";
    private static final String KEY_ALIAS = "mock_location_issuer_v1";
    private EditText deviceField;
    private EditText deviceKeyField;
    private EditText expiryField;
    private EditText featureField;
    private TextView privateStatus;
    private TextView generatedToken;
    private Button copyTokenButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        setContentView(buildPage());
        refreshPrivateStatus();
    }

    private View buildPage() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACKGROUND);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(4), dp(16), dp(4));
        header.setBackground(rounded(COLOR_CARD, 18, COLOR_BORDER, 1));
        TextView title = label("Mock License Issuer", 20, COLOR_TEXT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView tag = label("离线签发", 12, COLOR_TEAL, Typeface.BOLD);
        tag.setGravity(Gravity.CENTER);
        header.addView(tag, new LinearLayout.LayoutParams(dp(68), dp(48)));
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
        TextView eyebrow = label("仅供授权方使用", 12, COLOR_DANGER, Typeface.BOLD);
        body.addView(eyebrow, wrapParams(0));
        TextView headline = label("生成设备授权卡密", 25, COLOR_TEXT, Typeface.BOLD);
        body.addView(headline, wrapParams(dp(6)));
        TextView intro = label("私钥只从文件导入并加密保存在本机，不会打包进这个 APK。请勿把私钥文件或签发 APK 分发给他人。",
                14, COLOR_SUBTLE, Typeface.NORMAL);
        intro.setLineSpacing(dp(2), 1f);
        body.addView(intro, wrapParams(dp(8)));

        LinearLayout keyCard = card();
        privateStatus = label("", 14, COLOR_TEXT, Typeface.BOLD);
        privateStatus.setLineSpacing(dp(2), 1f);
        keyCard.addView(privateStatus);
        Button importButton = secondaryButton("导入私钥");
        importButton.setContentDescription("导入签发私钥");
        importButton.setOnClickListener(view -> choosePrivateKey());
        keyCard.addView(importButton, fixedParams(dp(46), dp(10)));
        body.addView(keyCard, wrapParams(dp(16)));

        TextView deviceTitle = label("设备绑定", 13, COLOR_TEAL, Typeface.BOLD);
        body.addView(deviceTitle, wrapParams(dp(20)));
        deviceField = input("设备码，填写 - 表示不绑定", getTargetDeviceCode());
        body.addView(deviceField, fixedParams(dp(54), dp(8)));
        TextView deviceHint = label("默认读取本机 Mock Location 的设备码；给虚拟机签发时，请替换成虚拟机 App 中显示的设备码。",
                12, COLOR_SUBTLE, Typeface.NORMAL);
        deviceHint.setLineSpacing(dp(2), 1f);
        body.addView(deviceHint, wrapParams(dp(6)));

        TextView deviceKeyTitle = label("设备密钥指纹（推荐）", 13, COLOR_TEAL, Typeface.BOLD);
        body.addView(deviceKeyTitle, wrapParams(dp(20)));
        deviceKeyField = input("填写 Mock Location 显示的 MLK2 指纹，留空表示不绑定", "");
        body.addView(deviceKeyField, fixedParams(dp(54), dp(8)));
        TextView deviceKeyHint = label("绑定设备密钥后，即使设备码被复制，卡密也不能直接在另一台设备使用。",
                12, COLOR_SUBTLE, Typeface.NORMAL);
        deviceKeyHint.setLineSpacing(dp(2), 1f);
        body.addView(deviceKeyHint, wrapParams(dp(6)));

        TextView expiryTitle = label("有效期天数", 13, COLOR_TEAL, Typeface.BOLD);
        body.addView(expiryTitle, wrapParams(dp(20)));
        expiryField = input("0 表示永久，填写天数生成限时试用卡", "0");
        body.addView(expiryField, fixedParams(dp(54), dp(8)));
        LinearLayout expiryRow = new LinearLayout(this);
        expiryRow.setOrientation(LinearLayout.HORIZONTAL);
        addExpiryButton(expiryRow, "永久", 0L);
        addExpiryButton(expiryRow, "1 天", 1L);
        addExpiryButton(expiryRow, "7 天", 7L);
        addExpiryButton(expiryRow, "30 天", 30L);
        body.addView(expiryRow, wrapParams(dp(8)));
        TextView expiryHint = label("填写 1、7、30 等天数即可生成试用卡；填写 0 生成永久卡。", 12,
                COLOR_SUBTLE, Typeface.NORMAL);
        body.addView(expiryHint, wrapParams(dp(6)));

        TextView featureTitle = label("功能权限", 13, COLOR_TEAL, Typeface.BOLD);
        body.addView(featureTitle, wrapParams(dp(20)));
        featureField = input("用逗号分隔功能", "trajectory,route,favorite");
        body.addView(featureField, fixedParams(dp(54), dp(8)));
        TextView featureHint = label("trajectory=轨迹模拟，route=导航线路，favorite=线路收藏。", 12,
                COLOR_SUBTLE, Typeface.NORMAL);
        body.addView(featureHint, wrapParams(dp(6)));

        Button generate = primaryButton("生成卡密");
        generate.setContentDescription("生成离线授权卡密");
        generate.setOnClickListener(view -> generateToken());
        body.addView(generate, fixedParams(dp(52), dp(20)));

        TextView outputTitle = label("生成结果", 13, COLOR_TEAL, Typeface.BOLD);
        body.addView(outputTitle, wrapParams(dp(20)));
        LinearLayout outputCard = card();
        outputCard.setOrientation(LinearLayout.VERTICAL);
        generatedToken = label("生成后卡密会显示在这里", 12, COLOR_SUBTLE, Typeface.NORMAL);
        generatedToken.setTextIsSelectable(true);
        generatedToken.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        generatedToken.setLineSpacing(dp(2), 1f);
        outputCard.addView(generatedToken);
        copyTokenButton = secondaryButton("复制卡密");
        copyTokenButton.setContentDescription("复制生成的卡密");
        copyTokenButton.setEnabled(false);
        copyTokenButton.setOnClickListener(view -> copyToken());
        outputCard.addView(copyTokenButton, fixedParams(dp(46), dp(10)));
        body.addView(outputCard, wrapParams(dp(8)));

        TextView note = label("签发 APK 只适合放在你自己控制的设备上。无服务端模式无法远程撤销卡密。",
                12, COLOR_SUBTLE, Typeface.NORMAL);
        note.setLineSpacing(dp(2), 1f);
        body.addView(note, wrapParams(dp(18)));
    }

    private void addExpiryButton(LinearLayout row, String text, long days) {
        Button button = secondaryButton(text);
        button.setOnClickListener(view -> {
            expiryField.setText(String.valueOf(days));
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1f);
        if (row.getChildCount() > 0) params.leftMargin = dp(7);
        row.addView(button, params);
    }

    private void choosePrivateKey() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PRIVATE_KEY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PRIVATE_KEY || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (java.io.InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IllegalStateException("无法读取私钥文件");
            byte[] bytes = readAll(stream);
            byte[] keyBytes = decodePrivateKey(bytes);
            PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(
                    new PKCS8EncodedKeySpec(keyBytes));
            if (!matchesPublicKey(privateKey)) {
                Toast.makeText(this, "私钥与 Mock Location 公钥不匹配", Toast.LENGTH_LONG).show();
                return;
            }
            if (!storePrivateKey(keyBytes)) {
                Toast.makeText(this, "私钥加密保存失败", Toast.LENGTH_LONG).show();
                return;
            }
            refreshPrivateStatus();
            Toast.makeText(this, "私钥导入成功", Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            Toast.makeText(this, "私钥文件格式不正确", Toast.LENGTH_LONG).show();
        }
    }

    private void generateToken() {
        byte[] keyBytes = readPrivateKey();
        if (keyBytes == null) {
            Toast.makeText(this, "请先导入与 Mock Location 匹配的私钥", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            PrivateKey privateKey = KeyFactory.getInstance("EC").generatePrivate(
                    new PKCS8EncodedKeySpec(keyBytes));
            String deviceCode = deviceField.getText().toString().trim().toUpperCase(Locale.US);
            if (deviceCode.isEmpty()) deviceCode = "";
            String deviceKeyId = deviceKeyField == null ? ""
                    : deviceKeyField.getText().toString().trim().toUpperCase(Locale.US);
            long durationDays = Long.parseLong(expiryField.getText().toString().trim());
            if (durationDays < 0L || durationDays > 36_500L) {
                throw new IllegalArgumentException("有效期天数无效");
            }
            long issuedAt = System.currentTimeMillis() / 1000L;
            long expiresAt = durationDays == 0L ? 0L
                    : issuedAt + durationDays * 86_400L;
            String featuresText = featureField.getText().toString().trim();
            JSONArray features = new JSONArray();
            for (String value : featuresText.split(",")) {
                String feature = value.trim();
                if (!feature.isEmpty()) features.put(feature);
            }
            if (features.length() == 0) throw new IllegalArgumentException("至少填写一个功能");
            JSONObject payload = new JSONObject();
            payload.put("product", PRODUCT_ID);
            payload.put("licenseId", UUID.randomUUID().toString());
            payload.put("deviceCode", "-".equals(deviceCode) ? "" : deviceCode);
            payload.put("deviceKeyId", deviceKeyId);
            payload.put("packageName", TARGET_PACKAGE);
            String certificateDigest = signingCertificateDigest();
            if (!certificateDigest.isEmpty()) payload.put("certSha256", certificateDigest);
            payload.put("issuedAt", issuedAt);
            payload.put("expiresAt", expiresAt);
            payload.put("licenseType", durationDays > 0L ? "trial" : "permanent");
            payload.put("durationDays", durationDays);
            payload.put("features", features);
            byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(payloadBytes);
            String token = TOKEN_PREFIX + "." + encode(payloadBytes) + "." + encode(signer.sign());
            generatedToken.setText(token);
            generatedToken.setTextColor(COLOR_TEXT);
            copyTokenButton.setEnabled(true);
            Toast.makeText(this, "卡密已生成", Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            Toast.makeText(this, "生成失败，请检查输入内容", Toast.LENGTH_LONG).show();
        }
    }

    private void copyToken() {
        if (generatedToken == null || generatedToken.getText().toString().startsWith("生成后")) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Mock Location 卡密", generatedToken.getText()));
        Toast.makeText(this, "卡密已复制", Toast.LENGTH_SHORT).show();
    }

    private void refreshPrivateStatus() {
        if (privateStatus == null) return;
        if (readPrivateKey() != null) {
            privateStatus.setText("已导入私钥 · 可签发卡密");
            privateStatus.setTextColor(COLOR_TEAL);
        } else {
            privateStatus.setText("尚未导入私钥");
            privateStatus.setTextColor(COLOR_DANGER);
        }
    }

    private boolean matchesPublicKey(PrivateKey privateKey) throws Exception {
        byte[] sample = "mock-location-license-check".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(sample);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey());
        verifier.update(sample);
        return verifier.verify(signature);
    }

    private byte[] readPrivateKey() {
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PRIVATE_KEY_KEY, "");
        if (stored.isEmpty()) return null;
        String[] parts = stored.split("\\.", -1);
        if (parts.length != 3 || !"v1".equals(parts[0])) return null;
        try {
            KeyStore keyStore = loadKeyStore();
            if (!keyStore.containsAlias(KEY_ALIAS)) return null;
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keyStore.getKey(KEY_ALIAS, null),
                    new javax.crypto.spec.GCMParameterSpec(128, decode(parts[1])));
            return cipher.doFinal(decode(parts[2]));
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean storePrivateKey(byte[] keyBytes) {
        try {
            KeyStore keyStore = loadKeyStore();
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                javax.crypto.KeyGenerator generator = javax.crypto.KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
                generator.generateKey();
                keyStore = loadKeyStore();
            }
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keyStore.getKey(KEY_ALIAS, null));
            String value = "v1." + encode(cipher.getIV()) + "."
                    + encode(cipher.doFinal(keyBytes));
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PRIVATE_KEY_KEY, value).apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private KeyStore loadKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return keyStore;
    }

    private PublicKey publicKey() throws Exception {
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(
                Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)));
    }

    private String getTargetDeviceCode() {
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) androidId = "unknown";
        String certificate = signingCertificateDigest();
        String source = androidId.trim().toLowerCase(Locale.US) + "|"
                + TARGET_PACKAGE + "|" + certificate;
        return "MLD1-" + hex(sha256(source.getBytes(StandardCharsets.UTF_8)));
    }

    private String signingCertificateDigest() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(TARGET_PACKAGE,
                    PackageManager.GET_SIGNATURES);
            if (info.signatures != null && info.signatures.length > 0) {
                return hex(sha256(info.signatures[0].toByteArray()));
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private byte[] decodePrivateKey(byte[] input) {
        String value = new String(input, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception ignored) {
            return Base64.decode(value, Base64.DEFAULT);
        }
    }

    private byte[] readAll(java.io.InputStream stream) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) >= 0) {
            if (count > 0) output.write(buffer, 0, count);
            if (output.size() > 64 * 1024) throw new java.io.IOException("文件过大");
        }
        return output.toByteArray();
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception ignored) {
            return new byte[0];
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.US, "%02x", value & 0xff));
        return output.toString().toUpperCase(Locale.US);
    }

    private String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private byte[] decode(String value) {
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private EditText input(String hint, String value) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(value);
        field.setHint(hint);
        field.setTextSize(13);
        field.setTextColor(COLOR_TEXT);
        field.setHintTextColor(COLOR_SUBTLE);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(rounded(COLOR_INPUT, 12, COLOR_BORDER, 1));
        return field;
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
        Button button = secondaryButton(text);
        button.setTextSize(15);
        button.setBackground(rounded(COLOR_ACCENT, 16, Color.TRANSPARENT, 0));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(COLOR_TEXT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setBackground(rounded(COLOR_CARD, 12, COLOR_BORDER, 1));
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(rounded(COLOR_CARD, 16, COLOR_BORDER, 0));
        // Keep issuer cards flat so device/key panels do not retain a shadow.
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
