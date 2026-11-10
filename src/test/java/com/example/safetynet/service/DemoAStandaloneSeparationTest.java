package com.example.safetynet.service;

import com.example.safetynet.controller.RideController;
import com.example.safetynet.controller.ViewController;
import com.example.safetynet.dto.RideLiveStatusDto;
import com.example.safetynet.dto.RouteLeg;
import com.example.safetynet.dto.RouteResult;
import com.example.safetynet.dto.Waypoint;
import org.springframework.http.ResponseEntity;
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
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DataJpaTest
@DisplayName("Real Ride & Live Monitor Integration and Separation Tests")
class DemoAStandaloneSeparationTest {

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    private AlertService alertService;
    private RideService rideService;
    private ViewController viewController;
    private RideController rideController;
    private StubRouteService stubRouteService;
    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;

    private Rider testRider;

    private static final String SAMPLE_ROUTE_URL =
            "https://www.google.com/maps/dir/10.5276,76.2144/10.6517,76.0409/10.1849,76.3753/10.0889,77.0595";

    private static final List<Waypoint> SAMPLE_WAYPOINTS = List.of(
            new Waypoint("Thrissur", 10.5276, 76.2144, 1),
            new Waypoint("Kunnamkulam", 10.6517, 76.0409, 2),
            new Waypoint("Angamaly", 10.1849, 76.3753, 3),
            new Waypoint("Munnar", 10.0889, 77.0595, 4)
    );

    private static class StubRouteService extends RouteService {
        private RouteResult stubResult;

        public void setStubResult(RouteResult result) {
            this.stubResult = result;
        }

        @Override
        public RouteResult calculateRoute(List<Waypoint> waypoints) {
            if (stubResult != null) {
                return stubResult;
            }
            return createDefaultResult(waypoints);
        }

        private RouteResult createDefaultResult(List<Waypoint> waypoints) {
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
    }

    @BeforeEach
    void setUp() {
        stubRouteService = new StubRouteService();
        stubRouteService.setStubResult(stubRouteService.createDefaultResult(SAMPLE_WAYPOINTS));

        GoogleMapsRouteParser parser = new GoogleMapsRouteParser();
        rideService = new RideService(riderRepository, rideRepository, checkpointRepository, stubRouteService, parser);
        viewController = new ViewController(rideService, rideRepository, riderRepository);
        rideController = new RideController(rideRepository, checkpointRepository, riderRepository);

        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        GeoProjectionService geoProjectionService = new GeoProjectionService();
        alertService = new AlertService(rideRepository, riderRepository, rideService, geoProjectionService, parser);
        alertService.setBotToken("mock-bot-token");
        alertService.setRestTemplate(restTemplate);

        testRider = new Rider();
        testRider.setName("Amal");
        testRider.setTelegramChatId("12345678");
        testRider.setEmergencyContactChatId("87654321");
        testRider = riderRepository.save(testRider);
    }

