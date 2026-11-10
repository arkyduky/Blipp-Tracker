package com.example.safetynet.controller;

import com.example.safetynet.dto.RideLiveStatusDto;
import com.example.safetynet.model.Checkpoint;
import com.example.safetynet.model.Ride;
import com.example.safetynet.model.Rider;
import com.example.safetynet.repository.CheckpointRepository;
import com.example.safetynet.repository.RideRepository;
import com.example.safetynet.repository.RiderRepository;
import com.example.safetynet.service.AlertService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ride")
public class RideController {

    private final RideRepository rideRepository;
    private final CheckpointRepository checkpointRepository;
    private final RiderRepository riderRepository;
    private AlertService alertService;

    public RideController(RideRepository rideRepository, CheckpointRepository checkpointRepository, RiderRepository riderRepository) {
        this(rideRepository, checkpointRepository, riderRepository, null);
    }

    @Autowired
    public RideController(RideRepository rideRepository,
                          CheckpointRepository checkpointRepository,
                          RiderRepository riderRepository,
                          @Autowired(required = false) AlertService alertService) {
        this.rideRepository = rideRepository;
        this.checkpointRepository = checkpointRepository;
        this.riderRepository = riderRepository;
        this.alertService = alertService;
    }

    public void setAlertService(AlertService alertService) {
        this.alertService = alertService;
    }

    public record ConnectionsUpdateRequest(String telegramChatId, String emergencyContactChatId) {}

    // Updates Rider Telegram and Emergency Contact Telegram chat IDs
    @PostMapping("/{id}/connections")
    public ResponseEntity<?> updateConnections(@PathVariable Long id, @RequestBody ConnectionsUpdateRequest request) {
        Ride ride = rideRepository.findById(id).orElseThrow();
        Rider rider = ride.getRider();
        if (rider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Rider not found"));
        }

        if (request.telegramChatId() != null && !request.telegramChatId().isBlank()) {
            rider.setTelegramChatId(request.telegramChatId().trim());
        }
        if (request.emergencyContactChatId() != null && !request.emergencyContactChatId().isBlank()) {
            rider.setEmergencyContactChatId(request.emergencyContactChatId().trim());
        }

        riderRepository.save(rider);
        if (alertService != null) {
            alertService.maybeSendRouteSubmissionPrompt(ride);
        }
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "telegramChatId", rider.getTelegramChatId() != null ? rider.getTelegramChatId() : "",
                "emergencyContactChatId", rider.getEmergencyContactChatId() != null ? rider.getEmergencyContactChatId() : ""
        ));
    }

    public record RideLocationDto(Double lastKnownLatitude, Double lastKnownLongitude, Instant lastLocationUpdatedAt) {}

    // Live Monitor — live location ingestion endpoint
    @GetMapping("/{id}/location")
    public RideLocationDto getLocation(@PathVariable Long id) {
        Ride ride = rideRepository.findById(id).orElseThrow();
        return new RideLocationDto(
                ride.getLastKnownLatitude(),
                ride.getLastKnownLongitude(),
                ride.getLastLocationUpdatedAt()
        );
    }

    // Live Monitor — read-only comprehensive live monitor telemetry endpoint
    @GetMapping("/{id}/live-status")
    public RideLiveStatusDto getLiveStatus(@PathVariable Long id) {
        Ride ride = rideRepository.findById(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Ride not found"));
        if (alertService != null) {
            alertService.maybeSendRouteSubmissionPrompt(ride);
        }
        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(ride);
        return RideLiveStatusDto.from(ride, checkpoints);
    }

    public record ActiveRideDiscoveryDto(boolean active, Long rideId, String riderName) {}

    // Live Monitor — dedicated discovery endpoint for zero-state monitor to detect newly created real rides
    @GetMapping({"/active", "/active-ride"})
    public ResponseEntity<ActiveRideDiscoveryDto> getActiveRealRide(@RequestParam(required = false) String telegramChatId) {
        List<Ride> activeRides = (rideRepository != null)
                ? rideRepository.findByStatus(Ride.RideStatus.ACTIVE)
                : Collections.emptyList();

        Ride activeRealRide = activeRides.stream()
                .filter(r -> !AlertService.isDemoRide(r))
                .filter(r -> telegramChatId == null || telegramChatId.isBlank()
                        || (r.getRider() != null && telegramChatId.equals(r.getRider().getTelegramChatId())))
                .max(Comparator.comparing(Ride::getId))
                .orElse(null);

        if (activeRealRide == null) {
            return ResponseEntity.ok(new ActiveRideDiscoveryDto(false, null, null));
        }

        if (alertService != null) {
            alertService.maybeSendRouteSubmissionPrompt(activeRealRide);
        }

        return ResponseEntity.ok(new ActiveRideDiscoveryDto(
                true,
                activeRealRide.getId(),
                activeRealRide.getRider() != null ? activeRealRide.getRider().getName() : "Rider"
        ));
    }
}
