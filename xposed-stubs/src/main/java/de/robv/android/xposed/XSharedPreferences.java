package de.robv.android.xposed;

public class XSharedPreferences {
    public XSharedPreferences(String packageName, String prefFileName) {}
    public boolean getBoolean(String key, boolean defaultValue) { return defaultValue; }
    public long getLong(String key, long defaultValue) { return defaultValue; }
    public float getFloat(String key, float defaultValue) { return defaultValue; }
    public boolean makeWorldReadable() { return false; }
    public void reload() {}
}