    private String buildTelegramTextMessageJson(long updateId, String chatId, String text) {
        return "{\n" +
                "  \"ok\": true,\n" +
                "  \"result\": [\n" +
                "    {\n" +
                "      \"update_id\": " + updateId + ",\n" +
                "      \"message\": {\n" +
                "        \"message_id\": 201,\n" +
                "        \"date\": " + (System.currentTimeMillis() / 1000) + ",\n" +
                "        \"chat\": { \"id\": " + chatId + " },\n" +
                "        \"text\": \"" + text + "\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    // --- Web Registration Tests ---

    @Test
    @DisplayName("1. Web setup /start registers rider and redirects to /live")
    void testWebSetupRegistersRiderAndRedirectsToLive() {
        Model model = new ExtendedModelMap();
        String view = viewController.startRide("Rider Amit", "11112222", "33334444", 25, 40, model);

        assertEquals("redirect:/live", view);

        Rider createdRider = riderRepository.findAll().stream()
                .filter(r -> "11112222".equals(r.getTelegramChatId()))
                .findFirst().orElseThrow();
        assertEquals("Rider Amit", createdRider.getName());
        assertEquals("33334444", createdRider.getEmergencyContactChatId());
        assertEquals(25, createdRider.getStationaryToleranceSeconds());
        assertEquals(40, createdRider.getGracePeriodSeconds());
    }

    @Test
    @DisplayName("2. Real ride creation with waypoints produces expected checkpoints")
    void testRealRideCreationWithWaypointsAndCheckpoints() {
        Ride realRide = rideService.createRideForRiderStrict(testRider, SAMPLE_WAYPOINTS);

        assertFalse(realRide.isDemo());
        assertEquals(10.5276, realRide.getOriginLatitude(), 0.001);
        assertEquals(76.2144, realRide.getOriginLongitude(), 0.001);
        assertEquals(10.0889, realRide.getDestinationLatitude(), 0.001);
        assertEquals(77.0595, realRide.getDestinationLongitude(), 0.001);

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(realRide);
        assertEquals(3, checkpoints.size(), "Real ride must have exactly 3 checkpoints");

        assertEquals(1, checkpoints.get(0).getSequenceNumber());
        assertEquals("Kunnamkulam", checkpoints.get(0).getName());

        assertEquals(2, checkpoints.get(1).getSequenceNumber());
        assertEquals("Angamaly", checkpoints.get(1).getName());

        assertEquals(3, checkpoints.get(2).getSequenceNumber());
        assertEquals("Munnar", checkpoints.get(2).getName());
    }

    @Test
    @DisplayName("3. Multiple real routes have isolated checkpoints")
    void testMultipleRealRoutesHaveIsolatedCheckpoints() {
        // Step 1: Create first real ride with 3 checkpoints
        Ride realRide1 = rideService.createRideForRiderStrict(testRider, SAMPLE_WAYPOINTS);
        List<Checkpoint> checkpointsBefore = checkpointRepository.findByRideOrderBySequenceNumberAsc(realRide1);
        assertEquals(3, checkpointsBefore.size());

        // Step 2: Create a second real ride with 5 waypoints (4 checkpoints)
        List<Waypoint> customWaypoints = List.of(
                new Waypoint("Point 0", 10.0, 76.0, 1),
                new Waypoint("Point 1", 10.1, 76.1, 2),
                new Waypoint("Point 2", 10.2, 76.2, 3),
                new Waypoint("Point 3", 10.3, 76.3, 4),
                new Waypoint("Point 4", 10.4, 76.4, 5)
        );

        List<RouteLeg> customLegs = List.of(
                new RouteLeg(10000.0, 600.0, 0, 1),
                new RouteLeg(10000.0, 600.0, 1, 2),
                new RouteLeg(10000.0, 600.0, 2, 3),
                new RouteLeg(10000.0, 600.0, 3, 4)
        );
        stubRouteService.setStubResult(new RouteResult("{\"type\":\"LineString\",\"coordinates\":[]}", List.of(), 40000.0, 2400.0, customLegs));

        Ride realRide2 = rideService.createRideForRiderStrict(testRider, customWaypoints);
        assertFalse(realRide2.isDemo());

        List<Checkpoint> realCheckpoints2 = checkpointRepository.findByRideOrderBySequenceNumberAsc(realRide2);
        assertEquals(4, realCheckpoints2.size());

        // Step 3: Assert first real ride checkpoints are completely isolated and still exactly 3
        List<Checkpoint> checkpointsAfter = checkpointRepository.findByRideOrderBySequenceNumberAsc(realRide1);
        assertEquals(3, checkpointsAfter.size(), "First ride checkpoints count must remain unchanged at 3");
        assertEquals("Kunnamkulam", checkpointsAfter.get(0).getName());
        assertEquals("Angamaly", checkpointsAfter.get(1).getName());
        assertEquals("Munnar", checkpointsAfter.get(2).getName());
    }

    // --- Live Monitor Tests ---

    @Test
    @DisplayName("4. Telegram route creation creates isDemo=false and existing demo does not block it")
    void testTelegramRouteCreationCreatesIsDemoFalseAndDemoADoesNotBlock() {
        // Existing demo record exists in DB
        Ride demoRide = new Ride(testRider);
        demoRide.setDemo(true);
        demoRide.setStatus(Ride.RideStatus.ACTIVE);
        rideRepository.save(demoRide);

        assertTrue(demoRide.isDemo());
        assertEquals(Ride.RideStatus.ACTIVE, demoRide.getStatus());

        String telegramJson = buildTelegramTextMessageJson(1, "12345678", SAMPLE_ROUTE_URL);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("12345678")))
                .andExpect(content().string(containsString("Route accepted")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        // Verify a new real ride was created with isDemo = false
        List<Ride> rides = rideRepository.findAll();
        assertEquals(2, rides.size());

        Ride realRide = rides.stream().filter(r -> !r.isDemo()).findFirst().orElse(null);
        assertNotNull(realRide, "Real ride with isDemo=false must be created");
        assertEquals(Ride.RideStatus.ACTIVE, realRide.getStatus());
        assertFalse(realRide.isDemo());
    }

    @Test
    @DisplayName("5. Real active ride still rejects duplicate real route submissions")
    void testRealActiveRideStillRejectsDuplicateRealRouteSubmissions() {
        // Create an active real ride (isDemo = false)
        Ride realRide = new Ride(testRider);
        realRide.setDemo(false);
        realRide.setStatus(Ride.RideStatus.ACTIVE);
        rideRepository.save(realRide);

        String telegramJson = buildTelegramTextMessageJson(2, "12345678", SAMPLE_ROUTE_URL);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("12345678")))
                .andExpect(content().string(containsString("already have an active ride in progress")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(1, rideRepository.count(), "No duplicate ride should be created");
    }

    @Test
    @DisplayName("6. Live Monitor before route submission has no route geometry or checkpoints")
    void testDemoBBeforeRouteSubmissionHasNoRouteGeometryOrCheckpoints() {
        Ride pendingRide = new Ride(testRider);
        pendingRide.setDemo(false);
        pendingRide = rideRepository.save(pendingRide);

        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(pendingRide);
        RideLiveStatusDto dto = RideLiveStatusDto.from(pendingRide, checkpoints);

        assertNull(dto.routeGeometry(), "Before route submission, route geometry must be null");
        assertNotNull(dto.checkpoints());
        assertTrue(dto.checkpoints().isEmpty(), "Before route submission, checkpoints must be empty");
    }

    @Test
    @DisplayName("7. After Telegram route submission, Live Monitor displays submitted route and checkpoints")
    void testDemoBAfterTelegramRouteSubmissionDisplaysRouteAndCheckpoints() {
        String telegramJson = buildTelegramTextMessageJson(3, "12345678", SAMPLE_ROUTE_URL);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride realRide = rideRepository.findAll().stream().filter(r -> !r.isDemo()).findFirst().orElseThrow();
        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(realRide);

        RideLiveStatusDto dto = RideLiveStatusDto.from(realRide, checkpoints);
        assertNotNull(dto.routeGeometry(), "Route geometry must be populated after submission");
        assertFalse(dto.checkpoints().isEmpty(), "Checkpoints must be populated after submission");
        assertEquals(3, dto.checkpoints().size());
    }

    @Test
    @DisplayName("8. Opening Live Monitor standalone with no route does not require a Ride ID")
    void testOpeningDemoBStandaloneDoesNotRequireRideId() {
        Model model = new ExtendedModelMap();
        String view = viewController.showStandaloneLiveMonitor(model);

        assertEquals("location", view);
        assertNull(model.getAttribute("ride"), "Live monitor standalone zero-state must have ride = null");
    }

    @Test
    @DisplayName("9. Demo ride accessing /ride/{id}/live redirects to /live")
    void testDemoRideAccessingLiveRedirectsToZeroStateLive() {
        Ride demoRide = new Ride(testRider);
        demoRide.setDemo(true);
        demoRide = rideRepository.save(demoRide);
        Model model = new ExtendedModelMap();

        String view = viewController.showLocationPage(demoRide.getId(), model);

        assertEquals("redirect:/live", view, "Accessing /ride/{demoId}/live must redirect to standalone /live");
        assertNull(model.getAttribute("ride"), "No demo ride data should be passed to model on redirect");
    }

    @Test
    @DisplayName("10. Real ride accessing /ride/{id}/live renders location page with real ride")
    void testRealRideAccessingLiveRendersLocationPageWithRealRide() {
        List<Waypoint> waypoints = List.of(
                new Waypoint("Origin", 10.5276, 76.2144, 1),
                new Waypoint("Dest", 10.0889, 77.0595, 2)
        );
        Ride realRide = rideService.createRideForRiderStrict(testRider, waypoints);
        Model model = new ExtendedModelMap();

        String view = viewController.showLocationPage(realRide.getId(), model);

        assertEquals("location", view);
        assertEquals(realRide, model.getAttribute("ride"));
        assertFalse(((Ride) model.getAttribute("ride")).isDemo());
    }

    @Test
    @DisplayName("11. Accessing /ride/{id} redirects directly to /ride/{id}/live")
    void testMinimapTemplateLinksToStandaloneLiveWithoutRideId() {
        Ride realRide = new Ride(testRider);
        realRide.setDemo(false);
        realRide = rideRepository.save(realRide);

        String view = viewController.showRide(realRide.getId());
        assertEquals("redirect:/ride/" + realRide.getId() + "/live", view);
    }

    @Test
    @DisplayName("12. /live initially has no ride and discovery returns inactive")
    void testLiveInitiallyHasNoRideAndDiscoveryReturnsInactive() {
        Model model = new ExtendedModelMap();
        String view = viewController.showStandaloneLiveMonitor(model);
        assertEquals("location", view);
        assertNull(model.getAttribute("ride"), "/live must have ride=null initially");

        ResponseEntity<RideController.ActiveRideDiscoveryDto> response = rideController.getActiveRealRide(null);
        assertNotNull(response.getBody());
        assertFalse(response.getBody().active(), "Must be active=false when no rides exist");
        assertNull(response.getBody().rideId(), "Must have rideId=null when no rides exist");
    }

    @Test
    @DisplayName("13. Existing demo ride in DB is never discovered by the discovery mechanism")
    void testDemoARideIsNeverDiscovered() {
        Ride demoRide = new Ride(testRider);
        demoRide.setDemo(true);
        demoRide.setStatus(Ride.RideStatus.ACTIVE);
        rideRepository.save(demoRide);

        assertTrue(demoRide.isDemo());

        ResponseEntity<RideController.ActiveRideDiscoveryDto> response = rideController.getActiveRealRide(null);
        assertNotNull(response.getBody());
        assertFalse(response.getBody().active(), "Demo active ride must never be discovered as an active real ride");
        assertNull(response.getBody().rideId(), "Demo rideId must never be returned");
    }

    @Test
    @DisplayName("14. Newly created real ride is discovered")
    void testNewlyCreatedRealRideIsDiscovered() {
        // Existing demo ride is active
        Ride demoRide = new Ride(testRider);
        demoRide.setDemo(true);
        demoRide.setStatus(Ride.RideStatus.ACTIVE);
        rideRepository.save(demoRide);

        // Real route created via Telegram route submission or service
        List<Waypoint> waypoints = List.of(
                new Waypoint("Origin", 10.5276, 76.2144, 1),
                new Waypoint("Dest", 10.0889, 77.0595, 2)
        );
        Ride realRide = rideService.createRideForRiderStrict(testRider, waypoints);

        ResponseEntity<RideController.ActiveRideDiscoveryDto> response = rideController.getActiveRealRide(null);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().active(), "Newly created real ride must be discovered");
        assertEquals(realRide.getId(), response.getBody().rideId(), "Discovered ride ID must match real ride");
    }

