package com.example.safetynet.service;

import com.example.safetynet.controller.RideController;
import com.example.safetynet.controller.ViewController;
import com.example.safetynet.dto.RideLiveStatusDto;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DataJpaTest
@DisplayName("Live Monitor Route Prompt Tests")
class LiveMonitorRoutePromptTest {

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    private RideService rideService;
    private AlertService alertService;
    private ViewController viewController;
    private RideController rideController;

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;

    private Rider testRider;

    private static final String SAMPLE_ROUTE_URL = "https://www.google.com/maps/dir/10.5276,76.2144/10.0889,77.0595";

    private static class StubRouteService extends RouteService {
        @Override
        public RouteResult calculateRoute(List<Waypoint> waypoints) {
            String geoJson = "{\"type\":\"LineString\",\"coordinates\":[[76.2144,10.5276],[77.0595,10.0889]]}";
            List<List<Double>> coords = List.of(List.of(76.2144, 10.5276), List.of(77.0595, 10.0889));
            List<RouteLeg> legs = List.of(new RouteLeg(25000.0, 1800.0, 0, 1));
            return new RouteResult(geoJson, coords, 25000.0, 1800.0, legs);
        }
    }

    @BeforeEach
    void setUp() {
        StubRouteService stubRouteService = new StubRouteService();
        GoogleMapsRouteParser parser = new GoogleMapsRouteParser();
        GeoProjectionService geoProjectionService = new GeoProjectionService();

        rideService = new RideService(riderRepository, rideRepository, checkpointRepository, stubRouteService, parser);
        alertService = new AlertService(rideRepository, riderRepository, rideService, geoProjectionService, parser, checkpointRepository);

        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        alertService.setRestTemplate(restTemplate);
        alertService.setBotToken("mock-bot-token");

        viewController = new ViewController(rideService, rideRepository, alertService);
        rideController = new RideController(rideRepository, checkpointRepository, riderRepository, alertService);

        testRider = new Rider();
        testRider.setName("Amit Rider");
        testRider.setTelegramChatId("55512345");
        testRider.setEmergencyContactChatId("55598765");
        testRider.setStationaryToleranceSeconds(25);
        testRider.setGracePeriodSeconds(40);
        testRider = riderRepository.save(testRider);
    }

