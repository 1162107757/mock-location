package dev.drift.location;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** WebView adapter for AMap JS API that exposes WGS-84 coordinates to the app. */
final class AmapWebMapView extends FrameLayout {
    interface OnCenterChangedListener {
        void onCenterChanged(double latitude, double longitude);
    }

    interface OnSearchResultListener {
        void onSearchResults(List<SearchResult> results);

        void onSearchFailed(String message);
    }

    private final WebView webView;
    private final CenterCrosshairView crosshairView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String apiKey;
    private final String securityCode;
    private OnCenterChangedListener centerChangedListener;
    private OnSearchResultListener searchResultListener;
    private double centerLatitude = LocationContract.DEFAULT_LATITUDE;
    private double centerLongitude = LocationContract.DEFAULT_LONGITUDE;
    private float zoom = 13f;
    private boolean created;
    private boolean mapReady;
    private boolean destroyed;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    AmapWebMapView(Context context, String apiKey, String securityCode) {
        super(context);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.securityCode = securityCode == null ? "" : securityCode;

        webView = new WebView(context);
        webView.setBackgroundColor(Color.rgb(15, 23, 42));
        webView.setContentDescription("地图选点区域，拖动地图移动中心位置，双指缩放");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                notifyMapError("地图网络加载失败，请检查虚拟机网络");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mainHandler.postDelayed(() -> {
                    if (!destroyed && !mapReady) {
                        notifyMapError("高德地图加载超时，请检查网络和 WebView");
                    }
                }, 12000L);
            }
        });
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            // AMap JS API 1.4.15 can request raster resources through both HTTP
            // and HTTPS. Android 7 otherwise blocks those requests and leaves
            // only the white map canvas visible.
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        webView.addJavascriptInterface(new JavaScriptBridge(), "DriftBridge");
        addView(webView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        crosshairView = new CenterCrosshairView(context);
        addView(crosshairView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void onCreate(Bundle savedInstanceState) {
        if (created) return;
        created = true;
        destroyed = false;
        webView.loadDataWithBaseURL(
                "https://localhost/",
                buildHtml(),
                "text/html",
                "UTF-8",
                null
        );
    }

    void setOnCenterChangedListener(OnCenterChangedListener listener) {
        centerChangedListener = listener;
    }

    void setOnSearchResultListener(OnSearchResultListener listener) {
        searchResultListener = listener;
    }

    void setCenter(double latitude, double longitude) {
        centerLatitude = clamp(latitude, -85.0d, 85.0d);
        centerLongitude = clamp(longitude, -180.0d, 180.0d);
        if (mapReady) {
            evaluate("setCenter(" + number(centerLatitude) + "," + number(centerLongitude)
                    + "," + number(zoom) + ")");
        }
        notifyCenterChanged();
    }

    double getCenterLatitude() {
        return centerLatitude;
    }

    double getCenterLongitude() {
        return centerLongitude;
    }

    void zoomIn() {
        if (mapReady) evaluate("zoomIn()");
    }

    void zoomOut() {
        if (mapReady) evaluate("zoomOut()");
    }

    void zoomToAtLeast(int targetZoom) {
        zoom = Math.max(zoom, Math.min(targetZoom, 20));
        if (mapReady) evaluate("setZoom(" + number(zoom) + ")");
    }

    void search(String query) {
        if (!mapReady) {
            notifySearchFailed("地图尚未加载完成，请稍后再试");
            return;
        }
        evaluate("searchLocation(" + quote(query) + ")");
    }

    void onResume() {
        webView.onResume();
        webView.resumeTimers();
        mainHandler.postDelayed(() -> {
            if (!destroyed) evaluate("resumeMap()");
        }, 100L);
    }

    void onPause() {
        webView.onPause();
        webView.pauseTimers();
    }

    void onDestroy() {
        destroyed = true;
        mapReady = false;
        webView.removeJavascriptInterface("DriftBridge");
        webView.loadUrl("about:blank");
        webView.stopLoading();
        webView.destroy();
    }

    void onSaveInstanceState(Bundle outState) {
        // The center is persisted by MainActivity; reloading the small HTML page is safer
        // than serializing a WebView's remote page state on Android 7.1.
    }

    private void evaluate(String script) {
        if (!destroyed) webView.evaluateJavascript("javascript:" + script, null);
    }

    private void notifyCenterChanged() {
        if (centerChangedListener != null) {
            centerChangedListener.onCenterChanged(centerLatitude, centerLongitude);
        }
    }

    private void notifySearchFailed(String message) {
        if (searchResultListener != null) searchResultListener.onSearchFailed(message);
    }

    private void notifyMapError(String message) {
        if (destroyed || mapReady) return;
        mapReady = false;
        notifySearchFailed(message);
    }

    private String buildHtml() {
        String template = readAsset("amap_map.html");
        boolean legacyMap = Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1;
        return template
                .replace("__AMAP_KEY__", escapeJavaScript(apiKey))
                .replace("__AMAP_SECURITY_CODE__", escapeJavaScript(securityCode))
                .replace("__AMAP_VERSION__", legacyMap ? "1.4.15" : "2.0")
                .replace("__AMAP_LEGACY__", legacyMap ? "true" : "false");
    }

    private String readAsset(String assetName) {
        StringBuilder content = new StringBuilder();
        try (InputStream stream = getContext().getAssets().open(assetName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) content.append(line).append('\n');
        } catch (Exception exception) {
            return "<!doctype html><html><body style=\"background:#0f172a\"></body></html>";
        }
        return content.toString();
    }

    private static String escapeJavaScript(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String quote(String value) {
        return JSONObject.quote(value == null ? "" : value);
    }

    private static String number(double value) {
        return String.format(Locale.US, "%.8f", value);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private final class JavaScriptBridge {
        @JavascriptInterface
        public void onMapReady() {
            mainHandler.post(() -> {
                if (destroyed) return;
                mapReady = true;
                evaluate("setCenter(" + number(centerLatitude) + "," + number(centerLongitude)
                        + "," + number(zoom) + ")");
            });
        }

        @JavascriptInterface
        public void onCenterChanged(double gcjLatitude, double gcjLongitude) {
            mainHandler.post(() -> {
                if (destroyed || !Double.isFinite(gcjLatitude) || !Double.isFinite(gcjLongitude)) return;
                CoordinateTransform.Coordinate wgs84 = CoordinateTransform.gcj02ToWgs84(
                        gcjLatitude, gcjLongitude);
                centerLatitude = wgs84.latitude;
                centerLongitude = wgs84.longitude;
                notifyCenterChanged();
            });
        }

        @JavascriptInterface
        public void onSearchResults(String json) {
            mainHandler.post(() -> deliverSearchResults(json));
        }

        @JavascriptInterface
        public void onSearchError(String message) {
            mainHandler.post(() -> notifySearchFailed(message == null ? "高德搜索失败" : message));
        }

        @JavascriptInterface
        public void onMapError(String message) {
            mainHandler.post(() -> notifyMapError(
                    message == null ? "高德 JS 地图加载失败" : message));
        }
    }

    private void deliverSearchResults(String json) {
        if (searchResultListener == null) return;
        ArrayList<SearchResult> results = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                double gcjLatitude = item.optDouble("latitude", Double.NaN);
                double gcjLongitude = item.optDouble("longitude", Double.NaN);
                if (!Double.isFinite(gcjLatitude) || !Double.isFinite(gcjLongitude)) continue;
                CoordinateTransform.Coordinate wgs84 = CoordinateTransform.gcj02ToWgs84(
                        gcjLatitude, gcjLongitude);
                results.add(new SearchResult(
                        item.optString("name", "地点"),
                        wgs84.latitude,
                        wgs84.longitude
                ));
            }
            searchResultListener.onSearchResults(results);
        } catch (Exception exception) {
            notifySearchFailed("高德搜索结果解析失败");
        }
    }

    static final class SearchResult {
        final String name;
        final double latitude;
        final double longitude;

        SearchResult(String name, double latitude, double longitude) {
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final class CenterCrosshairView extends View {
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CenterCrosshairView(Context context) {
            super(context);
            setClickable(false);
            setFocusable(false);
            ringPaint.setColor(Color.WHITE);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(context, 3));
            fillPaint.setColor(Color.rgb(34, 197, 94));
            fillPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float x = getWidth() / 2f;
            float y = getHeight() / 2f;
            float outer = dp(getContext(), 10);
            canvas.drawCircle(x, y, outer, ringPaint);
            canvas.drawCircle(x, y, dp(getContext(), 6), fillPaint);
            canvas.drawLine(x, y + outer, x, y + dp(getContext(), 18), ringPaint);
        }

        private static int dp(Context context, float value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }
    }
}
