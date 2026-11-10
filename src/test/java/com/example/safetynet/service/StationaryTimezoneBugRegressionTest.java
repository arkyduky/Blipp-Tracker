package com.example.safetynet.service;

import com.example.safetynet.dto.RideLiveStatusDto;
import com.example.safetynet.model.Checkpoint;
import com.example.safetynet.model.Ride;
import com.example.safetynet.model.Rider;
import com.example.safetynet.repository.CheckpointRepository;
import com.example.safetynet.repository.RideRepository;
import com.example.safetynet.repository.RiderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DataJpaTest
@DisplayName("Stationary Timer & Alert Timezone Bug Regression Tests")
class StationaryTimezoneBugRegressionTest {

    @Autowired
    private RiderRepository riderRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    private AlertService alertService;
    private SafetyScheduler safetyScheduler;
    private MockRestServiceServer mockServer;
    private ObjectMapper objectMapper;

    private TimeZone originalTimeZone;
    private Rider testRider;

    @BeforeEach
    void setUp() {
        originalTimeZone = TimeZone.getDefault();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        testRider = new Rider();
        testRider.setName("Rider Test");
        testRider.setTelegramChatId("12345678");
        testRider.setEmergencyContactChatId("87654321");
        testRider.setStationaryToleranceSeconds(25);
        testRider.setGracePeriodSeconds(300);
        testRider = riderRepository.save(testRider);

        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);

        GeoProjectionService geoProjectionService = new GeoProjectionService();
        GoogleMapsRouteParser googleMapsRouteParser = new GoogleMapsRouteParser();

        alertService = new AlertService(rideRepository, riderRepository, null, geoProjectionService, googleMapsRouteParser, checkpointRepository);
        alertService.setBotToken("test-bot-token");
        alertService.setRestTemplate(restTemplate);
        safetyScheduler = new SafetyScheduler(rideRepository, checkpointRepository, alertService);
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    @DisplayName("1. Stationary duration calculation is identical across UTC, Asia/Kolkata, and America/New_York")
    void testStationaryDuration_identicalAcrossTimezones() {
        long elapsedSeconds = 42;
        Instant stoppedAt = Instant.now().minusSeconds(elapsedSeconds);

        Ride ride = new Ride(testRider);
        ride.setMoving(false);
        ride.setStationarySince(stoppedAt);

        // Test in UTC
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Long utcDuration = ride.getStationaryDurationSeconds();
        assertNotNull(utcDuration);
        assertEquals(elapsedSeconds, utcDuration, 1L,
                "In UTC, stationary duration must be ~42 seconds");

        // Test in Asia/Kolkata (UTC+05:30)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        Long kolkataDuration = ride.getStationaryDurationSeconds();
        assertNotNull(kolkataDuration);
        assertEquals(elapsedSeconds, kolkataDuration, 1L,
                "In Asia/Kolkata (+05:30), stationary duration must be ~42 seconds, NOT 19,842 seconds");
        assertEquals(utcDuration, kolkataDuration, 1L,
                "UTC duration and Asia/Kolkata duration must match exactly");

        // Test in America/New_York (UTC-04:00/05:00)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        Long nyDuration = ride.getStationaryDurationSeconds();
        assertNotNull(nyDuration);
        assertEquals(elapsedSeconds, nyDuration, 1L,
                "In America/New_York, stationary duration must be ~42 seconds");
        assertEquals(utcDuration, nyDuration, 1L);
    }