    private String buildTelegramTextMessageJson(long updateId, String chatId, String text) {
        return "{\n" +
                "  \"ok\": true,\n" +
                "  \"result\": [\n" +
                "    {\n" +
                "      \"update_id\": " + updateId + ",\n" +
                "      \"message\": {\n" +
                "        \"message_id\": 200,\n" +
                "        \"from\": { \"id\": " + chatId + ", \"first_name\": \"User\" },\n" +
                "        \"chat\": { \"id\": " + chatId + ", \"type\": \"private\" },\n" +
                "        \"date\": " + (System.currentTimeMillis() / 1000) + ",\n" +
                "        \"text\": \"" + text + "\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    private String buildTelegramLocationUpdateJson(long updateId, String chatId, double lat, double lng, long epochSeconds) {
        return "{\n" +
                "  \"ok\": true,\n" +
                "  \"result\": [\n" +
                "    {\n" +
                "      \"update_id\": " + updateId + ",\n" +
                "      \"message\": {\n" +
                "        \"message_id\": 201,\n" +
                "        \"from\": { \"id\": " + chatId + ", \"first_name\": \"User\" },\n" +
                "        \"chat\": { \"id\": " + chatId + ", \"type\": \"private\" },\n" +
                "        \"date\": " + epochSeconds + ",\n" +
                "        \"location\": {\n" +
                "          \"latitude\": " + lat + ",\n" +
                "          \"longitude\": " + lng + ",\n" +
                "          \"live_period\": 1800\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    @Test
    @DisplayName("1. Telegram /start does NOT send the Google Maps route prompt")
    void testTelegramStartDoesNotSendGoogleMapsRoutePrompt() {
        String telegramJson = buildTelegramTextMessageJson(1, "55512345", "/start");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        // Note: No sendMessage expectation! /start must NOT send route prompt
        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(0, rideRepository.count(), "No ride should be created from /start");
    }

    @Test
    @DisplayName("2. Entering Live Monitor for a real ride sends the route prompt to the rider")
    void testEnteringLiveMonitorForRealRideSendsRoutePrompt() {
        Ride realRide = new Ride(testRider);
        realRide.setDemo(false);
        realRide = rideRepository.save(realRide);

        assertFalse(realRide.isRoutePromptSent(), "routePromptSent must initially be false");

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("55512345")))
                .andExpect(content().string(containsString("Please send your Google Maps route link.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        Model model = new ExtendedModelMap();
        String view = viewController.showLocationPage(realRide.getId(), model);

        assertEquals("location", view);
        mockServer.verify();

        Ride reloadedRide = rideRepository.findById(realRide.getId()).orElseThrow();
        assertTrue(reloadedRide.isRoutePromptSent(), "routePromptSent must be true after entering Live Monitor");
    }

    @Test
    @DisplayName("3. Repeated Live Monitor polling does NOT send duplicate prompts")
    void testRepeatedLiveMonitorPollingDoesNotSendDuplicatePrompts() {
        Ride realRide = new Ride(testRider);
        realRide.setDemo(false);
        realRide = rideRepository.save(realRide);

        // 1. First entry sends the prompt once
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("55512345")))
                .andExpect(content().string(containsString("Please send your Google Maps route link.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        Model model = new ExtendedModelMap();
        viewController.showLocationPage(realRide.getId(), model);
        mockServer.verify();

        mockServer.reset();

        // 2. Page refresh (GET /ride/{id}/live) does NOT send another prompt
        viewController.showLocationPage(realRide.getId(), model);

        // 3. Repeated live status polling (GET /ride/{id}/live-status) does NOT send duplicate prompts
        for (int i = 0; i < 5; i++) {
            RideLiveStatusDto dto = rideController.getLiveStatus(realRide.getId());
            assertNotNull(dto);
        }

        // 4. Standalone discovery polling (GET /ride/active) does NOT send duplicate prompts
        for (int i = 0; i < 5; i++) {
            ResponseEntity<RideController.ActiveRideDiscoveryDto> discovery = rideController.getActiveRealRide(null);
            assertTrue(discovery.getBody().active());
        }

        // 5. Standalone monitor page (GET /live) does NOT send duplicate prompts
        viewController.showStandaloneLiveMonitor(new ExtendedModelMap());

        // Verify zero additional sendMessage requests were made
        mockServer.verify();
    }

    @Test
    @DisplayName("4. Web setup /start registers rider and redirects to /live")
    void testWebSetupRegistration() {
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("55512345")))
                .andExpect(content().string(containsString("Please send your Google Maps route link.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        Model model = new ExtendedModelMap();
        String setupView = viewController.startRide("Test User", "55512345", "55598765", 25, 40, model);
        assertEquals("redirect:/live", setupView);

        Rider rider = riderRepository.findAll().stream()
                .filter(r -> "55512345".equals(r.getTelegramChatId()))
                .findFirst().orElseThrow();
        assertEquals("Test User", rider.getName());
        assertEquals(25, rider.getStationaryToleranceSeconds());
        assertEquals(40, rider.getGracePeriodSeconds());

        mockServer.verify();
    }

    @Test
    @DisplayName("5. Existing Telegram route submission still works")
    void testExistingTelegramRouteSubmissionStillWorks() {
        String telegramJson = buildTelegramTextMessageJson(5, "55512345", SAMPLE_ROUTE_URL);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("55512345")))
                .andExpect(content().string(containsString("Route accepted.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride createdRide = rideRepository.findAll().stream().filter(r -> !r.isDemo()).findFirst().orElseThrow();
        assertNotNull(createdRide.getRouteGeometry());
        List<Checkpoint> checkpoints = checkpointRepository.findByRideOrderBySequenceNumberAsc(createdRide);
        assertFalse(checkpoints.isEmpty());
    }

    @Test
    @DisplayName("6. Existing real-ride lifecycle remains intact")
    void testExistingRealRideLifecycleRemainsIntact() {
        // Step 1: Submit route via Telegram
        String routeJson = buildTelegramTextMessageJson(61, "55512345", SAMPLE_ROUTE_URL);
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(routeJson, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("Route accepted.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.pollTelegramUpdates();
        mockServer.verify();

        mockServer.reset();

        Ride ride = rideRepository.findAll().stream().filter(r -> !r.isDemo()).findFirst().orElseThrow();
        assertEquals(Ride.RideStatus.ACTIVE, ride.getStatus());

        // Step 2: Parent enters Live Monitor
        Model model = new ExtendedModelMap();
        String view = viewController.showLocationPage(ride.getId(), model);
        assertEquals("location", view);

        // Step 3: Rider streams live GPS location
        long now = System.currentTimeMillis() / 1000;
        String gpsJson = buildTelegramLocationUpdateJson(62, "55512345", 10.5276, 76.2144, now);
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(gpsJson, MediaType.APPLICATION_JSON));
        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(ride.getId()).orElseThrow();
        assertEquals(10.5276, updatedRide.getLastKnownLatitude(), 1e-4);
        assertEquals(76.2144, updatedRide.getLastKnownLongitude(), 1e-4);

        mockServer.reset();

        // Step 4: END command ends and resets the real ride
        String endJson = buildTelegramTextMessageJson(63, "55512345", "END");
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(endJson, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("Ride ended and reset.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.pollTelegramUpdates();
        mockServer.verify();

        // Real ride is deleted on reset
        long realRidesCount = rideRepository.findAll().stream().filter(r -> !r.isDemo()).count();
        assertEquals(0, realRidesCount, "Real ride should be cleared on END");
    }
}