    @Test
    @DisplayName("15. Monitor transitions from zero-state to that real ride")
    void testMonitorTransitionsFromZeroStateToRealRide() {
        // 1. Initial zero state
        Model model = new ExtendedModelMap();
        assertEquals("location", viewController.showStandaloneLiveMonitor(model));
        assertNull(model.getAttribute("ride"));

        // 2. Real ride created with checkpoints
        List<Waypoint> waypoints = List.of(
                new Waypoint("Origin", 10.5276, 76.2144, 1),
                new Waypoint("CP1", 10.3, 76.5, 2),
                new Waypoint("Dest", 10.0889, 77.0595, 3)
        );
        Ride realRide = rideService.createRideForRiderStrict(testRider, waypoints);

        // 3. Discovered by polling /ride/active
        ResponseEntity<RideController.ActiveRideDiscoveryDto> discoveryResponse = rideController.getActiveRealRide(null);
        assertTrue(discoveryResponse.getBody().active());
        Long discoveredId = discoveryResponse.getBody().rideId();
        assertEquals(realRide.getId(), discoveredId);

        // 4. Live telemetry fetched for discovered ride
        RideLiveStatusDto statusDto = rideController.getLiveStatus(discoveredId);
        assertNotNull(statusDto.routeGeometry(), "Route geometry must be available for discovered real ride");
        assertNotNull(statusDto.checkpoints(), "Checkpoints must be available for discovered real ride");
        assertFalse(statusDto.checkpoints().isEmpty());

        // 5. Page renders location for discovered real ride
        Model liveModel = new ExtendedModelMap();
        String liveView = viewController.showLocationPage(discoveredId, liveModel);
        assertEquals("location", liveView);
        assertEquals(realRide, liveModel.getAttribute("ride"));
    }

