package com.example.safetynet.repository;

import com.example.safetynet.model.Checkpoint;
import com.example.safetynet.model.Ride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CheckpointRepository extends JpaRepository<Checkpoint, Long> {

    Optional<Checkpoint> findFirstByRideAndStatusOrderBySequenceNumberAsc(
            Ride ride, Checkpoint.CheckpointStatus status);

    List<Checkpoint> findByStatus(Checkpoint.CheckpointStatus status);

    List<Checkpoint> findByStatusAndGraceDeadlineBefore(
            Checkpoint.CheckpointStatus status, Instant time);

    List<Checkpoint> findByRideOrderBySequenceNumberAsc(Ride ride);
}
