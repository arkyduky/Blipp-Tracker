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
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DataJpaTest
@DisplayName("Demo A Stationary Safety Pipeline Regression Tests")
class DemoAStationarySafetyTest {

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    private AlertService alertService;
    private SafetyScheduler safetyScheduler;
    private RideService rideService;
    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;

    private Rider demoRider;
    private Rider realRider;

    @BeforeEach
    void setUp() {
        demoRider = new Rider();
        demoRider.setName("Amit");
        demoRider.setTelegramChatId("5033200665");
        demoRider.setEmergencyContactChatId("9999999999");
        demoRider.setStationaryToleranceSeconds(25);
        demoRider.setGracePeriodSeconds(40);
        demoRider = riderRepository.save(demoRider);

        realRider = new Rider();
        realRider.setName("RealRider");
        realRider.setTelegramChatId("12345678");
        realRider.setEmergencyContactChatId("87654321");
        realRider.setStationaryToleranceSeconds(30);
        realRider.setGracePeriodSeconds(60);
        realRider = riderRepository.save(realRider);

        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        GeoProjectionService geoProjectionService = new GeoProjectionService();
        GoogleMapsRouteParser googleMapsRouteParser = new GoogleMapsRouteParser();

        rideService = new RideService(riderRepository, rideRepository, checkpointRepository, null, googleMapsRouteParser);
        alertService = new AlertService(rideRepository, riderRepository, rideService, geoProjectionService, googleMapsRouteParser, checkpointRepository);
        alertService.setBotToken("test-bot-token");
        alertService.setRestTemplate(restTemplate);

        safetyScheduler = new SafetyScheduler(rideRepository, checkpointRepository, alertService);
    }

    private String buildTelegramTextUpdateJson(long updateId, String chatId, String text, long epochSecond) {
        return "{\n" +
                "  \"ok\": true,\n" +
                "  \"result\": [\n" +
                "    {\n" +
                "      \"update_id\": " + updateId + ",\n" +
                "      \"message\": {\n" +
                "        \"message_id\": 201,\n" +
                "        \"from\": { \"id\": " + chatId + ", \"first_name\": \"Amit\" },\n" +
                "        \"chat\": { \"id\": " + chatId + ", \"type\": \"private\" },\n" +
                "        \"date\": " + epochSecond + ",\n" +
                "        \"text\": \"" + text + "\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
    }