    @Test
    @DisplayName("16. No fallback route/checkpoints are rendered before discovery")
    void testNoFallbackRouteOrCheckpointsBeforeDiscovery() {
        // Zero state has no ride
        Model model = new ExtendedModelMap();
        viewController.showStandaloneLiveMonitor(model);
        assertNull(model.getAttribute("ride"), "Zero state must not populate a fallback ride");

        // Even if demo ride exists in DB, discovery returns inactive
        Ride demoRide = new Ride(testRider);
        demoRide.setDemo(true);
        rideRepository.save(demoRide);

        ResponseEntity<RideController.ActiveRideDiscoveryDto> response = rideController.getActiveRealRide(null);
        assertFalse(response.getBody().active(), "Must not return demo ride as fallback");
        assertNull(response.getBody().rideId(), "Must not return demo ride ID as fallback");
    }

    // --- Alert Messages Tests ---

    @Test
    @DisplayName("17. Real ride notifications do not contain (DEMO) prefix")
    void testRealRideNotificationsDoNotContainDemoPrefix() {
        Ride realRide = new Ride(testRider);
        realRide.setDemo(false);
        realRide = rideRepository.save(realRide);

        Checkpoint cp = new Checkpoint(realRide, 1, 33.3, LocalDateTime.now().plusMinutes(10));
        cp = checkpointRepository.save(cp);

        // 1. sendPassiveUpdate
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(not(containsString("(DEMO)"))))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.sendPassiveUpdate(realRide, cp);
        mockServer.verify();

