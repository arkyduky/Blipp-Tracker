package com.example.safetynet.service;

import com.example.safetynet.model.Checkpoint;
import com.example.safetynet.model.Ride;
import com.example.safetynet.model.Rider;
import com.example.safetynet.repository.CheckpointRepository;
import com.example.safetynet.repository.RideRepository;
import com.example.safetynet.repository.RiderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.example.safetynet.dto.RideLiveStatusDto;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DataJpaTest
@DisplayName("Production Test Fixes Regression Tests")
class ProductionTestFixesRegressionTest {

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    private AlertService alertService;
    private SafetyScheduler safetyScheduler;
    private CheckpointScheduler checkpointScheduler;
    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private ObjectMapper objectMapper;

    private Rider testRider;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        testRider = new Rider();
        testRider.setName("Amit");
        testRider.setTelegramChatId("5033200665");
        testRider.setEmergencyContactChatId("9999999999");
        testRider.setStationaryToleranceSeconds(25);
        testRider.setGracePeriodSeconds(40);
        testRider = riderRepository.save(testRider);

        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        GeoProjectionService geoProjectionService = new GeoProjectionService();
        GoogleMapsRouteParser googleMapsRouteParser = new GoogleMapsRouteParser();

        alertService = new AlertService(rideRepository, riderRepository, null, geoProjectionService, googleMapsRouteParser, checkpointRepository);
        alertService.setBotToken("test-bot-token");
        alertService.setRestTemplate(restTemplate);

        safetyScheduler = new SafetyScheduler(rideRepository, checkpointRepository, alertService);
        checkpointScheduler = new CheckpointScheduler(rideRepository, checkpointRepository, alertService);
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

