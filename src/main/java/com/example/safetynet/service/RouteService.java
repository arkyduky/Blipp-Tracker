package com.example.safetynet.service;

import com.example.safetynet.dto.RouteLeg;
import com.example.safetynet.dto.RouteResult;
import com.example.safetynet.dto.Waypoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Service responsible for requesting driving routes and geometries from OSRM
 * based on an ordered list of geographic waypoints.
 */
@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);
    private static final String DEFAULT_OSRM_URL = "https://router.project-osrm.org";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String osrmBaseUrl;

    public RouteService() {
        this(new RestTemplate(), new ObjectMapper(), DEFAULT_OSRM_URL);
    }

    public RouteService(RestTemplate restTemplate) {
        this(restTemplate, new ObjectMapper(), DEFAULT_OSRM_URL);
    }

    @Autowired
    public RouteService(@Value("${osrm.base-url:https://router.project-osrm.org}") String osrmBaseUrl) {
        this(new RestTemplate(), new ObjectMapper(), osrmBaseUrl);
    }

    public RouteService(RestTemplate restTemplate, ObjectMapper objectMapper, String osrmBaseUrl) {
        this.restTemplate = restTemplate != null ? restTemplate : new RestTemplate();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.osrmBaseUrl = (osrmBaseUrl != null && !osrmBaseUrl.isBlank()) ? osrmBaseUrl.trim() : DEFAULT_OSRM_URL;
    }

    /**
     * Validates that the waypoint list is non-null, contains at least 2 waypoints,
     * and that all coordinates are within valid geographic bounds.
     */
    public void validateWaypoints(List<Waypoint> waypoints) {
        if (waypoints == null || waypoints.size() < 2) {
            throw new IllegalArgumentException("At least 2 waypoints are required for route calculation");
        }

        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            if (wp == null) {
                throw new IllegalArgumentException("Waypoint at index " + i + " must not be null");
            }
            if (wp.getLatitude() == null || wp.getLongitude() == null) {
                throw new IllegalArgumentException("Waypoint at index " + i + " has null latitude or longitude");
            }
            if (Double.isNaN(wp.getLatitude()) || Double.isInfinite(wp.getLatitude()) ||
                    wp.getLatitude() < -90.0 || wp.getLatitude() > 90.0) {
                throw new IllegalArgumentException("Waypoint at index " + i + " has invalid latitude: " + wp.getLatitude() + " (must be between -90 and 90)");
            }
            if (Double.isNaN(wp.getLongitude()) || Double.isInfinite(wp.getLongitude()) ||
                    wp.getLongitude() < -180.0 || wp.getLongitude() > 180.0) {
                throw new IllegalArgumentException("Waypoint at index " + i + " has invalid longitude: " + wp.getLongitude() + " (must be between -180 and 180)");
            }
        }
    }

    /**
     * Constructs the full OSRM driving route URL using the ordered coordinates
     * formatted strictly as longitude,latitude separated by semicolons.
     */
    public String buildOsrmUrl(List<Waypoint> waypoints) {
        validateWaypoints(waypoints);

        String base = osrmBaseUrl.endsWith("/")
                ? osrmBaseUrl.substring(0, osrmBaseUrl.length() - 1)
                : osrmBaseUrl;

        List<String> coordPairs = new ArrayList<>(waypoints.size());
        for (Waypoint wp : waypoints) {
            // CRITICAL: OSRM expects longitude first, then latitude: {lon},{lat}
            coordPairs.add(String.format(Locale.US, "%.6f,%.6f", wp.getLongitude(), wp.getLatitude()));
        }

        String coordsParam = String.join(";", coordPairs);
        return base + "/route/v1/driving/" + coordsParam + "?overview=full&geometries=geojson";
    }

    /**
     * Requests route calculation from OSRM for the given ordered waypoints.
     *
     * @param waypoints Ordered list of waypoints (minimum 2)
     * @return RouteResult with GeoJSON geometry, total distance/duration, and leg metrics
     * @throws IllegalArgumentException if waypoints fail validation
     * @throws IllegalStateException    if OSRM request fails or response is malformed
     */
    public RouteResult calculateRoute(List<Waypoint> waypoints) {
        validateWaypoints(waypoints);
        String url = buildOsrmUrl(waypoints);

        log.debug("Calling OSRM route service at: {}", url);

        ResponseEntity<String> response;
        try {
            response = restTemplate.getForEntity(url, String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("OSRM HTTP response error: status={}", e.getStatusCode());
            throw new IllegalStateException("OSRM service returned non-successful HTTP status: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            log.error("OSRM HTTP request failed for URL {}: {}", url, e.getMessage());
            throw new IllegalStateException("Failed to connect to OSRM service: " + e.getMessage(), e);
        }

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.error("OSRM HTTP response not successful: status={}", response.getStatusCode());
            throw new IllegalStateException("OSRM service returned non-successful HTTP status: " + response.getStatusCode());
        }

        return parseOsrmResponse(response.getBody(), waypoints.size());
    }

    /**
     * Parses an OSRM JSON response body into a RouteResult.
     */
    public RouteResult parseOsrmResponse(String responseBody, int waypointCount) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Empty response body received from OSRM");
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String code = root.path("code").asText("");

            if (!"Ok".equalsIgnoreCase(code)) {
                String message = root.path("message").asText("Unknown routing error");
                throw new IllegalStateException("OSRM routing failed with code '" + code + "': " + message);
            }

            JsonNode routesNode = root.path("routes");
            if (!routesNode.isArray() || routesNode.isEmpty()) {
                throw new IllegalStateException("No routes found in OSRM response");
            }

            JsonNode primaryRoute = routesNode.get(0);

            if (!primaryRoute.hasNonNull("distance") || !primaryRoute.hasNonNull("duration")) {
                throw new IllegalStateException("Missing distance or duration in OSRM primary route");
            }

            double totalDistance = primaryRoute.path("distance").asDouble();
            double totalDuration = primaryRoute.path("duration").asDouble();

            JsonNode geometryNode = primaryRoute.path("geometry");
            if (geometryNode.isMissingNode() || geometryNode.isNull()) {
                throw new IllegalStateException("Missing geometry in OSRM primary route");
            }

            String geometryGeoJson = objectMapper.writeValueAsString(geometryNode);

            JsonNode coordinatesNode = geometryNode.path("coordinates");
            List<List<Double>> coordinates = new ArrayList<>();
            if (coordinatesNode.isArray()) {
                for (JsonNode pt : coordinatesNode) {
                    if (pt.isArray() && pt.size() >= 2) {
                        coordinates.add(List.of(pt.get(0).asDouble(), pt.get(1).asDouble()));
                    }
                }
            }

            if (coordinates.isEmpty()) {
                throw new IllegalStateException("OSRM route geometry coordinates are empty");
            }

            JsonNode legsNode = primaryRoute.path("legs");
            if (!legsNode.isArray() || legsNode.isEmpty()) {
                throw new IllegalStateException("Missing or empty legs array in OSRM route");
            }

            List<RouteLeg> legs = new ArrayList<>();
            for (int i = 0; i < legsNode.size(); i++) {
                JsonNode legNode = legsNode.get(i);
                if (!legNode.hasNonNull("distance") || !legNode.hasNonNull("duration")) {
                    throw new IllegalStateException("Leg " + i + " missing distance or duration");
                }
                double legDistance = legNode.path("distance").asDouble();
                double legDuration = legNode.path("duration").asDouble();
                legs.add(new RouteLeg(legDistance, legDuration, i, i + 1));
            }

            return new RouteResult(geometryGeoJson, coordinates, totalDistance, totalDuration, legs);

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse OSRM JSON response: {}", e.getMessage(), e);
            throw new IllegalStateException("Malformed OSRM response: " + e.getMessage(), e);
        }
    }
}
