package dev.drift.location;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores a user-supplied AMap key encrypted with an Android Keystore AES key. */
final class AmapKeyStore {
    private static final String SECURE_PREFERENCES = "amap_secure_configuration";
    private static final String LEGACY_PREFERENCES = "amap_configuration";
    private static final String ENCRYPTED_KEY = "encrypted_key";
    private static final String INITIALIZATION_VECTOR = "initialization_vector";
    private static final String ENCRYPTED_JS_API_KEY = "encrypted_js_api_key";
    private static final String JS_API_KEY_INITIALIZATION_VECTOR = "js_api_key_initialization_vector";
    private static final String ENCRYPTED_JS_SECURITY_CODE = "encrypted_js_security_code";
    private static final String JS_SECURITY_CODE_INITIALIZATION_VECTOR = "js_security_code_initialization_vector";
    private static final String KEY_ALIAS = "drift_location_amap_key_v1";
    private static final String OBFUSCATED_DEFAULT_KEY = "C8Us8W6EQmmnGeh/g1zCQgqRePNgg0xpok3sIoRUlRI=";
    private static final String OBFUSCATED_DEFAULT_JS_API_KEY = "CJQq+jyCFzzyTbl1hQrJFVjGKPZt1xY+9Ra6f9cKyB4=";
    private static final String OBFUSCATED_DEFAULT_JS_SECURITY_CODE = "BJZ8927TTDqhTet3ggmUQQzCLvs90UA8o0m+d9AOkR8=";
    private static final byte[] OBFUSCATION_MASK = {
            (byte) 0x3d, (byte) 0xa7, (byte) 0x19, (byte) 0xc2,
            (byte) 0x58, (byte) 0xe1, (byte) 0x74, (byte) 0x0b,
            (byte) 0x93, (byte) 0x2f, (byte) 0xd8, (byte) 0x46,
            (byte) 0xb1, (byte) 0x6c, (byte) 0xf0, (byte) 0x27
    };

    private AmapKeyStore() {}

    static String get(Context context) {
        SharedPreferences securePreferences = context.getSharedPreferences(SECURE_PREFERENCES, Context.MODE_PRIVATE);
        String encrypted = decrypt(context, securePreferences);
        if (isValid(encrypted)) return encrypted;

        // Migrate the key saved by versions before encrypted storage was introduced.
        SharedPreferences legacyPreferences = context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE);
        String legacy = legacyPreferences.getString("api_key", "").trim();
        if (isValid(legacy)) {
            if (save(context, legacy)) {
                legacyPreferences.edit().remove("api_key").apply();
            }
            return legacy;
        }

