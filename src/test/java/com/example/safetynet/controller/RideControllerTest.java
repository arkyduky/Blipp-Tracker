package com.example.safetynet.controller;

import com.example.safetynet.dto.RideLiveStatusDto;
import com.example.safetynet.model.Checkpoint;
import com.example.safetynet.model.Ride;
import com.example.safetynet.model.Rider;
import com.example.safetynet.repository.CheckpointRepository;
import com.example.safetynet.repository.RideRepository;
import com.example.safetynet.repository.RiderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class RideControllerTest {

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private RiderRepository riderRepository;

    private RideController rideController;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        rideController = new RideController(rideRepository, checkpointRepository, riderRepository);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    private Rider createRider(String name) {
        Rider rider = new Rider();
        rider.setName(name);
        rider.setTelegramChatId("111222333");
        rider.setEmergencyContactChatId("444555666");
        return riderRepository.save(rider);
    }

    private Ride createRide(Rider rider) {
        Ride ride = new Ride(rider);
        ride.setRouteGeometry("{\"type\":\"LineString\",\"coordinates\":[[76.2144,10.5276],[77.0595,10.0889]]}");
        ride.setTotalDistanceMeters(144000.0);
        ride.setTotalDurationSeconds(9000.0);
        ride.setOriginLatitude(10.5276);
        ride.setOriginLongitude(76.2144);
        ride.setDestinationLatitude(10.0889);
        ride.setDestinationLongitude(77.0595);
        ride.setCurrentPosition(42.5);
        ride.setMoving(true);
        return rideRepository.save(ride);
    }

    @Test
    @DisplayName("getLiveStatus exposes all required telemetry fields for live monitor")
    void testGetLiveStatus_exposesAllTelemetryFields() throws Exception {
        Rider rider = createRider("Amit");
        Ride ride = createRide(rider);

        Checkpoint cp1 = new Checkpoint(ride, 1, "Kunnamkulam", 10.6517, 76.0409, 30000.0, 20.8, LocalDateTime.now().plusMinutes(40));
        cp1.setStatus(Checkpoint.CheckpointStatus.PASSED);
        checkpointRepository.save(cp1);

        Checkpoint cp2 = new Checkpoint(ride, 2, "Munnar", 10.0889, 77.0595, 144000.0, 100.0, LocalDateTime.now().plusMinutes(150));
        checkpointRepository.save(cp2);

        RideLiveStatusDto dto = rideController.getLiveStatus(ride.getId());

        assertNotNull(dto);
        assertEquals(ride.getId(), dto.rideId());
        assertEquals("Amit", dto.riderName());
        assertEquals("ACTIVE", dto.rideStatus());
        assertEquals(42.5, dto.currentPosition(), 0.001);
        assertTrue(dto.isMoving());

        // Route details
        assertEquals(144000.0, dto.totalDistanceMeters(), 0.001);
        assertEquals(9000.0, dto.totalDurationSeconds(), 0.001);
        assertEquals(10.5276, dto.originLatitude(), 0.0001);
        assertEquals(76.2144, dto.originLongitude(), 0.0001);
        assertEquals(10.0889, dto.destinationLatitude(), 0.0001);
        assertEquals(77.0595, dto.destinationLongitude(), 0.0001);
        assertEquals("Munnar", dto.destinationName());
        assertNotNull(dto.routeGeometry());

        // Checkpoints array
        assertNotNull(dto.checkpoints());
        assertEquals(2, dto.checkpoints().size());

        RideLiveStatusDto.CheckpointStatusDto cpDto1 = dto.checkpoints().get(0);
        assertEquals(1, cpDto1.sequenceNumber());
        assertEquals("Kunnamkulam", cpDto1.name());
        assertEquals(10.6517, cpDto1.latitude(), 0.0001);
        assertEquals(76.0409, cpDto1.longitude(), 0.0001);
        assertEquals(30000.0, cpDto1.distanceFromOriginMeters(), 0.001);
        assertEquals("PASSED", cpDto1.status());

        RideLiveStatusDto.CheckpointStatusDto cpDto2 = dto.checkpoints().get(1);
        assertEquals(2, cpDto2.sequenceNumber());
        assertEquals("Munnar", cpDto2.name());
        assertEquals(100.0, cpDto2.positionOnRoute(), 0.001);
        assertEquals("NOT_REACHED", cpDto2.status());

        // Verify JSON serialization produces valid JSON string without error
        String json = objectMapper.writeValueAsString(dto);
        assertNotNull(json);
        assertTrue(json.contains("\"riderName\":\"Amit\""));
        assertTrue(json.contains("\"currentPosition\":42.5"));
        assertTrue(json.contains("\"isMoving\":true"));
        assertTrue(json.contains("\"checkpoints\":["));
    }

    @Test
    @DisplayName("getLiveStatus cleanly handles missing GPS coordinates as null")
    void testGetLiveStatus_handlesMissingGpsCleanly() {
        Rider rider = createRider("Sita");
        Ride ride = createRide(rider);

        // Ride has no GPS coordinates yet
        assertNull(ride.getLastKnownLatitude());
        assertNull(ride.getLastKnownLongitude());
        assertNull(ride.getLastLocationUpdatedAt());

        RideLiveStatusDto dto = rideController.getLiveStatus(ride.getId());

        assertNotNull(dto);
        assertNull(dto.lastKnownLatitude(), "Missing latitude must be null");
        assertNull(dto.lastKnownLongitude(), "Missing longitude must be null");
        assertNull(dto.lastLocationUpdatedAt(), "Missing timestamp must be null");
    }

    @Test
    @DisplayName("getLiveStatus exposes populated GPS coordinates when present")
    void testGetLiveStatus_exposesPopulatedGps() {
        Rider rider = createRider("Rahul");
        Ride ride = createRide(rider);
        Instant updateTime = Instant.now();

        ride.setLastKnownLatitude(10.5300);
        ride.setLastKnownLongitude(76.2200);
        ride.setLastLocationUpdatedAt(updateTime);
        rideRepository.save(ride);

        RideLiveStatusDto dto = rideController.getLiveStatus(ride.getId());

        assertNotNull(dto);
        assertEquals(10.5300, dto.lastKnownLatitude(), 0.0001);
        assertEquals(76.2200, dto.lastKnownLongitude(), 0.0001);
        assertNotNull(dto.lastLocationUpdatedAt());
    }

    @Test
    @DisplayName("Existing /location endpoint remains working and unchanged")
    void testExistingLocationEndpointRemainsUnchanged() {
        Rider rider = createRider("Bob");
        Ride ride = createRide(rider);
        Instant now = Instant.now();
        ride.setLastKnownLatitude(10.1234);
        ride.setLastKnownLongitude(76.5678);
        ride.setLastLocationUpdatedAt(now);
        rideRepository.save(ride);

        RideController.RideLocationDto locDto = rideController.getLocation(ride.getId());

        assertNotNull(locDto);
        assertEquals(10.1234, locDto.lastKnownLatitude(), 0.0001);
        assertEquals(76.5678, locDto.lastKnownLongitude(), 0.0001);
        assertEquals(now, locDto.lastLocationUpdatedAt());
    }

    @Test
    @DisplayName("getActiveRealRide returns active=false when no rides exist")
    void testGetActiveRealRide_noRides() {
        var response = rideController.getActiveRealRide(null);
        assertNotNull(response.getBody());
        assertFalse(response.getBody().active());
        assertNull(response.getBody().rideId());
    }

    @Test
    @DisplayName("getActiveRealRide ignores Demo A rides (isDemo=true)")
    void testGetActiveRealRide_ignoresDemoRides() {
        Rider rider = createRider("DemoRider");
        Ride demoRide = createRide(rider);
        demoRide.setDemo(true);
        demoRide.setStatus(Ride.RideStatus.ACTIVE);
        rideRepository.save(demoRide);

        var response = rideController.getActiveRealRide(null);
        assertNotNull(response.getBody());
        assertFalse(response.getBody().active(), "Demo ride must not be returned");
        assertNull(response.getBody().rideId());
    }

    @Test
    @DisplayName("getActiveRealRide returns real active ride when present")
    void testGetActiveRealRide_returnsRealActiveRide() {
        Rider rider = createRider("RealRider");
        Ride realRide = createRide(rider);
        realRide.setDemo(false);
        realRide.setStatus(Ride.RideStatus.ACTIVE);
        rideRepository.save(realRide);

        var response = rideController.getActiveRealRide(null);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().active());
        assertEquals(realRide.getId(), response.getBody().rideId());
        assertEquals("RealRider", response.getBody().riderName());
    }

    @Test
    @DisplayName("getActiveRealRide filters by telegramChatId if provided")
    void testGetActiveRealRide_filtersByTelegramChatId() {
        Rider rider1 = createRider("Rider1");
        rider1.setTelegramChatId("chat-111");
        riderRepository.save(rider1);

        Ride ride1 = createRide(rider1);
        ride1.setDemo(false);
        ride1.setStatus(Ride.RideStatus.ACTIVE);
        rideRepository.save(ride1);

        // Matching chat ID returns ride
        var responseMatch = rideController.getActiveRealRide("chat-111");
        assertTrue(responseMatch.getBody().active());
        assertEquals(ride1.getId(), responseMatch.getBody().rideId());

        // Non-matching chat ID returns no ride
        var responseOther = rideController.getActiveRealRide("chat-999");
        assertFalse(responseOther.getBody().active());
        assertNull(responseOther.getBody().rideId());
    }

}