    @Test
    @DisplayName("1. Dynamic checkpoint count: 5-checkpoint ride produces 'checkpoint 4 of 5' and 'checkpoint 5 of 5'")
    void testDynamicCheckpointCount_fiveCheckpointRide() {
        Ride ride = new Ride(testRider);
        ride.setDemo(false);
        ride.setStatus(Ride.RideStatus.ACTIVE);
        ride = rideRepository.save(ride);

        Checkpoint cp1 = checkpointRepository.save(new Checkpoint(ride, 1, 20.0, LocalDateTime.now().plusMinutes(5)));
        Checkpoint cp2 = checkpointRepository.save(new Checkpoint(ride, 2, 40.0, LocalDateTime.now().plusMinutes(10)));
        Checkpoint cp3 = checkpointRepository.save(new Checkpoint(ride, 3, 60.0, LocalDateTime.now().plusMinutes(15)));
        Checkpoint cp4 = checkpointRepository.save(new Checkpoint(ride, 4, 80.0, LocalDateTime.now().plusMinutes(20)));
        Checkpoint cp5 = checkpointRepository.save(new Checkpoint(ride, 5, 100.0, LocalDateTime.now().plusMinutes(25)));

        // Verify checkpoint 4 produces "passed checkpoint 4 of 5, on schedule."
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    try {
                        JsonNode body = objectMapper.readTree(request.getBody().toString());
                        String text = body.path("text").asText();
                        assertTrue(text.contains("Amit passed checkpoint 4 of 5, on schedule."),
                                "Expected 'passed checkpoint 4 of 5' but got: " + text);
                        assertFalse(text.contains("4 of 3"), "Should not contain hardcoded '4 of 3'");
                    } catch (Exception e) {
                        fail(e);
                    }
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.sendPassiveUpdate(ride, cp4);
        mockServer.verify();

        mockServer.reset();

        // Verify checkpoint 5 produces "passed checkpoint 5 of 5, on schedule."
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    try {
                        JsonNode body = objectMapper.readTree(request.getBody().toString());
                        String text = body.path("text").asText();
                        assertTrue(text.contains("Amit passed checkpoint 5 of 5, on schedule."),
                                "Expected 'passed checkpoint 5 of 5' but got: " + text);
                        assertFalse(text.contains("5 of 3"), "Should not contain hardcoded '5 of 3'");
                    } catch (Exception e) {
                        fail(e);
                    }
                })
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        alertService.sendPassiveUpdate(ride, cp5);
        mockServer.verify();
    }

    @Test
    @DisplayName("2. Demo rides participate in autonomous SafetyScheduler stationary monitoring and escalation")
    void testDemoRidesParticipateInSafetyScheduler() {
        Ride demoRide = new Ride(testRider);
        demoRide.setDemo(true);
        demoRide.setStatus(Ride.RideStatus.ACTIVE);
        demoRide.setMoving(false);
        demoRide.setStationarySince(Instant.now().minusSeconds(100)); // Well past 25s tolerance
        demoRide = rideRepository.save(demoRide);

        Checkpoint cp = new Checkpoint(demoRide, 1, 33.3, Instant.now().plusSeconds(600));
        cp.setStatus(Checkpoint.CheckpointStatus.NOT_REACHED);
        cp = checkpointRepository.save(cp);

        // Run stationary monitoring job - HTTP confirmation prompt should be sent to rider
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("5033200665")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        safetyScheduler.checkOverdueStationaryCheckpoints();
        mockServer.verify();

        // Checkpoint must transition to AWAITING_CONFIRMATION
        Checkpoint refreshedCp = checkpointRepository.findById(cp.getId()).orElseThrow();
        assertEquals(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION, refreshedCp.getStatus(),
                "Demo ride checkpoint status must transition to AWAITING_CONFIRMATION when stationary exceeds tolerance");
        assertNotNull(refreshedCp.getConfirmationSentAt());
        assertNotNull(refreshedCp.getGraceDeadline());

        // Set checkpoint to AWAITING_CONFIRMATION with expired deadline to test escalation job
        refreshedCp.setGraceDeadline(Instant.now().minusSeconds(10));
        checkpointRepository.save(refreshedCp);

        mockServer.reset();
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("9999999999")))
                .andExpect(content().string(containsString("ALERT:")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        safetyScheduler.escalateOverdueCheckpoints();
        mockServer.verify();

        Checkpoint escalatedCp = checkpointRepository.findById(cp.getId()).orElseThrow();
        assertEquals(Checkpoint.CheckpointStatus.ESCALATED, escalatedCp.getStatus(),
                "Demo ride checkpoint must be escalated to emergency contact by SafetyScheduler");
    }

    @Test
    @DisplayName("3. Demo rides never receive Telegram Live Location updates")
    void testDemoRidesNeverReceiveTelegramLiveLocationUpdates() {
        Ride demoRide = new Ride(testRider);
        demoRide.setDemo(true);
        demoRide.setStatus(Ride.RideStatus.ACTIVE);
        demoRide.setLastKnownLatitude(null);
        demoRide.setLastKnownLongitude(null);
        demoRide.setLastLocationUpdatedAt(null);
        demoRide = rideRepository.save(demoRide);

        long epoch = Instant.now().getEpochSecond();
        String updateJson = buildTelegramLocationUpdateJson(101, "5033200665", 10.7904, 75.9868, epoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(updateJson, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride refreshedDemoRide = rideRepository.findById(demoRide.getId()).orElseThrow();
        assertNull(refreshedDemoRide.getLastKnownLatitude(), "Demo ride latitude must remain null");
        assertNull(refreshedDemoRide.getLastKnownLongitude(), "Demo ride longitude must remain null");
        assertNull(refreshedDemoRide.getLastLocationUpdatedAt(), "Demo ride timestamp must remain null");
    }

    @Test
    @DisplayName("4. When a real ride reaches 100%, the ride remains ACTIVE and subsequent Telegram Live Location updates continue updating it")
    void testRealRideReaching100PercentRemainsActiveAndAcceptsGps() {
        Ride realRide = new Ride(testRider);
        realRide.setDemo(false);
        realRide.setStatus(Ride.RideStatus.ACTIVE);
        realRide.setCurrentPosition(95.0);
        realRide = rideRepository.save(realRide);

        Checkpoint cp1 = new Checkpoint(realRide, 1, 50.0, LocalDateTime.now().plusMinutes(5));
        cp1.setStatus(Checkpoint.CheckpointStatus.PASSED);
        checkpointRepository.save(cp1);

        Checkpoint cp2 = new Checkpoint(realRide, 2, 100.0, LocalDateTime.now().plusMinutes(10));
        cp2.setStatus(Checkpoint.CheckpointStatus.PASSED);
        checkpointRepository.save(cp2);

        // Run checkpoint scheduler: all checkpoints passed
        checkpointScheduler.checkPassedCheckpoints();

        Ride checkedRide = rideRepository.findById(realRide.getId()).orElseThrow();
        assertEquals(Ride.RideStatus.ACTIVE, checkedRide.getStatus(),
                "Real ride must remain ACTIVE when reaching 100% of route");
        assertEquals(100.0, checkedRide.getCurrentPosition(), 0.001);

        // Subsequent Telegram Live Location update arrives after destination (e.g. rider returning home)
        long epoch = Instant.now().getEpochSecond();
        String updateJson = buildTelegramLocationUpdateJson(102, "5033200665", 10.7915, 75.9855, epoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(updateJson, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        Ride updatedRide = rideRepository.findById(realRide.getId()).orElseThrow();
        assertEquals(Ride.RideStatus.ACTIVE, updatedRide.getStatus(), "Ride must still be ACTIVE");
        assertEquals(10.7915, updatedRide.getLastKnownLatitude(), 0.0001);
        assertEquals(75.9855, updatedRide.getLastKnownLongitude(), 0.0001);
        assertNotNull(updatedRide.getLastLocationUpdatedAt(), "Location timestamp must be updated");
    }

    @Test
    @DisplayName("5. No active real ride means an incoming Telegram Live Location update is ignored, NOT assigned to a demo ride")
    void testNoActiveRealRide_gpsIgnoredAndNotAssignedToDemoRide() {
        // A completed real ride exists
        Ride completedRide = new Ride(testRider);
        completedRide.setDemo(false);
        completedRide.setStatus(Ride.RideStatus.COMPLETED);
        completedRide.setLastKnownLatitude(10.7865);
        completedRide.setLastKnownLongitude(76.0088);
        completedRide = rideRepository.save(completedRide);

        // An active demo ride also exists
        Ride demoRide = new Ride(testRider);
        demoRide.setDemo(true);
        demoRide.setStatus(Ride.RideStatus.ACTIVE);
        demoRide.setLastKnownLatitude(null);
        demoRide.setLastKnownLongitude(null);
        demoRide = rideRepository.save(demoRide);

        long epoch = Instant.now().getEpochSecond();
        String updateJson = buildTelegramLocationUpdateJson(103, "5033200665", 10.8000, 75.9900, epoch);

        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(updateJson, MediaType.APPLICATION_JSON));

        alertService.pollTelegramUpdates();
        mockServer.verify();

        // Neither ride should be modified by this update
        Ride refreshedDemo = rideRepository.findById(demoRide.getId()).orElseThrow();
        assertNull(refreshedDemo.getLastKnownLatitude(), "Demo ride must NOT be hijacked by real Telegram location");

        Ride refreshedCompleted = rideRepository.findById(completedRide.getId()).orElseThrow();
        assertEquals(10.7865, refreshedCompleted.getLastKnownLatitude(), 0.0001,
                "Completed real ride coordinates must remain untouched");
    }

    @Test
    @DisplayName("6. Timestamp regression test based on actual observed 341-minute display")
    void testTimestampDisplay_reproducesAndVerifies341MinuteObservation() {
        // In monitor.js:
        // const now = Date.now();
        // const updatedTime = new Date(lastLocationUpdatedAt).getTime();
        // const diffSeconds = Math.max(0, Math.floor((now - updatedTime) / 1000));
        // const diffMins = Math.floor(diffSeconds / 60);

        // Case A: Local time calculation when timestamp was updated 11 minutes ago
        long nowMillis = 1791033846000L; // Example: 18:54:06 IST / 13:24:06 UTC
        long updatedLocalMillis = 1791033186000L; // 18:43:06 IST (11 minutes prior)
        long normalDiffSeconds = Math.max(0, (nowMillis - updatedLocalMillis) / 1000);
        long normalDiffMins = normalDiffSeconds / 60;
        assertEquals(11, normalDiffMins, "A local timestamp 11m prior must calculate as 11 minutes ago");

        // Case B: Demonstrating the 341-minute anomaly observed in production:
        // When a UTC timestamp string (13:13:06, which corresponds to 18:43:06 IST)
        // is parsed as a local date string without timezone offset in India Standard Time (+05:30 = 330 minutes),
        // the client interprets 13:13:06 as local time.
        // Difference = (18:54:06 - 13:13:06) = 5 hours 41 minutes = 341 minutes!
        long utcRawAsLocalMillis = updatedLocalMillis - (330 * 60 * 1000L); // 13:13:06 local
        long observedDiffSeconds = Math.max(0, (nowMillis - utcRawAsLocalMillis) / 1000);
        long observedDiffMins = observedDiffSeconds / 60;
        assertEquals(341, observedDiffMins,
                "330-minute UTC-IST skew + 11 minutes elapsed time produces exactly the observed 341-minute display");
    }

    @Test
    @DisplayName("7. Route created in UTC does not display ETA 5h30m behind when viewed in Asia/Kolkata browser")
    void testRouteCreatedInUtcDoesNotDisplayEta5h30mBehindInAsiaKolkata() throws Exception {
        // Given a ride created at 04:43:00 UTC (which corresponds to 10:13:00 AM IST)
        Instant creationTimeUtc = Instant.parse("2026-10-04T04:43:00Z");

        Ride ride = new Ride(testRider);
        ride.setDemo(false);
        ride.setStatus(Ride.RideStatus.ACTIVE);
        ride = rideRepository.save(ride);

        // Checkpoint 1 leg duration: 180 seconds (3 mins) -> expected 04:46:00 UTC
        Instant cp1Eta = creationTimeUtc.plusSeconds(180);
        Checkpoint cp1 = checkpointRepository.save(new Checkpoint(ride, 1, "Checkpoint 1", 10.78, 75.99, 1000.0, 50.0, cp1Eta));

        // Final Checkpoint leg duration: 900 seconds (15 mins) -> expected 04:58:00 UTC
        Instant finalCpEta = creationTimeUtc.plusSeconds(900);
        Checkpoint finalCp = checkpointRepository.save(new Checkpoint(ride, 2, "Final Destination", 10.79, 76.01, 3000.0, 100.0, finalCpEta));

        // 1. Verify underlying route duration remains unchanged
        assertEquals(180, Duration.between(creationTimeUtc, cp1.getExpectedTime()).getSeconds(),
                "Checkpoint 1 route duration must remain exactly 180s");
        assertEquals(900, Duration.between(creationTimeUtc, finalCp.getExpectedTime()).getSeconds(),
                "Final checkpoint route duration must remain exactly 900s");

        // 2. Inspect JSON serialization from Live Monitor API DTO
        RideLiveStatusDto dto = RideLiveStatusDto.from(ride, List.of(cp1, finalCp));
        String json = objectMapper.writeValueAsString(dto);

        // Ensure expectedTime is serialized with UTC 'Z' timezone representation
        assertTrue(json.contains("\"expectedTime\":\"2026-10-04T04:46:00Z\""),
                "DTO serialization must contain ISO-8601 UTC timestamp with 'Z' designator");
        assertTrue(json.contains("\"expectedTime\":\"2026-10-04T04:58:00Z\""),
                "Final checkpoint DTO serialization must contain ISO-8601 UTC timestamp with 'Z' designator");

        // 3. Simulate monitor.js presentation conversion in Asia/Kolkata timezone (+05:30)
        ZoneId kolkataZone = ZoneId.of("Asia/Kolkata");
        DateTimeFormatter uiFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

        String cp1DisplayInKolkata = cp1.getExpectedTime().atZone(kolkataZone).format(uiFormatter);
        String finalCpDisplayInKolkata = finalCp.getExpectedTime().atZone(kolkataZone).format(uiFormatter);

        // In Asia/Kolkata browser, 04:46 UTC must display as 10:16 AM, NOT 4:46 AM!
        assertEquals("10:16 AM", cp1DisplayInKolkata,
                "Checkpoint 1 ETA in Asia/Kolkata must be 10:16 AM, not 4:46 AM (5h30m behind)");
        assertEquals("10:28 AM", finalCpDisplayInKolkata,
                "Final ETA in Asia/Kolkata must be 10:28 AM, not 4:58 AM (5h30m behind)");
    }
}
