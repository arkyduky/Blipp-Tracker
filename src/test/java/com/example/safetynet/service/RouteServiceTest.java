package com.example.safetynet.service;

import com.example.safetynet.dto.RouteLeg;
import com.example.safetynet.dto.RouteResult;
import com.example.safetynet.dto.Waypoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RouteServiceTest {

    private RouteService routeService;
    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        routeService = new RouteService(restTemplate, null, "https://router.project-osrm.org");
    }

    @Nested
    @DisplayName("Input Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Rejects null waypoint list")
        void testValidateWaypoints_nullListThrows() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    routeService.validateWaypoints(null));
            assertTrue(ex.getMessage().contains("At least 2 waypoints"));
        }

        @Test
        @DisplayName("Rejects empty waypoint list")
        void testValidateWaypoints_emptyListThrows() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    routeService.validateWaypoints(Collections.emptyList()));
            assertTrue(ex.getMessage().contains("At least 2 waypoints"));
        }

        @Test
        @DisplayName("Rejects list with only 1 waypoint")
        void testValidateWaypoints_singleWaypointThrows() {
            List<Waypoint> waypoints = List.of(new Waypoint("Start", 10.5276, 76.2144, 1));
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    routeService.validateWaypoints(waypoints));
            assertTrue(ex.getMessage().contains("At least 2 waypoints"));
        }

        @Test
        @DisplayName("Rejects null waypoint in list")
        void testValidateWaypoints_nullWaypointThrows() {
            List<Waypoint> waypoints = java.util.Arrays.asList(
                    new Waypoint("Start", 10.5276, 76.2144, 1),
                    null
            );
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    routeService.validateWaypoints(waypoints));
            assertTrue(ex.getMessage().contains("must not be null"));
        }

        @Test
        @DisplayName("Rejects waypoint with null latitude")
        void testValidateWaypoints_nullLatitudeThrows() {
            List<Waypoint> waypoints = List.of(
                    new Waypoint("Start", null, 76.2144, 1),
                    new Waypoint("End", 10.6517, 76.0409, 2)
            );
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    routeService.validateWaypoints(waypoints));
            assertTrue(ex.getMessage().contains("null latitude"));
        }

        @Test
        @DisplayName("Rejects waypoint with null longitude")
        void testValidateWaypoints_nullLongitudeThrows() {
            List<Waypoint> waypoints = List.of(
                    new Waypoint("Start", 10.5276, null, 1),
                    new Waypoint("End", 10.6517, 76.0409, 2)
            );
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    routeService.validateWaypoints(waypoints));
            assertTrue(ex.getMessage().contains("null latitude or longitude"));
        }

        @Test
        @DisplayName("Rejects latitude below -90")
        void testValidateWaypoints_latitudeBelowMinThrows() {
            List<Waypoint> waypoints = List.of(
                    new Waypoint("Start", -91.0, 76.2144, 1),
                    new Waypoint("End", 10.6517, 76.0409, 2)
            );
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    routeService.validateWaypoints(waypoints));
            assertTrue(ex.getMessage().contains("invalid latitude"));
        }

        @Test
        @DisplayName("Rejects latitude above 90")
        void testValidateWaypoints_latitudeAboveMaxThrows() {
            List<Waypoint> waypoints = List.of(
                    new Waypoint("Start", 90.1, 76.2144, 1),
                    new Waypoint("End", 10.6517, 76.0409, 2)
            );
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    routeService.validateWaypoints(waypoints));
            assertTrue(ex.getMessage().contains("invalid latitude"));
        }

        @Test
        @DisplayName("Rejects longitude below -180")
        void testValidateWaypoints_longitudeBelowMinThrows() {
            List<Waypoint> waypoints = List.of(
                    new Waypoint("Start", 10.5276, -180.5, 1),
                    new Waypoint("End", 10.6517, 76.0409, 2)
            );
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    routeService.validateWaypoints(waypoints));
            assertTrue(ex.getMessage().contains("invalid longitude"));
        }

        @Test
        @DisplayName("Rejects longitude above 180")
        void testValidateWaypoints_longitudeAboveMaxThrows() {
            List<Waypoint> waypoints = List.of(
                    new Waypoint("Start", 10.5276, 180.1, 1),
                    new Waypoint("End", 10.6517, 76.0409, 2)
            );
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    routeService.validateWaypoints(waypoints));
            assertTrue(ex.getMessage().contains("invalid longitude"));
        }
    }

    @Nested
    @DisplayName("Coordinate Ordering & URL Construction Tests")
    class UrlConstructionTests {

        @Test
        @DisplayName("Ensures coordinates are formatted as {longitude},{latitude} (lon first) and separated by semicolons")
        void testBuildOsrmUrl_ordersCoordinatesAsLongitudeThenLatitude() {
            // Origin: Thrissur (lat 10.5276, lon 76.2144)
            // Stop: Kunnamkulam (lat 10.6517, lon 76.0409)
            // Destination: Munnar (lat 10.0889, lon 77.0595)
            List<Waypoint> waypoints = List.of(
                    new Waypoint("Thrissur", 10.5276, 76.2144, 1),
                    new Waypoint("Kunnamkulam", 10.6517, 76.0409, 2),
                    new Waypoint("Munnar", 10.0889, 77.0595, 3)
            );

            String url = routeService.buildOsrmUrl(waypoints);

            assertNotNull(url);
            assertTrue(url.startsWith("https://router.project-osrm.org/route/v1/driving/"), "URL must target OSRM driving service");

            // CRITICAL: verify longitude comes before latitude:
            // "76.214400,10.527600;76.040900,10.651700;77.059500,10.088900"
            assertTrue(url.contains("76.214400,10.527600"), "First coordinate must be lon,lat (76.214400,10.527600)");
            assertTrue(url.contains("76.040900,10.651700"), "Second coordinate must be lon,lat (76.040900,10.651700)");
            assertTrue(url.contains("77.059500,10.088900"), "Third coordinate must be lon,lat (77.059500,10.088900)");

            // Confirm that latitude does NOT precede longitude:
            assertFalse(url.contains("10.527600,76.214400"), "Latitude must NOT be placed before longitude");

            // Verify query parameters
            assertTrue(url.contains("overview=full"), "Must request full overview geometry");
            assertTrue(url.contains("geometries=geojson"), "Must request GeoJSON geometry");
        }
    }

    @Nested
    @DisplayName("OSRM Response Parsing Tests")
    class ResponseParsingTests {

        private static final String VALID_OSRM_JSON = """
                {
                  "code": "Ok",
                  "routes": [
                    {
                      "geometry": {
                        "coordinates": [
                          [76.2144, 10.5276],
                          [76.1234, 10.6000],
                          [76.0409, 10.6517]
                        ],
                        "type": "LineString"
                      },
                      "legs": [
                        {
                          "distance": 14000.5,
                          "duration": 1100.2,
                          "summary": "SH-16",
                          "steps": []
                        },
                        {
                          "distance": 16025.0,
                          "duration": 1215.8,
                          "summary": "NH-544",
                          "steps": []
                        }
                      ],
                      "distance": 30025.5,
                      "duration": 2316.0,
                      "weight_name": "routability",
                      "weight": 2316.0
                    }
                  ],
                  "waypoints": []
                }
                """;

        @Test
        @DisplayName("Parses valid OSRM response into RouteResult with geometry, distance, duration, and legs")
        void testParseOsrmResponse_successfulRoute() {
            RouteResult result = routeService.parseOsrmResponse(VALID_OSRM_JSON, 3);

            assertNotNull(result);
            assertEquals(30025.5, result.getTotalDistanceMeters(), 0.001);
            assertEquals(2316.0, result.getTotalDurationSeconds(), 0.001);

            // Verify geometry
            assertNotNull(result.getGeometryGeoJson());
            assertTrue(result.getGeometryGeoJson().contains("LineString"));
            assertTrue(result.getGeometryGeoJson().contains("76.2144"));

            assertNotNull(result.getCoordinates());
            assertEquals(3, result.getCoordinates().size());
            assertEquals(List.of(76.2144, 10.5276), result.getCoordinates().get(0));
            assertEquals(List.of(76.0409, 10.6517), result.getCoordinates().get(2));

            // Verify legs
            List<RouteLeg> legs = result.getLegs();
            assertNotNull(legs);
            assertEquals(2, legs.size());

            RouteLeg leg0 = legs.get(0);
            assertEquals(14000.5, leg0.getDistanceMeters(), 0.001);
            assertEquals(1100.2, leg0.getDurationSeconds(), 0.001);
            assertEquals(0, leg0.getStartWaypointIndex());
            assertEquals(1, leg0.getEndWaypointIndex());

            RouteLeg leg1 = legs.get(1);
            assertEquals(16025.0, leg1.getDistanceMeters(), 0.001);
            assertEquals(1215.8, leg1.getDurationSeconds(), 0.001);
            assertEquals(1, leg1.getStartWaypointIndex());
            assertEquals(2, leg1.getEndWaypointIndex());
        }

        @Test
        @DisplayName("calculateRoute executes HTTP request and returns parsed result")
        void testCalculateRoute_successWithMockedHttp() {
            List<Waypoint> waypoints = List.of(
                    new Waypoint("A", 10.5276, 76.2144, 1),
                    new Waypoint("B", 10.6517, 76.0409, 2)
            );

            mockServer.expect(requestTo(containsString("route/v1/driving/76.214400,10.527600;76.040900,10.651700")))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(VALID_OSRM_JSON, MediaType.APPLICATION_JSON));

            RouteResult result = routeService.calculateRoute(waypoints);

            assertNotNull(result);
            assertEquals(30025.5, result.getTotalDistanceMeters(), 0.001);
            assertEquals(2, result.getLegs().size());
            mockServer.verify();
        }
    }

    @Nested
    @DisplayName("Failure Handling Tests")
    class FailureHandlingTests {

        @Test
        @DisplayName("Throws exception when OSRM returns non-Ok code")
        void testParseOsrmResponse_errorCodeThrows() {
            String errorJson = """
                    {
                      "code": "NoRoute",
                      "message": "Impossible route between points"
                    }
                    """;

            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    routeService.parseOsrmResponse(errorJson, 2));

            assertTrue(ex.getMessage().contains("OSRM routing failed with code 'NoRoute'"));
            assertTrue(ex.getMessage().contains("Impossible route"));
        }

        @Test
        @DisplayName("Throws exception when response body is empty or blank")
        void testParseOsrmResponse_emptyBodyThrows() {
            assertThrows(IllegalStateException.class, () ->
                    routeService.parseOsrmResponse("", 2));
            assertThrows(IllegalStateException.class, () ->
                    routeService.parseOsrmResponse(null, 2));
        }

        @Test
        @DisplayName("Throws exception when routes array is missing or empty")
        void testParseOsrmResponse_missingRoutesThrows() {
            String jsonNoRoutes = """
                    {
                      "code": "Ok",
                      "routes": []
                    }
                    """;
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    routeService.parseOsrmResponse(jsonNoRoutes, 2));
            assertTrue(ex.getMessage().contains("No routes found"));
        }

        @Test
        @DisplayName("Throws exception when route geometry is missing")
        void testParseOsrmResponse_missingGeometryThrows() {
            String jsonNoGeom = """
                    {
                      "code": "Ok",
                      "routes": [
                        {
                          "distance": 100.0,
                          "duration": 50.0,
                          "legs": [{"distance": 100.0, "duration": 50.0}]
                        }
                      ]
                    }
                    """;
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    routeService.parseOsrmResponse(jsonNoGeom, 2));
            assertTrue(ex.getMessage().contains("Missing geometry"));
        }

        @Test
        @DisplayName("Throws exception when HTTP call produces network error")
        void testCalculateRoute_networkExceptionThrows() {
            List<Waypoint> waypoints = List.of(
                    new Waypoint("A", 10.5276, 76.2144, 1),
                    new Waypoint("B", 10.6517, 76.0409, 2)
            );

            RestTemplate failingRestTemplate = new RestTemplate(new ClientHttpRequestFactory() {
                @Override
                public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
                    throw new IOException("Connection timed out to OSRM");
                }
            });

            RouteService failingService = new RouteService(failingRestTemplate, null, "https://router.project-osrm.org");

            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    failingService.calculateRoute(waypoints));

            assertTrue(ex.getMessage().contains("Failed to connect to OSRM service"));
        }

        @Test
        @DisplayName("Throws exception when HTTP call returns 500 server error")
        void testCalculateRoute_http500Throws() {
            List<Waypoint> waypoints = List.of(
                    new Waypoint("A", 10.5276, 76.2144, 1),
                    new Waypoint("B", 10.6517, 76.0409, 2)
            );

            mockServer.expect(requestTo(containsString("route/v1/driving")))
                    .andRespond(withServerError());

            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    routeService.calculateRoute(waypoints));

            assertTrue(ex.getMessage().contains("non-successful HTTP status"));
            mockServer.verify();
        }
    }
}
