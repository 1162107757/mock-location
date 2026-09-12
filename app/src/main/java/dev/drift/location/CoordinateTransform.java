package dev.drift.location;

/** Keeps app and Android locations in WGS-84 and converts only for GCJ-02 map SDKs. */
final class CoordinateTransform {
    private static final double EARTH_SEMI_MAJOR_AXIS = 6378245.0d;
    private static final double ECCENTRICITY_SQUARED = 0.00669342162296594323d;

    static Coordinate wgs84ToGcj02(double latitude, double longitude) {
        if (outsideMainlandChina(latitude, longitude)) {
            return new Coordinate(latitude, longitude);
        }
        double latitudeOffset = transformLatitude(longitude - 105.0d, latitude - 35.0d);
        double longitudeOffset = transformLongitude(longitude - 105.0d, latitude - 35.0d);
        double radians = Math.toRadians(latitude);
        double sinLatitude = Math.sin(radians);
        double magic = 1.0d - ECCENTRICITY_SQUARED * sinLatitude * sinLatitude;
        double sqrtMagic = Math.sqrt(magic);
        latitudeOffset = latitudeOffset * 180.0d
                / ((EARTH_SEMI_MAJOR_AXIS * (1.0d - ECCENTRICITY_SQUARED))
                / (magic * sqrtMagic) * Math.PI);
        longitudeOffset = longitudeOffset * 180.0d
                / (EARTH_SEMI_MAJOR_AXIS / sqrtMagic * Math.cos(radians) * Math.PI);
        return new Coordinate(latitude + latitudeOffset, longitude + longitudeOffset);
    }

    /** Iteratively reverses the GCJ-02 offset while keeping WGS-84 as app storage format. */
    static Coordinate gcj02ToWgs84(double latitude, double longitude) {
        if (outsideMainlandChina(latitude, longitude)) {
            return new Coordinate(latitude, longitude);
        }
        double candidateLatitude = latitude;
        double candidateLongitude = longitude;
        for (int iteration = 0; iteration < 8; iteration++) {
            Coordinate transformed = wgs84ToGcj02(candidateLatitude, candidateLongitude);
            double latitudeError = transformed.latitude - latitude;
            double longitudeError = transformed.longitude - longitude;
            candidateLatitude -= latitudeError;
            candidateLongitude -= longitudeError;
            if (Math.abs(latitudeError) < 1e-7d && Math.abs(longitudeError) < 1e-7d) break;
        }
        return new Coordinate(candidateLatitude, candidateLongitude);
    }

    private static boolean outsideMainlandChina(double latitude, double longitude) {
        return longitude < 72.004d || longitude > 137.8347d
                || latitude < 0.8293d || latitude > 55.8271d;
    }

    private static double transformLatitude(double x, double y) {
        double result = -100.0d + 2.0d * x + 3.0d * y + 0.2d * y * y
                + 0.1d * x * y + 0.2d * Math.sqrt(Math.abs(x));
        result += (20.0d * Math.sin(6.0d * x * Math.PI)
                + 20.0d * Math.sin(2.0d * x * Math.PI)) * 2.0d / 3.0d;
        result += (20.0d * Math.sin(y * Math.PI)
                + 40.0d * Math.sin(y / 3.0d * Math.PI)) * 2.0d / 3.0d;
        result += (160.0d * Math.sin(y / 12.0d * Math.PI)
                + 320.0d * Math.sin(y * Math.PI / 30.0d)) * 2.0d / 3.0d;
        return result;
    }

    private static double transformLongitude(double x, double y) {
        double result = 300.0d + x + 2.0d * y + 0.1d * x * x
                + 0.1d * x * y + 0.1d * Math.sqrt(Math.abs(x));
        result += (20.0d * Math.sin(6.0d * x * Math.PI)
                + 20.0d * Math.sin(2.0d * x * Math.PI)) * 2.0d / 3.0d;
        result += (20.0d * Math.sin(x * Math.PI)
                + 40.0d * Math.sin(x / 3.0d * Math.PI)) * 2.0d / 3.0d;
        result += (150.0d * Math.sin(x / 12.0d * Math.PI)
                + 300.0d * Math.sin(x / 30.0d * Math.PI)) * 2.0d / 3.0d;
        return result;
    }

    static final class Coordinate {
        final double latitude;
        final double longitude;

        Coordinate(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private CoordinateTransform() {}
}
