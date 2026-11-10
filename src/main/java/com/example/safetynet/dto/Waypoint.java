package com.example.safetynet.dto;

import java.util.Objects;

/**
 * Geographic waypoint representing an ordered stop along a route.
 */
public class Waypoint {

    private String name;
    private Double latitude;
    private Double longitude;
    private int sequence;

    public Waypoint() {
    }

    public Waypoint(Double latitude, Double longitude) {
        this(null, latitude, longitude, 0);
    }

    public Waypoint(String name, Double latitude, Double longitude) {
        this(name, latitude, longitude, 0);
    }

    public Waypoint(String name, Double latitude, Double longitude, int sequence) {
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.sequence = sequence;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Waypoint waypoint = (Waypoint) o;
        return sequence == waypoint.sequence &&
                Objects.equals(name, waypoint.name) &&
                Objects.equals(latitude, waypoint.latitude) &&
                Objects.equals(longitude, waypoint.longitude);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, latitude, longitude, sequence);
    }

    @Override
    public String toString() {
        return "Waypoint{" +
                "name='" + name + '\'' +
                ", latitude=" + latitude +
                ", longitude=" + longitude +
                ", sequence=" + sequence +
                '}';
    }
}
