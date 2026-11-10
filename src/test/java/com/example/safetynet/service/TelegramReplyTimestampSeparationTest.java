package com.example.safetynet.service;

import com.example.safetynet.controller.ViewController;
import com.example.safetynet.dto.RouteLeg;
import com.example.safetynet.dto.RouteResult;
import com.example.safetynet.dto.Waypoint;
import com.example.safetynet.model.Checkpoint;
import com.example.safetynet.model.Ride;
import com.example.safetynet.model.Rider;
import com.example.safetynet.repository.CheckpointRepository;
import com.example.safetynet.repository.RideRepository;
import com.example.safetynet.repository.RiderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DataJpaTest
@DisplayName("Telegram Reply Timestamp Separation Tests")
class TelegramReplyTimestampSeparationTest {

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    private AlertService alertService;
    private RideService rideService;
    private ViewController viewController;
    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Rider testRider;

    private static class StubRouteService extends RouteService {
        @Override
        public RouteResult calculateRoute(List<Waypoint> waypoints) {
            String geoJson = "{\"type\":\"LineString\",\"coordinates\":[[76.2144,10.5276],[76.0409,10.6517]]}";
            List<List<Double>> coords = List.of(List.of(76.2144, 10.5276), List.of(76.0409, 10.6517));
            List<RouteLeg> legs = List.of(new RouteLeg(25000.0, 1800.0, 0, 1));
            return new RouteResult(geoJson, coords, 25000.0, 1800.0, legs);
        }
    }

    @BeforeEach
    void setUp() {
        StubRouteService stubRouteService = new StubRouteService();
        GoogleMapsRouteParser parser = new GoogleMapsRouteParser();
        rideService = new RideService(riderRepository, rideRepository, checkpointRepository, stubRouteService, parser);
        viewController = new ViewController(rideService, rideRepository);

        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        GeoProjectionService geoProjectionService = new GeoProjectionService();
        alertService = new AlertService(rideRepository, riderRepository, rideService, geoProjectionService, parser);
        alertService.setRestTemplate(restTemplate);
        alertService.setBotToken("mock-bot-token");

        testRider = new Rider();
        testRider.setName("Test Rider");
        testRider.setTelegramChatId("12345678");
        testRider.setEmergencyContactChatId("87654321");
        testRider.setStationaryToleranceSeconds(20);
        testRider.setGracePeriodSeconds(30);
        testRider = riderRepository.save(testRider);
    }

