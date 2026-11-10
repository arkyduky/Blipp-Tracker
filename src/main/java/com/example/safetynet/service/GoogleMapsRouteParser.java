package com.example.safetynet.service;

import com.example.safetynet.dto.Waypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses shared Google Maps route URLs into ordered geographic Waypoint objects.
 * Supports:
 * 1. Full directions URLs with explicit path coordinates (/dir/lat,lon/lat,lon/...)
 * 2. Directions URLs with place names and protobuf coordinate parameters (/dir/Name1/Name2/.../data=!1d<lon>!2d<lat>...)
 * 3. Directions API v1 query parameters (?api=1&origin=...&destination=...&waypoints=...)
 * 4. Legacy and mobile directions query parameters (?saddr=...&daddr=...+to:... and ?geocode=...)
 * 5. Shortened share links (maps.app.goo.gl, goo.gl/maps) via HTTP redirect resolution
 */
@Component
public class GoogleMapsRouteParser {

    private static final Logger log = LoggerFactory.getLogger(GoogleMapsRouteParser.class);

    // Coordinate pattern matching "lat,lon" with optional spaces
    private static final Pattern COORD_PATTERN = Pattern.compile(
            "([-+]?\\d{1,2}(?:\\.\\d+)?)[,\\s]+([-+]?\\d{1,3}(?:\\.\\d+)?)"
    );

    // Google Maps data parameter protobuf coordinates:
    // !1d<lon>!2d<lat>, !3d<lat>!4d<lon>, !2d<lat>!1d<lon>, !4d<lon>!3d<lat>, !2d<lon>!3d<lat>, !3d<lat>!2d<lon>
    private static final Pattern PROTOBUF_COORD_PATTERN = Pattern.compile(
            "!1d([-+]?\\d+(?:\\.\\d+)?)[!]2d([-+]?\\d+(?:\\.\\d+)?)|" +
            "!3d([-+]?\\d+(?:\\.\\d+)?)[!]4d([-+]?\\d+(?:\\.\\d+)?)|" +
            "!2d([-+]?\\d+(?:\\.\\d+)?)[!]1d([-+]?\\d+(?:\\.\\d+)?)|" +
            "!4d([-+]?\\d+(?:\\.\\d+)?)[!]3d([-+]?\\d+(?:\\.\\d+)?)|" +
            "!2d([-+]?\\d+(?:\\.\\d+)?)[!]3d([-+]?\\d+(?:\\.\\d+)?)|" +
            "!3d([-+]?\\d+(?:\\.\\d+)?)[!]2d([-+]?\\d+(?:\\.\\d+)?)"
    );

    // Allowed Google Maps hostnames for security
    private static final Pattern GOOGLE_HOST_PATTERN = Pattern.compile(
            "^(?:[a-zA-Z0-9-]+\\.)*(?:google\\.[a-z.]+|goo\\.gl)$",
            Pattern.CASE_INSENSITIVE
    );

    public interface RedirectResolver {
        String resolveRedirect(String shortUrl);
    }

    public static class DefaultRedirectResolver implements RedirectResolver {
        private static final int TIMEOUT_MS = 5000;
        private static final int MAX_REDIRECTS = 5;

