package com.example.safetynet.service;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DataJpaTest
@DisplayName("AlertService Telegram Location Integration Tests")
class AlertServiceTelegramLocationTest {

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    private AlertService alertService;
    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;

    private Rider testRider;
    private Ride testRide;

    private final String sampleRouteGeometry = "{\"type\":\"LineString\",\"coordinates\":[[76.0,10.0],[76.1,10.0]]}";

    @BeforeEach
    void setUp() {
        testRider = new Rider();
        testRider.setName("Amal");
        testRider.setTelegramChatId("12345678");
        testRider.setEmergencyContactChatId("87654321");
        testRider = riderRepository.save(testRider);

        testRide = new Ride(testRider);
        testRide.setStatus(Ride.RideStatus.ACTIVE);
        testRide.setCurrentPosition(0.0);
        testRide.setRouteGeometry(sampleRouteGeometry);
        testRide.setTotalDistanceMeters(10940.0);
        testRide = rideRepository.save(testRide);

        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        GeoProjectionService geoProjectionService = new GeoProjectionService();
        alertService = new AlertService(rideRepository, geoProjectionService);
        alertService.setBotToken("mock-bot-token");
        alertService.setRestTemplate(restTemplate);
    }

    private String buildTelegramLocationUpdateJson(long updateId, String chatId, double lat, double lon, long epochSecond) {
        return "{\n" +
                "  \"ok\": true,\n" +
                "  \"result\": [\n" +
                "    {\n" +
                "      \"update_id\": " + updateId + ",\n" +
                "      \"message\": {\n" +
                "        \"message_id\": 101,\n" +
                "        \"date\": " + epochSecond + ",\n" +
                "        \"chat\": { \"id\": " + chatId + " },\n" +
                "        \"location\": { \"latitude\": " + lat + ", \"longitude\": " + lon + " }\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    private String buildTelegramEditedLocationUpdateJson(long updateId, String chatId, double lat, double lon, long editEpochSecond) {
        return "{\n" +
                "  \"ok\": true,\n" +
                "  \"result\": [\n" +
                "    {\n" +
                "      \"update_id\": " + updateId + ",\n" +
                "      \"edited_message\": {\n" +
                "        \"message_id\": 102,\n" +
                "        \"date\": " + (editEpochSecond - 100) + ",\n" +
                "        \"edit_date\": " + editEpochSecond + ",\n" +
                "        \"chat\": { \"id\": " + chatId + " },\n" +
                "        \"location\": { \"latitude\": " + lat + ", \"longitude\": " + lon + " }\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    @Test
    @DisplayName("Valid location update updates lastKnownLatitude/Longitude and projected currentPosition")
    void testValidLocationUpdatesCoordinatesAndProgress() {
        long nowEpoch = Instant.now().getEpochSecond();
        // Midpoint of the route is at (10.0, 76.05)
        String telegramResponse = buildTelegramLocationUpdateJson(1, "12345678", 10.0, 76.05, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertEquals(10.0, updatedRide.getLastKnownLatitude(), 1e-4);
        assertEquals(76.05, updatedRide.getLastKnownLongitude(), 1e-4);
        assertNotNull(updatedRide.getLastLocationUpdatedAt());
        assertEquals(50.0, updatedRide.getCurrentPosition(), 1.0);
    }

    @Test
    @DisplayName("Edited message (live location update) correctly updates coordinates and progress")
    void testEditedLiveLocationUpdate() {
        long nowEpoch = Instant.now().getEpochSecond();
        // 75% along the route: (10.0, 76.075)
        String telegramResponse = buildTelegramEditedLocationUpdateJson(2, "12345678", 10.0, 76.075, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertEquals(10.0, updatedRide.getLastKnownLatitude(), 1e-4);
        assertEquals(76.075, updatedRide.getLastKnownLongitude(), 1e-4);
        assertEquals(75.0, updatedRide.getCurrentPosition(), 1.0);
    }

    @Test
    @DisplayName("Invalid geometry preserves previous currentPosition while storing coordinates")
    void testInvalidGeometryPreservesPreviousPosition() {
        testRide.setCurrentPosition(42.5);
        testRide.setRouteGeometry("invalid-json-geometry");
        rideRepository.save(testRide);

        long nowEpoch = Instant.now().getEpochSecond();
        String telegramResponse = buildTelegramLocationUpdateJson(3, "12345678", 10.0, 76.05, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertEquals(10.0, updatedRide.getLastKnownLatitude(), 1e-4);
        assertEquals(76.05, updatedRide.getLastKnownLongitude(), 1e-4);
        // Position must remain unchanged at 42.5%
        assertEquals(42.5, updatedRide.getCurrentPosition(), 1e-4);
    }

    @Test
    @DisplayName("Stale location does not overwrite newer location or progress")
    void testStaleLocationUpdateIgnored() {
        // Initial newer location:
        Instant futureTime = Instant.now().plusSeconds(300);
        testRide.setLastKnownLatitude(10.0);
        testRide.setLastKnownLongitude(76.09);
        testRide.setLastLocationUpdatedAt(futureTime);
        testRide.setCurrentPosition(90.0);
        rideRepository.save(testRide);

        // Incoming update has past timestamp (5 minutes before now)
        long staleEpoch = futureTime.minusSeconds(600).getEpochSecond();
        String telegramResponse = buildTelegramLocationUpdateJson(4, "12345678", 10.0, 76.01, staleEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        // Location and position must NOT have been updated with stale values
        assertEquals(10.0, updatedRide.getLastKnownLatitude(), 1e-4);
        assertEquals(76.09, updatedRide.getLastKnownLongitude(), 1e-4);
        assertEquals(futureTime, updatedRide.getLastLocationUpdatedAt());
        assertEquals(90.0, updatedRide.getCurrentPosition(), 1e-4);
    }

    @Test
    @DisplayName("Real Kerala route: Thrissur -> Kunnamkulam -> Angamaly -> Munnar projection flow")
    void testKeralaRouteProjectionFlow() {
        String keralaRouteGeometry = "{\"type\":\"LineString\",\"coordinates\":[[76.2144,10.5276],[76.0409,10.6517],[76.3753,10.1849],[77.0595,10.0889]]}";
        testRide.setRouteGeometry(keralaRouteGeometry);
        testRide.setTotalDistanceMeters(180000.0);
        rideRepository.save(testRide);

        long nowEpoch = Instant.now().getEpochSecond();

        // 1. Rider at Kunnamkulam (first stop)
        String cp1Response = buildTelegramLocationUpdateJson(10, "12345678", 10.6517, 76.0409, nowEpoch + 10);
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(cp1Response, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride rideAfterCp1 = rideRepository.findById(testRide.getId()).orElseThrow();
        assertEquals(10.6517, rideAfterCp1.getLastKnownLatitude(), 1e-4);
        assertEquals(76.0409, rideAfterCp1.getLastKnownLongitude(), 1e-4);
        assertTrue(rideAfterCp1.getCurrentPosition() > 10.0 && rideAfterCp1.getCurrentPosition() < 25.0,
                "Position after reaching Kunnamkulam should be ~15-20%");

        // 2. Rider arrives at Munnar (destination)
        mockServer.reset();
        String destResponse = buildTelegramLocationUpdateJson(11, "12345678", 10.0889, 77.0595, nowEpoch + 20);
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(destResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride rideAtDest = rideRepository.findById(testRide.getId()).orElseThrow();
        assertEquals(10.0889, rideAtDest.getLastKnownLatitude(), 1e-4);
        assertEquals(77.0595, rideAtDest.getLastKnownLongitude(), 1e-4);
        assertEquals(100.0, rideAtDest.getCurrentPosition(), 1.0,
                "Position at Munnar destination should reach ~100%");
    }

    // =========================================================================
    // Stage 7: GPS Movement & Stationary State Tests
    // =========================================================================

    @Test
    @DisplayName("Stage 7.1 — First GPS update: isMoving = true, stationarySince = null")
    void testFirstGpsUpdateSetsMovingTrue() {
        assertNull(testRide.getLastKnownLatitude());
        assertFalse(testRide.isMoving());
        assertNull(testRide.getStationarySince());

        long nowEpoch = Instant.now().getEpochSecond();
        String telegramResponse = buildTelegramLocationUpdateJson(20, "12345678", 10.0, 76.0, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertTrue(updatedRide.isMoving(), "First GPS update must set isMoving = true");
        assertNull(updatedRide.getStationarySince(), "First GPS update must have stationarySince = null");
        assertEquals(10.0, updatedRide.getLastKnownLatitude(), 1e-4);
        assertEquals(76.0, updatedRide.getLastKnownLongitude(), 1e-4);
    }

    @Test
    @DisplayName("Stage 7.2 — Meaningful movement beyond threshold: isMoving = true, stationarySince = null")
    void testMeaningfulMovementBeyondThreshold() {
        // Set previous point
        testRide.setLastKnownLatitude(10.0);
        testRide.setLastKnownLongitude(76.0);
        testRide.setLastLocationUpdatedAt(Instant.now().minusSeconds(10));
        testRide.setMoving(false);
        testRide.setStationarySince(Instant.now().minusSeconds(10));
        rideRepository.save(testRide);

        // Move ~111 meters north (0.001 deg lat, well beyond 10m threshold)
        long nowEpoch = Instant.now().getEpochSecond();
        String telegramResponse = buildTelegramLocationUpdateJson(21, "12345678", 10.001, 76.0, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertTrue(updatedRide.isMoving(), "Movement >= threshold must set isMoving = true");
        assertNull(updatedRide.getStationarySince(), "Movement >= threshold must clear stationarySince");
        assertEquals(10.001, updatedRide.getLastKnownLatitude(), 1e-4);
    }

    @Test
    @DisplayName("Stage 7.3 — Rider becomes stationary below threshold: isMoving = false, stationarySince set")
    void testRiderBecomesStationaryBelowThreshold() {
        // Initially moving
        testRide.setLastKnownLatitude(10.0);
        testRide.setLastKnownLongitude(76.0);
        testRide.setLastLocationUpdatedAt(Instant.now().minusSeconds(10));
        testRide.setMoving(true);
        testRide.setStationarySince(null);
        rideRepository.save(testRide);

        // Jitter update ~2.2 meters away (0.00002 deg lat < 10m threshold)
        long nowEpoch = Instant.now().getEpochSecond();
        Instant expectedStop = Instant.ofEpochSecond(nowEpoch);
        String telegramResponse = buildTelegramLocationUpdateJson(22, "12345678", 10.00002, 76.0, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertFalse(updatedRide.isMoving(), "Movement < threshold must set isMoving = false");
        assertEquals(expectedStop, updatedRide.getStationarySince(), "stationarySince must record the stop timestamp");
    }

    @Test
    @DisplayName("Stage 7.4 & 7.9 — Continued stationary GPS jitter: stationarySince is preserved and not reset")
    void testContinuedStationaryGpsJitterPreservesStationarySince() {
        // Rider became stationary at T0
        long t0Epoch = Instant.now().getEpochSecond() - 30;
        Instant t0Time = Instant.ofEpochSecond(t0Epoch);
        testRide.setLastKnownLatitude(10.0);
        testRide.setLastKnownLongitude(76.0);
        testRide.setLastLocationUpdatedAt(t0Time);
        testRide.setMoving(false);
        testRide.setStationarySince(t0Time);
        rideRepository.save(testRide);

        // Jitter update 1: 5s later, moved 2 meters (lat + 0.00002)
        long t1Epoch = t0Epoch + 5;
        String resp1 = buildTelegramLocationUpdateJson(23, "12345678", 10.00002, 76.0, t1Epoch);
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(resp1, MediaType.APPLICATION_JSON));
        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride ride1 = rideRepository.findById(testRide.getId()).orElseThrow();
        assertFalse(ride1.isMoving());
        assertEquals(t0Time, ride1.getStationarySince(), "stationarySince must NOT be reset on first jitter");

        // Jitter update 2: 10s later, moved 1 meter back (lat + 0.00001)
        mockServer.reset();
        long t2Epoch = t0Epoch + 10;
        String resp2 = buildTelegramLocationUpdateJson(24, "12345678", 10.00001, 76.0, t2Epoch);
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(resp2, MediaType.APPLICATION_JSON));
        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride ride2 = rideRepository.findById(testRide.getId()).orElseThrow();
        assertFalse(ride2.isMoving());
        assertEquals(t0Time, ride2.getStationarySince(), "stationarySince must still remain original t0 time");
    }

    @Test
    @DisplayName("Stage 7.5 — Stationary to moving: starts moving again, stationarySince cleared")
    void testStationaryToMovingClearsStationarySince() {
        Instant stopTime = Instant.now().minusSeconds(120);
        testRide.setLastKnownLatitude(10.0);
        testRide.setLastKnownLongitude(76.0);
        testRide.setLastLocationUpdatedAt(stopTime);
        testRide.setMoving(false);
        testRide.setStationarySince(stopTime);
        rideRepository.save(testRide);

        // Rider moves ~550 meters east (lat 10.0, lon 76.005)
        long nowEpoch = Instant.now().getEpochSecond();
        String telegramResponse = buildTelegramLocationUpdateJson(25, "12345678", 10.0, 76.005, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertTrue(updatedRide.isMoving(), "Rider resuming movement must set isMoving = true");
        assertNull(updatedRide.getStationarySince(), "Resuming movement must clear stationarySince");
    }

    @Test
    @DisplayName("Stage 7.6 — Stale GPS update does not change movement state or stationary timer")
    void testStaleGpsUpdateDoesNotChangeMovementState() {
        Instant futureTime = Instant.now().plusSeconds(300);
        testRide.setLastKnownLatitude(10.0);
        testRide.setLastKnownLongitude(76.05);
        testRide.setLastLocationUpdatedAt(futureTime);
        testRide.setMoving(true);
        testRide.setStationarySince(null);
        testRide.setCurrentPosition(50.0);
        rideRepository.save(testRide);

        // Stale update: 10 minutes earlier
        long staleEpoch = futureTime.minusSeconds(600).getEpochSecond();
        String telegramResponse = buildTelegramLocationUpdateJson(26, "12345678", 10.0, 76.01, staleEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertTrue(updatedRide.isMoving(), "Stale update must not alter isMoving");
        assertNull(updatedRide.getStationarySince(), "Stale update must not alter stationarySince");
        assertEquals(10.0, updatedRide.getLastKnownLatitude(), 1e-4);
        assertEquals(76.05, updatedRide.getLastKnownLongitude(), 1e-4);
        assertEquals(50.0, updatedRide.getCurrentPosition(), 1e-4);
    }

    @Test
    @DisplayName("Stage 7.7 — Invalid GPS coordinates do not alter movement state")
    void testInvalidGpsDoesNotAlterMovementState() {
        testRide.setLastKnownLatitude(10.0);
        testRide.setLastKnownLongitude(76.0);
        testRide.setMoving(true);
        testRide.setStationarySince(null);
        rideRepository.save(testRide);

        long nowEpoch = Instant.now().getEpochSecond();
        // Latitude 95.0 is out of bounds
        String telegramResponse = buildTelegramLocationUpdateJson(27, "12345678", 95.0, 76.0, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertTrue(updatedRide.isMoving(), "Invalid GPS must not alter isMoving");
        assertNull(updatedRide.getStationarySince());
        assertEquals(10.0, updatedRide.getLastKnownLatitude(), 1e-4);
    }

    @Test
    @DisplayName("Stage 7.8 — GPS distance exactly at threshold (10.0m) qualifies as moving")
    void testGpsExactlyAtThresholdIsMoving() {
        testRide.setLastKnownLatitude(10.0);
        testRide.setLastKnownLongitude(76.0);
        testRide.setLastLocationUpdatedAt(Instant.now().minusSeconds(10));
        testRide.setMoving(false);
        testRide.setStationarySince(Instant.now().minusSeconds(10));
        rideRepository.save(testRide);

        // Move by exactly 10.0 meters in latitude
        double offsetDegLat = 10.0 / ((Math.PI / 180.0) * 6371000.0);
        long nowEpoch = Instant.now().getEpochSecond();
        String telegramResponse = buildTelegramLocationUpdateJson(28, "12345678", 10.0 + offsetDegLat, 76.0, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertTrue(updatedRide.isMoving(), "Distance >= threshold must register as moving");
        assertNull(updatedRide.getStationarySince(), "stationarySince must be cleared when moving");
    }

    @Test
    @DisplayName("Stage 7.10 — Stage 6 projection still updates currentPosition alongside movement state")
    void testProjectionAndMovementUpdatedConcurrently() {
        testRide.setLastKnownLatitude(10.0);
        testRide.setLastKnownLongitude(76.0);
        testRide.setCurrentPosition(0.0);
        testRide.setMoving(true);
        testRide.setLastLocationUpdatedAt(Instant.now().minusSeconds(10));
        rideRepository.save(testRide);

        // Midpoint: (10.0, 76.05) - distance is ~5.4 km (clearly moving)
        long nowEpoch = Instant.now().getEpochSecond();
        String telegramResponse = buildTelegramLocationUpdateJson(29, "12345678", 10.0, 76.05, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramResponse, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertTrue(updatedRide.isMoving());
        assertNull(updatedRide.getStationarySince());
        assertEquals(50.0, updatedRide.getCurrentPosition(), 1.0, "currentPosition must be updated to ~50%");
    }

    @Test
    @DisplayName("Stage 7 Safety Regression — End-to-end compatibility with SafetyScheduler")
    void testSafetySchedulerEndToEndCompatibility() {
        // Setup checkpoint awaiting passing
        Checkpoint cp = new Checkpoint(testRide, 1, "Midpoint Stop", 10.0, 76.05, 5470.0,
                50.0, Instant.now().plusSeconds(3600));
        cp.setStatus(Checkpoint.CheckpointStatus.NOT_REACHED);
        checkpointRepository.save(cp);

        // Rider tolerance is 20 seconds
        testRider.setStationaryToleranceSeconds(20);
        riderRepository.save(testRider);

        SafetyScheduler safetyScheduler = new SafetyScheduler(rideRepository, checkpointRepository, alertService);

        // 1. Initial location set at T - 30 seconds
        long tStopEpoch = Instant.now().getEpochSecond() - 30;
        Instant tStopTime = Instant.ofEpochSecond(tStopEpoch);
        testRide.setLastKnownLatitude(10.0);
        testRide.setLastKnownLongitude(76.0);
        testRide.setLastLocationUpdatedAt(tStopTime);
        testRide.setMoving(true);
        rideRepository.save(testRide);

        // 2. Rider becomes stationary (moves 1 meter at T - 25 seconds)
        long tStationaryEpoch = tStopEpoch + 5; // 25 seconds ago
        String stationaryUpdate = buildTelegramLocationUpdateJson(30, "12345678", 10.00001, 76.0, tStationaryEpoch);
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(stationaryUpdate, MediaType.APPLICATION_JSON));
        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride stationaryRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertFalse(stationaryRide.isMoving());
        assertNotNull(stationaryRide.getStationarySince());

        // 3. Safety scheduler runs: stationary time is ~25 seconds >= 20 seconds tolerance
        // Mock the Telegram sendMessage prompt call that SafetyScheduler will trigger
        mockServer.reset();
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        safetyScheduler.checkOverdueStationaryCheckpoints();
        mockServer.verify();

        Checkpoint cpAfterStationary = checkpointRepository.findById(cp.getId()).orElseThrow();
        assertEquals(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION, cpAfterStationary.getStatus(),
                "Checkpoint should transition to AWAITING_CONFIRMATION when stationary exceeds tolerance");

        // 4. Rider starts moving again (moves ~500m): isMoving = true, stationarySince = null
        mockServer.reset();
        long tMoveEpoch = Instant.now().getEpochSecond();
        String moveUpdate = buildTelegramLocationUpdateJson(31, "12345678", 10.005, 76.0, tMoveEpoch);
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(moveUpdate, MediaType.APPLICATION_JSON));
        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride movingRide = rideRepository.findById(testRide.getId()).orElseThrow();
        assertTrue(movingRide.isMoving());
        assertNull(movingRide.getStationarySince());

        // 5. SafetyScheduler pollForReplies detects ride.isMoving() == true and cancels the prompt!
        safetyScheduler.pollForReplies();

        Checkpoint cpAfterResuming = checkpointRepository.findById(cp.getId()).orElseThrow();
        assertEquals(Checkpoint.CheckpointStatus.NOT_REACHED, cpAfterResuming.getStatus(),
                "Checkpoint prompt should be automatically cleared when rider resumes movement");
        assertNull(cpAfterResuming.getConfirmationSentAt());
        assertNull(cpAfterResuming.getGraceDeadline());
    }
}