    private String buildTelegramTextMessageJson(long updateId, String chatId, String text) {
        return "{\n" +
                "  \"ok\": true,\n" +
                "  \"result\": [\n" +
                "    {\n" +
                "      \"update_id\": " + updateId + ",\n" +
                "      \"message\": {\n" +
                "        \"message_id\": 101,\n" +
                "        \"from\": { \"id\": " + chatId + ", \"first_name\": \"User\" },\n" +
                "        \"chat\": { \"id\": " + chatId + ", \"type\": \"private\" },\n" +
                "        \"date\": " + (System.currentTimeMillis() / 1000) + ",\n" +
                "        \"text\": \"" + text + "\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    @Test
    @DisplayName("1. 'chat id' response has NO timestamp")
    void testChatIdResponseHasNoTimestamp() {
        String telegramJson = buildTelegramTextMessageJson(1, "12345678", "chat id");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertEquals("Your Telegram Chat ID is:\n12345678", text);
                    assertFalse(text.matches("^\\[\\d{4}-\\d{2}-\\d{2}.*"), "Response must NOT start with timestamp");
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();
    }

    @Test
    @DisplayName("2. Route-submission prompt has NO timestamp")
    void testRouteSubmissionPromptHasNoTimestamp() {
        // Trigger via sendRouteSubmissionPrompt directly
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertEquals("Please send your Google Maps route link.", text);
                    assertFalse(text.matches("^\\[\\d{4}-\\d{2}-\\d{2}.*"), "Prompt must NOT have timestamp");
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.sendRouteSubmissionPrompt("12345678");
        mockServer.verify();

        mockServer.reset();

        // Also trigger via entering Live Monitor for a real ride
        Ride realRide = new Ride(testRider);
        realRide.setDemo(false);
        realRide = rideRepository.save(realRide);
        viewController.setAlertService(alertService);

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertEquals("Please send your Google Maps route link.", text);
                    assertFalse(text.matches("^\\[\\d{4}-\\d{2}-\\d{2}.*"), "Live monitor prompt must NOT have timestamp");
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        Model model = new ExtendedModelMap();
        viewController.showLocationPage(realRide.getId(), model);
        mockServer.verify();
    }

    @Test
    @DisplayName("3. START confirmation has NO timestamp")
    void testStartConfirmationHasNoTimestamp() {
        // Test standard operational START message sent via sendTo
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertEquals("Ride started. Please share your Live Location.", text);
                    assertFalse(text.matches("^\\[\\d{4}-\\d{2}-\\d{2}.*"), "Start confirmation must NOT have timestamp");
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.sendTo("12345678", "Ride started. Please share your Live Location.");
        mockServer.verify();
    }

    @Test
    @DisplayName("4. END confirmation has NO timestamp")
    void testEndConfirmationHasNoTimestamp() {
        // Step 1: Create active real ride
        Ride realRide = rideService.createRideForRiderStrict(testRider, List.of(
                new Waypoint("Thrissur", 10.5276, 76.2144, 1),
                new Waypoint("Kunnamkulam", 10.6517, 76.0409, 2)
        ));

        // Step 2: Rider sends END
        String endJson = buildTelegramTextMessageJson(4, "12345678", "END");
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(endJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertEquals("Ride ended and reset. You can now send a new Google Maps route link.", text);
                    assertFalse(text.matches("^\\[\\d{4}-\\d{2}-\\d{2}.*"), "END confirmation must NOT have timestamp");
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        mockServer.reset();

        // Step 3: Rider sends /end when no ride exists
        String noRideEndJson = buildTelegramTextMessageJson(5, "12345678", "/end");
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(noRideEndJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertEquals("No active ride found. Please send your Google Maps route link.", text);
                    assertFalse(text.matches("^\\[\\d{4}-\\d{2}-\\d{2}.*"), "No active ride message must NOT have timestamp");
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();
    }

    @Test
    @DisplayName("5. Route-accepted confirmation has NO timestamp")
    void testRouteAcceptedConfirmationHasNoTimestamp() {
        String routeUrl = "https://www.google.com/maps/dir/10.5276,76.2144/10.6517,76.0409";
        String routeJson = buildTelegramTextMessageJson(6, "12345678", routeUrl);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(routeJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertTrue(text.startsWith("Route accepted."), "Must start with Route accepted.");
                    assertFalse(text.matches("^\\[\\d{4}-\\d{2}-\\d{2}.*"), "Route accepted confirmation must NOT have timestamp");
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();
    }

    @Test
    @DisplayName("6. Actual checkpoint/safety alert retains its timestamp")
    void testActualCheckpointSafetyAlertRetainsTimestamp() {
        Ride ride = rideService.createRideForRiderStrict(testRider, List.of(
                new Waypoint("Thrissur", 10.5276, 76.2144, 1),
                new Waypoint("Kunnamkulam", 10.6517, 76.0409, 2)
        ));
        Checkpoint cp = checkpointRepository.findByRideOrderBySequenceNumberAsc(ride).get(0);

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertTrue(text.matches("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\] .*"),
                            "Safety alert must retain [YYYY-MM-DD HH:mm:ss] timestamp prefix");
                    assertTrue(text.contains("passed checkpoint 1 of 3, on schedule."));
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.sendPassiveUpdate(ride, cp);
        mockServer.verify();
    }

    @Test
    @DisplayName("7. Alert formatting contains timestamp and no DEMO prefix")
    void testExistingAlertFormattingOtherwiseUnchanged() {
        Ride realRide = new Ride(testRider);
        realRide.setDemo(false);
        realRide.setStatus(Ride.RideStatus.ACTIVE);
        realRide = rideRepository.save(realRide);
        Checkpoint cp = new Checkpoint(realRide, 1, 33.3, java.time.Instant.now().plusSeconds(600));
        cp = checkpointRepository.save(cp);

        // 1. sendPassiveUpdate
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertTrue(text.matches("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\] Test Rider passed checkpoint 1 of 3, on schedule.$"));
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.sendPassiveUpdate(realRide, cp);
        mockServer.verify();

        mockServer.reset();

        // 2. sendConfirmationPrompt
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertTrue(text.matches("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\] Checkpoint 1 — you've been stationary a while\\. Reply OK or tell us why\\.$"));
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.sendConfirmationPrompt(realRide, cp);
        mockServer.verify();

        mockServer.reset();

        // 3. sendResolvedUpdate
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertTrue(text.matches("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\] Test Rider delayed at checkpoint 1, confirmed fine: Still rolling$"));
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.sendResolvedUpdate(realRide, cp, "Still rolling");
        mockServer.verify();

        mockServer.reset();

        // 4. sendEmergencyAlert
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    JsonNode node = objectMapper.readTree(request.getBody().toString());
                    String text = node.path("text").asText();
                    assertTrue(text.matches("^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\] ALERT: Test Rider has not responded at checkpoint 1 and has been stationary for a while\\.$"));
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        alertService.sendEmergencyAlert(realRide, cp);
        mockServer.verify();
    }
}
