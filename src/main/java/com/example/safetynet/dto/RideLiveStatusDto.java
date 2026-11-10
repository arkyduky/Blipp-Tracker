package com.example.safetynet.dto;

import com.example.safetynet.model.Checkpoint;
import com.example.safetynet.model.Ride;

import java.time.Instant;
import java.util.List;

/**
 * Read-only DTO exposing comprehensive real-time ride, route, checkpoint,
 * and safety status data for the parent-facing live monitor.
 */
public record RideLiveStatusDto(
        Long rideId,
        String riderName,
        String rideStatus,
        double currentPosition,
        boolean isMoving,
        Instant stationarySince,
        Long stationaryDurationSeconds,
        Double lastKnownLatitude,
        Double lastKnownLongitude,
        Instant lastLocationUpdatedAt,
        Double originLatitude,
        Double originLongitude,
        Double destinationLatitude,
        Double destinationLongitude,
        String originName,
        String destinationName,
        Double totalDistanceMeters,
        Double totalDurationSeconds,
        String routeGeometry,
        List<CheckpointStatusDto> checkpoints
) {
    public record CheckpointStatusDto(
            Long id,
            int sequenceNumber,
            String name,
            Double latitude,
            Double longitude,
            Double distanceFromOriginMeters,
            double positionOnRoute,
            Instant expectedTime,
            String status,
            Instant confirmationSentAt,
            Instant graceDeadline
    ) {
        public static CheckpointStatusDto from(Checkpoint cp) {
            return new CheckpointStatusDto(
                    cp.getId(),
                    cp.getSequenceNumber(),
                    cp.getName() != null ? cp.getName() : "Checkpoint " + cp.getSequenceNumber(),
                    cp.getLatitude(),
                    cp.getLongitude(),
                    cp.getDistanceFromOriginMeters(),
                    cp.getPositionOnRoute(),
                    cp.getExpectedTime(),
                    cp.getStatus() != null ? cp.getStatus().name() : "NOT_REACHED",
                    cp.getConfirmationSentAt(),
                    cp.getGraceDeadline()
            );
        }
    }

    public static RideLiveStatusDto from(Ride ride, List<Checkpoint> checkpoints) {
        String riderName = (ride.getRider() != null && ride.getRider().getName() != null)
                ? ride.getRider().getName()
                : "Rider";

        String originName = "Origin";
        if (ride.getOriginLatitude() != null && Math.abs(ride.getOriginLatitude() - 10.5276) < 0.05) {
            originName = "Thrissur";
        }
        String destName = "Destination";

        if (checkpoints != null && !checkpoints.isEmpty()) {
            destName = checkpoints.get(checkpoints.size() - 1).getName();
        }

        List<CheckpointStatusDto> cpDtos = (checkpoints != null)
                ? checkpoints.stream().map(CheckpointStatusDto::from).toList()
                : List.of();

        return new RideLiveStatusDto(
                ride.getId(),
                riderName,
                ride.getStatus() != null ? ride.getStatus().name() : "ACTIVE",
                ride.getCurrentPosition(),
                ride.isMoving(),
                ride.getStationarySince(),
                ride.getStationaryDurationSeconds(),
                ride.getLastKnownLatitude(),
                ride.getLastKnownLongitude(),
                ride.getLastLocationUpdatedAt(),
                ride.getOriginLatitude(),
                ride.getOriginLongitude(),
                ride.getDestinationLatitude(),
                ride.getDestinationLongitude(),
                originName,
                destName,
                ride.getTotalDistanceMeters(),
                ride.getTotalDurationSeconds(),
                ride.getRouteGeometry(),
                cpDtos
        );
    }
}
