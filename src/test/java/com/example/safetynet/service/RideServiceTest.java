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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class RideServiceTest {

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    private StubRouteService stubRouteService;
    private RideService rideService;

    private static class StubRouteService extends RouteService {
        private RouteResult stubResult;
        private boolean shouldThrow = false;

        public void setStubResult(RouteResult result) {
            this.stubResult = result;
            this.shouldThrow = false;
        }

        public void setShouldThrow(boolean shouldThrow) {
            this.shouldThrow = shouldThrow;
        }

        @Override
        public RouteResult calculateRoute(List<Waypoint> waypoints) {
            if (shouldThrow) {
                throw new IllegalStateException("Simulated OSRM service failure");
            }
            if (stubResult != null) {
                return stubResult;
            }
            return super.calculateRoute(waypoints);
        }
    }

    private RouteResult createSampleRouteResult() {
        String geoJson = """
                {"type":"LineString","coordinates":[[76.2144,10.5276],[76.0409,10.6517],[76.3753,10.1849],[77.0595,10.0889]]}
                """;

        List<List<Double>> coords = List.of(
                List.of(76.2144, 10.5276),
                List.of(76.0409, 10.6517),
                List.of(76.3753, 10.1849),
                List.of(77.0595, 10.0889)
        );

        List<RouteLeg> legs = List.of(
                new RouteLeg(25000.0, 1800.0, 0, 1), // Leg 0: 25 km, 30 min (Thrissur -> Kunnamkulam)
                new RouteLeg(35000.0, 2400.0, 1, 2), // Leg 1: 35 km, 40 min (Kunnamkulam -> Angamaly)
                new RouteLeg(40000.0, 3000.0, 2, 3)  // Leg 2: 40 km, 50 min (Angamaly -> Munnar)
        );

        return new RouteResult(geoJson, coords, 100000.0, 7200.0, legs);
    }

    private List<Waypoint> createSampleWaypoints() {
        return List.of(
                new Waypoint("Thrissur", 10.5276, 76.2144, 1),
                new Waypoint("Kunnamkulam", 10.6517, 76.0409, 2),
                new Waypoint("Angamaly", 10.1849, 76.3753, 3),
                new Waypoint("Munnar", 10.0889, 77.0595, 4)
        );
    }

    private Rider createSampleRider() {
        Rider rider = new Rider();
        rider.setName("Amit");
        rider.setTelegramChatId("12345678");
        rider.setEmergencyContactChatId("87654321");
        rider.setStationaryToleranceSeconds(25);
        rider.setGracePeriodSeconds(40);
        return rider;
    }

    @BeforeEach
    void setUp() {
        stubRouteService = new StubRouteService();
        stubRouteService.setStubResult(createSampleRouteResult());
        rideService = new RideService(riderRepository, rideRepository, checkpointRepository, stubRouteService);
    }

    @Test
    @DisplayName("1. Ride route metadata persistence")
    void testRideRouteMetadataPersistence() {
        Rider rider = createSampleRider();
        List<Waypoint> waypoints = createSampleWaypoints();

        Ride created = rideService.createRideForRider(rider, waypoints);

        Ride persisted = rideRepository.findById(created.getId()).orElseThrow();
        assertNotNull(persisted.getRouteGeometry(), "Route geometry must be persisted");
        assertTrue(persisted.getRouteGeometry().contains("LineString"));
        assertEquals(100000.0, persisted.getTotalDistanceMeters(), 0.001);
        assertEquals(7200.0, persisted.getTotalDurationSeconds(), 0.001);
        assertEquals(10.5276, persisted.getOriginLatitude(), 0.0001);
        assertEquals(76.2144, persisted.getOriginLongitude(), 0.0001);
        assertEquals(10.0889, persisted.getDestinationLatitude(), 0.0001);
        assertEquals(77.0595, persisted.getDestinationLongitude(), 0.0001);
    }

    @Test
    @DisplayName("2. Checkpoint waypoint metadata persistence")
    void testCheckpointWaypointMetadataPersistence() {
        Rider rider = createSampleRider();
        List<Waypoint> waypoints = createSampleWaypoints();

        Ride created = rideService.createRideForRider(rider, waypoints);

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(created);
        assertFalse(checkpoints.isEmpty());

        Checkpoint cp1 = checkpoints.get(0);
        assertEquals("Kunnamkulam", cp1.getName());
        assertEquals(10.6517, cp1.getLatitude(), 0.0001);
        assertEquals(76.0409, cp1.getLongitude(), 0.0001);
        assertEquals(25000.0, cp1.getDistanceFromOriginMeters(), 0.001);
        assertNotNull(cp1.getExpectedTime());
        assertEquals(1, cp1.getSequenceNumber());
    }

    @Test
    @DisplayName("3. Real checkpoint count follows supplied waypoints (N waypoints -> N-1 checkpoints)")
    void testRealCheckpointCountFollowsSuppliedWaypoints() {
        Rider rider = createSampleRider();
        List<Waypoint> waypoints = createSampleWaypoints(); // 4 waypoints: 1 origin + 3 milestone checkpoints

        Ride created = rideService.createRideForRider(rider, waypoints);

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(created);
        assertEquals(3, checkpoints.size(), "4 route waypoints (origin + 3 milestones) must yield exactly 3 checkpoints");
    }

    @Test
    @DisplayName("4. Checkpoint order follows waypoint order")
    void testCheckpointOrderFollowsWaypointOrder() {
        Rider rider = createSampleRider();
        List<Waypoint> waypoints = createSampleWaypoints();

        Ride created = rideService.createRideForRider(rider, waypoints);

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(created);
        assertEquals(1, checkpoints.get(0).getSequenceNumber());
        assertEquals("Kunnamkulam", checkpoints.get(0).getName());

        assertEquals(2, checkpoints.get(1).getSequenceNumber());
        assertEquals("Angamaly", checkpoints.get(1).getName());

        assertEquals(3, checkpoints.get(2).getSequenceNumber());
        assertEquals("Munnar", checkpoints.get(2).getName());
    }

    @Test
    @DisplayName("5. Cumulative checkpoint distance is correct")
    void testCumulativeCheckpointDistanceIsCorrect() {
        Rider rider = createSampleRider();
        List<Waypoint> waypoints = createSampleWaypoints();

        Ride created = rideService.createRideForRider(rider, waypoints);

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(created);
        // Leg 0: 25000m
        assertEquals(25000.0, checkpoints.get(0).getDistanceFromOriginMeters(), 0.001);

        // Leg 0 + Leg 1: 25000m + 35000m = 60000m
        assertEquals(60000.0, checkpoints.get(1).getDistanceFromOriginMeters(), 0.001);

        // Total: 25000m + 35000m + 40000m = 100000m
        assertEquals(100000.0, checkpoints.get(2).getDistanceFromOriginMeters(), 0.001);
    }

    @Test
    @DisplayName("6. Checkpoint positionOnRoute is correct")
    void testCheckpointPositionOnRouteIsCorrect() {
        Rider rider = createSampleRider();
        List<Waypoint> waypoints = createSampleWaypoints();

        Ride created = rideService.createRideForRider(rider, waypoints);

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(created);
        // Checkpoint 1: 25000 / 100000 * 100 = 25.0%
        assertEquals(25.0, checkpoints.get(0).getPositionOnRoute(), 0.001);

        // Checkpoint 2: 60000 / 100000 * 100 = 60.0%
        assertEquals(60.0, checkpoints.get(1).getPositionOnRoute(), 0.001);

        // Checkpoint 3 (Destination): 100000 / 100000 * 100 = 100.0%
        assertEquals(100.0, checkpoints.get(2).getPositionOnRoute(), 0.001);
    }

    @Test
    @DisplayName("7. Expected checkpoint times use cumulative OSRM leg duration")
    void testExpectedCheckpointTimesUseCumulativeDuration() {
        Rider rider = createSampleRider();
        List<Waypoint> waypoints = createSampleWaypoints();
        Instant beforeCreation = Instant.now().minusSeconds(1);

        Ride created = rideService.createRideForRider(rider, waypoints);
        Instant afterCreation = Instant.now().plusSeconds(1);

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(created);

        // Leg 0 duration = 1800s (30m)
        long diffSeconds1 = Duration.between(beforeCreation, checkpoints.get(0).getExpectedTime()).getSeconds();
        assertTrue(diffSeconds1 >= 1800 && diffSeconds1 <= 1805, "Checkpoint 1 ETA should be ~1800s from creation");

        // Leg 0 + Leg 1 duration = 1800s + 2400s = 4200s (70m)
        long diffSeconds2 = Duration.between(beforeCreation, checkpoints.get(1).getExpectedTime()).getSeconds();
        assertTrue(diffSeconds2 >= 4200 && diffSeconds2 <= 4205, "Checkpoint 2 ETA should be ~4200s from creation");

        // Total duration = 7200s (120m)
        long diffSeconds3 = Duration.between(beforeCreation, checkpoints.get(2).getExpectedTime()).getSeconds();
        assertTrue(diffSeconds3 >= 7200 && diffSeconds3 <= 7205, "Destination ETA should be ~7200s from creation");
    }

    @Test
    @DisplayName("8. Final checkpoint is at 100%")
    void testFinalCheckpointIsAt100Percent() {
        Rider rider = createSampleRider();
        List<Waypoint> waypoints = createSampleWaypoints();

        Ride created = rideService.createRideForRider(rider, waypoints);

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(created);
        Checkpoint finalCheckpoint = checkpoints.get(checkpoints.size() - 1);
        assertEquals(100.0, finalCheckpoint.getPositionOnRoute(), "Final checkpoint must be at exactly 100%");
    }

    @Test
    @DisplayName("9. Existing checkpoint status defaults remain intact (NOT_REACHED)")
    void testExistingCheckpointStatusDefaultsRemainIntact() {
        Rider rider = createSampleRider();
        List<Waypoint> waypoints = createSampleWaypoints();

        Ride created = rideService.createRideForRider(rider, waypoints);

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(created);
        for (Checkpoint cp : checkpoints) {
            assertEquals(Checkpoint.CheckpointStatus.NOT_REACHED, cp.getStatus());
            assertNull(cp.getConfirmationSentAt());
            assertNull(cp.getGraceDeadline());
        }
    }

    @Test
    @DisplayName("10. Real ride lifecycle: endAndClearRealRidesForRider clears ride and checkpoints")
    void testEndAndClearRealRidesForRider() {
        Rider rider = createSampleRider();
        List<Waypoint> waypoints = createSampleWaypoints();

        Ride created = rideService.createRideForRiderStrict(rider, waypoints);
        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(created);
        assertEquals(3, checkpoints.size());

        boolean cleared = rideService.endAndClearRealRidesForRider(rider.getTelegramChatId());
        assertTrue(cleared, "Real ride should be cleared");
        assertTrue(rideRepository.findById(created.getId()).isEmpty());
        assertTrue(checkpointRepository.findByRideOrderBySequenceNumberAsc(created).isEmpty());
    }

    @Test
    @DisplayName("Backward compatibility fallback when RouteService fails")
    void testFallbackWhenRouteServiceFails() {
        stubRouteService.setShouldThrow(true);
        Rider rider = createSampleRider();
        List<Waypoint> waypoints = createSampleWaypoints();

        Ride created = rideService.createRideForRider(rider, waypoints);

        assertNotNull(created);
        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(created);
        assertEquals(3, checkpoints.size(), "Fallback should generate 3 checkpoints");
        assertEquals(100.0, checkpoints.get(2).getPositionOnRoute());
    }

    @Test
    @DisplayName("createRideForRiderWithRoute parses Google Maps link and creates ride with checkpoints")
    void testCreateRideForRiderWithRoute_integratesWithGoogleMapsParser() {
        Rider rider = createSampleRider();
        String googleMapsUrl = "https://www.google.com/maps/dir/10.5276,76.2144/10.6517,76.0409/10.0889,77.0595";

        Ride created = rideService.createRideForRiderWithRoute(rider, googleMapsUrl);

        assertNotNull(created);
        assertNotNull(created.getId());
        assertEquals(10.5276, created.getOriginLatitude(), 0.0001);
        assertEquals(76.2144, created.getOriginLongitude(), 0.0001);
        assertEquals(10.0889, created.getDestinationLatitude(), 0.0001);
        assertEquals(77.0595, created.getDestinationLongitude(), 0.0001);

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(created);
        assertEquals(2, checkpoints.size(), "2 checkpoints (Stop 1 and Destination) should be created");
        assertEquals("Stop 1", checkpoints.get(0).getName());
        assertEquals(10.6517, checkpoints.get(0).getLatitude(), 0.0001);
        assertEquals("Destination", checkpoints.get(1).getName());
        assertEquals(10.0889, checkpoints.get(1).getLatitude(), 0.0001);
    }
}
