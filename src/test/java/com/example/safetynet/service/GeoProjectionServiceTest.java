package com.example.safetynet.service;

import com.example.safetynet.dto.GeoProjectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GeoProjectionService Unit Tests")
class GeoProjectionServiceTest {

    private GeoProjectionService service;

    // A two-point route from (10.0, 76.0) to (10.0, 76.1) heading due east
    private final String twoPointRoute = "{\"type\":\"LineString\",\"coordinates\":[[76.0,10.0],[76.1,10.0]]}";

    // A three-point multi-segment route: (10.0, 76.0) -> (10.0, 76.1) -> (10.1, 76.1)
    private final String multiSegmentRoute = "{\"type\":\"LineString\",\"coordinates\":[[76.0,10.0],[76.1,10.0],[76.1,10.1]]}";

    @BeforeEach
    void setUp() {
        service = new GeoProjectionService();
    }

    @Test
    @DisplayName("1. Two-point route, GPS at origin -> ~0%")
    void testGpsAtOrigin() {
        GeoProjectionResult result = service.project(twoPointRoute, 10.0, 76.0);
        assertTrue(result.isValid(), "Projection should be valid");
        assertEquals(0.0, result.getProgressPercent(), 0.01, "Progress should be approximately 0%");
        assertEquals(0.0, result.getDistanceAlongRouteMeters(), 0.5, "Distance along route should be ~0m");
        assertEquals(0.0, result.getDistanceFromRouteMeters(), 0.5, "Distance from route should be ~0m");
        assertEquals(10.0, result.getProjectedLatitude(), 1e-5);
        assertEquals(76.0, result.getProjectedLongitude(), 1e-5);
    }

    @Test
    @DisplayName("2. Two-point route, GPS at destination -> ~100%")
    void testGpsAtDestination() {
        GeoProjectionResult result = service.project(twoPointRoute, 10.0, 76.1);
        assertTrue(result.isValid(), "Projection should be valid");
        assertEquals(100.0, result.getProgressPercent(), 0.01, "Progress should be approximately 100%");
        assertTrue(result.getDistanceAlongRouteMeters() > 10000.0, "Route length should be > 10km");
        assertEquals(0.0, result.getDistanceFromRouteMeters(), 0.5, "Distance from route should be ~0m");
        assertEquals(10.0, result.getProjectedLatitude(), 1e-5);
        assertEquals(76.1, result.getProjectedLongitude(), 1e-5);
    }

    @Test
    @DisplayName("3. Two-point route, GPS at midpoint -> ~50%")
    void testGpsAtMidpoint() {
        GeoProjectionResult result = service.project(twoPointRoute, 10.0, 76.05);
        assertTrue(result.isValid(), "Projection should be valid");
        assertEquals(50.0, result.getProgressPercent(), 0.5, "Progress should be approximately 50%");
        assertEquals(0.0, result.getDistanceFromRouteMeters(), 0.5, "Distance from route should be ~0m");
        assertEquals(10.0, result.getProjectedLatitude(), 1e-5);
        assertEquals(76.05, result.getProjectedLongitude(), 1e-4);
    }

    @Test
    @DisplayName("4. GPS offset from a route segment -> projection chooses segment correctly")
    void testGpsOffsetFromSegment() {
        // Offset 0.001 degrees latitude north (~111 meters) from midpoint
        GeoProjectionResult result = service.project(twoPointRoute, 10.001, 76.05);
        assertTrue(result.isValid(), "Projection should be valid");
        assertEquals(50.0, result.getProgressPercent(), 0.5, "Progress should remain ~50% at midpoint offset");
        assertEquals(111.0, result.getDistanceFromRouteMeters(), 5.0, "Distance from route should be ~111m");
        assertEquals(10.0, result.getProjectedLatitude(), 1e-4, "Projected latitude should snap to segment");
        assertEquals(76.05, result.getProjectedLongitude(), 1e-4, "Projected longitude should snap to midpoint");
    }

    @Test
    @DisplayName("5. Multi-segment route -> correct nearest segment")
    void testMultiSegmentRouteSelection() {
        // Leg 1 is east (~10.9 km), Leg 2 is north (~11.1 km)
        // Place GPS point near middle of Leg 2: lat 10.05, lon 76.1001 (offset east by ~11m)
        GeoProjectionResult result = service.project(multiSegmentRoute, 10.05, 76.1001);
        assertTrue(result.isValid(), "Projection should be valid");
        // Leg 1 + half of Leg 2 is ~75% of route
        assertEquals(75.0, result.getProgressPercent(), 3.0, "Progress should be ~75%");
        assertEquals(11.0, result.getDistanceFromRouteMeters(), 3.0, "Distance from route should be ~11m");
        assertEquals(10.05, result.getProjectedLatitude(), 1e-4);
        assertEquals(76.1, result.getProjectedLongitude(), 1e-4);
    }

    @Test
    @DisplayName("6. GPS near a segment boundary")
    void testGpsNearSegmentBoundary() {
        // Right at junction point (10.0, 76.1)
        GeoProjectionResult result = service.project(multiSegmentRoute, 10.0, 76.1);
        assertTrue(result.isValid(), "Projection should be valid");
        assertEquals(0.0, result.getDistanceFromRouteMeters(), 0.5);
        // Halfway along total two-segment path (~49-51%)
        assertTrue(result.getProgressPercent() >= 48.0 && result.getProgressPercent() <= 52.0,
                "Progress should be approximately half at boundary");
    }

