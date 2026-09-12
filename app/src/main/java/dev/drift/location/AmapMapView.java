package dev.drift.location;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;

/** AMap UI adapter that exposes WGS-84 coordinates to the rest of the app. */
final class AmapMapView extends FrameLayout implements AMap.OnCameraChangeListener {
    interface OnCenterChangedListener {
        void onCenterChanged(double latitude, double longitude);
    }

    private final MapView sdkMapView;
    private final CenterCrosshairView crosshairView;
    private AMap amap;
    private OnCenterChangedListener centerChangedListener;
    private double centerLatitude = LocationContract.DEFAULT_LATITUDE;
    private double centerLongitude = LocationContract.DEFAULT_LONGITUDE;
    private float zoom = 13f;
    private boolean created;

    AmapMapView(Context context) {
        super(context);
        sdkMapView = new MapView(context);
        addView(sdkMapView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        crosshairView = new CenterCrosshairView(context);
        addView(crosshairView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void onCreate(Bundle savedInstanceState) {
        if (created) return;
        created = true;
        sdkMapView.onCreate(savedInstanceState);
        amap = sdkMapView.getMap();
        amap.setOnCameraChangeListener(this);
        amap.setMapType(AMap.MAP_TYPE_NORMAL);
        amap.getUiSettings().setZoomControlsEnabled(false);
        amap.getUiSettings().setCompassEnabled(false);
        amap.getUiSettings().setScaleControlsEnabled(false);
        amap.getUiSettings().setRotateGesturesEnabled(false);
        amap.getUiSettings().setTiltGesturesEnabled(false);
        amap.getUiSettings().setLogoBottomMargin(dp(250));
        moveCamera(false);
    }

    void setOnCenterChangedListener(OnCenterChangedListener listener) {
        centerChangedListener = listener;
    }

    void setCenter(double latitude, double longitude) {
        centerLatitude = clamp(latitude, -85.0d, 85.0d);
        centerLongitude = clamp(longitude, -180.0d, 180.0d);
        moveCamera(false);
        notifyCenterChanged();
    }

    double getCenterLatitude() {
        return centerLatitude;
    }

    double getCenterLongitude() {
        return centerLongitude;
    }

    void zoomIn() {
        if (amap == null) return;
        amap.animateCamera(CameraUpdateFactory.zoomIn());
    }

    void zoomOut() {
        if (amap == null) return;
        amap.animateCamera(CameraUpdateFactory.zoomOut());
    }

    void zoomToAtLeast(int targetZoom) {
        zoom = Math.max(zoom, Math.min(targetZoom, 20));
        moveCamera(true);
    }

    void onResume() {
        sdkMapView.onResume();
    }

    void onPause() {
        sdkMapView.onPause();
    }

    void onDestroy() {
        sdkMapView.onDestroy();
    }

    void onSaveInstanceState(Bundle outState) {
        sdkMapView.onSaveInstanceState(outState);
    }

    @Override
    public void onCameraChange(CameraPosition cameraPosition) {
        // Persist only settled camera positions so dragging does not flood the location service.
    }

    @Override
    public void onCameraChangeFinish(CameraPosition cameraPosition) {
        if (cameraPosition == null || cameraPosition.target == null) return;
        zoom = cameraPosition.zoom;
        CoordinateTransform.Coordinate wgs84 = CoordinateTransform.gcj02ToWgs84(
                cameraPosition.target.latitude,
                cameraPosition.target.longitude
        );
        centerLatitude = wgs84.latitude;
        centerLongitude = wgs84.longitude;
        notifyCenterChanged();
    }

    private void moveCamera(boolean animate) {
        if (amap == null) return;
        CoordinateTransform.Coordinate gcj02 = CoordinateTransform.wgs84ToGcj02(
                centerLatitude,
                centerLongitude
        );
        LatLng target = new LatLng(gcj02.latitude, gcj02.longitude);
        if (animate) {
            amap.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom));
        } else {
            amap.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom));
        }
    }

    private void notifyCenterChanged() {
        if (centerChangedListener != null) {
            centerChangedListener.onCenterChanged(centerLatitude, centerLongitude);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class CenterCrosshairView extends View {
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CenterCrosshairView(Context context) {
            super(context);
            setClickable(false);
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
