package com.example.safetynet.dto;

import java.util.Objects;

/**
 * Result of projecting a GPS coordinate onto a route polyline.
 */
public class GeoProjectionResult {

    private final boolean valid;
    private final double progressPercent;
    private final double distanceAlongRouteMeters;
    private final double distanceFromRouteMeters;
    private final Double projectedLatitude;
    private final Double projectedLongitude;

    public GeoProjectionResult(boolean valid, double progressPercent,
                               double distanceAlongRouteMeters, double distanceFromRouteMeters,
                               Double projectedLatitude, Double projectedLongitude) {
        this.valid = valid;
        this.progressPercent = progressPercent;
        this.distanceAlongRouteMeters = distanceAlongRouteMeters;
        this.distanceFromRouteMeters = distanceFromRouteMeters;
        this.projectedLatitude = projectedLatitude;
        this.projectedLongitude = projectedLongitude;
    }

    public static GeoProjectionResult invalid() {
        return new GeoProjectionResult(false, 0.0, 0.0, 0.0, null, null);
    }

    public static GeoProjectionResult valid(double progressPercent,
                                            double distanceAlongRouteMeters,
                                            double distanceFromRouteMeters,
                                            Double projectedLatitude,
                                            Double projectedLongitude) {
        return new GeoProjectionResult(true, progressPercent, distanceAlongRouteMeters,
                distanceFromRouteMeters, projectedLatitude, projectedLongitude);
    }

    public boolean isValid() {
        return valid;
    }

    public double getProgressPercent() {
        return progressPercent;
    }

    public double getDistanceAlongRouteMeters() {
        return distanceAlongRouteMeters;
    }

    public double getDistanceFromRouteMeters() {
        return distanceFromRouteMeters;
    }

    public Double getProjectedLatitude() {
        return projectedLatitude;
    }

    public Double getProjectedLongitude() {
        return projectedLongitude;
    }

    // Record-style accessor aliases
    public double progressPercent() {
        return progressPercent;
    }

    public double distanceAlongRouteMeters() {
        return distanceAlongRouteMeters;
    }

    public double distanceFromRouteMeters() {
        return distanceFromRouteMeters;
    }

    public Double projectedLatitude() {
        return projectedLatitude;
    }

    public Double projectedLongitude() {
        return projectedLongitude;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GeoProjectionResult that = (GeoProjectionResult) o;
        return valid == that.valid &&
                Double.compare(that.progressPercent, progressPercent) == 0 &&
                Double.compare(that.distanceAlongRouteMeters, distanceAlongRouteMeters) == 0 &&
                Double.compare(that.distanceFromRouteMeters, distanceFromRouteMeters) == 0 &&
                Objects.equals(projectedLatitude, that.projectedLatitude) &&
                Objects.equals(projectedLongitude, that.projectedLongitude);
    }

    @Override
    public int hashCode() {
        return Objects.hash(valid, progressPercent, distanceAlongRouteMeters, distanceFromRouteMeters,
                projectedLatitude, projectedLongitude);
    }

    @Override
    public String toString() {
        return "GeoProjectionResult{" +
                "valid=" + valid +
                ", progressPercent=" + progressPercent +
                ", distanceAlongRouteMeters=" + distanceAlongRouteMeters +
                ", distanceFromRouteMeters=" + distanceFromRouteMeters +
                ", projectedLatitude=" + projectedLatitude +
                ", projectedLongitude=" + projectedLongitude +
                '}';
    }
}
