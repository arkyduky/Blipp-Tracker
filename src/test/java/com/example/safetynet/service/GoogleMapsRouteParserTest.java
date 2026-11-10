package com.example.safetynet.service;

import com.example.safetynet.dto.Waypoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoogleMapsRouteParserTest {

    private GoogleMapsRouteParser parser;
    private RouteService routeService;

    @BeforeEach
    void setUp() {
        parser = new GoogleMapsRouteParser();
        routeService = new RouteService();
    }

    @Test
    @DisplayName("1. Full directions URL with explicit path coordinates parses all waypoints")
    void testFullDirectionsUrlWithExplicitCoordinates() {
        String url = "https://www.google.com/maps/dir/10.5276,76.2144/10.6517,76.0409/10.1849,76.3753/10.0889,77.0595";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(4, waypoints.size());

        // Origin
        assertEquals(1, waypoints.get(0).getSequence());
        assertEquals(10.5276, waypoints.get(0).getLatitude(), 0.0001);
        assertEquals(76.2144, waypoints.get(0).getLongitude(), 0.0001);
        assertEquals("Origin", waypoints.get(0).getName());

        // Intermediate stops
        assertEquals(2, waypoints.get(1).getSequence());
        assertEquals(10.6517, waypoints.get(1).getLatitude(), 0.0001);
        assertEquals(76.0409, waypoints.get(1).getLongitude(), 0.0001);

        assertEquals(3, waypoints.get(2).getSequence());
        assertEquals(10.1849, waypoints.get(2).getLatitude(), 0.0001);
        assertEquals(76.3753, waypoints.get(2).getLongitude(), 0.0001);

        // Destination
        assertEquals(4, waypoints.get(3).getSequence());
        assertEquals(10.0889, waypoints.get(3).getLatitude(), 0.0001);
        assertEquals(77.0595, waypoints.get(3).getLongitude(), 0.0001);
        assertEquals("Destination", waypoints.get(3).getName());

        // parseRouteAsArray parity
        Waypoint[] array = parser.parseRouteAsArray(url);
        assertEquals(4, array.length);
        assertEquals(waypoints.get(0).getLatitude(), array[0].getLatitude());
        assertEquals(waypoints.get(3).getLongitude(), array[3].getLongitude());
    }

    @Test
    @DisplayName("2. Full directions URL with camera trailing params (/dir/.../@lat,lon,zoom/) parses clean stops")
    void testFullDirectionsUrlWithCameraParams() {
        String url = "https://www.google.com/maps/dir/10.5276,76.2144/10.6517,76.0409/10.0889,77.0595/@10.3703,76.5169,10z/";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(3, waypoints.size());
        assertEquals(10.5276, waypoints.get(0).getLatitude(), 0.0001);
        assertEquals(10.6517, waypoints.get(1).getLatitude(), 0.0001);
        assertEquals(10.0889, waypoints.get(2).getLatitude(), 0.0001);
    }

    @Test
    @DisplayName("3. Multiple ordered waypoints with place names and !1d<lon>!2d<lat> protobuf data coordinates")
    void testPlaceNamesWithProtobufDataCoordinates() {
        String url = "https://www.google.com/maps/dir/Thrissur/Kunnamkulam/Angamaly/Munnar/@10.3703,76.5169,10z/data=!4m26!4m25!1m5!1m1!1s0x3ba7ee15ed42d1bb:0x82e45aa016ca7db!2m2!1d76.2144!2d10.5276!1m5!1m1!1s0x3ba7954b9d034871:0x1b131aeb7c7e9bb4!2m2!1d76.0409!2d10.6517!1m5!1m1!1s0x3b0805175cf156cb:0xb8e96bf71ecb3d!2m2!1d76.3753!2d10.1849!1m5!1m1!1s0x3b0799794d099023:0x4d328905da671f64!2m2!1d77.0595!2d10.0889!3e0";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(4, waypoints.size());

        // Verify ordering and names
        assertEquals("Thrissur", waypoints.get(0).getName());
        assertEquals(10.5276, waypoints.get(0).getLatitude(), 0.0001);
        assertEquals(76.2144, waypoints.get(0).getLongitude(), 0.0001);
        assertEquals(1, waypoints.get(0).getSequence());

        assertEquals("Kunnamkulam", waypoints.get(1).getName());
        assertEquals(10.6517, waypoints.get(1).getLatitude(), 0.0001);
        assertEquals(76.0409, waypoints.get(1).getLongitude(), 0.0001);
        assertEquals(2, waypoints.get(1).getSequence());

        assertEquals("Angamaly", waypoints.get(2).getName());
        assertEquals(10.1849, waypoints.get(2).getLatitude(), 0.0001);
        assertEquals(76.3753, waypoints.get(2).getLongitude(), 0.0001);
        assertEquals(3, waypoints.get(2).getSequence());

        assertEquals("Munnar", waypoints.get(3).getName());
        assertEquals(10.0889, waypoints.get(3).getLatitude(), 0.0001);
        assertEquals(77.0595, waypoints.get(3).getLongitude(), 0.0001);
        assertEquals(4, waypoints.get(3).getSequence());
    }

    @Test
    @DisplayName("4. Directions API v1 query parameters format (?api=1&origin=...&destination=...&waypoints=...)")
    void testDirectionsQueryParamsFormat() {
        String url = "https://www.google.com/maps/dir/?api=1&origin=10.5276,76.2144&destination=10.0889,77.0595&waypoints=10.6517,76.0409%7C10.1849,76.3753";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(4, waypoints.size());

        assertEquals(10.5276, waypoints.get(0).getLatitude(), 0.0001);
        assertEquals(76.2144, waypoints.get(0).getLongitude(), 0.0001);
        assertEquals("Origin", waypoints.get(0).getName());

        assertEquals(10.6517, waypoints.get(1).getLatitude(), 0.0001);
        assertEquals(76.0409, waypoints.get(1).getLongitude(), 0.0001);

        assertEquals(10.1849, waypoints.get(2).getLatitude(), 0.0001);
        assertEquals(76.3753, waypoints.get(2).getLongitude(), 0.0001);

        assertEquals(10.0889, waypoints.get(3).getLatitude(), 0.0001);
        assertEquals(77.0595, waypoints.get(3).getLongitude(), 0.0001);
        assertEquals("Destination", waypoints.get(3).getName());
    }

    @Test
    @DisplayName("5. Legacy query parameters format (?saddr=...&daddr=...+to:...)")
    void testLegacySaddrDaddrFormat() {
        String url = "https://maps.google.com/maps?saddr=10.5276,76.2144&daddr=10.6517,76.0409+to:10.0889,77.0595";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(3, waypoints.size());

        assertEquals(10.5276, waypoints.get(0).getLatitude(), 0.0001);
        assertEquals(76.2144, waypoints.get(0).getLongitude(), 0.0001);
        assertEquals("Origin", waypoints.get(0).getName());

        assertEquals(10.6517, waypoints.get(1).getLatitude(), 0.0001);
        assertEquals(76.0409, waypoints.get(1).getLongitude(), 0.0001);

        assertEquals(10.0889, waypoints.get(2).getLatitude(), 0.0001);
        assertEquals(77.0595, waypoints.get(2).getLongitude(), 0.0001);
        assertEquals("Destination", waypoints.get(2).getName());
    }

    @Test
    @DisplayName("6. Shortened URL redirect resolution using mock RedirectResolver")
    void testShortenedUrlResolutionWithMock() {
        String shortUrl = "https://maps.app.goo.gl/KeralaRouteTest";
        String resolvedTarget = "https://www.google.com/maps/dir/10.5276,76.2144/10.0889,77.0595";

        GoogleMapsRouteParser parserWithMock = new GoogleMapsRouteParser(url -> {
            if (url.equals(shortUrl)) return resolvedTarget;
            return url;
        });

        List<Waypoint> waypoints = parserWithMock.parseRoute(shortUrl);

        assertNotNull(waypoints);
        assertEquals(2, waypoints.size());
        assertEquals(10.5276, waypoints.get(0).getLatitude(), 0.0001);
        assertEquals(10.0889, waypoints.get(1).getLatitude(), 0.0001);
    }

    @Test
    @DisplayName("7. Coordinate validation rejects out-of-bounds latitude and longitude")
    void testCoordinateValidation_rejectsOutOfBounds() {
        // Latitude > 90
        String invalidLat = "https://www.google.com/maps/dir/95.5276,76.2144/10.0889,77.0595";
        RouteParseException ex1 = assertThrows(RouteParseException.class, () -> parser.parseRoute(invalidLat));
        assertTrue(ex1.getMessage().contains("latitude") || ex1.getMessage().contains("coordinates"));

        // Longitude > 180
        String invalidLon = "https://www.google.com/maps/dir/10.5276,195.2144/10.0889,77.0595";
        RouteParseException ex2 = assertThrows(RouteParseException.class, () -> parser.parseRoute(invalidLon));
        assertTrue(ex2.getMessage().contains("longitude") || ex2.getMessage().contains("coordinates"));
    }

    @Test
    @DisplayName("8. Rejects non-Google Maps URLs")
    void testInvalidNonGoogleUrl() {
        assertThrows(RouteParseException.class, () -> parser.parseRoute("https://www.bing.com/maps/dir/10,76/10,77"));
        assertThrows(RouteParseException.class, () -> parser.parseRoute("https://openstreetmap.org/directions?from=10,76&to=10,77"));
        assertThrows(RouteParseException.class, () -> parser.parseRoute("https://example.com/route"));
    }

    @Test
    @DisplayName("9. Rejects null, blank, and empty inputs")
    void testBlankOrNullInput() {
        assertThrows(RouteParseException.class, () -> parser.parseRoute(null));
        assertThrows(RouteParseException.class, () -> parser.parseRoute(""));
        assertThrows(RouteParseException.class, () -> parser.parseRoute("   "));
    }

    @Test
    @DisplayName("10. Rejects route with fewer than 2 waypoints")
    void testFewerThanTwoWaypoints() {
        String singlePointUrl = "https://www.google.com/maps/dir/10.5276,76.2144";
        RouteParseException ex = assertThrows(RouteParseException.class, () -> parser.parseRoute(singlePointUrl));
        assertTrue(ex.getMessage().contains("at least 2 waypoints"));
    }

    @Test
    @DisplayName("11. Rejects route with place names but missing coordinates without undocumented scraping")
    void testPlaceNamesWithoutCoordinates_rejectsGracefully() {
        String namesOnlyUrl = "https://www.google.com/maps/dir/Thrissur/Munnar";
        RouteParseException ex = assertThrows(RouteParseException.class, () -> parser.parseRoute(namesOnlyUrl));
        assertTrue(ex.getMessage().contains("does not contain geographic coordinates"));
    }

    @Test
    @DisplayName("12. Parsed Waypoint[] enters RouteService cleanly and builds valid OSRM query")
    void testParsedWaypointsEnterRouteService() {
        String url = "https://www.google.com/maps/dir/10.5276,76.2144/10.6517,76.0409/10.1849,76.3753/10.0889,77.0595";

        List<Waypoint> waypoints = parser.parseRoute(url);

        // Validate via RouteService
        assertDoesNotThrow(() -> routeService.validateWaypoints(waypoints));

        // Build OSRM query URL
        String osrmUrl = routeService.buildOsrmUrl(waypoints);
        assertNotNull(osrmUrl);

        // OSRM coordinates must be formatted as lon,lat separated by semicolons
        assertTrue(osrmUrl.contains("76.214400,10.527600;76.040900,10.651700;76.375300,10.184900;77.059500,10.088900"));
    }

    @Test
    @DisplayName("13. 5-stop ordered route A -> B -> C -> D -> E preserves all 5 waypoints in exact sequence")
    void testFiveStopMultiWaypointRoutePreservedInOrder() {
        String url = "https://www.google.com/maps/dir/10.5276,76.2144/10.6517,76.0409/10.3528,76.2120/10.1849,76.3753/10.0889,77.0595";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(5, waypoints.size(), "All 5 waypoints must be preserved, none lost");

        // Sequence 1: Origin
        assertEquals(1, waypoints.get(0).getSequence());
        assertEquals(10.5276, waypoints.get(0).getLatitude(), 1e-4);
        assertEquals(76.2144, waypoints.get(0).getLongitude(), 1e-4);
        assertEquals("Origin", waypoints.get(0).getName());

        // Sequence 2: Stop 1
        assertEquals(2, waypoints.get(1).getSequence());
        assertEquals(10.6517, waypoints.get(1).getLatitude(), 1e-4);
        assertEquals(76.0409, waypoints.get(1).getLongitude(), 1e-4);

        // Sequence 3: Stop 2
        assertEquals(3, waypoints.get(2).getSequence());
        assertEquals(10.3528, waypoints.get(2).getLatitude(), 1e-4);
        assertEquals(76.2120, waypoints.get(2).getLongitude(), 1e-4);

        // Sequence 4: Stop 3
        assertEquals(4, waypoints.get(3).getSequence());
        assertEquals(10.1849, waypoints.get(3).getLatitude(), 1e-4);
        assertEquals(76.3753, waypoints.get(3).getLongitude(), 1e-4);

        // Sequence 5: Destination
        assertEquals(5, waypoints.get(4).getSequence());
        assertEquals(10.0889, waypoints.get(4).getLatitude(), 1e-4);
        assertEquals(77.0595, waypoints.get(4).getLongitude(), 1e-4);
        assertEquals("Destination", waypoints.get(4).getName());
    }

    @Test
    @DisplayName("14. Route with !3d<lat>!4d<lon> protobuf parameters parses all stops")
    void testProtobufWith3dLat4dLonParameters() {
        String url = "https://www.google.com/maps/dir/Thrissur/Kunnamkulam/Chalakudy/Munnar/@10.3703,76.5169,10z/data=!4m26!4m25!1m5!1m1!1s0x3ba7ee15ed42d1bb:0x82e45aa016ca7db!3d10.5276!4d76.2144!1m5!1m1!1s0x3ba7954b9d034871:0x1b131aeb7c7e9bb4!3d10.6517!4d76.0409!1m5!1m1!1s0x3ba7ef35ed42d1bb:0x82e45aa016ca7db!3d10.3069!4d76.3317!1m5!1m1!1s0x3b0799794d099023:0x4d328905da671f64!3d10.0889!4d77.0595";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(4, waypoints.size(), "Must parse all 4 stops with !3d!4d encoding");
        assertEquals("Thrissur", waypoints.get(0).getName());
        assertEquals(10.5276, waypoints.get(0).getLatitude(), 1e-4);
        assertEquals(76.2144, waypoints.get(0).getLongitude(), 1e-4);

        assertEquals("Kunnamkulam", waypoints.get(1).getName());
        assertEquals(10.6517, waypoints.get(1).getLatitude(), 1e-4);
        assertEquals(76.0409, waypoints.get(1).getLongitude(), 1e-4);

        assertEquals("Chalakudy", waypoints.get(2).getName());
        assertEquals(10.3069, waypoints.get(2).getLatitude(), 1e-4);
        assertEquals(76.3317, waypoints.get(2).getLongitude(), 1e-4);

        assertEquals("Munnar", waypoints.get(3).getName());
        assertEquals(10.0889, waypoints.get(3).getLatitude(), 1e-4);
        assertEquals(77.0595, waypoints.get(3).getLongitude(), 1e-4);
    }

    @Test
    @DisplayName("15. Route with !2d<lat>!1d<lon> reversed protobuf parameters parses correctly")
    void testProtobufWith2dLat1dLonParameters() {
        String url = "https://www.google.com/maps/dir/OriginPlace/DestPlace/data=!4m10!1m3!2d10.5276!1d76.2144!1m3!2d10.0889!1d77.0595";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(2, waypoints.size());
        assertEquals(10.5276, waypoints.get(0).getLatitude(), 1e-4);
        assertEquals(76.2144, waypoints.get(0).getLongitude(), 1e-4);
        assertEquals(10.0889, waypoints.get(1).getLatitude(), 1e-4);
        assertEquals(77.0595, waypoints.get(1).getLongitude(), 1e-4);
    }

    @Test
    @DisplayName("16. Route with !4d<lon>!3d<lat> reversed protobuf parameters parses correctly")
    void testProtobufWith4dLon3dLatParameters() {
        String url = "https://www.google.com/maps/dir/OriginPlace/DestPlace/data=!4m10!1m3!4d76.2144!3d10.5276!1m3!4d77.0595!3d10.0889";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(2, waypoints.size());
        assertEquals(10.5276, waypoints.get(0).getLatitude(), 1e-4);
        assertEquals(76.2144, waypoints.get(0).getLongitude(), 1e-4);
        assertEquals(10.0889, waypoints.get(1).getLatitude(), 1e-4);
        assertEquals(77.0595, waypoints.get(1).getLongitude(), 1e-4);
    }

    @Test
    @DisplayName("17. Mixed coordinate and named path segments where missing coords are supplied in protobuf data")
    void testMixedCoordinateAndNamedPathSegments() {
        // Path has: explicit coord (origin) -> name (Kunnamkulam) -> explicit coord (Angamaly) -> name (Munnar)
        String url = "https://www.google.com/maps/dir/10.5276,76.2144/Kunnamkulam/10.1849,76.3753/Munnar/@10.3703,76.5169,10z/data=!4m10!1m2!1d76.0409!2d10.6517!1m2!1d77.0595!2d10.0889";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(4, waypoints.size());
        assertEquals(1, waypoints.get(0).getSequence());
        assertEquals(10.5276, waypoints.get(0).getLatitude(), 1e-4);
        assertEquals(76.2144, waypoints.get(0).getLongitude(), 1e-4);

        assertEquals(2, waypoints.get(1).getSequence());
        assertEquals("Kunnamkulam", waypoints.get(1).getName());
        assertEquals(10.6517, waypoints.get(1).getLatitude(), 1e-4);
        assertEquals(76.0409, waypoints.get(1).getLongitude(), 1e-4);

        assertEquals(3, waypoints.get(2).getSequence());
        assertEquals(10.1849, waypoints.get(2).getLatitude(), 1e-4);
        assertEquals(76.3753, waypoints.get(2).getLongitude(), 1e-4);

        assertEquals(4, waypoints.get(3).getSequence());
        assertEquals("Munnar", waypoints.get(3).getName());
        assertEquals(10.0889, waypoints.get(3).getLatitude(), 1e-4);
        assertEquals(77.0595, waypoints.get(3).getLongitude(), 1e-4);
    }

    @Test
    @DisplayName("18. Rejects identical coordinates where route distance is 0")
    void testRejectsIdenticalCoordinates() {
        String identicalCoordsUrl = "https://www.google.com/maps/dir/10.5276,76.2144/10.5276,76.2144";

        RouteParseException ex = assertThrows(RouteParseException.class, () -> parser.parseRoute(identicalCoordsUrl));
        assertTrue(ex.getMessage().contains("identical") || ex.getMessage().contains("distance is 0"));
    }

    @Test
    @DisplayName("19. Multi-stop legacy query parameters parses all intermediate stops (+to: and space to:)")
    void testMultiStopLegacyQueryParams() {
        // Test with +to:
        String urlPlus = "https://maps.google.com/maps?saddr=10.5276,76.2144&daddr=10.6517,76.0409+to:10.1849,76.3753+to:10.0889,77.0595";
        List<Waypoint> waypointsPlus = parser.parseRoute(urlPlus);

        assertNotNull(waypointsPlus);
        assertEquals(4, waypointsPlus.size());
        assertEquals(1, waypointsPlus.get(0).getSequence());
        assertEquals(10.5276, waypointsPlus.get(0).getLatitude(), 1e-4);
        assertEquals(2, waypointsPlus.get(1).getSequence());
        assertEquals(10.6517, waypointsPlus.get(1).getLatitude(), 1e-4);
        assertEquals(3, waypointsPlus.get(2).getSequence());
        assertEquals(10.1849, waypointsPlus.get(2).getLatitude(), 1e-4);
        assertEquals(4, waypointsPlus.get(3).getSequence());
        assertEquals(10.0889, waypointsPlus.get(3).getLatitude(), 1e-4);

        // Test with space to:
        String urlSpace = "https://maps.google.com/maps?saddr=10.5276,76.2144&daddr=10.6517,76.0409 to:10.0889,77.0595";
        List<Waypoint> waypointsSpace = parser.parseRoute(urlSpace);
        assertNotNull(waypointsSpace);
        assertEquals(3, waypointsSpace.size());
    }

    @Test
    @DisplayName("20. Real 8-waypoint Google Maps route URL with place names and protobuf data coordinates parses all 8 stops")
    void testEightWaypointGoogleMapsRouteFormat_allWaypointsPreservedInOrder() {
        // Realistic Google Maps directions URL with 8 named stops and corresponding protobuf data coordinates
        String url8 = "https://www.google.com/maps/dir/"
                + "Thrissur,+Kerala/"
                + "Kunnamkulam,+Kerala/"
                + "Guruvayur,+Kerala/"
                + "Chavakkad,+Kerala/"
                + "Kodungallur,+Kerala/"
                + "Angamaly,+Kerala/"
                + "Aluva,+Kerala/"
                + "Kochi,+Kerala/"
                + "@10.3200,76.2100,10z/data=!4m50!4m49"
                + "!1m5!1m1!1s0x3ba7ee15ed42d1bb:0x82e45aa016ca7db!2m2!1d76.2144!2d10.5276"
                + "!1m5!1m1!1s0x3ba7954b9d034871:0x1b131aeb7c7e9bb4!2m2!1d76.0409!2d10.6517"
                + "!1m5!1m1!1s0x3ba7947da0df54cf:0xa82fa8f237db8759!2m2!1d76.0416!2d10.5946"
                + "!1m5!1m1!1s0x3ba793a6f1947b77:0xdbb73fc2679cfef6!2m2!1d76.0249!2d10.5843"
                + "!1m5!1m1!1s0x3b081cf5d3a50d2b:0x597adff6a17b088b!2m2!1d76.2140!2d10.2277"
                + "!1m5!1m1!1s0x3b0805175cf156cb:0xb8e96bf71ecb3d!2m2!1d76.3753!2d10.1849"
                + "!1m5!1m1!1s0x3b080eb21dd83a69:0x2289c629fb3e370a!2m2!1d76.3556!2d10.1076"
                + "!1m5!1m1!1s0x3b080d514abec6bf:0x285839490100494!2m2!1d76.2673!2d9.9312!3e0";

        List<Waypoint> waypoints = parser.parseRoute(url8);

        assertNotNull(waypoints, "Parsed waypoints must not be null");
        assertEquals(8, waypoints.size(), "All 8 waypoints must be preserved exactly in sequence");

        // Verify sequential sequence numbers 1 to 8
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, waypoints.get(i).getSequence(), "Waypoint index " + i + " must have sequence " + (i + 1));
            assertNotNull(waypoints.get(i).getLatitude(), "Latitude must not be null");
            assertNotNull(waypoints.get(i).getLongitude(), "Longitude must not be null");
            assertFalse(waypoints.get(i).getName().isBlank(), "Name must not be blank");
        }

        // 1. Origin: Thrissur
        assertEquals("Thrissur, Kerala", waypoints.get(0).getName());
        assertEquals(10.5276, waypoints.get(0).getLatitude(), 1e-4);
        assertEquals(76.2144, waypoints.get(0).getLongitude(), 1e-4);

        // 2. Kunnamkulam
        assertEquals("Kunnamkulam, Kerala", waypoints.get(1).getName());
        assertEquals(10.6517, waypoints.get(1).getLatitude(), 1e-4);
        assertEquals(76.0409, waypoints.get(1).getLongitude(), 1e-4);

        // 3. Guruvayur
        assertEquals("Guruvayur, Kerala", waypoints.get(2).getName());
        assertEquals(10.5946, waypoints.get(2).getLatitude(), 1e-4);
        assertEquals(76.0416, waypoints.get(2).getLongitude(), 1e-4);

        // 4. Chavakkad
        assertEquals("Chavakkad, Kerala", waypoints.get(3).getName());
        assertEquals(10.5843, waypoints.get(3).getLatitude(), 1e-4);
        assertEquals(76.0249, waypoints.get(3).getLongitude(), 1e-4);

        // 5. Kodungallur
        assertEquals("Kodungallur, Kerala", waypoints.get(4).getName());
        assertEquals(10.2277, waypoints.get(4).getLatitude(), 1e-4);
        assertEquals(76.2140, waypoints.get(4).getLongitude(), 1e-4);

        // 6. Angamaly
        assertEquals("Angamaly, Kerala", waypoints.get(5).getName());
        assertEquals(10.1849, waypoints.get(5).getLatitude(), 1e-4);
        assertEquals(76.3753, waypoints.get(5).getLongitude(), 1e-4);

        // 7. Aluva
        assertEquals("Aluva, Kerala", waypoints.get(6).getName());
        assertEquals(10.1076, waypoints.get(6).getLatitude(), 1e-4);
        assertEquals(76.3556, waypoints.get(6).getLongitude(), 1e-4);

        // 8. Destination: Kochi
        assertEquals("Kochi, Kerala", waypoints.get(7).getName());
        assertEquals(9.9312, waypoints.get(7).getLatitude(), 1e-4);
        assertEquals(76.2673, waypoints.get(7).getLongitude(), 1e-4);
    }

    @Test
    @DisplayName("21. Real mobile Google Maps share redirect URL with geocode= parameter parses all 9 stops of circular route")
    void testRealMobileGoogleMapsShareUrl_circularRouteWithNineStops() {
        String url = "https://maps.google.com/?geocode=FVyvpAAdCHGHBA%3D%3D;FXfLpAAdC2aHBClzbIxIALunOzFnXN1b2FxEHA%3D%3D;FaXXpAAd6ZqHBClXsBG6h7mnOzFv5ydyGf1fEA%3D%3D;FZQupQAdEomHBCmJk4s2HbqnOzH_Y0I2Je4-6g%3D%3D;FRIYpQAd2ymHBCkJ1-pLALunOzHZRz8mMs4qMQ%3D%3D;Fe-zpAAdLsmGBCknOO_D-LqnOzFI7LFx6otlTw%3D%3D;FWhNpAAdjYmGBCnBT_sBFrunOzHhuUU-NQ5z9A%3D%3D;FeqLpAAdQ8mHBClJq842brmnOzFAhWlnP_WkRg%3D%3D;FVyvpAAdCHGHBA%3D%3D&daddr=Polpakkara,+Pothanoor,+Keralam+to:Perumparamba+Shiva+Temple,+Edappal,+Keralam+to:Parappuram,+Keralam+679582+to:Nariparambu,+Edappal-Tirur+Rd,+Chamravattom,+Keralam+to:Karma+Road,+Keralam+to:Ponnani,+Keralam+to:Edappal+Flyover,+Edappal,+Keralam+679576+to:10.7927959,75.9851598&saddr=10.7927960,75.9851600&dirflg=d";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(9, waypoints.size(), "Must extract all 9 waypoints from mobile geocode parameter");

        // Sequence numbers 1 to 9
        for (int i = 0; i < 9; i++) {
            assertEquals(i + 1, waypoints.get(i).getSequence());
            assertNotNull(waypoints.get(i).getLatitude());
            assertNotNull(waypoints.get(i).getLongitude());
            assertFalse(waypoints.get(i).getName().isBlank());
        }

        // 1. Origin: 10.792796, 75.985160
        assertEquals("Origin", waypoints.get(0).getName());
        assertEquals(10.792796, waypoints.get(0).getLatitude(), 1e-4);
        assertEquals(75.985160, waypoints.get(0).getLongitude(), 1e-4);

        // 2. Polpakkara
        assertEquals("Polpakkara, Pothanoor, Keralam", waypoints.get(1).getName());
        assertEquals(10.799991, waypoints.get(1).getLatitude(), 1e-4);
        assertEquals(75.982347, waypoints.get(1).getLongitude(), 1e-4);

        // 3. Perumparamba Shiva Temple
        assertEquals("Perumparamba Shiva Temple, Edappal, Keralam", waypoints.get(2).getName());
        assertEquals(10.803109, waypoints.get(2).getLatitude(), 1e-4);
        assertEquals(75.995881, waypoints.get(2).getLongitude(), 1e-4);

        // 4. Parappuram
        assertEquals("Parappuram, Keralam 679582", waypoints.get(3).getName());
        assertEquals(10.825364, waypoints.get(3).getLatitude(), 1e-4);
        assertEquals(75.991314, waypoints.get(3).getLongitude(), 1e-4);

        // 5. Nariparambu
        assertEquals("Nariparambu, Edappal-Tirur Rd, Chamravattom, Keralam", waypoints.get(4).getName());
        assertEquals(10.819602, waypoints.get(4).getLatitude(), 1e-4);
        assertEquals(75.966939, waypoints.get(4).getLongitude(), 1e-4);

        // 6. Karma Road
        assertEquals("Karma Road, Keralam", waypoints.get(5).getName());
        assertEquals(10.793967, waypoints.get(5).getLatitude(), 1e-4);
        assertEquals(75.942190, waypoints.get(5).getLongitude(), 1e-4);

        // 7. Ponnani
        assertEquals("Ponnani, Keralam", waypoints.get(6).getName());
        assertEquals(10.767720, waypoints.get(6).getLatitude(), 1e-4);
        assertEquals(75.925901, waypoints.get(6).getLongitude(), 1e-4);

        // 8. Edappal Flyover
        assertEquals("Edappal Flyover, Edappal, Keralam 679576", waypoints.get(7).getName());
        assertEquals(10.783722, waypoints.get(7).getLatitude(), 1e-4);
        assertEquals(76.007747, waypoints.get(7).getLongitude(), 1e-4);

        // 9. Destination: 10.792796, 75.985160
        assertEquals("Destination", waypoints.get(8).getName());
        assertEquals(10.792796, waypoints.get(8).getLatitude(), 1e-4);
        assertEquals(75.985160, waypoints.get(8).getLongitude(), 1e-4);
    }

    @Test
    @DisplayName("22. Real mobile Google Maps 8-waypoint route with geocode= parameter preserves all 8 stops in exact order")
    void testRealMobileGoogleMapsShareUrl_eightWaypointRoute() {
        // 8-waypoint point-to-point route: Origin + 7 stops
        String geocode8 = "FVyvpAAdCHGHBA%3D%3D;"
                + "FXfLpAAdC2aHBClzbIxIALunOzFnXN1b2FxEHA%3D%3D;"
                + "FaXXpAAd6ZqHBClXsBG6h7mnOzFv5ydyGf1fEA%3D%3D;"
                + "FZQupQAdEomHBCmJk4s2HbqnOzH_Y0I2Je4-6g%3D%3D;"
                + "FRIYpQAd2ymHBCkJ1-pLALunOzHZRz8mMs4qMQ%3D%3D;"
                + "Fe-zpAAdLsmGBCknOO_D-LqnOzFI7LFx6otlTw%3D%3D;"
                + "FWhNpAAdjYmGBCnBT_sBFrunOzHhuUU-NQ5z9A%3D%3D;"
                + "FeqLpAAdQ8mHBClJq842brmnOzFAhWlnP_WkRg%3D%3D";
        String daddr7 = "Polpakkara,+Pothanoor,+Keralam"
                + "+to:Perumparamba+Shiva+Temple,+Edappal,+Keralam"
                + "+to:Parappuram,+Keralam+679582"
                + "+to:Nariparambu,+Edappal-Tirur+Rd,+Chamravattom,+Keralam"
                + "+to:Karma+Road,+Keralam"
                + "+to:Ponnani,+Keralam"
                + "+to:Edappal+Flyover,+Edappal,+Keralam+679576";
        String url8 = "https://maps.google.com/?geocode=" + geocode8
                + "&daddr=" + daddr7
                + "&saddr=10.7927960,75.9851600&dirflg=d";

        List<Waypoint> waypoints = parser.parseRoute(url8);

        assertNotNull(waypoints);
        assertEquals(8, waypoints.size(), "Must extract all 8 waypoints in sequence");

        // Verify sequential sequence numbers 1 to 8
        for (int i = 0; i < 8; i++) {
            assertEquals(i + 1, waypoints.get(i).getSequence());
            assertNotNull(waypoints.get(i).getLatitude());
            assertNotNull(waypoints.get(i).getLongitude());
            assertFalse(waypoints.get(i).getName().isBlank());
        }

        // 1. Origin
        assertEquals("Origin", waypoints.get(0).getName());
        assertEquals(10.792796, waypoints.get(0).getLatitude(), 1e-4);
        assertEquals(75.985160, waypoints.get(0).getLongitude(), 1e-4);

        // 2. Polpakkara
        assertEquals("Polpakkara, Pothanoor, Keralam", waypoints.get(1).getName());
        assertEquals(10.799991, waypoints.get(1).getLatitude(), 1e-4);
        assertEquals(75.982347, waypoints.get(1).getLongitude(), 1e-4);

        // 3. Perumparamba Shiva Temple
        assertEquals("Perumparamba Shiva Temple, Edappal, Keralam", waypoints.get(2).getName());
        assertEquals(10.803109, waypoints.get(2).getLatitude(), 1e-4);
        assertEquals(75.995881, waypoints.get(2).getLongitude(), 1e-4);

        // 4. Parappuram
        assertEquals("Parappuram, Keralam 679582", waypoints.get(3).getName());
        assertEquals(10.825364, waypoints.get(3).getLatitude(), 1e-4);
        assertEquals(75.991314, waypoints.get(3).getLongitude(), 1e-4);

        // 5. Nariparambu
        assertEquals("Nariparambu, Edappal-Tirur Rd, Chamravattom, Keralam", waypoints.get(4).getName());
        assertEquals(10.819602, waypoints.get(4).getLatitude(), 1e-4);
        assertEquals(75.966939, waypoints.get(4).getLongitude(), 1e-4);

        // 6. Karma Road
        assertEquals("Karma Road, Keralam", waypoints.get(5).getName());
        assertEquals(10.793967, waypoints.get(5).getLatitude(), 1e-4);
        assertEquals(75.942190, waypoints.get(5).getLongitude(), 1e-4);

        // 7. Ponnani
        assertEquals("Ponnani, Keralam", waypoints.get(6).getName());
        assertEquals(10.767720, waypoints.get(6).getLatitude(), 1e-4);
        assertEquals(75.925901, waypoints.get(6).getLongitude(), 1e-4);

        // 8. Edappal Flyover (Destination)
        assertEquals("Edappal Flyover, Edappal, Keralam 679576", waypoints.get(7).getName());
        assertEquals(10.783722, waypoints.get(7).getLatitude(), 1e-4);
        assertEquals(76.007747, waypoints.get(7).getLongitude(), 1e-4);
    }

    @Test
    @DisplayName("23. Real mobile Google Maps URL with named place origin and stops parses all coordinates from geocode=")
    void testRealMobileGoogleMapsShareUrl_fiveNamedStops() {
        String url = "https://maps.google.com/?geocode=Fc2WpAAdMoaHBCkb_cEAALunOzGSJ5pcDM7qRA%3D%3D;FRIYpQAd4CmHBCkAAAAAAAAAADHZRz8mMs4qMQ%3D%3D;Fe-zpAAdLsmGBCknOO_D-LqnOzFI7LFx6otlTw%3D%3D;FVqFpAAdoW6GBCkAAAAAAAAAADG_LJof_HEPbg%3D%3D;FXfLpAAdC2aHBClzbIxIALunOzFnXN1b2FxEHA%3D%3D&daddr=Nariparambu,+Edappal-Tirur+Rd,+Chamravattom,+Kerala,+India+to:Karma+Road,+Keralam+to:Ponnani+Court,+Malappuram,+Ponnani,+Kerala,+India,+Ponnani+to:Polpakkara,+Pothanoor,+Keralam&saddr=Tharakkal,+Chembayil+house,+Thattanpadi+Road,+tharakkal,+Edappal,+Keralam";

        List<Waypoint> waypoints = parser.parseRoute(url);

        assertNotNull(waypoints);
        assertEquals(5, waypoints.size());

        assertEquals("Tharakkal, Chembayil house, Thattanpadi Road, tharakkal, Edappal, Keralam", waypoints.get(0).getName());
        assertEquals(10.786509, waypoints.get(0).getLatitude(), 1e-4);
        assertEquals(75.990578, waypoints.get(0).getLongitude(), 1e-4);
        assertEquals(1, waypoints.get(0).getSequence());

        assertEquals("Nariparambu, Edappal-Tirur Rd, Chamravattom, Kerala, India", waypoints.get(1).getName());
        assertEquals(10.819602, waypoints.get(1).getLatitude(), 1e-4);
        assertEquals(75.966944, waypoints.get(1).getLongitude(), 1e-4);
        assertEquals(2, waypoints.get(1).getSequence());

        assertEquals("Karma Road, Keralam", waypoints.get(2).getName());
        assertEquals(10.793967, waypoints.get(2).getLatitude(), 1e-4);
        assertEquals(75.942190, waypoints.get(2).getLongitude(), 1e-4);
        assertEquals(3, waypoints.get(2).getSequence());

        assertEquals("Ponnani Court, Malappuram, Ponnani, Kerala, India, Ponnani", waypoints.get(3).getName());
        assertEquals(10.782042, waypoints.get(3).getLatitude(), 1e-4);
        assertEquals(75.919009, waypoints.get(3).getLongitude(), 1e-4);
        assertEquals(4, waypoints.get(3).getSequence());

        assertEquals("Polpakkara, Pothanoor, Keralam", waypoints.get(4).getName());
        assertEquals(10.799991, waypoints.get(4).getLatitude(), 1e-4);
        assertEquals(75.982347, waypoints.get(4).getLongitude(), 1e-4);
        assertEquals(5, waypoints.get(4).getSequence());
    }

    @Test
    @DisplayName("24. Real mobile short link https://maps.app.goo.gl/tsdegiGw7Q7Snc2XA resolves via RedirectResolver and parses all stops")
    void testMobileShortLinkWithMockResolver_resolvesAndParsesAllStops() {
        String shortUrl = "https://maps.app.goo.gl/tsdegiGw7Q7Snc2XA?g_st=ic";
        String resolvedUrl = "https://maps.google.com/?geocode=FVyvpAAdCHGHBA%3D%3D;FXfLpAAdC2aHBClzbIxIALunOzFnXN1b2FxEHA%3D%3D;FaXXpAAd6ZqHBClXsBG6h7mnOzFv5ydyGf1fEA%3D%3D;FZQupQAdEomHBCmJk4s2HbqnOzH_Y0I2Je4-6g%3D%3D;FRIYpQAd2ymHBCkJ1-pLALunOzHZRz8mMs4qMQ%3D%3D;Fe-zpAAdLsmGBCknOO_D-LqnOzFI7LFx6otlTw%3D%3D;FWhNpAAdjYmGBCnBT_sBFrunOzHhuUU-NQ5z9A%3D%3D;FeqLpAAdQ8mHBClJq842brmnOzFAhWlnP_WkRg%3D%3D;FVyvpAAdCHGHBA%3D%3D&daddr=Polpakkara,+Pothanoor,+Keralam+to:Perumparamba+Shiva+Temple,+Edappal,+Keralam+to:Parappuram,+Keralam+679582+to:Nariparambu,+Edappal-Tirur+Rd,+Chamravattom,+Keralam+to:Karma+Road,+Keralam+to:Ponnani,+Keralam+to:Edappal+Flyover,+Edappal,+Keralam+679576+to:10.7927959,75.9851598&saddr=10.7927960,75.9851600&dirflg=d";

        GoogleMapsRouteParser parserWithMock = new GoogleMapsRouteParser(url -> {
            if (url.startsWith(shortUrl) || shortUrl.startsWith(url)) return resolvedUrl;
            return url;
        });

        List<Waypoint> waypoints = parserWithMock.parseRoute(shortUrl);

        assertNotNull(waypoints);
        assertEquals(9, waypoints.size());
        assertEquals("Origin", waypoints.get(0).getName());
        assertEquals(10.792796, waypoints.get(0).getLatitude(), 1e-4);
        assertEquals("Polpakkara, Pothanoor, Keralam", waypoints.get(1).getName());
        assertEquals(10.799991, waypoints.get(1).getLatitude(), 1e-4);
        assertEquals("Destination", waypoints.get(8).getName());
        assertEquals(10.792796, waypoints.get(8).getLatitude(), 1e-4);
    }

    @Test
    @DisplayName("25. Identical-coordinate rejection still works when geocode contains genuinely identical coordinates")
    void testMobileGeocodeWithAllIdenticalCoordinates_rejectsZeroDistance() {
        // 3 tokens all encoding identical coordinate 10.792796, 75.985160
        String identicalGeocode = "https://maps.google.com/?geocode=FVyvpAAdCHGHBA%3D%3D;FVyvpAAdCHGHBA%3D%3D;FVyvpAAdCHGHBA%3D%3D&saddr=SamePlace&daddr=SamePlace+to:SamePlace";

        RouteParseException ex = assertThrows(RouteParseException.class, () -> parser.parseRoute(identicalGeocode));
        assertTrue(ex.getMessage().contains("identical") || ex.getMessage().contains("distance is 0"),
                "Must reject route with identical coordinates: " + ex.getMessage());
    }
}
