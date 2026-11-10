package com.example.safetynet.service;

import com.example.safetynet.dto.RouteLeg;
import com.example.safetynet.dto.RouteResult;
import com.example.safetynet.dto.Waypoint;
import com.example.safetynet.model.Checkpoint;
import com.example.safetynet.model.Ride;
import com.example.safetynet.model.Rider;
import com.example.safetynet.repository.CheckpointRepository;
import com.example.safetynet.repository.RideRepository;
import com.example.safetynet.repository.RiderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service managing Ride and Checkpoint lifecycle, persisting real route geometry,
 * and generating checkpoints with realistic ETAs from OSRM route legs.
 */
@Service
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);


    private final RiderRepository riderRepository;
    private final RideRepository rideRepository;
    private final CheckpointRepository checkpointRepository;
    private final RouteService routeService;
    private final GoogleMapsRouteParser googleMapsRouteParser;

    @Autowired
    public RideService(RiderRepository riderRepository,
                       RideRepository rideRepository,
                       CheckpointRepository checkpointRepository,
                       RouteService routeService,
                       GoogleMapsRouteParser googleMapsRouteParser) {
        this.riderRepository = riderRepository;
        this.rideRepository = rideRepository;
        this.checkpointRepository = checkpointRepository;
        this.routeService = routeService;
        this.googleMapsRouteParser = googleMapsRouteParser != null ? googleMapsRouteParser : new GoogleMapsRouteParser();
    }

    public RideService(RiderRepository riderRepository,
                       RideRepository rideRepository,
                       CheckpointRepository checkpointRepository,
                       RouteService routeService) {
        this(riderRepository, rideRepository, checkpointRepository, routeService, new GoogleMapsRouteParser());
    }

    public RideService(RiderRepository riderRepository,
                       RideRepository rideRepository,
                       CheckpointRepository checkpointRepository) {
        this(riderRepository, rideRepository, checkpointRepository, new RouteService(), new GoogleMapsRouteParser());
    }

    /**
     * Creates a Ride using a Google Maps shared route link.
     * Parses ordered Waypoint objects and passes them to RouteService and Stage 2 persistence.
     */
    public Ride createRideForRiderWithRoute(Rider rider, String routeUrl) {
        if (routeUrl == null || routeUrl.isBlank()) {
            throw new IllegalArgumentException("Route URL is missing");
        }
        List<Waypoint> waypoints = googleMapsRouteParser.parseRoute(routeUrl);
        return createRideForRider(rider, waypoints);
    }

    /**
     * Registers and persists a rider. Updates existing rider if telegramChatId matches.
     */
    public Rider registerRider(Rider rider) {
        if (riderRepository == null) {
            return rider;
        }
        if (rider != null && rider.getTelegramChatId() != null && !rider.getTelegramChatId().isBlank()) {
            Rider existing = riderRepository.findAll().stream()
                    .filter(r -> rider.getTelegramChatId().trim().equals(r.getTelegramChatId()))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                existing.setName(rider.getName());
                existing.setEmergencyContactChatId(rider.getEmergencyContactChatId());
                existing.setStationaryToleranceSeconds(rider.getStationaryToleranceSeconds());
                existing.setGracePeriodSeconds(rider.getGracePeriodSeconds());
                return riderRepository.save(existing);
            }
        }
        return riderRepository.save(rider);
    }

    /**
     * Strictly creates a Ride from a Google Maps route URL without falling back to synthetic checkpoints.
     * Throws RouteParseException or IllegalStateException if parsing or routing fails,
     * ensuring no inconsistent ACTIVE Ride is persisted.
     */
    public Ride createRideForRiderWithStrictRoute(Rider rider, String routeUrl) {
        if (routeUrl == null || routeUrl.isBlank()) {
            throw new IllegalArgumentException("Route URL is missing");
        }
        List<Waypoint> waypoints = googleMapsRouteParser.parseRoute(routeUrl);
        return createRideForRiderStrict(rider, waypoints);
    }

    /**
     * Strictly creates a Ride for the rider using ordered waypoints without fallback.
     * If RouteService fails, throws an IllegalStateException and does not persist any Ride or Checkpoints.
     */
    public Ride createRideForRiderStrict(Rider rider, List<Waypoint> waypoints) {
        if (waypoints == null || waypoints.size() < 2) {
            throw new IllegalArgumentException("At least 2 waypoints are required");
        }

        if (routeService == null) {
            throw new IllegalStateException("RouteService is not available");
        }

        RouteResult routeResult = routeService.calculateRoute(waypoints);
        if (routeResult == null || routeResult.getGeometryGeoJson() == null) {
            throw new IllegalStateException("Failed to calculate route geometry from OSRM");
        }

        riderRepository.save(rider);

        Ride ride = new Ride(rider);
        ride.setDemo(false);
        ride.setStationarySince(null);
        ride.setStatus(Ride.RideStatus.ACTIVE);
        ride.setRoutePromptSent(true);

        Waypoint origin = waypoints.get(0);
        Waypoint destination = waypoints.get(waypoints.size() - 1);
        ride.setOriginLatitude(origin.getLatitude());
        ride.setOriginLongitude(origin.getLongitude());
        ride.setDestinationLatitude(destination.getLatitude());
        ride.setDestinationLongitude(destination.getLongitude());

        ride.setRouteGeometry(routeResult.getGeometryGeoJson());
        ride.setTotalDistanceMeters(routeResult.getTotalDistanceMeters());
        ride.setTotalDurationSeconds(routeResult.getTotalDurationSeconds());

        ride = rideRepository.save(ride);
        createRealCheckpoints(ride, waypoints, routeResult);

        return ride;
    }



    /**
     * Creates a Ride for the rider using ordered waypoints, querying OSRM for real
     * road geometry, distance, duration, and generating checkpoints from route legs.
     */
    public Ride createRideForRider(Rider rider, List<Waypoint> waypoints) {
        riderRepository.save(rider);

        Ride ride = new Ride(rider);
        ride.setStationarySince(null);

        if (waypoints == null || waypoints.size() < 2) {
            log.warn("Insufficient waypoints provided ({}), creating fallback checkpoints",
                    waypoints == null ? 0 : waypoints.size());
            rideRepository.save(ride);
            createFallbackCheckpoints(ride);
            return ride;
        }

        RouteResult routeResult = null;
        try {
            if (routeService != null) {
                routeResult = routeService.calculateRoute(waypoints);
            }
        } catch (Exception e) {
            log.warn("RouteService calculation failed, falling back to synthetic checkpoints: {}", e.getMessage());
        }

        Waypoint origin = waypoints.get(0);
        Waypoint destination = waypoints.get(waypoints.size() - 1);
        ride.setOriginLatitude(origin.getLatitude());
        ride.setOriginLongitude(origin.getLongitude());
        ride.setDestinationLatitude(destination.getLatitude());
        ride.setDestinationLongitude(destination.getLongitude());

        if (routeResult != null) {
            ride.setRouteGeometry(routeResult.getGeometryGeoJson());
            ride.setTotalDistanceMeters(routeResult.getTotalDistanceMeters());
            ride.setTotalDurationSeconds(routeResult.getTotalDurationSeconds());
            ride = rideRepository.save(ride);

            createRealCheckpoints(ride, waypoints, routeResult);
        } else {
            ride = rideRepository.save(ride);
            createFallbackCheckpoints(ride);
        }

        return ride;
    }

    /**
     * Creates checkpoints from OSRM route legs and waypoints.
     * Waypoint 0 is the route origin (stored on Ride).
     * Waypoint 1..N-1 become Checkpoints 1..N-1, where:
     * - OSRM Leg (i - 1) connects Waypoint (i - 1) to Waypoint i
     * - Cumulative distance is summed across legs 0..(i - 1)
     * - Cumulative duration is summed across legs 0..(i - 1)
     * - ETA = ride start + cumulative duration
     * - positionOnRoute = (cumulativeDistance / totalDistance) * 100
     * - The final destination checkpoint is explicitly set to 100.0%.
     */
    private void createRealCheckpoints(Ride ride, List<Waypoint> waypoints, RouteResult routeResult) {
        Instant start = Instant.now();
        List<RouteLeg> legs = routeResult.getLegs();
        double totalDistance = routeResult.getTotalDistanceMeters();
        double totalDuration = routeResult.getTotalDurationSeconds();

        double cumulativeDistance = 0.0;
        double cumulativeDuration = 0.0;

        for (int i = 1; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            int legIndex = i - 1;

            if (legIndex < legs.size()) {
                RouteLeg leg = legs.get(legIndex);
                cumulativeDistance += leg.getDistanceMeters();
                cumulativeDuration += leg.getDurationSeconds();
            }

            boolean isDestination = (i == waypoints.size() - 1);
            if (isDestination) {
                cumulativeDistance = totalDistance;
                cumulativeDuration = totalDuration;
            }

            double positionOnRoute;
            if (isDestination) {
                positionOnRoute = 100.0;
            } else if (totalDistance > 0) {
                positionOnRoute = Math.min(100.0, (cumulativeDistance / totalDistance) * 100.0);
            } else {
                positionOnRoute = (100.0 / (waypoints.size() - 1)) * i;
            }

            Instant expectedTime = start.plusSeconds((long) Math.round(cumulativeDuration));

            String name = (wp.getName() != null && !wp.getName().isBlank())
                    ? wp.getName()
                    : "Checkpoint " + i;

            Checkpoint checkpoint = new Checkpoint(
                    ride,
                    i,
                    name,
                    wp.getLatitude(),
                    wp.getLongitude(),
                    cumulativeDistance,
                    positionOnRoute,
                    expectedTime
            );
            checkpoint.setStatus(Checkpoint.CheckpointStatus.NOT_REACHED);
            checkpointRepository.save(checkpoint);
        }
    }

    private void createFallbackCheckpoints(Ride ride) {
        Instant start = Instant.now();
        int checkpointCount = 3;
        int totalMinutes = 30;
        for (int i = 1; i <= checkpointCount; i++) {
            double position = (100.0 / checkpointCount) * i;
            Instant expectedTime = start.plusSeconds((long) (totalMinutes * 60.0 / checkpointCount * i));
            Checkpoint checkpoint = new Checkpoint(ride, i, position, expectedTime);
            checkpoint.setName("Checkpoint " + i);
            checkpointRepository.save(checkpoint);
        }
    }


    /**
     * Clears and deletes active real (non-demo) rides and their checkpoints for the given rider.
     * Demo A rides (isDemo == true) are strictly protected and never deleted.
     * Returns true if at least one ride was cleared, false otherwise.
     */
    @Transactional
    public boolean endAndClearRealRidesForRider(String telegramChatId) {
        if (telegramChatId == null || telegramChatId.isBlank() || rideRepository == null) {
            return false;
        }
        List<Ride> realRides = rideRepository.findAll().stream()
                .filter(r -> !r.isDemo())
                .filter(r -> r.getRider() != null && telegramChatId.equals(r.getRider().getTelegramChatId()))
                .toList();

        if (realRides.isEmpty()) {
            return false;
        }

        for (Ride ride : realRides) {
            if (checkpointRepository != null) {
                List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(ride);
                if (!checkpoints.isEmpty()) {
                    checkpointRepository.deleteAll(checkpoints);
                }
            }
            rideRepository.delete(ride);
            log.info("Cleared real ride id={} and associated checkpoints for telegramChatId={}",
                    ride.getId(), telegramChatId);
        }
        return true;
    }
}
