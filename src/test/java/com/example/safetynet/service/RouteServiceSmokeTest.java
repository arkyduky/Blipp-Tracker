package com.example.safetynet.service;

import com.example.safetynet.dto.RouteLeg;
import com.example.safetynet.dto.RouteResult;
import com.example.safetynet.dto.Waypoint;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("smoke")
class RouteServiceSmokeTest {

    @Test
    void testLiveOsrmRouteCalculation() {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("router.project-osrm.org", 443), 2000);
            socket.close();
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.abort("Public OSRM server not reachable, skipping live smoke test: " + e.getMessage());
        }

        RouteService routeService = new RouteService();

        // Kerala route: Thrissur -> Kunnamkulam -> Munnar
        List<Waypoint> waypoints = List.of(
                new Waypoint("Thrissur", 10.5276, 76.2144, 1),
                new Waypoint("Kunnamkulam", 10.6517, 76.0409, 2),
                new Waypoint("Munnar", 10.0889, 77.0595, 3)
        );

        RouteResult result = routeService.calculateRoute(waypoints);

        assertNotNull(result, "RouteResult must not be null");
        System.out.println("=== Live OSRM Smoke Test Result ===");
        System.out.println("Total distance: " + result.getTotalDistanceMeters() + " meters (" +
                String.format("%.2f", result.getTotalDistanceMeters() / 1000.0) + " km)");
        System.out.println("Total duration: " + result.getTotalDurationSeconds() + " seconds (" +
                String.format("%.1f", result.getTotalDurationSeconds() / 60.0) + " minutes)");
        System.out.println("Coordinate count: " + result.getCoordinates().size());
        System.out.println("Leg count: " + result.getLegs().size());

        for (int i = 0; i < result.getLegs().size(); i++) {
            RouteLeg leg = result.getLegs().get(i);
            System.out.println("Leg " + i + ": " + String.format("%.2f", leg.getDistanceMeters() / 1000.0) + " km, " +
                    String.format("%.1f", leg.getDurationSeconds() / 60.0) + " min");
        }

        assertTrue(result.getTotalDistanceMeters() > 0, "Total distance must be > 0");
        assertTrue(result.getTotalDurationSeconds() > 0, "Total duration must be > 0");
        assertTrue(result.getCoordinates().size() > 10, "Should have multiple geometry coordinates");
        assertEquals(2, result.getLegs().size(), "3 waypoints should yield exactly 2 legs");

        RouteLeg leg0 = result.getLegs().get(0);
        assertEquals(0, leg0.getStartWaypointIndex());
        assertEquals(1, leg0.getEndWaypointIndex());
        assertTrue(leg0.getDistanceMeters() > 0);

        RouteLeg leg1 = result.getLegs().get(1);
        assertEquals(1, leg1.getStartWaypointIndex());
        assertEquals(2, leg1.getEndWaypointIndex());
        assertTrue(leg1.getDistanceMeters() > 0);
    }
}
