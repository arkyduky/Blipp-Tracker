package com.example.safetynet.service;

import com.example.safetynet.dto.GeoProjectionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for projecting raw GPS coordinates onto a planned route polyline
 * (GeoJSON LineString) and calculating progress percentage (0-100) and off-route distance.
 */
@Service
public class GeoProjectionService {

    private static final Logger log = LoggerFactory.getLogger(GeoProjectionService.class);
    private static final double EARTH_RADIUS_METERS = 6371000.0;

    private final ObjectMapper objectMapper;

    public GeoProjectionService() {
        this(new ObjectMapper());
    }

    @Autowired
    public GeoProjectionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Projects a GPS point (latitude, longitude) onto the given route geometry.
     *
     * @param routeGeometry GeoJSON string containing LineString coordinates
     * @param latitude      GPS latitude
     * @param longitude     GPS longitude
     * @return GeoProjectionResult with progressPercent clamped to [0, 100], or invalid result
     */
    public GeoProjectionResult project(String routeGeometry, double latitude, double longitude) {
        return project(routeGeometry, latitude, longitude, null);
    }

    /**
     * Projects a GPS point onto the given route geometry with an optional authoritative total route distance.
     *
     * @param routeGeometry                    GeoJSON string containing LineString coordinates
     * @param latitude                         GPS latitude
     * @param longitude                        GPS longitude
     * @param authoritativeTotalDistanceMeters Authoritative route distance in meters (e.g. from Ride.totalDistanceMeters)
     * @return GeoProjectionResult with progressPercent clamped to [0, 100], or invalid result
     */
    public GeoProjectionResult project(String routeGeometry, double latitude, double longitude,
                                       Double authoritativeTotalDistanceMeters) {
        // 1. Validate GPS coordinate
        if (Double.isNaN(latitude) || Double.isInfinite(latitude) || latitude < -90.0 || latitude > 90.0 ||
                Double.isNaN(longitude) || Double.isInfinite(longitude) || longitude < -180.0 || longitude > 180.0) {
            log.warn("Invalid GPS coordinates for projection: lat={}, lon={}", latitude, longitude);
            return GeoProjectionResult.invalid();
        }

        // 2. Validate route geometry presence
        if (routeGeometry == null || routeGeometry.isBlank()) {
            log.warn("Cannot project GPS coordinate: route geometry is null or blank");
            return GeoProjectionResult.invalid();
        }

        // 3. Parse GeoJSON LineString coordinates
        List<double[]> points;
        try {
            JsonNode root = objectMapper.readTree(routeGeometry);
            JsonNode coordinatesNode = root.path("coordinates");
            if (coordinatesNode.isMissingNode() || !coordinatesNode.isArray()) {
                coordinatesNode = root.path("geometry").path("coordinates");
            }
            if (coordinatesNode.isMissingNode() || !coordinatesNode.isArray() || coordinatesNode.size() < 2) {
                log.warn("Route geometry has fewer than 2 coordinates or invalid GeoJSON structure");
                return GeoProjectionResult.invalid();
            }

            points = new ArrayList<>(coordinatesNode.size());
            for (JsonNode pt : coordinatesNode) {
                if (!pt.isArray() || pt.size() < 2) {
                    log.warn("Malformed coordinate entry in route geometry");
                    return GeoProjectionResult.invalid();
                }
                // GeoJSON format: [longitude, latitude]
                double ptLon = pt.get(0).asDouble();
                double ptLat = pt.get(1).asDouble();
                if (Double.isNaN(ptLat) || Double.isInfinite(ptLat) || ptLat < -90.0 || ptLat > 90.0 ||
                        Double.isNaN(ptLon) || Double.isInfinite(ptLon) || ptLon < -180.0 || ptLon > 180.0) {
                    log.warn("Out of bounds coordinate in route geometry: lon={}, lat={}", ptLon, ptLat);
                    return GeoProjectionResult.invalid();
                }
                // Store as [lat, lon]
                points.add(new double[]{ptLat, ptLon});
            }
        } catch (Exception e) {
            log.warn("Failed to parse route geometry GeoJSON: {}", e.getMessage());
            return GeoProjectionResult.invalid();
        }

        if (points.size() < 2) {
            log.warn("Route geometry contains fewer than 2 valid coordinates");
            return GeoProjectionResult.invalid();
        }

        // 4. Compute segment lengths and cumulative distances
        int segmentCount = points.size() - 1;
        double[] segmentLengths = new double[segmentCount];
        double[] cumulativeDistances = new double[points.size()];
        cumulativeDistances[0] = 0.0;

        for (int i = 0; i < segmentCount; i++) {
            double[] p1 = points.get(i);
            double[] p2 = points.get(i + 1);
            double len = haversineDistanceMeters(p1[0], p1[1], p2[0], p2[1]);
            segmentLengths[i] = len;
            cumulativeDistances[i + 1] = cumulativeDistances[i] + len;
        }

        double totalCalculatedDistance = cumulativeDistances[points.size() - 1];

        // 5. Project GPS point onto each segment and find the closest segment
        double minDistanceToRoute = Double.MAX_VALUE;
        double bestDistanceAlongRoute = 0.0;
        double bestProjLat = points.get(0)[0];
        double bestProjLon = points.get(0)[1];

        for (int i = 0; i < segmentCount; i++) {
            double[] p1 = points.get(i);
            double[] p2 = points.get(i + 1);

            double latA = p1[0];
            double lonA = p1[1];
            double latB = p2[0];
            double lonB = p2[1];

            // Local planar projection in meters
            double midLatRad = Math.toRadians((latA + latB) / 2.0);
            double cosMidLat = Math.cos(midLatRad);
            double metersPerDegLat = (Math.PI / 180.0) * EARTH_RADIUS_METERS;
            double metersPerDegLon = metersPerDegLat * cosMidLat;

            double vx = (lonB - lonA) * metersPerDegLon;
            double vy = (latB - latA) * metersPerDegLat;
            double wx = (longitude - lonA) * metersPerDegLon;
            double wy = (latitude - latA) * metersPerDegLat;

            double vLenSq = vx * vx + vy * vy;
            double tClamped;
            if (vLenSq <= 1e-12) {
                tClamped = 0.0;
            } else {
                double t = (wx * vx + wy * vy) / vLenSq;
                tClamped = Math.max(0.0, Math.min(1.0, t));
            }

            double projLat = latA + tClamped * (latB - latA);
            double projLon = lonA + tClamped * (lonB - lonA);

            // Geodesic distance from GPS point to closest point on segment
            double distToSegment = haversineDistanceMeters(latitude, longitude, projLat, projLon);
            double distAlongRoute = cumulativeDistances[i] + tClamped * segmentLengths[i];

            if (distToSegment < minDistanceToRoute) {
                minDistanceToRoute = distToSegment;
                bestDistanceAlongRoute = distAlongRoute;
                bestProjLat = projLat;
                bestProjLon = projLon;
            }
        }

        // 6. Calculate progress percentage
        double effectiveTotalDistance = (authoritativeTotalDistanceMeters != null && authoritativeTotalDistanceMeters > 0)
                ? authoritativeTotalDistanceMeters
                : totalCalculatedDistance;

        double progressPercent = 0.0;
        double distanceAlongRouteMeters = 0.0;

        if (totalCalculatedDistance > 0.0) {
            double ratio = bestDistanceAlongRoute / totalCalculatedDistance;
            progressPercent = ratio * 100.0;
            distanceAlongRouteMeters = ratio * effectiveTotalDistance;
        }

        // Clamp to [0, 100]
        if (progressPercent < 0.0) {
            progressPercent = 0.0;
        } else if (progressPercent > 100.0) {
            progressPercent = 100.0;
        }

        if (distanceAlongRouteMeters < 0.0) {
            distanceAlongRouteMeters = 0.0;
        } else if (distanceAlongRouteMeters > effectiveTotalDistance) {
            distanceAlongRouteMeters = effectiveTotalDistance;
        }

        return GeoProjectionResult.valid(
                progressPercent,
                distanceAlongRouteMeters,
                minDistanceToRoute,
                bestProjLat,
                bestProjLon
        );
    }

    /**
     * Computes the great-circle distance between two geographic coordinates using the Haversine formula.
     */
    public static double haversineDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double rLat1 = Math.toRadians(lat1);
        double rLat2 = Math.toRadians(lat2);

        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.cos(rLat1) * Math.cos(rLat2) * Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return EARTH_RADIUS_METERS * c;
    }
}