    @Test
    @DisplayName("1. Real stationary ride exceeding tolerance triggers sendConfirmationPrompt() and transitions checkpoint to AWAITING_CONFIRMATION")
    void testDemoAStationaryExceedingTolerance_triggersConfirmationPrompt() {
        Ride ride = new Ride(demoRider);
        ride.setDemo(false);
        ride.setCurrentPosition(43.0);
        ride.setMoving(false);
        ride.setStationarySince(Instant.now().minusSeconds(35)); // > 25s tolerance
        ride = rideRepository.save(ride);

        Checkpoint cp1 = new Checkpoint(ride, 1, 20.0, Instant.now().plusSeconds(600));
        cp1.setStatus(Checkpoint.CheckpointStatus.PASSED);
        checkpointRepository.save(cp1);

        Checkpoint cp2 = new Checkpoint(ride, 2, 60.0, Instant.now().plusSeconds(1200));
        cp2.setStatus(Checkpoint.CheckpointStatus.NOT_REACHED);
        checkpointRepository.save(cp2);

        // Expect Telegram prompt to rider (5033200665) without (DEMO) prefix
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("5033200665")))
                .andExpect(content().string(not(containsString("(DEMO)"))))
                .andExpect(content().string(containsString("Checkpoint 2")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        safetyScheduler.checkOverdueStationaryCheckpoints();
        mockServer.verify();

        Checkpoint refreshedCp2 = checkpointRepository.findById(cp2.getId()).orElseThrow();
        assertEquals(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION, refreshedCp2.getStatus());
        assertNotNull(refreshedCp2.getConfirmationSentAt());
        assertNotNull(refreshedCp2.getGraceDeadline());
    }

    @Test
    @DisplayName("2. Real ride reply handling resolves prompt and notifies emergency contact")
    void testDemoAReplyHandling_resolvesPromptAndNotifiesEmergencyContact() {
        Ride ride = new Ride(demoRider);
        ride.setDemo(false);
        ride.setCurrentPosition(43.0);
        ride.setMoving(false);
        ride.setStationarySince(Instant.now().minusSeconds(50));
        ride = rideRepository.save(ride);

        Checkpoint cp2 = new Checkpoint(ride, 2, 60.0, Instant.now().plusSeconds(1200));
        cp2.setStatus(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION);
        cp2.setConfirmationSentAt(Instant.now().minusSeconds(10));
        cp2.setGraceDeadline(Instant.now().plusSeconds(30));
        cp2 = checkpointRepository.save(cp2);

        // Simulate rider replying "I am fine"
        long replyEpoch = Instant.now().getEpochSecond();
        String telegramUpdate = buildTelegramTextUpdateJson(501, "5033200665", "I am fine", replyEpoch);

        // 1. getUpdates during pollForReplies
        mockServer.expect(requestTo(containsString("getUpdates")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(telegramUpdate, MediaType.APPLICATION_JSON));

        // 2. sendResolvedUpdate to emergency contact (9999999999) without (DEMO)
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("9999999999")))
                .andExpect(content().string(not(containsString("(DEMO)"))))
                .andExpect(content().string(containsString("confirmed fine: I am fine")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        safetyScheduler.pollForReplies();
        mockServer.verify();

        Checkpoint resolvedCp = checkpointRepository.findById(cp2.getId()).orElseThrow();
        assertEquals(Checkpoint.CheckpointStatus.NOT_REACHED, resolvedCp.getStatus(),
                "Checkpoint must revert to NOT_REACHED when prompt is resolved");
        assertNull(resolvedCp.getConfirmationSentAt());
        assertNull(resolvedCp.getGraceDeadline());

        Ride refreshedRide = rideRepository.findById(ride.getId()).orElseThrow();
        assertNull(refreshedRide.getStationarySince(), "Stationary clock must reset to null on reply resolution");
    }

    @Test
    @DisplayName("3. Real ride resuming movement clears prompt without sending alert")
    void testDemoAResumingMovement_clearsPromptSilently() {
        Ride ride = new Ride(demoRider);
        ride.setDemo(false);
        ride.setCurrentPosition(45.0);
        ride.setMoving(true); // Rider resumed moving!
        ride = rideRepository.save(ride);

        Checkpoint cp2 = new Checkpoint(ride, 2, 60.0, Instant.now().plusSeconds(1200));
        cp2.setStatus(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION);
        cp2.setConfirmationSentAt(Instant.now().minusSeconds(10));
        cp2.setGraceDeadline(Instant.now().plusSeconds(30));
        cp2 = checkpointRepository.save(cp2);

        // No Telegram sendMessage calls expected
        safetyScheduler.pollForReplies();

        Checkpoint clearedCp = checkpointRepository.findById(cp2.getId()).orElseThrow();
        assertEquals(Checkpoint.CheckpointStatus.NOT_REACHED, clearedCp.getStatus(),
                "Checkpoint prompt must be cancelled silently when motion resumes");
        assertNull(clearedCp.getConfirmationSentAt());
        assertNull(clearedCp.getGraceDeadline());
    }

    @Test
    @DisplayName("4. Real ride with no reply reaches escalation and sends emergency alert to parent/emergency contact")
    void testDemoANoReply_escalatesAndSendsEmergencyAlert() {
        Ride ride = new Ride(demoRider);
        ride.setDemo(false);
        ride.setCurrentPosition(43.0);
        ride.setMoving(false);
        ride.setStationarySince(Instant.now().minusSeconds(75));
        ride = rideRepository.save(ride);

        Checkpoint cp2 = new Checkpoint(ride, 2, 60.0, Instant.now().plusSeconds(1200));
        cp2.setStatus(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION);
        cp2.setConfirmationSentAt(Instant.now().minusSeconds(45));
        cp2.setGraceDeadline(Instant.now().minusSeconds(5)); // Grace period expired!
        cp2 = checkpointRepository.save(cp2);

        // Expect emergency alert to emergency contact (9999999999) without (DEMO)
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("9999999999")))
                .andExpect(content().string(not(containsString("(DEMO)"))))
                .andExpect(content().string(containsString("ALERT: Amit has not responded at checkpoint 2")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        safetyScheduler.escalateOverdueCheckpoints();
        mockServer.verify();

        Checkpoint escalatedCp = checkpointRepository.findById(cp2.getId()).orElseThrow();
        assertEquals(Checkpoint.CheckpointStatus.ESCALATED, escalatedCp.getStatus(),
                "Checkpoint must transition to ESCALATED when grace period expires");
    }

    @Test
    @DisplayName("5. Real rides continue to follow existing safety pipeline unchanged (without DEMO prefix)")
    void testRealRideSafetyPipelineUnchanged() {
        Ride realRide = new Ride(realRider);
        realRide.setDemo(false);
        realRide.setStatus(Ride.RideStatus.ACTIVE);
        realRide.setMoving(false);
        realRide.setStationarySince(Instant.now().minusSeconds(40)); // > 30s tolerance
        realRide = rideRepository.save(realRide);

        Checkpoint cp = new Checkpoint(realRide, 1, 25.0, Instant.now().plusSeconds(3600));
        cp.setStatus(Checkpoint.CheckpointStatus.NOT_REACHED);
        cp = checkpointRepository.save(cp);

        // Real ride confirmation prompt: sent to 12345678, WITHOUT (DEMO) prefix
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("12345678")))
                .andExpect(content().string(not(containsString("(DEMO)"))))
                .andExpect(content().string(containsString("Checkpoint 1 — you've been stationary a while")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        safetyScheduler.checkOverdueStationaryCheckpoints();
        mockServer.verify();

        Checkpoint awaitingCp = checkpointRepository.findById(cp.getId()).orElseThrow();
        assertEquals(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION, awaitingCp.getStatus());

        // Expire grace window to test real ride escalation
        awaitingCp.setGraceDeadline(Instant.now().minusSeconds(10));
        checkpointRepository.save(awaitingCp);

        mockServer.reset();
        // Real ride escalation: sent to emergency contact 87654321, WITHOUT (DEMO) prefix
        mockServer.expect(requestTo(containsString("sendMessage")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("87654321")))
                .andExpect(content().string(not(containsString("(DEMO)"))))
                .andExpect(content().string(containsString("ALERT: RealRider has not responded at checkpoint 1")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        safetyScheduler.escalateOverdueCheckpoints();
        mockServer.verify();

        Checkpoint escalatedCp = checkpointRepository.findById(cp.getId()).orElseThrow();
        assertEquals(Checkpoint.CheckpointStatus.ESCALATED, escalatedCp.getStatus());
    }

    @Test
    @DisplayName("6. Existing demo records and real rides remain correctly separated: clearRealRide preserves existing demo records")
    void testDemoAAndRealRidesRemainSeparated() {
        Ride demoRide = new Ride(demoRider);
        demoRide.setDemo(true);
        demoRide = rideRepository.save(demoRide);
        assertTrue(demoRide.isDemo());

        Ride realRide = new Ride(realRider);
        realRide.setDemo(false);
        realRide.setStatus(Ride.RideStatus.ACTIVE);
        realRide = rideRepository.save(realRide);

        assertEquals(2, rideRepository.count());

        // Ending real ride for realRider's chat ID clears real ride but strictly preserves existing demo records
        rideService.endAndClearRealRidesForRider(realRider.getTelegramChatId());

        assertEquals(1, rideRepository.count());
        assertTrue(rideRepository.findById(demoRide.getId()).isPresent(),
                "Existing demo ride record must be strictly preserved when clearing real rides");
    }
}