        String defaultKey = decodeDefaultKey();
        save(context, defaultKey);
        return defaultKey;
    }

    static boolean save(Context context, String key) {
        if (!isValid(key)) return false;
        return saveEncrypted(context, key, ENCRYPTED_KEY, INITIALIZATION_VECTOR);
    }

    static String getJsApiKey(Context context) {
        SharedPreferences preferences = securePreferences(context);
        String value = decrypt(context, preferences, ENCRYPTED_JS_API_KEY,
                JS_API_KEY_INITIALIZATION_VECTOR);
        if (!isValid(value)) {
            ensureDefaultJsConfiguration(context);
            value = decrypt(context, securePreferences(context), ENCRYPTED_JS_API_KEY,
                    JS_API_KEY_INITIALIZATION_VECTOR);
        }
        return isValid(value) ? value : "";
    }

    static String getJsSecurityCode(Context context) {
        SharedPreferences preferences = securePreferences(context);
        String value = decrypt(context, preferences, ENCRYPTED_JS_SECURITY_CODE,
                JS_SECURITY_CODE_INITIALIZATION_VECTOR);
        if (!isValidSecurityCode(value)) {
            ensureDefaultJsConfiguration(context);
            value = decrypt(context, securePreferences(context), ENCRYPTED_JS_SECURITY_CODE,
                    JS_SECURITY_CODE_INITIALIZATION_VECTOR);
        }
        return isValidSecurityCode(value) ? value : "";
    }

    static boolean saveJsConfiguration(Context context, String apiKey, String securityCode) {
        if (!isValid(apiKey) || !isValidSecurityCode(securityCode)) return false;
        try {
            EncryptedValue encryptedKey = encrypt(apiKey.trim());
            EncryptedValue encryptedSecurityCode = encrypt(securityCode.trim());
            if (encryptedKey == null || encryptedSecurityCode == null) return false;
            return securePreferences(context).edit()
                    .putString(ENCRYPTED_JS_API_KEY, encryptedKey.value)
                    .putString(JS_API_KEY_INITIALIZATION_VECTOR, encryptedKey.initializationVector)
                    .putString(ENCRYPTED_JS_SECURITY_CODE, encryptedSecurityCode.value)
                    .putString(JS_SECURITY_CODE_INITIALIZATION_VECTOR, encryptedSecurityCode.initializationVector)
                    .commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String decrypt(Context context, SharedPreferences preferences) {
        return decrypt(context, preferences, ENCRYPTED_KEY, INITIALIZATION_VECTOR);
    }

    private static String decrypt(Context context, SharedPreferences preferences,
                                  String encryptedKey, String initializationVectorKey) {
        String encryptedValue = preferences.getString(encryptedKey, "");
        String initializationVector = preferences.getString(initializationVectorKey, "");
        if (encryptedValue.isEmpty() || initializationVector.isEmpty()) return "";
        try {
            SecretKey secretKey = getOrCreateSecretKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    new GCMParameterSpec(128, Base64.decode(initializationVector, Base64.DEFAULT))
            );
            byte[] plaintext = cipher.doFinal(Base64.decode(encryptedValue, Base64.DEFAULT));
            return new String(plaintext, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            preferences.edit().remove(encryptedKey).remove(initializationVectorKey).apply();
            return "";
        }
    }

    private static boolean saveEncrypted(Context context, String value,
                                         String encryptedKey, String initializationVectorKey) {
        try {
            EncryptedValue encrypted = encrypt(value.trim());
            if (encrypted == null) return false;
            return securePreferences(context).edit()
                    .putString(encryptedKey, encrypted.value)
                    .putString(initializationVectorKey, encrypted.initializationVector)
                    .commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static EncryptedValue encrypt(String value) throws Exception {
        SecretKey secretKey = getOrCreateSecretKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return new EncryptedValue(
                Base64.encodeToString(encrypted, Base64.NO_WRAP),
                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
        );
    }

    private static SharedPreferences securePreferences(Context context) {
        return context.getSharedPreferences(SECURE_PREFERENCES, Context.MODE_PRIVATE);
    }

    private static void ensureDefaultJsConfiguration(Context context) {
        String defaultKey = decodeObfuscated(OBFUSCATED_DEFAULT_JS_API_KEY);
        String defaultSecurityCode = decodeObfuscated(OBFUSCATED_DEFAULT_JS_SECURITY_CODE);
        saveJsConfiguration(context, defaultKey, defaultSecurityCode);
    }

    private static SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
            keyStore.deleteEntry(KEY_ALIAS);
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return keyGenerator.generateKey();
    }

    private static String decodeDefaultKey() {
        return decodeObfuscated(OBFUSCATED_DEFAULT_KEY);
    }

    private static String decodeObfuscated(String value) {
        byte[] encoded = Base64.decode(value, Base64.DEFAULT);
        for (int index = 0; index < encoded.length; index++) {
            encoded[index] = (byte) (encoded[index] ^ OBFUSCATION_MASK[index % OBFUSCATION_MASK.length]);
        }
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static boolean isValid(String key) {
        return key != null && key.matches("[0-9A-Za-z]{32}");
    }

    private static boolean isValidSecurityCode(String code) {
        return code != null && code.matches("[0-9A-Za-z]{16,64}");
    }

    private static final class EncryptedValue {
        final String value;
        final String initializationVector;

        EncryptedValue(String value, String initializationVector) {
            this.value = value;
            this.initializationVector = initializationVector;
        }
    }
}