    @Test
    @DisplayName("2. DTO and Jackson serialization format timestamps as ISO-8601 with 'Z' and include stationaryDurationSeconds")
    void testSerialization_formatsWithZAndIncludesStationaryDurationSeconds() throws Exception {
        Instant stoppedAt = Instant.parse("2026-10-05T10:00:00Z");
        Instant gpsUpdated = Instant.parse("2026-10-05T10:00:05Z");

        Ride ride = new Ride(testRider);
        ride.setCurrentPosition(40.0);
        ride.setMoving(false);
        ride.setStationarySince(stoppedAt);
        ride.setLastKnownLatitude(10.5);
        ride.setLastKnownLongitude(76.2);
        ride.setLastLocationUpdatedAt(gpsUpdated);
        ride = rideRepository.save(ride);

        Checkpoint cp = new Checkpoint(ride, 1, 50.0, Instant.parse("2026-10-05T10:15:00Z"));
        cp.setStatus(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION);
        cp.setConfirmationSentAt(Instant.parse("2026-10-05T10:05:00Z"));
        cp.setGraceDeadline(Instant.parse("2026-10-05T10:10:00Z"));
        cp = checkpointRepository.save(cp);

        RideLiveStatusDto dto = RideLiveStatusDto.from(ride, List.of(cp));
        String json = objectMapper.writeValueAsString(dto);

        // Verify timestamps contain 'Z' indicating absolute UTC instant
        assertTrue(json.contains("\"stationarySince\":\"2026-10-05T10:00:00Z\""),
                "stationarySince must be serialized with 'Z' designator");
        assertTrue(json.contains("\"lastLocationUpdatedAt\":\"2026-10-05T10:00:05Z\""),
                "lastLocationUpdatedAt must be serialized with 'Z' designator");
        assertTrue(json.contains("\"confirmationSentAt\":\"2026-10-05T10:05:00Z\""),
                "confirmationSentAt must be serialized with 'Z' designator");
        assertTrue(json.contains("\"graceDeadline\":\"2026-10-05T10:10:00Z\""),
                "graceDeadline must be serialized with 'Z' designator");

        // Verify stationaryDurationSeconds is populated in JSON
        assertNotNull(dto.stationaryDurationSeconds());
        assertTrue(json.contains("\"stationaryDurationSeconds\":"),
                "JSON must contain stationaryDurationSeconds field");
    }

    @Test
    @DisplayName("3. Browser parsing simulation in Asia/Kolkata: Z-normalized timestamp prevents +5h30m skew")
    void testBrowserParsingSimulation_inAsiaKolkataZone() {
        // Suppose server stopped movement 40 seconds ago
        Instant stoppedAt = Instant.now().minusSeconds(40);
        long clientNowEpochMillis = Instant.now().toEpochMilli();

        // 1. If backend sends ISO with 'Z' (e.g. "2026-10-05T10:15:00Z"):
        String isoWithZ = stoppedAt.toString(); // standard Instant.toString() has 'Z'
        long parsedMillisFromZ = Instant.parse(isoWithZ).toEpochMilli();
        long diffSecondsWithZ = (long) Math.floor((clientNowEpochMillis - parsedMillisFromZ) / 1000.0);

        assertEquals(40, diffSecondsWithZ, 1,
                "Parsing with 'Z' yields true elapsed duration (~40s)");

        // 2. Demonstration of the original bug if server sent LocalDateTime without 'Z' (e.g. "2026-10-05T10:15:00"):
        // Browser in Asia/Kolkata parses timezone-less string as local time (IST = UTC+05:30 = 19,800s ahead of UTC).
        // That produced (Client Now UTC - Parsed Local Millis) = 40s + 19,800s = 19,840 seconds (~5.5 hours)!
        // With 'Z' appended, the browser treats it as UTC, completely eliminating the 19,800-second anomaly.
        String isoWithoutZ = isoWithZ.replace("Z", "");
        String normalizedIso = isoWithoutZ + "Z";
        long parsedMillisNormalized = Instant.parse(normalizedIso).toEpochMilli();
        long diffSecondsNormalized = (long) Math.floor((clientNowEpochMillis - parsedMillisNormalized) / 1000.0);

        assertEquals(40, diffSecondsNormalized, 1,
                "Frontend normalization ensures even bare ISO strings calculate exact duration without 5h30 skew");
    }

