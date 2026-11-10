package com.example.safetynet.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an OSRM route calculation containing road geometry, total distance,
 * total duration, and individual per-leg details.
 */
public class RouteResult {

    private String geometryGeoJson;
    private List<List<Double>> coordinates = new ArrayList<>();
    private double totalDistanceMeters;
    private double totalDurationSeconds;
    private List<RouteLeg> legs = new ArrayList<>();

    public RouteResult() {
    }

    public RouteResult(String geometryGeoJson, List<List<Double>> coordinates,
                       double totalDistanceMeters, double totalDurationSeconds,
                       List<RouteLeg> legs) {
        this.geometryGeoJson = geometryGeoJson;
        this.coordinates = coordinates != null ? coordinates : new ArrayList<>();
        this.totalDistanceMeters = totalDistanceMeters;
        this.totalDurationSeconds = totalDurationSeconds;
        this.legs = legs != null ? legs : new ArrayList<>();
    }

    public String getGeometryGeoJson() {
        return geometryGeoJson;
    }

    /**
     * Convenience alias for getGeometryGeoJson() used by UI and persistence layers.
     */
    public String getGeometry() {
        return geometryGeoJson;
    }

    public void setGeometryGeoJson(String geometryGeoJson) {
        this.geometryGeoJson = geometryGeoJson;
    }

    public List<List<Double>> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(List<List<Double>> coordinates) {
        this.coordinates = coordinates;
    }

    public double getTotalDistanceMeters() {
        return totalDistanceMeters;
    }

    public void setTotalDistanceMeters(double totalDistanceMeters) {
        this.totalDistanceMeters = totalDistanceMeters;
    }

    public double getTotalDurationSeconds() {
        return totalDurationSeconds;
    }

    public void setTotalDurationSeconds(double totalDurationSeconds) {
        this.totalDurationSeconds = totalDurationSeconds;
    }

    public List<RouteLeg> getLegs() {
        return legs;
    }

    public void setLegs(List<RouteLeg> legs) {
        this.legs = legs;
    }

    @Override
    public String toString() {
        return "RouteResult{" +
                "totalDistanceMeters=" + totalDistanceMeters +
                ", totalDurationSeconds=" + totalDurationSeconds +
                ", coordinatesCount=" + (coordinates != null ? coordinates.size() : 0) +
                ", legsCount=" + (legs != null ? legs.size() : 0) +
                '}';
    }
}
