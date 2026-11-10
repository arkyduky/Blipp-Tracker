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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DataJpaTest
@DisplayName("Stage 8: AlertService Telegram Route Submission Integration Tests")
class AlertServiceRouteSubmissionTest {

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    private AlertService alertService;
    private RideService rideService;
    private StubRouteService stubRouteService;
    private GoogleMapsRouteParser googleMapsRouteParser;
    private GeoProjectionService geoProjectionService;
    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;

    private Rider testRider;

    private static final String SAMPLE_ROUTE_URL =
            "https://www.google.com/maps/dir/10.5276,76.2144/10.6517,76.0409/10.1849,76.3753/10.0889,77.0595";

    private static class StubRouteService extends RouteService {
        private RouteResult stubResult;
        private boolean shouldThrow = false;
        private final AtomicInteger callCount = new AtomicInteger(0);

        public void setStubResult(RouteResult result) {
            this.stubResult = result;
            this.shouldThrow = false;
        }

        public void setShouldThrow(boolean shouldThrow) {
            this.shouldThrow = shouldThrow;
        }

        public int getCallCount() {
            return callCount.get();
        }

        @Override
        public RouteResult calculateRoute(List<Waypoint> waypoints) {
            callCount.incrementAndGet();
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
        String geoJson = "{\"type\":\"LineString\",\"coordinates\":[[76.2144,10.5276],[76.0409,10.6517],[76.3753,10.1849],[77.0595,10.0889]]}";
        List<List<Double>> coords = List.of(
                List.of(76.2144, 10.5276),
                List.of(76.0409, 10.6517),
                List.of(76.3753, 10.1849),
                List.of(77.0595, 10.0889)
        );
        List<RouteLeg> legs = List.of(
                new RouteLeg(25000.0, 1800.0, 0, 1),
                new RouteLeg(35000.0, 2400.0, 1, 2),
                new RouteLeg(40000.0, 3000.0, 2, 3)
        );
        return new RouteResult(geoJson, coords, 100000.0, 7200.0, legs);
    }

    @BeforeEach
    void setUp() {
        testRider = new Rider();
        testRider.setName("Amal");
        testRider.setTelegramChatId("12345678");
        testRider.setEmergencyContactChatId("87654321");
        testRider = riderRepository.save(testRider);

        stubRouteService = new StubRouteService();
        stubRouteService.setStubResult(createSampleRouteResult());

        googleMapsRouteParser = new GoogleMapsRouteParser();
        rideService = new RideService(riderRepository, rideRepository, checkpointRepository, stubRouteService, googleMapsRouteParser);

        geoProjectionService = new GeoProjectionService();

        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        alertService = new AlertService(rideRepository, riderRepository, rideService, geoProjectionService, googleMapsRouteParser);
        alertService.setBotToken("mock-bot-token");
        alertService.setRestTemplate(restTemplate);
    }

