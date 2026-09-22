package dev.drift.location;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;

/** Offline license validation and device-bound access control. */
final class LicenseManager {
    static final String FEATURE_TRAJECTORY = "trajectory";
    static final String FEATURE_ROUTE = "route";
    static final String FEATURE_FAVORITE = "favorite";
    static final String PRODUCT_ID = "mock-location";
    private static final String TOKEN_PREFIX = "ML2";
    private static final String LEGACY_TOKEN_PREFIX = "ML1";
    private static final String PREFS = "offline_license";
    private static final String TOKEN_KEY = "encrypted_token";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "mock_location_license_v1";
    private static final String DEVICE_KEY_ALIAS = "mock_location_device_v2";
    private static volatile Access cachedAccess;
    private static volatile long cachedAccessAtMs;

    /** Public EC P-256 key; the matching private key is kept only by the issuer tool. */
    private static final String PUBLIC_KEY_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEMylrVJ6jTNnqoENIsjdyGTKEg4lP5UKi40GY1i7ND9NaTWgESaZpNelPv9nBQ5TwKkmQXS7aE7hI5upwRN4JWg";

    private LicenseManager() {
    }

    static Validation validateStored(Context context) {
        String token = readStoredToken(context);
        if (token.isEmpty()) return Validation.invalid("尚未激活卡密");
        return validateToken(context, token);
    }

    /** Returns the current app-wide access state. No card means no access. */
    static synchronized Access getAccess(Context context) {
        long cacheAge = SystemClock.elapsedRealtime() - cachedAccessAtMs;
        if (cachedAccess != null && cacheAge >= 0L && cacheAge < 1_000L) return cachedAccess;
        Validation validation = validateStored(context);
        if (validation.valid) {
            cachedAccess = Access.licensed(validation);
            cachedAccessAtMs = SystemClock.elapsedRealtime();
            return cachedAccess;
        }
        cachedAccess = Access.locked("需要卡密才能使用，请输入卡密");
        cachedAccessAtMs = SystemClock.elapsedRealtime();
        return cachedAccess;
    }

    static boolean hasAccess(Context context) {
        return getAccess(context).allowed;
    }

    /** Returns whether a specific feature is available under the current card. */
    static boolean hasFeatureAccess(Context context, String feature) {
        Access access = getAccess(context);
        if (!access.allowed) return false;
        return access.license != null && access.license.hasFeature(feature);
    }

    static Validation activate(Context context, String token) {
        Validation validation = validateToken(context, token);
        if (!validation.valid) return validation;
        if (!writeStoredToken(context, token.trim())) {
            return Validation.invalid("设备加密存储不可用，无法保存授权");
        }
        cachedAccess = null;
        return validation;
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(TOKEN_KEY).apply();
        cachedAccess = null;
    }

    static boolean isTrajectoryUnlocked(Context context) {
        return hasFeatureAccess(context, FEATURE_TRAJECTORY);
    }

