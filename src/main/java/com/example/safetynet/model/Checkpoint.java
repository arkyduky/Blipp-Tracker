package com.example.safetynet.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

@Entity
@Table(name = "checkpoints")
public class Checkpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "ride_id")
    private Ride ride;

    private int sequenceNumber;
    private double positionOnRoute; // 0-100
    private Instant expectedTime;

    private String name;
    private Double latitude;
    private Double longitude;
    private Double distanceFromOriginMeters;

    @Enumerated(EnumType.STRING)
    private CheckpointStatus status = CheckpointStatus.NOT_REACHED;

    private Instant confirmationSentAt; // nullable
    private Instant graceDeadline;      // nullable

    public enum CheckpointStatus {
        NOT_REACHED, PASSED, AWAITING_CONFIRMATION, ESCALATED
    }

    public Checkpoint() {
    }

    public Checkpoint(Ride ride, int sequenceNumber, double positionOnRoute, Instant expectedTime) {
        this.ride = ride;
        this.sequenceNumber = sequenceNumber;
        this.positionOnRoute = positionOnRoute;
        this.expectedTime = expectedTime;
    }

    public Checkpoint(Ride ride, int sequenceNumber, double positionOnRoute, LocalDateTime expectedTime) {
        this(ride, sequenceNumber, positionOnRoute,
                expectedTime != null ? expectedTime.toInstant(ZoneOffset.UTC) : null);
    }

    public Checkpoint(Ride ride, int sequenceNumber, String name, Double latitude, Double longitude,
                      Double distanceFromOriginMeters, double positionOnRoute, Instant expectedTime) {
        this.ride = ride;
        this.sequenceNumber = sequenceNumber;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.distanceFromOriginMeters = distanceFromOriginMeters;
        this.positionOnRoute = positionOnRoute;
        this.expectedTime = expectedTime;
    }

    public Checkpoint(Ride ride, int sequenceNumber, String name, Double latitude, Double longitude,
                      Double distanceFromOriginMeters, double positionOnRoute, LocalDateTime expectedTime) {
        this(ride, sequenceNumber, name, latitude, longitude, distanceFromOriginMeters, positionOnRoute,
                expectedTime != null ? expectedTime.toInstant(ZoneOffset.UTC) : null);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Ride getRide() { return ride; }
    public void setRide(Ride ride) { this.ride = ride; }

    public int getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(int sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public double getPositionOnRoute() { return positionOnRoute; }
    public void setPositionOnRoute(double positionOnRoute) { this.positionOnRoute = positionOnRoute; }

    public Instant getExpectedTime() { return expectedTime; }
    public void setExpectedTime(Instant expectedTime) { this.expectedTime = expectedTime; }
    public void setExpectedTime(LocalDateTime expectedTime) {
        this.expectedTime = expectedTime != null ? expectedTime.toInstant(ZoneOffset.UTC) : null;
    }

    public CheckpointStatus getStatus() { return status; }
    public void setStatus(CheckpointStatus status) { this.status = status; }

    public Instant getConfirmationSentAt() { return confirmationSentAt; }
    public void setConfirmationSentAt(Instant confirmationSentAt) { this.confirmationSentAt = confirmationSentAt; }

    public Instant getGraceDeadline() { return graceDeadline; }
    public void setGraceDeadline(Instant graceDeadline) { this.graceDeadline = graceDeadline; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public Double getDistanceFromOriginMeters() { return distanceFromOriginMeters; }
    public void setDistanceFromOriginMeters(Double distanceFromOriginMeters) { this.distanceFromOriginMeters = distanceFromOriginMeters; }
}
