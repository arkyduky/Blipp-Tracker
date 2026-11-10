package com.example.safetynet.service;

import com.example.safetynet.model.Checkpoint;
import com.example.safetynet.model.Ride;
import com.example.safetynet.repository.CheckpointRepository;
import com.example.safetynet.repository.RideRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class CheckpointScheduler {

    private final RideRepository rideRepository;
    private final CheckpointRepository checkpointRepository;
    private final AlertService alertService;

    @Autowired
    public CheckpointScheduler(RideRepository rideRepository, CheckpointRepository checkpointRepository,
                                AlertService alertService) {
        this.rideRepository = rideRepository;
        this.checkpointRepository = checkpointRepository;
        this.alertService = alertService;
    }

    // Job 1 — position-based checkpoint passing. Runs regardless of moving/stationary state.
    @Scheduled(fixedRate = 1000)
    public void checkPassedCheckpoints() {
        List<Ride> activeRides = rideRepository.findByStatus(Ride.RideStatus.ACTIVE);

        for (Ride ride : activeRides) {
            List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(ride);

            for (Checkpoint cp : checkpoints) {
                // If checkpoint has not yet been passed and rider's position has reached or exceeded it
                if (cp.getStatus() != Checkpoint.CheckpointStatus.PASSED 
                        && ride.getCurrentPosition() >= cp.getPositionOnRoute()) {
                    
                    cp.setStatus(Checkpoint.CheckpointStatus.PASSED);
                    cp.setConfirmationSentAt(null);
                    cp.setGraceDeadline(null);
                    checkpointRepository.save(cp);

                    alertService.sendPassiveUpdate(ride, cp);

                    // Ensure any preceding checkpoints are also cleared and marked PASSED
                    for (Checkpoint prev : checkpoints) {
                        if (prev.getSequenceNumber() < cp.getSequenceNumber() 
                                && prev.getStatus() != Checkpoint.CheckpointStatus.PASSED) {
                            prev.setStatus(Checkpoint.CheckpointStatus.PASSED);
                            prev.setConfirmationSentAt(null);
                            prev.setGraceDeadline(null);
                            checkpointRepository.save(prev);
                        }
                    }
                }
            }

            boolean allPassed = checkpointRepository.findByRideOrderBySequenceNumberAsc(ride).stream()
                    .allMatch(cp -> cp.getStatus() == Checkpoint.CheckpointStatus.PASSED);
            if (allPassed) {
                // Reaching the final checkpoint marks the route as completed (progress = 100%),
                // but does NOT terminate the active Live Location tracking session.
                // The real ride remains ACTIVE and continues accepting Telegram Live Location updates
                // until the rider explicitly sends END.
                if (ride.getCurrentPosition() < 100.0) {
                    ride.setCurrentPosition(100.0);
                    rideRepository.save(ride);
                }
            }
        }
    }
}