    static String getDeviceCode(Context context) {
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) androidId = "unknown";
        String certificate = signingCertificateDigest(context);
        String source = androidId.trim().toLowerCase(Locale.US) + "|"
                + context.getPackageName() + "|" + certificate;
        return "MLD1-" + hex(sha256(source.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Returns a stable identifier for a non-exportable Android Keystore key.
     * The identifier is safe to show to the issuer; the private key never leaves
     * the device. Android 7 devices without hardware-backed Keystore still get
     * the same API-level protection, while newer devices may use secure hardware.
     */
    static String getDeviceKeyId(Context context) {
        try {
            KeyStore keyStore = loadKeyStore();
            if (!keyStore.containsAlias(DEVICE_KEY_ALIAS)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC, KEYSTORE_PROVIDER);
                generator.initialize(new KeyGenParameterSpec.Builder(DEVICE_KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                        .build());
                generator.generateKeyPair();
                keyStore = loadKeyStore();
            }
            Certificate certificate = keyStore.getCertificate(DEVICE_KEY_ALIAS);
            if (certificate == null || certificate.getPublicKey() == null) return "";
            return "MLK2-" + hex(sha256(certificate.getPublicKey().getEncoded()));
        } catch (Exception ignored) {
            return "";
        }
    }

    static String formatExpiry(long expiresAtSeconds) {
        if (expiresAtSeconds <= 0) return "永久授权";
        long now = System.currentTimeMillis() / 1000L;
        long remaining = expiresAtSeconds - now;
        if (remaining <= 0) return "已过期";
        long days = remaining / 86_400L;
        if (days == 0) return "今天到期";
        return "有效期至 " + new java.text.SimpleDateFormat(
                "yyyy-MM-dd", Locale.US).format(new java.util.Date(expiresAtSeconds * 1000L));
    }

    static String featureSummary(Validation validation) {
        if (validation == null || !validation.valid) return "未解锁高级功能";
        if (validation.hasFeature("*")) return "全部功能";
        StringBuilder output = new StringBuilder();
        if (validation.hasFeature(FEATURE_TRAJECTORY)) output.append("轨迹模拟");
        if (validation.hasFeature(FEATURE_ROUTE)) appendFeature(output, "导航线路");
        if (validation.hasFeature(FEATURE_FAVORITE)) appendFeature(output, "线路收藏");
        return output.length() == 0 ? "未配置高级功能" : output.toString();
    }

    static Validation validateToken(Context context, String rawToken) {
        if (rawToken == null || rawToken.trim().isEmpty()) {
            return Validation.invalid("请输入卡密");
        }
        String[] parts = rawToken.trim().split("\\.", -1);
        if (parts.length != 3 || (!TOKEN_PREFIX.equals(parts[0])
                && !LEGACY_TOKEN_PREFIX.equals(parts[0]))) {
            return Validation.invalid("卡密格式不正确");
        }
        try {
            boolean modernToken = TOKEN_PREFIX.equals(parts[0]);
            byte[] payloadBytes = decode(parts[1]);
            byte[] signatureBytes = decode(parts[2]);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey());
            verifier.update(payloadBytes);
            if (!verifier.verify(signatureBytes)) {
                return Validation.invalid("卡密签名无效");
            }
            JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
            if (!PRODUCT_ID.equals(payload.optString("product", ""))) {
                return Validation.invalid("卡密不属于 Mock Location");
            }
            String licenseId = payload.optString("licenseId", "").trim();
            if (licenseId.isEmpty()) return Validation.invalid("卡密缺少编号");
            long expiresAt = payload.optLong("expiresAt", 0L);
            long now = System.currentTimeMillis() / 1000L;
            long issuedAt = payload.optLong("issuedAt", 0L);
            if (modernToken && issuedAt > now + 300L) {
                return Validation.invalid("卡密签发时间无效");
            }
            if (expiresAt > 0 && now >= expiresAt) return Validation.invalid("卡密已过期");
            int minVersion = payload.optInt("minVersionCode", 0);
            int maxVersion = payload.optInt("maxVersionCode", 0);
            if (minVersion > 0 && BuildConfig.VERSION_CODE < minVersion) {
                return Validation.invalid("当前版本过低，请更新后使用");
            }
            if (maxVersion > 0 && BuildConfig.VERSION_CODE > maxVersion) {
                return Validation.invalid("当前版本不再支持此卡密");
            }
            String boundDevice = payload.optString("deviceCode", "").trim().toUpperCase(Locale.US);
            if (!boundDevice.isEmpty() && !boundDevice.equals(getDeviceCode(context))) {
                return Validation.invalid("卡密与当前设备不匹配");
            }
            if (modernToken) {
                String packageName = payload.optString("packageName", "").trim();
                if (!packageName.isEmpty() && !context.getPackageName().equals(packageName)) {
                    return Validation.invalid("卡密不属于当前应用");
                }
                String certificateDigest = payload.optString("certSha256", "")
                        .trim().toUpperCase(Locale.US);
                if (!certificateDigest.isEmpty()
                        && !certificateDigest.equals(signingCertificateDigest(context))) {
                    return Validation.invalid("应用签名不匹配");
                }
                String boundKey = payload.optString("deviceKeyId", "")
                        .trim().toUpperCase(Locale.US);
                if (!boundKey.isEmpty()) {
                    String currentKey = getDeviceKeyId(context);
                    if (currentKey.isEmpty() || !boundKey.equals(currentKey)) {
                        return Validation.invalid("卡密与当前设备密钥不匹配");
                    }
                }
            }
            JSONArray features = payload.optJSONArray("features");
            boolean trialCard = "trial".equalsIgnoreCase(
                    payload.optString("licenseType", ""));
            return Validation.valid(licenseId, expiresAt, boundDevice, features, trialCard);
        } catch (Exception exception) {
            return Validation.invalid("卡密校验失败");
        }
    }

    private static void appendFeature(StringBuilder output, String value) {
        if (output.length() > 0) output.append("、");
        output.append(value);
    }

    private static PublicKey publicKey() throws Exception {
        byte[] encoded = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT);
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static String readStoredToken(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = preferences.getString(TOKEN_KEY, "");
        if (stored.isEmpty()) return "";
        if (!stored.startsWith("v1.")) return "";
        String[] parts = stored.split("\\.", -1);
        if (parts.length != 3) return "";
        try {
            KeyStore keyStore = loadKeyStore();
            if (!keyStore.containsAlias(KEY_ALIAS)) return "";
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keyStore.getKey(KEY_ALIAS, null),
                    new javax.crypto.spec.GCMParameterSpec(128, decode(parts[1])));
            return new String(cipher.doFinal(decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return "";
        }
    }

    private static boolean writeStoredToken(Context context, String token) {
        try {
            KeyStore keyStore = loadKeyStore();
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                javax.crypto.KeyGenerator generator = javax.crypto.KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
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
                    + encode(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(TOKEN_KEY, value).apply();
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private static KeyStore loadKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        return keyStore;
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception ignored) {
            return new byte[0];
        }
    }

    private static String signingCertificateDigest(Context context) {
        try {
            PackageManager manager = context.getPackageManager();
            // GET_SIGNATURES is deprecated on newer Android versions but is
            // available on every supported version, including Android 7.
            PackageInfo info = manager.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNATURES);
            if (info.signatures != null && info.signatures.length > 0) {
                return hex(sha256(info.signatures[0].toByteArray()));
            }
        } catch (Exception ignored) {
            // A missing digest still produces a stable code from Android ID and package name.
        }
        return "";
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.US, "%02x", value & 0xff));
        return output.toString().toUpperCase(Locale.US);
    }

