package com.example.safetynet.model;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "rides")
public class Ride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "rider_id")
    private Rider rider;

    @Enumerated(EnumType.STRING)
    private RideStatus status = RideStatus.ACTIVE;

    private boolean isDemo = false;

    private double currentPosition = 0.0; // 0-100 along the route

    private boolean isMoving = false;

    private Instant stationarySince; // nullable — set the instant movement stops

    private Double lastKnownLatitude;   // nullable — null until a location has ever been received
    private Double lastKnownLongitude;  // nullable
    private Instant lastLocationUpdatedAt; // nullable

    @Column(columnDefinition = "TEXT")
    private String routeGeometry; // GeoJSON LineString representing planned route

    private Double totalDistanceMeters;
    private Double totalDurationSeconds;

    private Double originLatitude;
    private Double originLongitude;
    private Double destinationLatitude;
    private Double destinationLongitude;

    private LocalDateTime createdAt = LocalDateTime.now();

    private boolean routePromptSent = false;

    public enum RideStatus {
        ACTIVE, COMPLETED
    }

    public Ride() {
    }

    public Ride(Rider rider) {
        this.rider = rider;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Rider getRider() { return rider; }
    public void setRider(Rider rider) { this.rider = rider; }

    public RideStatus getStatus() { return status; }
    public void setStatus(RideStatus status) { this.status = status; }

    public double getCurrentPosition() { return currentPosition; }
    public void setCurrentPosition(double currentPosition) { this.currentPosition = currentPosition; }

    public boolean isMoving() { return isMoving; }
    public void setMoving(boolean moving) { isMoving = moving; }

    public Instant getStationarySince() { return stationarySince; }
    public void setStationarySince(Instant stationarySince) { this.stationarySince = stationarySince; }

    @Transient
    public Long getStationaryDurationSeconds() {
        if (stationarySince == null) {
            return null;
        }
        long seconds = Duration.between(stationarySince, Instant.now()).getSeconds();
        return Math.max(0L, seconds);
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Double getLastKnownLatitude() { return lastKnownLatitude; }
    public void setLastKnownLatitude(Double lastKnownLatitude) { this.lastKnownLatitude = lastKnownLatitude; }

    public Double getLastKnownLongitude() { return lastKnownLongitude; }
    public void setLastKnownLongitude(Double lastKnownLongitude) { this.lastKnownLongitude = lastKnownLongitude; }

    public Instant getLastLocationUpdatedAt() { return lastLocationUpdatedAt; }
    public void setLastLocationUpdatedAt(Instant lastLocationUpdatedAt) { this.lastLocationUpdatedAt = lastLocationUpdatedAt; }

    public String getRouteGeometry() { return routeGeometry; }
    public void setRouteGeometry(String routeGeometry) { this.routeGeometry = routeGeometry; }

    public Double getTotalDistanceMeters() { return totalDistanceMeters; }
    public void setTotalDistanceMeters(Double totalDistanceMeters) { this.totalDistanceMeters = totalDistanceMeters; }

    public Double getTotalDurationSeconds() { return totalDurationSeconds; }
    public void setTotalDurationSeconds(Double totalDurationSeconds) { this.totalDurationSeconds = totalDurationSeconds; }

    public Double getOriginLatitude() { return originLatitude; }
    public void setOriginLatitude(Double originLatitude) { this.originLatitude = originLatitude; }

    public Double getOriginLongitude() { return originLongitude; }
    public void setOriginLongitude(Double originLongitude) { this.originLongitude = originLongitude; }

    public Double getDestinationLatitude() { return destinationLatitude; }
    public void setDestinationLatitude(Double destinationLatitude) { this.destinationLatitude = destinationLatitude; }

    public Double getDestinationLongitude() { return destinationLongitude; }
    public void setDestinationLongitude(Double destinationLongitude) { this.destinationLongitude = destinationLongitude; }

    public boolean isDemo() { return isDemo; }
    public void setDemo(boolean isDemo) { this.isDemo = isDemo; }

    public boolean isRoutePromptSent() { return routePromptSent; }
    public void setRoutePromptSent(boolean routePromptSent) { this.routePromptSent = routePromptSent; }
}