        @Override
        public String resolveRedirect(String shortUrl) {
            String currentUrl = shortUrl;
            int redirects = 0;

            while (redirects < MAX_REDIRECTS) {
                try {
                    URI uri = URI.create(currentUrl);
                    String host = uri.getHost();
                    if (host == null || !GOOGLE_HOST_PATTERN.matcher(host).matches()) {
                        throw new RouteParseException("Redirect target is not a recognized Google domain: " + host);
                    }

                    HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(TIMEOUT_MS);
                    conn.setReadTimeout(TIMEOUT_MS);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

                    int status = conn.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = conn.getHeaderField("Location");
                        if (location == null || location.isBlank()) {
                            throw new RouteParseException("Redirect from shortened link missing Location header");
                        }
                        currentUrl = uri.resolve(location).toString();
                        redirects++;
                    } else {
                        // Reached target URL
                        return currentUrl;
                    }
                } catch (RouteParseException rpe) {
                    throw rpe;
                } catch (Exception e) {
                    throw new RouteParseException("Failed to resolve shortened Google Maps link: " + e.getMessage(), e);
                }
            }
            return currentUrl;
        }
    }

    private final RedirectResolver redirectResolver;

    public GoogleMapsRouteParser() {
        this(new DefaultRedirectResolver());
    }

    public GoogleMapsRouteParser(RedirectResolver redirectResolver) {
        this.redirectResolver = redirectResolver != null ? redirectResolver : new DefaultRedirectResolver();
    }

    /**
     * Parses a Google Maps shared route URL into an ordered List of Waypoint objects.
     */
    public List<Waypoint> parseRoute(String inputUrl) {
        if (inputUrl == null || inputUrl.isBlank()) {
            throw new RouteParseException("Google Maps route URL must not be null or blank");
        }

        String url = inputUrl.trim().replace(" ", "%20");

        // Basic URI syntax check
        URI uri;
        try {
            uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("Missing scheme or host");
            }
        } catch (Exception e) {
            throw new RouteParseException("Invalid Google Maps route URL syntax: " + url, e);
        }

        String host = uri.getHost();
        if (!GOOGLE_HOST_PATTERN.matcher(host).matches()) {
            throw new RouteParseException("URL is not a recognized Google Maps link: " + host);
        }

        // Handle shortened share URLs (maps.app.goo.gl, goo.gl)
        if (host.equalsIgnoreCase("maps.app.goo.gl") || host.equalsIgnoreCase("goo.gl")) {
            url = redirectResolver.resolveRedirect(url);
            url = url.replace(" ", "%20");
            try {
                uri = URI.create(url);
                host = uri.getHost();
                if (host == null || !GOOGLE_HOST_PATTERN.matcher(host).matches()) {
                    throw new RouteParseException("Redirect target is not a recognized Google domain: " + host);
                }
            } catch (RouteParseException rpe) {
                throw rpe;
            } catch (Exception e) {
                throw new RouteParseException("Invalid resolved Google Maps URL syntax: " + url, e);
            }
        }

        List<Waypoint> waypoints = null;

        // Strategy 1: Directions API v1 query parameters (?api=1&origin=...&destination=...&waypoints=...)
        waypoints = tryParseDirectionsQueryParams(uri);

        // Strategy 2: Legacy query parameters (?saddr=...&daddr=...)
        if (waypoints == null || waypoints.isEmpty()) {
            waypoints = tryParseLegacyQueryParams(uri);
        }

        // Strategy 3: Path-based directions (/dir/... or /maps/dir/...)
        if (waypoints == null || waypoints.isEmpty()) {
            waypoints = tryParsePathDirections(url, uri);
        }

        // Validation
        if (waypoints == null || waypoints.isEmpty()) {
            throw new RouteParseException("Google Maps route URL does not contain geographic coordinates for waypoints. Please share a directions link containing coordinates or waypoints.");
        }

        if (waypoints.size() < 2) {
            throw new RouteParseException("Google Maps route must contain at least 2 waypoints (origin and destination), found: " + waypoints.size());
        }

        // Validate each waypoint coordinate and ensure sequence numbers are sequential
        for (int i = 0; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            wp.setSequence(i + 1);

            Double lat = wp.getLatitude();
            Double lon = wp.getLongitude();

            if (lat == null || lon == null) {
                throw new RouteParseException("Waypoint " + (i + 1) + " has missing coordinates");
            }
            if (Double.isNaN(lat) || Double.isInfinite(lat) || lat < -90.0 || lat > 90.0) {
                throw new RouteParseException("Waypoint " + (i + 1) + " has invalid latitude: " + lat + " (must be between -90.0 and 90.0)");
            }
            if (Double.isNaN(lon) || Double.isInfinite(lon) || lon < -180.0 || lon > 180.0) {
                throw new RouteParseException("Waypoint " + (i + 1) + " has invalid longitude: " + lon + " (must be between -180.0 and 180.0)");
            }

            // Ensure waypoint has a fallback name if blank
            if (wp.getName() == null || wp.getName().isBlank()) {
                if (i == 0) {
                    wp.setName("Origin");
                } else if (i == waypoints.size() - 1) {
                    wp.setName("Destination");
                } else {
                    wp.setName("Stop " + i);
                }
            }
        }

        // Validate that not all waypoints have identical coordinates (prevents 0.0 km fake routes)
        boolean allIdentical = true;
        Waypoint firstWp = waypoints.get(0);
        for (int i = 1; i < waypoints.size(); i++) {
            Waypoint wp = waypoints.get(i);
            if (Math.abs(wp.getLatitude() - firstWp.getLatitude()) > 1e-6 ||
                Math.abs(wp.getLongitude() - firstWp.getLongitude()) > 1e-6) {
                allIdentical = false;
                break;
            }
        }
        if (allIdentical) {
            throw new RouteParseException("Google Maps route coordinates are identical; route distance is 0. Please share a valid route with distinct waypoints.");
        }

        return waypoints;
    }

    /**
     * Parses a Google Maps shared route URL and returns ordered Waypoint array.
     */
    public Waypoint[] parseRouteAsArray(String inputUrl) {
        List<Waypoint> list = parseRoute(inputUrl);
        return list.toArray(new Waypoint[0]);
    }

    /**
     * Strategy 1: Directions API v1 query parameters (?api=1&origin=...&destination=...&waypoints=...)
     */
    private List<Waypoint> tryParseDirectionsQueryParams(URI uri) {
        String query = uri.getQuery();
        if (query == null || query.isBlank()) return null;

        String origin = getQueryParam(query, "origin");
        String destination = getQueryParam(query, "destination");
        String waypointsParam = getQueryParam(query, "waypoints");

        if (origin == null || destination == null) return null;

        CoordinatePair originCoord = extractFirstCoordinate(origin);
        CoordinatePair destCoord = extractFirstCoordinate(destination);

        if (originCoord == null || destCoord == null) return null;

        List<Waypoint> result = new ArrayList<>();
        int seq = 1;
        result.add(new Waypoint(cleanName(origin, "Origin"), originCoord.lat, originCoord.lon, seq++));

        if (waypointsParam != null && !waypointsParam.isBlank()) {
            String[] stops = waypointsParam.split("(?i)\\||%7c");
            for (int i = 0; i < stops.length; i++) {
                String stop = stops[i].trim();
                CoordinatePair stopCoord = extractFirstCoordinate(stop);
                if (stopCoord != null) {
                    result.add(new Waypoint(cleanName(stop, "Stop " + (i + 1)), stopCoord.lat, stopCoord.lon, seq++));
                }
            }
        }

        result.add(new Waypoint(cleanName(destination, "Destination"), destCoord.lat, destCoord.lon, seq));
        return result;
    }

    /**
     * Strategy 2: Query parameters (?saddr=...&daddr=... and mobile ?geocode=...)
     */
    private List<Waypoint> tryParseLegacyQueryParams(URI uri) {
        String query = uri.getQuery();
        if (query == null || query.isBlank()) return null;

        String saddr = getQueryParam(query, "saddr");
        String daddr = getQueryParam(query, "daddr");
        String geocode = getQueryParam(query, "geocode");

        List<CoordinatePair> geocodeCoords = extractGeocodeCoordinates(geocode);

        // Sub-strategy 2A: Protobuf coordinates encoded in mobile geocode parameter
        if (geocodeCoords.size() >= 2) {
            List<String> rawNames = new ArrayList<>();
            if (saddr != null && !saddr.isBlank()) {
                rawNames.add(saddr.trim());
            }
            if (daddr != null && !daddr.isBlank()) {
                String[] daddrParts = daddr.split("(?i)(?:\\+|\\s+)?to:");
                for (String part : daddrParts) {
                    if (!part.isBlank()) {
                        rawNames.add(part.trim());
                    }
                }
            }

            List<Waypoint> result = new ArrayList<>();
            int total = geocodeCoords.size();

            // Case 2A-1: rawNames matches geocodeCoords count (1-to-1 correspondence)
            if (rawNames.size() == total) {
                for (int i = 0; i < total; i++) {
                    CoordinatePair cp = geocodeCoords.get(i);
                    String fallback = (i == 0) ? "Origin" : (i == total - 1 ? "Destination" : "Stop " + i);
                    result.add(new Waypoint(cleanName(rawNames.get(i), fallback), cp.lat, cp.lon, i + 1));
                }
                return result;
            }

            // Case 2A-2: saddr omitted (current location origin), rawNames has (total - 1) stops
            if (rawNames.size() == total - 1) {
                result.add(new Waypoint("Origin", geocodeCoords.get(0).lat, geocodeCoords.get(0).lon, 1));
                for (int i = 0; i < rawNames.size(); i++) {
                    CoordinatePair cp = geocodeCoords.get(i + 1);
                    boolean isFinal = (i == rawNames.size() - 1);
                    String fallback = isFinal ? "Destination" : "Stop " + (i + 1);
                    result.add(new Waypoint(cleanName(rawNames.get(i), fallback), cp.lat, cp.lon, i + 2));
                }
                return result;
            }

            // Case 2A-3: Mapping available rawNames to geocode coordinates with fallbacks
            for (int i = 0; i < total; i++) {
                CoordinatePair cp = geocodeCoords.get(i);
                String fallback = (i == 0) ? "Origin" : (i == total - 1 ? "Destination" : "Stop " + i);
                String name = (i < rawNames.size()) ? cleanName(rawNames.get(i), fallback) : fallback;
                result.add(new Waypoint(name, cp.lat, cp.lon, i + 1));
            }
            return result;
        }

        // Sub-strategy 2B: Literal coordinates in saddr / daddr
        if (saddr == null || daddr == null) return null;

        CoordinatePair originCoord = extractFirstCoordinate(saddr);
        if (originCoord == null) return null;

        List<Waypoint> result = new ArrayList<>();
        int seq = 1;
        result.add(new Waypoint(cleanName(saddr, "Origin"), originCoord.lat, originCoord.lon, seq++));

        // daddr can contain intermediate stops separated by "+to:" or " to:"
        String[] daddrParts = daddr.split("(?i)(?:\\+|\\s+)?to:");
        for (int i = 0; i < daddrParts.length; i++) {
            String part = daddrParts[i].trim();
            CoordinatePair partCoord = extractFirstCoordinate(part);
            if (partCoord != null) {
                boolean isFinal = (i == daddrParts.length - 1);
                String fallback = isFinal ? "Destination" : "Stop " + (i + 1);
                result.add(new Waypoint(cleanName(part, fallback), partCoord.lat, partCoord.lon, seq++));
            }
        }

        return result.size() >= 2 ? result : null;
    }

    /**
     * Strategy 3: Path-based directions (/dir/... or /maps/dir/...)
     */
    private List<Waypoint> tryParsePathDirections(String fullUrl, URI uri) {
        String path = uri.getPath();
        if (path == null) return null;

        int dirIndex = path.indexOf("/dir/");
        if (dirIndex == -1) return null;

        String dirPath = path.substring(dirIndex + 5);
        String[] rawSegments = dirPath.split("/");

        List<String> pathStops = new ArrayList<>();
        for (String raw : rawSegments) {
            if (raw == null || raw.isBlank()) continue;
            // Ignore camera viewport (@lat,lon,zoom) and metadata (data=, am=)
            if (raw.startsWith("@") || raw.startsWith("data=") || raw.startsWith("am=")) continue;
            pathStops.add(raw.trim());
        }

        if (pathStops.isEmpty()) return null;

        List<CoordinatePair> dataCoords = extractProtobufDataCoordinates(fullUrl);

        // Check 3A: Are coordinates directly present in all path segments?
        List<Waypoint> segmentWaypoints = new ArrayList<>();
        boolean allSegmentsHaveCoords = true;

        for (int i = 0; i < pathStops.size(); i++) {
            String seg = pathStops.get(i);
            CoordinatePair cp = extractFirstCoordinate(seg);
            if (cp != null) {
                String fallback = (i == 0) ? "Origin" : (i == pathStops.size() - 1 ? "Destination" : "Stop " + i);
                String name = cleanName(seg, fallback);
                segmentWaypoints.add(new Waypoint(name, cp.lat, cp.lon, i + 1));
            } else {
                allSegmentsHaveCoords = false;
                break;
            }
        }

        if (allSegmentsHaveCoords && !segmentWaypoints.isEmpty()) {
            return segmentWaypoints;
        }

        // Check 3B: Protobuf coordinates in data= parameter combined with path stops
        if (dataCoords.size() >= 2) {
            // Case B1: dataCoords count matches pathStops count (1-to-1 correspondence)
            if (dataCoords.size() == pathStops.size()) {
                List<Waypoint> result = new ArrayList<>();
                for (int i = 0; i < dataCoords.size(); i++) {
                    CoordinatePair cp = dataCoords.get(i);
                    String fallback = (i == 0) ? "Origin" : (i == dataCoords.size() - 1 ? "Destination" : "Stop " + i);
                    String name = cleanName(pathStops.get(i), fallback);
                    result.add(new Waypoint(name, cp.lat, cp.lon, i + 1));
                }
                return result;
            }

            // Case B2: Mixed segments (some with coords in path, others resolved via dataCoords)
            List<Waypoint> mixedResult = new ArrayList<>();
            int dataCoordIdx = 0;
            boolean resolvedAll = true;

            for (int i = 0; i < pathStops.size(); i++) {
                String seg = pathStops.get(i);
                CoordinatePair cp = extractFirstCoordinate(seg);
                String fallback = (i == 0) ? "Origin" : (i == pathStops.size() - 1 ? "Destination" : "Stop " + i);

                if (cp != null) {
                    String name = cleanName(seg, fallback);
                    mixedResult.add(new Waypoint(name, cp.lat, cp.lon, i + 1));
                    if (dataCoordIdx < dataCoords.size() &&
                            Math.abs(dataCoords.get(dataCoordIdx).lat - cp.lat) < 1e-4 &&
                            Math.abs(dataCoords.get(dataCoordIdx).lon - cp.lon) < 1e-4) {
                        dataCoordIdx++;
                    }
                } else if (dataCoordIdx < dataCoords.size()) {
                    CoordinatePair dataCp = dataCoords.get(dataCoordIdx++);
                    String name = cleanName(seg, fallback);
                    mixedResult.add(new Waypoint(name, dataCp.lat, dataCp.lon, i + 1));
                } else {
                    resolvedAll = false;
                    break;
                }
            }

            if (resolvedAll && mixedResult.size() >= 2) {
                return mixedResult;
            }

            // Case B3: dataCoords has >= 2 coordinates, map available pathStops
            List<Waypoint> result = new ArrayList<>();
            for (int i = 0; i < dataCoords.size(); i++) {
                CoordinatePair cp = dataCoords.get(i);
                String fallback = (i == 0) ? "Origin" : (i == dataCoords.size() - 1 ? "Destination" : "Stop " + i);
                String name = fallback;

                if (i < pathStops.size()) {
                    name = cleanName(pathStops.get(i), fallback);
                }

                result.add(new Waypoint(name, cp.lat, cp.lon, i + 1));
            }
            return result;
        }

        return null;
    }

    /**
     * Extracts coordinate pairs from URL data parameters supporting multiple Google Maps protobuf encodings.
     */
    private List<CoordinatePair> extractProtobufDataCoordinates(String url) {
        List<CoordinatePair> coords = new ArrayList<>();
        Matcher matcher = PROTOBUF_COORD_PATTERN.matcher(url);

        while (matcher.find()) {
            try {
                double lat;
                double lon;
                if (matcher.group(1) != null && matcher.group(2) != null) {
                    lon = Double.parseDouble(matcher.group(1));
                    lat = Double.parseDouble(matcher.group(2));
                } else if (matcher.group(3) != null && matcher.group(4) != null) {
                    lat = Double.parseDouble(matcher.group(3));
                    lon = Double.parseDouble(matcher.group(4));
                } else if (matcher.group(5) != null && matcher.group(6) != null) {
                    lat = Double.parseDouble(matcher.group(5));
                    lon = Double.parseDouble(matcher.group(6));
                } else if (matcher.group(7) != null && matcher.group(8) != null) {
                    lon = Double.parseDouble(matcher.group(7));
                    lat = Double.parseDouble(matcher.group(8));
                } else if (matcher.group(9) != null && matcher.group(10) != null) {
                    lon = Double.parseDouble(matcher.group(9));
                    lat = Double.parseDouble(matcher.group(10));
                } else if (matcher.group(11) != null && matcher.group(12) != null) {
                    lat = Double.parseDouble(matcher.group(11));
                    lon = Double.parseDouble(matcher.group(12));
                } else {
                    continue;
                }

                if (lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0) {
                    // Deduplicate consecutive identical coordinates (e.g. repeated place markers)
                    if (coords.isEmpty() || Math.abs(coords.get(coords.size() - 1).lat - lat) > 1e-6
                            || Math.abs(coords.get(coords.size() - 1).lon - lon) > 1e-6) {
                        coords.add(new CoordinatePair(lat, lon));
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return coords;
    }

    /**
     * Extracts coordinates from Google Maps mobile geocode parameter.
     * Tokens are separated by semicolons (;) and Base64-encoded protobuf messages.
     * Field 2 (32-bit fixed, wire type 5) = latitude in microdegrees (int32 / 1e6).
     * Field 3 (32-bit fixed, wire type 5) = longitude in microdegrees (int32 / 1e6).
     */
    private List<CoordinatePair> extractGeocodeCoordinates(String geocodeParam) {
        if (geocodeParam == null || geocodeParam.isBlank()) {
            return Collections.emptyList();
        }
        List<CoordinatePair> coords = new ArrayList<>();
        String[] tokens = geocodeParam.split(";");
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            CoordinatePair cp = decodeGeocodeToken(trimmed);
            if (cp != null) {
                coords.add(cp);
            }
        }
        return coords;
    }

    private CoordinatePair decodeGeocodeToken(String token) {
        try {
            String decoded = URLDecoder.decode(token, StandardCharsets.UTF_8).trim();
            String normalized = decoded.replace('-', '+').replace('_', '/');
            int pad = (4 - (normalized.length() % 4)) % 4;
            normalized += "=".repeat(pad);

            byte[] bytes = Base64.getDecoder().decode(normalized);
            Double lat = null;
            Double lon = null;

            int i = 0;
            while (i < bytes.length) {
                int tagByte = bytes[i++] & 0xFF;
                int fieldNum = tagByte >>> 3;
                int wireType = tagByte & 0x07;

                if (wireType == 5) { // 32-bit fixed
                    if (i + 4 > bytes.length) break;
                    int intVal = (bytes[i] & 0xFF) |
                            ((bytes[i + 1] & 0xFF) << 8) |
                            ((bytes[i + 2] & 0xFF) << 16) |
                            ((bytes[i + 3] & 0xFF) << 24);
                    i += 4;
                    double coord = intVal / 1e6;
                    if (fieldNum == 2) {
                        lat = coord;
                    } else if (fieldNum == 3) {
                        lon = coord;
                    }
                } else if (wireType == 0) { // varint
                    while (i < bytes.length && (bytes[i++] & 0x80) != 0) {
                        // skip varint bytes
                    }
                } else if (wireType == 1) { // 64-bit fixed
                    if (i + 8 > bytes.length) break;
                    i += 8;
                } else if (wireType == 2) { // length-delimited
                    int len = 0;
                    int shift = 0;
                    while (i < bytes.length) {
                        int b = bytes[i++] & 0xFF;
                        len |= (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) break;
                        shift += 7;
                    }
                    if (len < 0 || i + len > bytes.length) break;
                    i += len;
                } else {
                    break;
                }
            }

            if (lat != null && lon != null) {
                if (lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0) {
                    return new CoordinatePair(lat, lon);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private CoordinatePair extractFirstCoordinate(String text) {
        if (text == null) return null;
        try {
            String decoded = URLDecoder.decode(text, StandardCharsets.UTF_8);
            Matcher matcher = COORD_PATTERN.matcher(decoded);
            if (matcher.find()) {
                double lat = Double.parseDouble(matcher.group(1));
                double lon = Double.parseDouble(matcher.group(2));
                return new CoordinatePair(lat, lon);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String cleanName(String raw, String fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            String decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8).replace('+', ' ').trim();
            // Remove camera info if present
            if (decoded.contains("@")) {
                decoded = decoded.substring(0, decoded.indexOf('@')).trim();
            }
            // If the string is purely coordinates, return fallback
            if (COORD_PATTERN.matcher(decoded).matches()) {
                return fallback;
            }
            // Strip out any trailing/leading coordinates from a name string (e.g. "Thrissur 10.52,76.21")
            Matcher m = COORD_PATTERN.matcher(decoded);
            if (m.find()) {
                String prefix = decoded.substring(0, m.start()).replaceAll("[,\\s]+$", "").trim();
                if (!prefix.isBlank()) return prefix;
            }
            return decoded.isBlank() ? fallback : decoded;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String getQueryParam(String query, String paramName) {
        if (query == null || query.isBlank()) return null;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx != -1) {
                String key = pair.substring(0, idx).trim();
                if (key.equalsIgnoreCase(paramName)) {
                    String value = pair.substring(idx + 1).trim();
                    try {
                        return URLDecoder.decode(value, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private static class CoordinatePair {
        final double lat;
        final double lon;

        CoordinatePair(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }
}
