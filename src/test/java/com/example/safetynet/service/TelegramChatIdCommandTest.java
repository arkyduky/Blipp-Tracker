package com.example.safetynet.service;

import com.example.safetynet.dto.RouteLeg;
import com.example.safetynet.dto.RouteResult;
import com.example.safetynet.dto.Waypoint;
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

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DataJpaTest
@DisplayName("Telegram 'chat id' Diagnostic Command Tests")
class TelegramChatIdCommandTest {

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    private AlertService alertService;
    private RideService rideService;
    private StubRouteService stubRouteService;
    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;

    private Rider registeredRider;

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
        stubRouteService = new StubRouteService();
        GoogleMapsRouteParser parser = new GoogleMapsRouteParser();
        rideService = new RideService(riderRepository, rideRepository, checkpointRepository, stubRouteService, parser);

        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        GeoProjectionService geoProjectionService = new GeoProjectionService();
        alertService = new AlertService(rideRepository, riderRepository, rideService, geoProjectionService, parser);
        alertService.setRestTemplate(restTemplate);
        alertService.setBotToken("mock-bot-token");

        registeredRider = new Rider();
        registeredRider.setName("Registered Rider");
        registeredRider.setTelegramChatId("123456789");
        registeredRider.setEmergencyContactChatId("987654321");
        registeredRider.setStationaryToleranceSeconds(20);
        registeredRider.setGracePeriodSeconds(30);
        riderRepository.save(registeredRider);
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
    @DisplayName("1. 'chat id' returns the sender's actual Telegram chat ID")
    void testChatIdReturnsActualSenderChatId() {
        String telegramJson = buildTelegramTextMessageJson(1, "123456789", "chat id");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("123456789")))
                .andExpect(content().string(containsString("Your Telegram Chat ID is:\\n123456789")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();
    }

    @Test
    @DisplayName("2. 'CHAT ID' works case-insensitively")
    void testChatIdCaseInsensitive() {
        String telegramJson = buildTelegramTextMessageJson(2, "123456789", "CHAT ID");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("123456789")))
                .andExpect(content().string(containsString("Your Telegram Chat ID is:\\n123456789")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        // Also verify method directly on Chat Id
        assertTrue(alertService.isChatIdCommand("Chat Id"));
        assertTrue(alertService.isChatIdCommand("CHAT ID"));
        assertTrue(alertService.isChatIdCommand("chat id"));
    }

    @Test
    @DisplayName("3. Surrounding whitespace works")
    void testChatIdSurroundingWhitespace() {
        String telegramJson = buildTelegramTextMessageJson(3, "123456789", "   chat id   ");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("123456789")))
                .andExpect(content().string(containsString("Your Telegram Chat ID is:\\n123456789")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertTrue(alertService.isChatIdCommand("  chat id  "));
        assertTrue(alertService.isChatIdCommand("\tchat id\n"));
    }

    @Test
    @DisplayName("4. An unregistered Telegram account can use it")
    void testUnregisteredTelegramAccountCanUseCommand() {
        String unregisteredChatId = "999888777";
        // Assert account is not registered
        assertFalse(riderRepository.findAll().stream()
                .anyMatch(r -> unregisteredChatId.equals(r.getTelegramChatId())));

        String telegramJson = buildTelegramTextMessageJson(4, unregisteredChatId, "chat id");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString(unregisteredChatId)))
                .andExpect(content().string(containsString("Your Telegram Chat ID is:\\n" + unregisteredChatId)))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();
    }

    @Test
    @DisplayName("5. The command does not create a Rider")
    void testCommandDoesNotCreateRider() {
        long ridersBefore = riderRepository.count();
        String unregisteredChatId = "555444333";

        String telegramJson = buildTelegramTextMessageJson(5, unregisteredChatId, "chat id");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString(unregisteredChatId)))
                .andExpect(content().string(containsString("Your Telegram Chat ID is:\\n" + unregisteredChatId)))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(ridersBefore, riderRepository.count(), "Rider count must not change");
        assertFalse(riderRepository.findAll().stream()
                .anyMatch(r -> unregisteredChatId.equals(r.getTelegramChatId())));
    }

    @Test
    @DisplayName("6. The command does not create or modify a Ride")
    void testCommandDoesNotCreateOrModifyRide() {
        // Setup an existing ride
        Ride initialRide = rideService.createRideForRiderStrict(registeredRider, List.of(
                new Waypoint("Thrissur", 10.5276, 76.2144, 1),
                new Waypoint("Kunnamkulam", 10.6517, 76.0409, 2)
        ));
        initialRide.setCurrentPosition(35.0);
        initialRide.setMoving(true);
        initialRide = rideRepository.save(initialRide);

        long ridesBefore = rideRepository.count();

        String telegramJson = buildTelegramTextMessageJson(6, "123456789", "chat id");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("123456789")))
                .andExpect(content().string(containsString("Your Telegram Chat ID is:\\n123456789")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(ridesBefore, rideRepository.count(), "Ride count must not change");
        Ride reloadedRide = rideRepository.findById(initialRide.getId()).orElseThrow();
        assertEquals(35.0, reloadedRide.getCurrentPosition(), 0.001);
        assertTrue(reloadedRide.isMoving());
        assertEquals(Ride.RideStatus.ACTIVE, reloadedRide.getStatus());
    }

    @Test
    @DisplayName("7. Unrelated text is not treated as the command")
    void testUnrelatedTextIsNotTreatedAsChatIdCommand() {
        assertFalse(alertService.isChatIdCommand("my chat id"));
        assertFalse(alertService.isChatIdCommand("what is my chat id"));
        assertFalse(alertService.isChatIdCommand("chat identifier"));
        assertFalse(alertService.isChatIdCommand("chat id please"));
        assertFalse(alertService.isChatIdCommand("chat"));
        assertFalse(alertService.isChatIdCommand("id"));
        assertFalse(alertService.isChatIdCommand(""));
        assertFalse(alertService.isChatIdCommand(null));

        // When sending "my chat id" via pollTelegramUpdates, it is NOT treated as chatIdCommand
        String telegramJson = buildTelegramTextMessageJson(7, "123456789", "my chat id");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramJson, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();
    }

    @Test
    @DisplayName("8. The existing single Telegram polling stream is still used")
    void testExistingSinglePollingStreamHandlesChatIdAndSequentialUpdates() {
        // Message 1: "chat id"
        String chatIdJson = buildTelegramTextMessageJson(10, "123456789", "chat id");

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(chatIdJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("Your Telegram Chat ID is:\\n123456789")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        // Message 2: Route submission on the exact same polling stream on next tick
        mockServer.reset();
        String routeUrl = "https://www.google.com/maps/dir/10.5276,76.2144/10.6517,76.0409";
        String routeJson = buildTelegramTextMessageJson(11, "123456789", routeUrl);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(routeJson, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("Route accepted.")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        assertEquals(1, rideRepository.count(), "Ride should be created by the subsequent route in the same stream");
    }
}
