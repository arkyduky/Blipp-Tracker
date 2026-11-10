package com.example.safetynet.dto;

import java.util.Objects;

/**
 * Represents a single leg between two consecutive waypoints along a route.
 */
public class RouteLeg {

    private double distanceMeters;
    private double durationSeconds;
    private int startWaypointIndex;
    private int endWaypointIndex;

    public RouteLeg() {
    }

    public RouteLeg(double distanceMeters, double durationSeconds, int startWaypointIndex, int endWaypointIndex) {
        this.distanceMeters = distanceMeters;
        this.durationSeconds = durationSeconds;
        this.startWaypointIndex = startWaypointIndex;
        this.endWaypointIndex = endWaypointIndex;
    }

    public double getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(double distanceMeters) {
        this.distanceMeters = distanceMeters;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(double durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public int getStartWaypointIndex() {
        return startWaypointIndex;
    }

    public void setStartWaypointIndex(int startWaypointIndex) {
        this.startWaypointIndex = startWaypointIndex;
    }

    public int getEndWaypointIndex() {
        return endWaypointIndex;
    }

    public void setEndWaypointIndex(int endWaypointIndex) {
        this.endWaypointIndex = endWaypointIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RouteLeg routeLeg = (RouteLeg) o;
        return Double.compare(routeLeg.distanceMeters, distanceMeters) == 0 &&
                Double.compare(routeLeg.durationSeconds, durationSeconds) == 0 &&
                startWaypointIndex == routeLeg.startWaypointIndex &&
                endWaypointIndex == routeLeg.endWaypointIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(distanceMeters, durationSeconds, startWaypointIndex, endWaypointIndex);
    }

    @Override
    public String toString() {
        return "RouteLeg{" +
                "distanceMeters=" + distanceMeters +
                ", durationSeconds=" + durationSeconds +
                ", startWaypointIndex=" + startWaypointIndex +
                ", endWaypointIndex=" + endWaypointIndex +
                '}';
    }
}