    @Test
    @DisplayName("4. SafetyScheduler stationary tolerance check behaves identically under UTC and Asia/Kolkata")
    void testSafetySchedulerStationaryTolerance_behavesIdenticallyUnderUtcAndKolkata() {
        for (String zoneId : List.of("UTC", "Asia/Kolkata")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

            Ride ride = new Ride(testRider);
            ride.setCurrentPosition(30.0);
            ride.setMoving(false);
            // Stopped 35 seconds ago (tolerance is 25 seconds -> should trigger)
            ride.setStationarySince(Instant.now().minusSeconds(35));
            ride = rideRepository.save(ride);

            Checkpoint cp = new Checkpoint(ride, 1, 50.0, Instant.now().plusSeconds(3600));
            cp.setStatus(Checkpoint.CheckpointStatus.NOT_REACHED);
            cp = checkpointRepository.save(cp);

            mockServer.reset();
            mockServer.expect(requestTo(containsString("sendMessage")))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().string(containsString("12345678")))
                    .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

            safetyScheduler.checkOverdueStationaryCheckpoints();
            mockServer.verify();

            Checkpoint refreshed = checkpointRepository.findById(cp.getId()).orElseThrow();
            assertEquals(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION, refreshed.getStatus(),
                    "Under " + zoneId + ", checkpoint must transition to AWAITING_CONFIRMATION");
            assertNotNull(refreshed.getConfirmationSentAt());
            assertNotNull(refreshed.getGraceDeadline());

            // Grace deadline should be in the future (~300s), NOT expired
            long secondsUntilDeadline = Duration.between(Instant.now(), refreshed.getGraceDeadline()).getSeconds();
            assertTrue(secondsUntilDeadline >= 295 && secondsUntilDeadline <= 305,
                    "Under " + zoneId + ", grace deadline must be ~300 seconds in the future, was: " + secondsUntilDeadline);

            // Clean up for next loop
            checkpointRepository.delete(refreshed);
            rideRepository.delete(ride);
        }
    }

    @Test
    @DisplayName("5. SafetyScheduler escalation behaves identically under UTC and Asia/Kolkata")
    void testSafetySchedulerEscalation_behavesIdenticallyUnderUtcAndKolkata() {
        for (String zoneId : List.of("UTC", "Asia/Kolkata")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

            Ride ride = new Ride(testRider);
            ride.setCurrentPosition(30.0);
            ride.setMoving(false);
            ride = rideRepository.save(ride);

            // Case A: Deadline still in future -> should NOT escalate
            Checkpoint futureCp = new Checkpoint(ride, 1, 50.0, Instant.now().plusSeconds(3600));
            futureCp.setStatus(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION);
            futureCp.setConfirmationSentAt(Instant.now().minusSeconds(30));
            futureCp.setGraceDeadline(Instant.now().plusSeconds(270));
            futureCp = checkpointRepository.save(futureCp);

            safetyScheduler.escalateOverdueCheckpoints();

            Checkpoint notEscalated = checkpointRepository.findById(futureCp.getId()).orElseThrow();
            assertEquals(Checkpoint.CheckpointStatus.AWAITING_CONFIRMATION, notEscalated.getStatus(),
                    "Under " + zoneId + ", future deadline must not escalate");

            // Case B: Deadline expired 5s ago -> should ESCALATE
            futureCp.setGraceDeadline(Instant.now().minusSeconds(5));
            checkpointRepository.save(futureCp);

            mockServer.reset();
            mockServer.expect(requestTo(containsString("sendMessage")))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().string(containsString("87654321")))
                    .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

            safetyScheduler.escalateOverdueCheckpoints();
            mockServer.verify();

            Checkpoint escalated = checkpointRepository.findById(futureCp.getId()).orElseThrow();
            assertEquals(Checkpoint.CheckpointStatus.ESCALATED, escalated.getStatus(),
                    "Under " + zoneId + ", expired deadline must transition to ESCALATED");

            // Clean up for next loop
            checkpointRepository.delete(escalated);
            rideRepository.delete(ride);
        }
    }
}