    private String buildTelegramTextMessageJson(long updateId, String chatId, String text) {
        return "{\n" +
                "  \"ok\": true,\n" +
                "  \"result\": [\n" +
                "    {\n" +
                "      \"update_id\": " + updateId + ",\n" +
                "      \"message\": {\n" +
                "        \"message_id\": 101,\n" +
                "        \"date\": " + Instant.now().getEpochSecond() + ",\n" +
                "        \"chat\": { \"id\": " + chatId + " },\n" +
                "        \"text\": \"" + text.replace("\"", "\\\"") + "\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    private String buildTelegramLocationUpdateJson(long updateId, String chatId, double lat, double lon, long epochSecond) {
        return "{\n" +
                "  \"ok\": true,\n" +
                "  \"result\": [\n" +
                "    {\n" +
                "      \"update_id\": " + updateId + ",\n" +
                "      \"message\": {\n" +
                "        \"message_id\": 102,\n" +
                "        \"date\": " + epochSecond + ",\n" +
                "        \"chat\": { \"id\": " + chatId + " },\n" +
                "        \"location\": { \"latitude\": " + lat + ", \"longitude\": " + lon + " }\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    private String buildTelegramEditedLocationUpdateJson(long updateId, String chatId, double lat, double lon, long epochSecond) {
        return "{\n" +
                "  \"ok\": true,\n" +
                "  \"result\": [\n" +
                "    {\n" +
                "      \"update_id\": " + updateId + ",\n" +
                "      \"edited_message\": {\n" +
                "        \"message_id\": 103,\n" +
                "        \"date\": " + (epochSecond - 10) + ",\n" +
                "        \"edit_date\": " + epochSecond + ",\n" +
                "        \"chat\": { \"id\": " + chatId + " },\n" +
                "        \"location\": { \"latitude\": " + lat + ", \"longitude\": " + lon + " }\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    @Test
    @DisplayName("1. Valid Google Maps route creates an ACTIVE Ride with real geometry, distance, duration, and checkpoints")
    void testValidRouteSubmissionCreatesActiveRideWithCheckpoints() {
        String telegramJson = buildTelegramTextMessageJson(1, "12345678", SAMPLE_ROUTE_URL);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        List<Ride> rides = rideRepository.findAll();
        assertEquals(1, rides.size());

        Ride ride = rides.get(0);
        assertEquals(Ride.RideStatus.ACTIVE, ride.getStatus());
        assertEquals(testRider.getId(), ride.getRider().getId());
        assertEquals(10.5276, ride.getOriginLatitude(), 1e-4);
        assertEquals(76.2144, ride.getOriginLongitude(), 1e-4);
        assertEquals(10.0889, ride.getDestinationLatitude(), 1e-4);
        assertEquals(77.0595, ride.getDestinationLongitude(), 1e-4);
        assertEquals(100000.0, ride.getTotalDistanceMeters(), 1.0);
        assertEquals(7200.0, ride.getTotalDurationSeconds(), 1.0);
        assertNotNull(ride.getRouteGeometry());
        assertTrue(ride.getRouteGeometry().contains("LineString"));

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(ride);
        assertEquals(3, checkpoints.size());

        Checkpoint cp1 = checkpoints.get(0);
        assertEquals(1, cp1.getSequenceNumber());
        assertEquals(10.6517, cp1.getLatitude(), 1e-4);
        assertEquals(76.0409, cp1.getLongitude(), 1e-4);
        assertEquals(25.0, cp1.getPositionOnRoute(), 0.1);

        Checkpoint cp2 = checkpoints.get(1);
        assertEquals(2, cp2.getSequenceNumber());
        assertEquals(10.1849, cp2.getLatitude(), 1e-4);
        assertEquals(76.3753, cp2.getLongitude(), 1e-4);
        assertEquals(60.0, cp2.getPositionOnRoute(), 0.1);

        Checkpoint cp3 = checkpoints.get(2);
        assertEquals(3, cp3.getSequenceNumber());
        assertEquals(10.0889, cp3.getLatitude(), 1e-4);
        assertEquals(77.0595, cp3.getLongitude(), 1e-4);
        assertEquals(100.0, cp3.getPositionOnRoute(), 0.1);
    }

    @Test
    @DisplayName("2. Successful Telegram confirmation contains real route information")
    void testSuccessfulTelegramConfirmationContainsRealRouteInfo() {
        String telegramJson = buildTelegramTextMessageJson(2, "12345678", "Here is my route: " + SAMPLE_ROUTE_URL);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("12345678")))
                .andExpect(content().string(containsString("Route accepted.")))
                .andExpect(content().string(containsString("100.0 km")))
                .andExpect(content().string(containsString("3 checkpoints")))
                .andExpect(content().string(containsString("Share your Live Location")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();
    }

    @Test
    @DisplayName("3. Unknown rider creates no Ride and makes no OSRM call")
    void testUnknownRiderCreatesNoRideAndMakesNoRouteCall() {
        String telegramJson = buildTelegramTextMessageJson(3, "99999999", SAMPLE_ROUTE_URL);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("99999999")))
                .andExpect(content().string(containsString("not registered")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(0, rideRepository.count(), "No ride must be created for unknown rider");
        assertEquals(0, stubRouteService.getCallCount(), "No OSRM call must be made for unknown rider");
    }

    @Test
    @DisplayName("4. Invalid route creates no Ride and leaves existing rides untouched")
    void testInvalidRouteCreatesNoRideAndLeavesRidesUntouched() {
        String invalidUrl = "https://www.google.com/maps/invalid_directions_url";
        String telegramJson = buildTelegramTextMessageJson(4, "12345678", invalidUrl);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("12345678")))
                .andExpect(content().string(containsString("Could not parse")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(0, rideRepository.count(), "No ride must be created for unparseable route");
    }

    @Test
    @DisplayName("5. Existing active ride prevents duplicate creation")
    void testExistingActiveRidePreventsDuplicateCreation() {
        Ride existingRide = new Ride(testRider);
        existingRide.setStatus(Ride.RideStatus.ACTIVE);
        existingRide.setCurrentPosition(10.0);
        existingRide = rideRepository.save(existingRide);

        String telegramJson = buildTelegramTextMessageJson(5, "12345678", SAMPLE_ROUTE_URL);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("12345678")))
                .andExpect(content().string(containsString("already have an active ride")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        List<Ride> activeRides = rideRepository.findByStatus(Ride.RideStatus.ACTIVE);
        assertEquals(1, activeRides.size(), "Must not create duplicate active ride");
        assertEquals(existingRide.getId(), activeRides.get(0).getId());
    }

    @Test
    @DisplayName("6. OSRM failure leaves no inconsistent ACTIVE Ride")
    void testOsrmFailureLeavesNoInconsistentActiveRide() {
        stubRouteService.setShouldThrow(true);

        String telegramJson = buildTelegramTextMessageJson(6, "12345678", SAMPLE_ROUTE_URL);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("12345678")))
                .andExpect(content().string(containsString("Route calculation failed")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(0, rideRepository.count(), "No inconsistent ACTIVE ride must be persisted on OSRM failure");
        assertEquals(0, checkpointRepository.count(), "No checkpoints must be created on OSRM failure");
    }

    @Test
    @DisplayName("7. Live Location still updates currentPosition, isMoving, and stationarySince on submitted route ride")
    void testLiveLocationUpdatesProgressAndMovementOnSubmittedRoute() {
        // Step 1: Create ride via route submission
        testValidRouteSubmissionCreatesActiveRideWithCheckpoints();
        Ride ride = rideRepository.findAll().get(0);

        // Step 2: Receive Live Location update at Kunnamkulam (10.6517, 76.0409)
        mockServer.reset();
        long nowEpoch = Instant.now().getEpochSecond();
        String locationJson = buildTelegramLocationUpdateJson(7, "12345678", 10.6517, 76.0409, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(locationJson, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(ride.getId()).orElseThrow();
        assertEquals(10.6517, updatedRide.getLastKnownLatitude(), 1e-4);
        assertEquals(76.0409, updatedRide.getLastKnownLongitude(), 1e-4);
        assertNotNull(updatedRide.getLastLocationUpdatedAt());
        assertTrue(updatedRide.getCurrentPosition() > 0.0, "Progress should advance from origin");
        assertTrue(updatedRide.isMoving(), "Initial location should set isMoving=true");
        assertNull(updatedRide.getStationarySince());
    }

    @Test
    @DisplayName("8. Edited Live Location still works on submitted route ride")
    void testEditedLiveLocationUpdatesProgressAndMovementOnSubmittedRoute() {
        testValidRouteSubmissionCreatesActiveRideWithCheckpoints();
        Ride ride = rideRepository.findAll().get(0);

        mockServer.reset();
        long nowEpoch = Instant.now().getEpochSecond();
        // Edited location at Angamaly (10.1849, 76.3753) - ~53% along the route geometry
        String editedJson = buildTelegramEditedLocationUpdateJson(8, "12345678", 10.1849, 76.3753, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(editedJson, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(ride.getId()).orElseThrow();
        assertEquals(10.1849, updatedRide.getLastKnownLatitude(), 1e-4);
        assertEquals(76.3753, updatedRide.getLastKnownLongitude(), 1e-4);
        assertTrue(updatedRide.getCurrentPosition() > 50.0, "Progress should be past 50%");
        assertTrue(updatedRide.isMoving());
    }

    @Test
    @DisplayName("9. Non-route text does not create a Ride and does not prompt for route")
    void testNonRouteTextDoesNotCreateRide() {
        String telegramJson = buildTelegramTextMessageJson(9, "12345678", "OK I am ready to start");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(0, rideRepository.count(), "Non-route text must not create a ride");
    }

    @Test
    @DisplayName("10. Route processing uses the existing single polling stream with consecutive messages")
    void testSinglePollingStreamHandlesRouteSubmissionFollowedByLiveLocation() {
        // Message 1: Route URL
        String routeJson = buildTelegramTextMessageJson(10, "12345678", SAMPLE_ROUTE_URL);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(routeJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(1, rideRepository.count());
        Ride createdRide = rideRepository.findAll().get(0);
        assertEquals(0.0, createdRide.getCurrentPosition(), 0.01);

        // Message 2: Live Location update on the very next poll of the same stream
        mockServer.reset();
        long nowEpoch = Instant.now().getEpochSecond();
        String locJson = buildTelegramLocationUpdateJson(11, "12345678", 10.6517, 76.0409, nowEpoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(locJson, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride liveRide = rideRepository.findById(createdRide.getId()).orElseThrow();
        assertEquals(10.6517, liveRide.getLastKnownLatitude(), 1e-4);
        assertEquals(76.0409, liveRide.getLastKnownLongitude(), 1e-4);
        assertTrue(liveRide.getCurrentPosition() > 10.0, "Progress should advance past 10%");
    }

    @Test
    @DisplayName("11. END command clears active real ride and checkpoints from H2 database")
    void testEndCommandClearsActiveRealRideAndCheckpoints() {
        // Step 1: Submit route so active real ride exists
        String routeJson = buildTelegramTextMessageJson(20, "12345678", SAMPLE_ROUTE_URL);
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(routeJson, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(1, rideRepository.count(), "One real ride must exist");
        assertTrue(checkpointRepository.count() > 0, "Checkpoints must exist");

        // Step 2: Rider sends END command
        mockServer.reset();
        String endJson = buildTelegramTextMessageJson(21, "12345678", "END");
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(endJson, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("12345678")))
                .andExpect(content().string(containsString("Ride ended and reset. You can now send a new Google Maps route link.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(0, rideRepository.count(), "Real ride must be deleted from H2");
        assertEquals(0, checkpointRepository.count(), "Checkpoints must be deleted from H2");
    }

    @Test
    @DisplayName("12. END command variations are recognized case-insensitively with slash commands")
    void testEndCommandVariations() {
        assertTrue(alertService.isEndCommand("END"));
        assertTrue(alertService.isEndCommand("end"));
        assertTrue(alertService.isEndCommand("End"));
        assertTrue(alertService.isEndCommand("/end"));
        assertTrue(alertService.isEndCommand("/END"));
        assertTrue(alertService.isEndCommand("  END  "));
        assertTrue(alertService.isEndCommand("  /end  "));
        assertTrue(alertService.isEndCommand("END RIDE"));
        assertTrue(alertService.isEndCommand("/endride"));
        assertTrue(alertService.isEndCommand("/end@SafetyNetBot"));

        assertFalse(alertService.isEndCommand("START"));
        assertFalse(alertService.isEndCommand("hello"));
        assertFalse(alertService.isEndCommand(""));
        assertFalse(alertService.isEndCommand(null));
    }

    @Test
    @DisplayName("13. END command when no real active ride exists prompts rider to send route")
    void testEndCommandWhenNoActiveRidePromptsForRoute() {
        assertEquals(0, rideRepository.count());

        String endJson = buildTelegramTextMessageJson(30, "12345678", "/end");
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(endJson, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("12345678")))
                .andExpect(content().string(containsString("No active ride found. Please send your Google Maps route link.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();
    }

    @Test
    @DisplayName("14. Submitting a new route succeeds immediately after END command reset")
    void testNewRouteSubmissionSucceedsAfterEndCommand() {
        // Step 1: Create initial ride
        String routeJson1 = buildTelegramTextMessageJson(40, "12345678", SAMPLE_ROUTE_URL);
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(routeJson1, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.pollTelegramUpdates();
        mockServer.verify();
        assertEquals(1, rideRepository.count());

        // Step 2: Send END
        mockServer.reset();
        String endJson = buildTelegramTextMessageJson(41, "12345678", "END");
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(endJson, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.pollTelegramUpdates();
        mockServer.verify();
        assertEquals(0, rideRepository.count());

        // Step 3: Send new route URL - should be accepted without 'already active' error
        mockServer.reset();
        String routeJson2 = buildTelegramTextMessageJson(42, "12345678", SAMPLE_ROUTE_URL);
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(routeJson2, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("Route accepted.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.pollTelegramUpdates();
        mockServer.verify();
        assertEquals(1, rideRepository.count(), "New ride should be created after reset");
    }

    @Test
    @DisplayName("15. END command strictly preserves Demo A rides and checkpoints")
    void testEndCommandPreservesDemoARides() {
        // Setup existing persisted demo record with checkpoint
        Ride demoRide = new Ride(testRider);
        demoRide.setDemo(true);
        demoRide.setStatus(Ride.RideStatus.ACTIVE);
        demoRide = rideRepository.save(demoRide);
        long demoRideId = demoRide.getId();

        Checkpoint cp = new Checkpoint(demoRide, 1, 50.0, Instant.now().plusSeconds(600));
        checkpointRepository.save(cp);
        int initialCheckpointsCount = 1;

        // Create Real ride with checkpoints
        Ride realRide = rideService.createRideForRiderStrict(testRider, List.of(
                new Waypoint("Thrissur", 10.5276, 76.2144, 1),
                new Waypoint("Kunnamkulam", 10.6517, 76.0409, 2),
                new Waypoint("Angamaly", 10.1849, 76.3753, 3),
                new Waypoint("Munnar", 10.0889, 77.0595, 4)
        ));
        long realRideId = realRide.getId();

        assertEquals(2, rideRepository.count(), "Two rides should exist (Demo A + Real)");

        // Send END command
        String endJson = buildTelegramTextMessageJson(50, "12345678", "END");
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(endJson, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("Ride ended and reset.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        // Verify Real ride is deleted, but Demo A ride is completely preserved
        assertEquals(1, rideRepository.count(), "Only Demo A ride should remain");
        assertTrue(rideRepository.findById(demoRideId).isPresent(), "Demo A ride must still exist");
        assertTrue(rideRepository.findById(realRideId).isEmpty(), "Real ride must be deleted");

        List<Checkpoint> demoCheckpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(demoRide);
        assertEquals(initialCheckpointsCount, demoCheckpoints.size(), "Demo A checkpoints must be preserved");
    }
}
