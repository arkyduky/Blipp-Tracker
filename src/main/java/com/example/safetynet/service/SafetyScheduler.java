package com.example.safetynet.service;

import com.example.safetynet.model.Checkpoint;
import com.example.safetynet.model.Ride;
import com.example.safetynet.repository.CheckpointRepository;
import com.example.safetynet.repository.RideRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class SafetyScheduler {

    private final RideRepository rideRepository;
    private final CheckpointRepository checkpointRepository;
    private final AlertService alertService;

    @Autowired
    public SafetyScheduler(RideRepository rideRepository, CheckpointRepository checkpointRepository,
                            AlertService alertService) {
        this.rideRepository = rideRepository;
        this.checkpointRepository = checkpointRepository;
        this.alertService = alertService;
    }

    // Job 2 — motion not registered past the rider's configured stationary tolerance.
    // Moving riders are never touched here.
    @Scheduled(fixedRate = 1000)
    public void checkOverdueStationaryCheckpoints() {
        List<Ride> stationaryRides = rideRepository.findByStatusAndIsMovingFalse(Ride.RideStatus.ACTIVE);

        for (Ride ride : stationaryRides) {
            if (ride.getStationarySince() == null) continue;

            // Prevent triggering another alert if one is already pending or escalated
            boolean hasPendingAlert = checkpointRepository.findByRideOrderBySequenceNumberAsc(ride).stream()
                    .anyMatch(cp -> cp.getStatus() == Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION
                                 || cp.getStatus() == Checkpoint.CheckpointStatus.ESCALATED);
            if (hasPendingAlert) continue;

            Optional<Checkpoint> nextOpt = checkpointRepository
                    .findFirstByRideAndStatusOrderBySequenceNumberAsc(ride, Checkpoint.CheckpointStatus.NOT_REACHED);
            if (nextOpt.isEmpty()) continue;

            Checkpoint next = nextOpt.get();
            long stationarySeconds = Duration.between(ride.getStationarySince(), Instant.now()).getSeconds();
            long toleranceForThisRider = ride.getRider().getStationaryToleranceSeconds();

            if (stationarySeconds >= toleranceForThisRider) {
                alertService.sendConfirmationPrompt(ride, next);
                next.setStatus(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION);
                next.setConfirmationSentAt(Instant.now());
                next.setGraceDeadline(Instant.now().plusSeconds(ride.getRider().getGracePeriodSeconds()));
                checkpointRepository.save(next);
            }
        }
    }

    // Job 3 — poll for the rider's reply (Telegram getUpdates, no webhook needed).
    // Also handles the case where movement resumes before any reply arrives —
    // movement always overrides a pending prompt, silently, no message sent either way.
    @Scheduled(fixedRate = 3000)
    public void pollForReplies() {
        List<Checkpoint> awaiting = checkpointRepository
                .findByStatus(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION);

        for (Checkpoint cp : awaiting) {
            Ride ride = cp.getRide();

            if (ride != null && ride.isMoving()) {
                // Rider resumed moving before replying — cancel the prompt, no escalation possible now.
                cp.setStatus(Checkpoint.CheckpointStatus.NOT_REACHED);
                cp.setConfirmationSentAt(null);
                cp.setGraceDeadline(null);
                checkpointRepository.save(cp);
                continue;
            }

            String reply = alertService.fetchReply(cp);
            if (reply == null) continue;

            cp.setStatus(Checkpoint.CheckpointStatus.NOT_REACHED); // resolved, still waiting to physically pass it
            cp.setConfirmationSentAt(null);
            cp.setGraceDeadline(null);
            checkpointRepository.save(cp);

            if (ride != null) {
                ride.setStationarySince(null); // clock resets — resuming counts as a fresh stop if it happens again
                rideRepository.save(ride);
            }

            alertService.sendResolvedUpdate(ride, cp, reply);
        }
    }

    // Job 4 — grace window expired with no reply. This is the only path that escalates.
    @Scheduled(fixedRate = 1000)
    public void escalateOverdueCheckpoints() {
        List<Checkpoint> overdue = checkpointRepository.findByStatusAndGraceDeadlineBefore(
                Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION, Instant.now());

        for (Checkpoint cp : overdue) {
            alertService.sendEmergencyAlert(cp.getRide(), cp);
            cp.setStatus(Checkpoint.CheckpointStatus.ESCALATED);
            checkpointRepository.save(cp);
        }
    }
}