        mockServer.reset();

        // 2. sendConfirmationPrompt
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(not(containsString("(DEMO)"))))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.sendConfirmationPrompt(realRide, cp);
        mockServer.verify();

        mockServer.reset();

        // 3. sendResolvedUpdate
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(not(containsString("(DEMO)"))))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.sendResolvedUpdate(realRide, cp, "All good");
        mockServer.verify();

        mockServer.reset();

        // 4. sendEmergencyAlert
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(not(containsString("(DEMO)"))))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.sendEmergencyAlert(realRide, cp);
        mockServer.verify();
    }

    @Test
    @DisplayName("18. Telegram /start does NOT send Google Maps route prompt")
    void testTelegramStartDoesNotSendGoogleMapsRoutePrompt() {
        String telegramJson = buildTelegramTextMessageJson(100, "12345678", "/start");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();
    }

    @Test
    @DisplayName("19. Web setup registration sends route submission prompt and redirects to /live")
    void testDemoADoesNotTriggerRoutePrompt() {
        viewController.setAlertService(alertService);

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("99887766")))
                .andExpect(content().string(containsString("Please send your Google Maps route link.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        Model model = new ExtendedModelMap();
        String view = viewController.startRide("Test Rider", "99887766", "11223344", 20, 30, model);

        assertEquals("redirect:/live", view);
        mockServer.verify();
    }

    @Test
    @DisplayName("20. END command clears real ride, preserves existing demo ride in DB, and resets discovery endpoint")
    void testEndCommandClearsRealRideAndResetsDiscoveryEndpointPreservingDemoA() {
        // 1. Setup existing demo ride record in DB
        Ride demoRide = new Ride(testRider);
        demoRide.setDemo(true);
        demoRide.setStatus(Ride.RideStatus.ACTIVE);
        demoRide = rideRepository.save(demoRide);
        long demoRideId = demoRide.getId();

        // 2. Setup Real ride from Google Maps
        List<Waypoint> realWaypoints = List.of(
                new Waypoint("Thrissur", 10.5276, 76.2144, 1),
                new Waypoint("Kunnamkulam", 10.6517, 76.0409, 2),
                new Waypoint("Angamaly", 10.1849, 76.3753, 3),
                new Waypoint("Munnar", 10.0889, 77.0595, 4)
        );
        stubRouteService.setStubResult(stubRouteService.createDefaultResult(realWaypoints));
        Ride realRide = rideService.createRideForRiderStrict(testRider, realWaypoints);
        long realRideId = realRide.getId();

        // 3. Discovery endpoint sees the real ride
        ResponseEntity<RideController.ActiveRideDiscoveryDto> beforeEnd =
                rideController.getActiveRealRide(testRider.getTelegramChatId());
        assertTrue(beforeEnd.getBody().active());
        assertEquals(realRideId, beforeEnd.getBody().rideId());

        // 4. Rider sends END via Telegram
        String endJson = buildTelegramTextMessageJson(200, "12345678", "END");
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(endJson, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("Ride ended and reset.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        // 5. Real ride is gone from H2, but existing demo record remains
        assertTrue(rideRepository.findById(realRideId).isEmpty(), "Real ride must be removed from H2");
        assertTrue(rideRepository.findById(demoRideId).isPresent(), "Existing demo ride record must still exist in H2");

        // 6. Discovery endpoint returns false/null (zero-state discovery)
        ResponseEntity<RideController.ActiveRideDiscoveryDto> afterEnd =
                rideController.getActiveRealRide(testRider.getTelegramChatId());
        assertFalse(afterEnd.getBody().active(), "No active real ride should be discovered");
        assertNull(afterEnd.getBody().rideId(), "Discovered rideId should be null");
    }
}
