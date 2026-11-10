package com.example.safetynet.repository;

import com.example.safetynet.model.Ride;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RideRepository extends JpaRepository<Ride, Long> {
    List<Ride> findByStatus(Ride.RideStatus status);
    List<Ride> findByStatusAndIsMovingFalse(Ride.RideStatus status);
}