    static final class Access {
        final boolean allowed;
        final String message;
        final long expiresAtSeconds;
        final Validation license;

        private Access(boolean allowed, String message, long expiresAtSeconds,
                       Validation license) {
            this.allowed = allowed;
            this.message = message;
            this.expiresAtSeconds = expiresAtSeconds;
            this.license = license;
        }

        static Access licensed(Validation license) {
            return new Access(true, license.trialCard ? "试用卡已激活" : "已激活",
                    license.expiresAtSeconds, license);
        }

        static Access locked(String message) {
            return new Access(false, message, 0L, null);
        }
    }

    static final class Validation {
        final boolean valid;
        final String message;
        final String licenseId;
        final long expiresAtSeconds;
        final String boundDevice;
        final boolean trialCard;
        private final JSONArray features;

        private Validation(boolean valid, String message, String licenseId,
                           long expiresAtSeconds, String boundDevice, JSONArray features,
                           boolean trialCard) {
            this.valid = valid;
            this.message = message;
            this.licenseId = licenseId;
            this.expiresAtSeconds = expiresAtSeconds;
            this.boundDevice = boundDevice;
            this.features = features;
            this.trialCard = trialCard;
        }

        static Validation invalid(String message) {
            return new Validation(false, message, "", 0L, "", null, false);
        }

        static Validation valid(String licenseId, long expiresAtSeconds,
                                String boundDevice, JSONArray features, boolean trialCard) {
            return new Validation(true, "验证通过", licenseId, expiresAtSeconds,
                    boundDevice, features, trialCard);
        }

        boolean hasFeature(String feature) {
            if (!valid || features == null) return false;
            for (int index = 0; index < features.length(); index++) {
                if (feature.equals(features.optString(index, "")) || "*".equals(features.optString(index))) {
                    return true;
                }
            }
            return false;
        }
    }
}
