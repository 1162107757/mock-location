package dev.drift.location;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.BufferedInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MapCanvasView extends View {
    public interface OnCenterChangedListener {
        void onCenterChanged(double latitude, double longitude);
    }

    private static final int TILE_SIZE = 256;
    private static final double MAX_LATITUDE = 85.05112878;

    private final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attributionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tileDestination = new RectF();
    private final LruCache<String, Bitmap> tileCache;
    private final Set<String> loadingTiles = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService tileExecutor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScaleGestureDetector scaleDetector;

    private double centerLatitude = LocationContract.DEFAULT_LATITUDE;
    private double centerLongitude = LocationContract.DEFAULT_LONGITUDE;
    private int zoom = 13;
    private float lastTouchX;
    private float lastTouchY;
    private float scaleAccumulator = 1f;
    private boolean dragging;
    private OnCenterChangedListener centerChangedListener;

    public MapCanvasView(Context context) {
        this(context, null);
    }

    public MapCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("地图选点区域，拖动地图移动中心位置，双指缩放");

        int cacheSize = Math.max(8 * 1024 * 1024, (int) (Runtime.getRuntime().maxMemory() / 16));
        tileCache = new LruCache<>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0.55f);
        ColorMatrix shade = new ColorMatrix(new float[]{
                0.66f, 0, 0, 0, 10,
                0, 0.70f, 0, 0, 14,
                0, 0, 0.78f, 0, 20,
                0, 0, 0, 1, 0
        });
        matrix.postConcat(shade);
        tilePaint.setColorFilter(new ColorMatrixColorFilter(matrix));

        placeholderPaint.setColor(Color.rgb(30, 41, 59));
        linePaint.setColor(Color.rgb(71, 85, 105));
        linePaint.setStrokeWidth(dp(1));
        markerPaint.setColor(Color.rgb(34, 197, 94));
        markerInnerPaint.setColor(Color.rgb(15, 23, 42));
        attributionPaint.setColor(Color.WHITE);
        attributionPaint.setTextSize(dp(10));
        attributionPaint.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                scaleAccumulator = 1f;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleAccumulator *= detector.getScaleFactor();
                boolean changed = false;
                while (scaleAccumulator >= 1.25f && zoom < 19) {
                    zoom++;
                    scaleAccumulator /= 1.25f;
                    changed = true;
                }
                while (scaleAccumulator <= 0.80f && zoom > 2) {
                    zoom--;
                    scaleAccumulator /= 0.80f;
                    changed = true;
                }
                if (changed) {
                    invalidate();
                }
                return true;
            }
        });
    }

    public void setOnCenterChangedListener(OnCenterChangedListener listener) {
        centerChangedListener = listener;
    }

    public void setCenter(double latitude, double longitude) {
        centerLatitude = clampLatitude(latitude);
        centerLongitude = wrapLongitude(longitude);
        notifyCenterChanged();
        invalidate();
    }

    public double getCenterLatitude() {
        return centerLatitude;
    }

    public double getCenterLongitude() {
        return centerLongitude;
    }

    public void zoomIn() {
        if (zoom < 19) {
            zoom++;
            invalidate();
        }
    }

    public void zoomOut() {
        if (zoom > 2) {
            zoom--;
            invalidate();
        }
    }

    public void zoomToAtLeast(int targetZoom) {
        int clamped = Math.max(2, Math.min(19, targetZoom));
        if (zoom < clamped) {
            zoom = clamped;
            invalidate();
        }
    }

    public void moveByMeters(double northMeters, double eastMeters) {
        double latitudeRadians = Math.toRadians(centerLatitude);
        double latitudeDelta = northMeters / 111_320.0;
        double longitudeScale = Math.max(0.01, Math.cos(latitudeRadians));
        double longitudeDelta = eastMeters / (111_320.0 * longitudeScale);
        setCenter(centerLatitude + latitudeDelta, centerLongitude + longitudeDelta);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(15, 23, 42));
        if (getWidth() == 0 || getHeight() == 0) return;

        double centerWorldX = longitudeToWorldX(centerLongitude, zoom);
        double centerWorldY = latitudeToWorldY(centerLatitude, zoom);
        double leftWorld = centerWorldX - getWidth() / 2.0;
        double topWorld = centerWorldY - getHeight() / 2.0;

        int firstTileX = (int) Math.floor(leftWorld / TILE_SIZE);
        int firstTileY = (int) Math.floor(topWorld / TILE_SIZE);
        int lastTileX = (int) Math.floor((leftWorld + getWidth()) / TILE_SIZE);
        int lastTileY = (int) Math.floor((topWorld + getHeight()) / TILE_SIZE);
        int tileCount = 1 << zoom;

        for (int tileY = firstTileY; tileY <= lastTileY; tileY++) {
            if (tileY < 0 || tileY >= tileCount) continue;
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                int wrappedX = Math.floorMod(tileX, tileCount);
                float left = (float) (tileX * TILE_SIZE - leftWorld);
                float top = (float) (tileY * TILE_SIZE - topWorld);
                tileDestination.set(left, top, left + TILE_SIZE, top + TILE_SIZE);
                String key = zoom + "/" + wrappedX + "/" + tileY;
                Bitmap bitmap = tileCache.get(key);
                if (bitmap != null && !bitmap.isRecycled()) {
                    canvas.drawBitmap(bitmap, null, tileDestination, tilePaint);
                } else {
                    drawTilePlaceholder(canvas, tileDestination);
                    loadTile(key, zoom, wrappedX, tileY);
                }
            }
        }

        drawCenterMarker(canvas);
        canvas.drawText("© OpenStreetMap contributors", dp(10), getHeight() - dp(10), attributionPaint);
    }

    private void drawTilePlaceholder(Canvas canvas, RectF destination) {
        canvas.drawRect(destination, placeholderPaint);
        canvas.drawLine(destination.left, destination.top, destination.right, destination.top, linePaint);
        canvas.drawLine(destination.left, destination.top, destination.left, destination.bottom, linePaint);
    }

    private void drawCenterMarker(Canvas canvas) {
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float radius = dp(16);
        Path pin = new Path();
        pin.moveTo(centerX, centerY + dp(25));
        pin.cubicTo(centerX - dp(5), centerY + dp(13), centerX - radius, centerY + dp(6), centerX - radius, centerY - dp(4));
        pin.arcTo(new RectF(centerX - radius, centerY - dp(20), centerX + radius, centerY + dp(12)), 180, 180, false);
        pin.cubicTo(centerX + radius, centerY + dp(6), centerX + dp(5), centerY + dp(13), centerX, centerY + dp(25));
        pin.close();
        markerPaint.setShadowLayer(dp(8), 0, dp(4), 0x66000000);
        canvas.drawPath(pin, markerPaint);
        markerPaint.clearShadowLayer();
        canvas.drawCircle(centerX, centerY - dp(4), dp(6), markerInnerPaint);
    }

    private void loadTile(String key, int tileZoom, int tileX, int tileY) {
        if (!loadingTiles.add(key)) return;
        tileExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(String.format(Locale.US,
                        "https://tile.openstreetmap.de/%d/%d/%d.png", tileZoom, tileX, tileY));
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("User-Agent", "DriftLocation/0.1 (Android location testing tool)");
                try (BufferedInputStream stream = new BufferedInputStream(connection.getInputStream())) {
                    Bitmap bitmap = BitmapFactory.decodeStream(stream);
                    if (bitmap != null) {
                        mainHandler.post(() -> {
                            tileCache.put(key, bitmap);
                            loadingTiles.remove(key);
                            invalidate();
                        });
                        return;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
            loadingTiles.remove(key);
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                    float deltaX = event.getX() - lastTouchX;
                    float deltaY = event.getY() - lastTouchY;
                    panByPixels(deltaX, deltaY);
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                notifyCenterChanged();
                performClick();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void panByPixels(float deltaX, float deltaY) {
        double worldX = longitudeToWorldX(centerLongitude, zoom) - deltaX;
        double worldY = latitudeToWorldY(centerLatitude, zoom) - deltaY;
        centerLongitude = worldXToLongitude(worldX, zoom);
        centerLatitude = worldYToLatitude(worldY, zoom);
        notifyCenterChanged();
        invalidate();
    }

    private void notifyCenterChanged() {
        if (centerChangedListener != null) {
            centerChangedListener.onCenterChanged(centerLatitude, centerLongitude);
        }
    }

    private static double longitudeToWorldX(double longitude, int zoom) {
        double worldSize = TILE_SIZE * (double) (1 << zoom);
        return (wrapLongitude(longitude) + 180.0) / 360.0 * worldSize;
    }

    private static double latitudeToWorldY(double latitude, int zoom) {
        double worldSize = TILE_SIZE * (double) (1 << zoom);
        double radians = Math.toRadians(clampLatitude(latitude));
        return (1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians)) / Math.PI) / 2.0 * worldSize;
    }

    private static double worldXToLongitude(double worldX, int zoom) {
        double worldSize = TILE_SIZE * (double) (1 << zoom);
        return wrapLongitude(worldX / worldSize * 360.0 - 180.0);
    }

    private static double worldYToLatitude(double worldY, int zoom) {
        double worldSize = TILE_SIZE * (double) (1 << zoom);
        double normalizedY = Math.max(0, Math.min(worldSize, worldY)) / worldSize;
        double mercator = Math.PI * (1.0 - 2.0 * normalizedY);
        return Math.toDegrees(Math.atan(Math.sinh(mercator)));
    }

    private static double clampLatitude(double latitude) {
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
    }

    private static double wrapLongitude(double longitude) {
        double wrapped = (longitude + 180.0) % 360.0;
        if (wrapped < 0) wrapped += 360.0;
        return wrapped - 180.0;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        tileExecutor.shutdownNow();
    }
}