    @Test
    @DisplayName("7. GPS before origin -> clamped near 0%")
    void testGpsBeforeOrigin() {
        // West of origin (lon 75.95 < 76.0)
        GeoProjectionResult result = service.project(twoPointRoute, 10.0, 75.95);
        assertTrue(result.isValid(), "Projection should be valid");
        assertEquals(0.0, result.getProgressPercent(), 0.001, "Progress before origin must be clamped to 0%");
        assertEquals(0.0, result.getDistanceAlongRouteMeters(), 0.001, "Distance along route should be 0m");
        assertTrue(result.getDistanceFromRouteMeters() > 5000.0, "Distance from route should be > 5km");
        assertEquals(10.0, result.getProjectedLatitude(), 1e-5);
        assertEquals(76.0, result.getProjectedLongitude(), 1e-5);
    }

    @Test
    @DisplayName("8. GPS after destination -> clamped near 100%")
    void testGpsAfterDestination() {
        // East of destination (lon 76.15 > 76.1)
        GeoProjectionResult result = service.project(twoPointRoute, 10.0, 76.15);
        assertTrue(result.isValid(), "Projection should be valid");
        assertEquals(100.0, result.getProgressPercent(), 0.001, "Progress after destination must be clamped to 100%");
        assertTrue(result.getDistanceFromRouteMeters() > 5000.0, "Distance from route should be > 5km");
        assertEquals(10.0, result.getProjectedLatitude(), 1e-5);
        assertEquals(76.1, result.getProjectedLongitude(), 1e-5);
    }

    @Test
    @DisplayName("9. Invalid latitude rejected")
    void testInvalidLatitude() {
        assertFalse(service.project(twoPointRoute, 90.1, 76.0).isValid());
        assertFalse(service.project(twoPointRoute, -90.1, 76.0).isValid());
        assertFalse(service.project(twoPointRoute, Double.NaN, 76.0).isValid());
        assertFalse(service.project(twoPointRoute, Double.POSITIVE_INFINITY, 76.0).isValid());
        assertFalse(service.project(twoPointRoute, Double.NEGATIVE_INFINITY, 76.0).isValid());
    }

    @Test
    @DisplayName("10. Invalid longitude rejected")
    void testInvalidLongitude() {
        assertFalse(service.project(twoPointRoute, 10.0, 180.1).isValid());
        assertFalse(service.project(twoPointRoute, 10.0, -180.1).isValid());
        assertFalse(service.project(twoPointRoute, 10.0, Double.NaN).isValid());
        assertFalse(service.project(twoPointRoute, 10.0, Double.POSITIVE_INFINITY).isValid());
        assertFalse(service.project(twoPointRoute, 10.0, Double.NEGATIVE_INFINITY).isValid());
    }

    @Test
    @DisplayName("11. Null geometry returns invalid result")
    void testNullGeometry() {
        GeoProjectionResult result = service.project(null, 10.0, 76.0);
        assertNotNull(result);
        assertFalse(result.isValid());
        assertEquals(0.0, result.getProgressPercent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    @DisplayName("12. Blank geometry returns invalid result")
    void testBlankGeometry(String blank) {
        GeoProjectionResult result = service.project(blank, 10.0, 76.0);
        assertNotNull(result);
        assertFalse(result.isValid());
        assertEquals(0.0, result.getProgressPercent());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{not json}",
            "{\"type\":\"Point\",\"coordinates\":[76.0,10.0]}",
            "{\"type\":\"LineString\",\"coordinates\":\"bad-format\"}"
    })
    @DisplayName("13. Malformed GeoJSON returns invalid result")
    void testMalformedGeoJson(String malformed) {
        GeoProjectionResult result = service.project(malformed, 10.0, 76.0);
        assertNotNull(result);
        assertFalse(result.isValid());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"LineString\",\"coordinates\":[]}",
            "{\"type\":\"LineString\",\"coordinates\":[[76.0,10.0]]}"
    })
    @DisplayName("14. Fewer than two coordinates returns invalid result")
    void testFewerThanTwoCoordinates(String invalidGeo) {
        GeoProjectionResult result = service.project(invalidGeo, 10.0, 76.0);
        assertNotNull(result);
        assertFalse(result.isValid());
    }

    @Test
    @DisplayName("15. Verify distanceFromRouteMeters is accurate")
    void testDistanceFromRouteAccuracy() {
        // Offset 500 meters north of segment
        double offsetDegLat = 500.0 / 111195.0;
        GeoProjectionResult result = service.project(twoPointRoute, 10.0 + offsetDegLat, 76.05);
        assertTrue(result.isValid());
        assertEquals(500.0, result.getDistanceFromRouteMeters(), 5.0, "Should measure approximately 500 meters");
    }

    @Test
    @DisplayName("16. Verify route-distance/progress calculations are reasonably tolerant of floating-point/geographic approximation")
    void testFloatingPointToleranceAndAuthoritativeDistance() {
        double authoritativeDistanceMeters = 10940.0;
        GeoProjectionResult result = service.project(twoPointRoute, 10.0, 76.05, authoritativeDistanceMeters);
        assertTrue(result.isValid());
        // 50% along route of 10940 meters is 5470m
        assertTrue(result.getProgressPercent() >= 49.0 && result.getProgressPercent() <= 51.0,
                "Progress percent should be within 1% of 50%");
        assertTrue(result.getDistanceAlongRouteMeters() >= 5300.0 && result.getDistanceAlongRouteMeters() <= 5600.0,
                "Distance along route should be ~5470m");
    }

    @Test
    @DisplayName("Wrapped Feature GeoJSON structure supported")
    void testWrappedFeatureGeoJson() {
        String wrapped = "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[76.0,10.0],[76.1,10.0]]}}";
        GeoProjectionResult result = service.project(wrapped, 10.0, 76.05);
        assertTrue(result.isValid());
        assertEquals(50.0, result.getProgressPercent(), 0.5);
    }
}
