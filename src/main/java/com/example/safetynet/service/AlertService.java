package com.example.safetynet.service;

import com.example.safetynet.model.Checkpoint;
import com.example.safetynet.model.Ride;
import com.example.safetynet.model.Rider;
import com.example.safetynet.repository.RideRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.example.safetynet.dto.GeoProjectionResult;
import com.example.safetynet.dto.Waypoint;
import com.example.safetynet.repository.CheckpointRepository;
import com.example.safetynet.repository.RiderRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    @Value("${telegram.bot-token}")
    private String botToken;

    private final RideRepository rideRepository;
    private final GeoProjectionService geoProjectionService;
    private RiderRepository riderRepository;
    private RideService rideService;
    private GoogleMapsRouteParser googleMapsRouteParser;
    @Autowired(required = false)
    private CheckpointRepository checkpointRepository;
    private RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern GOOGLE_MAPS_URL_PATTERN = Pattern.compile(
            "https?://(?:[a-zA-Z0-9-]+\\.)*(?:google\\.[a-z.]+/maps|maps\\.google\\.[a-z.]+|goo\\.gl/maps|maps\\.app\\.goo\\.gl)[^\\s]*",
            Pattern.CASE_INSENSITIVE
    );

    private long lastUpdateId = 0;

    private static class ReplyRecord {
        final String text;
        final long epochSecond;

        ReplyRecord(String text, long epochSecond) {
            this.text = text;
            this.epochSecond = epochSecond;
        }
    }

    private final Map<String, List<ReplyRecord>> chatReplies = new ConcurrentHashMap<>();

    public AlertService(RideRepository rideRepository) {
        this(rideRepository, null, null, new GeoProjectionService(), new GoogleMapsRouteParser());
    }

    public AlertService(RideRepository rideRepository, GeoProjectionService geoProjectionService) {
        this(rideRepository, null, null, geoProjectionService, new GoogleMapsRouteParser());
    }

    public AlertService(RideRepository rideRepository,
                        RiderRepository riderRepository,
                        RideService rideService,
                        GeoProjectionService geoProjectionService,
                        GoogleMapsRouteParser googleMapsRouteParser) {
        this(rideRepository, riderRepository, rideService, geoProjectionService, googleMapsRouteParser, null);
    }

    @Autowired
    public AlertService(RideRepository rideRepository,
                        RiderRepository riderRepository,
                        RideService rideService,
                        GeoProjectionService geoProjectionService,
                        GoogleMapsRouteParser googleMapsRouteParser,
                        @Autowired(required = false) CheckpointRepository checkpointRepository) {
        this.rideRepository = rideRepository;
        this.riderRepository = riderRepository;
        this.rideService = rideService;
        this.geoProjectionService = geoProjectionService != null ? geoProjectionService : new GeoProjectionService();
        this.googleMapsRouteParser = googleMapsRouteParser != null ? googleMapsRouteParser : new GoogleMapsRouteParser();
        this.checkpointRepository = checkpointRepository;
    }

    public void setCheckpointRepository(CheckpointRepository checkpointRepository) {
        this.checkpointRepository = checkpointRepository;
    }

    public void setRiderRepository(RiderRepository riderRepository) {
        this.riderRepository = riderRepository;
    }

    public void setRideService(RideService rideService) {
        this.rideService = rideService;
    }

    public void setGoogleMapsRouteParser(GoogleMapsRouteParser googleMapsRouteParser) {
        this.googleMapsRouteParser = googleMapsRouteParser != null ? googleMapsRouteParser : new GoogleMapsRouteParser();
    }

    @Value("${safety.gps.movement-threshold-meters:10.0}")
    private double movementThresholdMeters = 10.0;

    void setBotToken(String botToken) {
        this.botToken = botToken;
    }

    void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate != null ? restTemplate : new RestTemplate();
    }

    public double getMovementThresholdMeters() {
        return movementThresholdMeters;
    }

    public void setMovementThresholdMeters(double movementThresholdMeters) {
        this.movementThresholdMeters = movementThresholdMeters;
    }

    private String getTelegramUrl(String method) {
        return "https://api.telegram.org/bot" + botToken + "/" + method;
    }

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final String ROUTE_SUBMISSION_PROMPT = "Please send your Google Maps route link.";

    public void sendRouteSubmissionPrompt(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }
        sendTo(chatId, ROUTE_SUBMISSION_PROMPT);
    }

    public boolean maybeSendRouteSubmissionPrompt(Ride ride) {
        if (ride == null || isDemoRide(ride) || ride.isRoutePromptSent()
                || (ride.getRouteGeometry() != null && !ride.getRouteGeometry().isBlank())) {
            return false;
        }
        Rider rider = ride.getRider();
        if (rider == null || rider.getTelegramChatId() == null || rider.getTelegramChatId().isBlank()) {
            return false;
        }
        try {
            sendRouteSubmissionPrompt(rider.getTelegramChatId().trim());
        } catch (Exception e) {
            log.error("Failed to send route submission prompt to rider {}: {}", rider.getName(), e.getMessage());
        }
        ride.setRoutePromptSent(true);
        if (rideRepository != null) {
            rideRepository.save(ride);
        }
        return true;
    }

    public void sendTo(String chatId, String text) {
        sendMessage(chatId, text, false);
    }

    public void sendAlert(String chatId, String text) {
        sendMessage(chatId, text, true);
    }

    private void sendMessage(String chatId, String text, boolean withTimestamp) {
        if (chatId == null || chatId.isBlank()) {
            log.warn("Cannot send Telegram message: chat_id is missing");
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String formattedText;
            if (withTimestamp) {
                String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
                formattedText = String.format("[%s] %s", timestamp, text);
            } else {
                formattedText = text;
            }

            Map<String, Object> request = new HashMap<>();
            request.put("chat_id", chatId);
            request.put("text", formattedText);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            restTemplate.postForObject(getTelegramUrl("sendMessage"), entity, String.class);
            log.info("Telegram sent to {}: {}", chatId, formattedText);
        } catch (Exception e) {
            log.error("Telegram send failed to {}: {}", chatId, e.getMessage());
        }
    }

    public void sendPassiveUpdate(Ride ride, Checkpoint checkpoint) {
        Rider rider = ride.getRider();
        int totalCheckpoints = 3;
        if (checkpointRepository != null && ride != null) {
            List<Checkpoint> cps = checkpointRepository.findByRideOrderBySequenceNumberAsc(ride);
            if (cps != null && !cps.isEmpty()) {
                totalCheckpoints = cps.size();
            }
        }
        if (checkpoint != null && checkpoint.getSequenceNumber() > totalCheckpoints) {
            totalCheckpoints = checkpoint.getSequenceNumber();
        }
        String message = String.format("%s passed checkpoint %d of %d, on schedule.",
                rider.getName(), checkpoint.getSequenceNumber(), totalCheckpoints);
        sendAlert(rider.getEmergencyContactChatId(), message);
    }

    public void sendConfirmationPrompt(Ride ride, Checkpoint checkpoint) {
        Rider rider = ride.getRider();
        String message = String.format("Checkpoint %d — you've been stationary a while. Reply OK or tell us why.",
                checkpoint.getSequenceNumber());
        sendAlert(rider.getTelegramChatId(), message);
    }

    public void sendResolvedUpdate(Ride ride, Checkpoint checkpoint, String riderReply) {
        Rider rider = ride.getRider();
        String message = String.format("%s delayed at checkpoint %d, confirmed fine: %s",
                rider.getName(), checkpoint.getSequenceNumber(), riderReply);
        sendAlert(rider.getEmergencyContactChatId(), message);
    }

    public void sendEmergencyAlert(Ride ride, Checkpoint checkpoint) {
        Rider rider = ride.getRider();
        String message = String.format("ALERT: %s has not responded at checkpoint %d and has been stationary for a while.",
                rider.getName(), checkpoint.getSequenceNumber());
        sendAlert(rider.getEmergencyContactChatId(), message);
    }

    @Scheduled(fixedRate = 2000)
    public synchronized void pollTelegramUpdates() {
        if (botToken == null || botToken.isBlank()) {
            return;
        }

        try {
            String url = getTelegramUrl("getUpdates") + "?offset=" + (lastUpdateId + 1);
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            if (!root.path("ok").asBoolean(false)) {
                return;
            }

            JsonNode result = root.path("result");
            for (JsonNode update : result) {
                long updateId = update.path("update_id").asLong();
                if (updateId > lastUpdateId) {
                    lastUpdateId = updateId;
                }

                JsonNode messageNode = update.hasNonNull("message")
                        ? update.get("message")
                        : (update.hasNonNull("edited_message") ? update.get("edited_message") : null);
                if (messageNode == null || messageNode.isMissingNode()) continue;

                String fromChatId = messageNode.path("chat").path("id").asText();

                // Branch 1: Location update
                JsonNode locationNode = messageNode.path("location");
                if (!locationNode.isMissingNode() && locationNode.hasNonNull("latitude") && locationNode.hasNonNull("longitude")) {
                    try {
                        double lat = locationNode.path("latitude").asDouble();
                        double lng = locationNode.path("longitude").asDouble();

                        if (Double.isNaN(lat) || Double.isInfinite(lat) || lat < -90.0 || lat > 90.0 ||
                                Double.isNaN(lng) || Double.isInfinite(lng) || lng < -180.0 || lng > 180.0) {
                            log.warn("Invalid GPS coordinate received for chat {}: lat={}, lng={}", fromChatId, lat, lng);
                        } else {
                            List<Ride> activeRides = (rideRepository != null)
                                    ? rideRepository.findByStatus(Ride.RideStatus.ACTIVE)
                                    : Collections.emptyList();

                            // Strictly assign Telegram Live Location ONLY to an ACTIVE REAL (non-demo) ride
                            Ride targetRide = activeRides.stream()
                                    .filter(r -> !isDemoRide(r) && r.getRider() != null && fromChatId.equals(r.getRider().getTelegramChatId()))
                                    .max(Comparator.comparing(Ride::getId))
                                    .orElse(null);

                            if (targetRide != null) {
                                long messageEpoch = messageNode.hasNonNull("edit_date")
                                        ? messageNode.path("edit_date").asLong()
                                        : (messageNode.hasNonNull("date") ? messageNode.path("date").asLong() : 0);

                                Instant incomingTime = messageEpoch > 0
                                        ? Instant.ofEpochSecond(messageEpoch)
                                        : Instant.now();

                                if (targetRide.getLastLocationUpdatedAt() != null && incomingTime.isBefore(targetRide.getLastLocationUpdatedAt())) {
                                    log.info("Stale GPS update ignored for ride {}: incoming timestamp {} is older than lastLocationUpdatedAt {}",
                                            targetRide.getId(), incomingTime, targetRide.getLastLocationUpdatedAt());
                                } else {
                                    Double prevLat = targetRide.getLastKnownLatitude();
                                    Double prevLng = targetRide.getLastKnownLongitude();

                                    // Determine movement state from previous accepted GPS coordinates
                                    if (prevLat == null || prevLng == null) {
                                        // Case 1 — First valid GPS update: no previous position
                                        targetRide.setMoving(true);
                                        targetRide.setStationarySince(null);
                                    } else {
                                        double distanceMoved = GeoProjectionService.haversineDistanceMeters(prevLat, prevLng, lat, lng);
                                        if (distanceMoved >= movementThresholdMeters - 1e-6) {
                                            // Case 2 — Meaningful movement
                                            targetRide.setMoving(true);
                                            targetRide.setStationarySince(null);
                                        } else {
                                            // Case 3 — Below movement threshold (stationary / jitter)
                                            targetRide.setMoving(false);
                                            if (targetRide.getStationarySince() == null) {
                                                targetRide.setStationarySince(incomingTime);
                                            }
                                        }
                                    }

                                    targetRide.setLastKnownLatitude(lat);
                                    targetRide.setLastKnownLongitude(lng);
                                    targetRide.setLastLocationUpdatedAt(incomingTime);

                                    String routeGeometry = targetRide.getRouteGeometry();
                                    if (routeGeometry != null && !routeGeometry.isBlank()) {
                                        GeoProjectionResult projection = geoProjectionService.project(
                                                routeGeometry, lat, lng, targetRide.getTotalDistanceMeters());
                                        if (projection != null && projection.isValid()) {
                                            targetRide.setCurrentPosition(projection.getProgressPercent());
                                            log.debug("Projected GPS for ride {}: position={}% (distAlong={}m, distFrom={}m)",
                                                    targetRide.getId(), projection.getProgressPercent(),
                                                    projection.getDistanceAlongRouteMeters(), projection.getDistanceFromRouteMeters());
                                        } else {
                                            log.warn("GPS projection skipped or invalid for ride {}", targetRide.getId());
                                        }
                                    } else {
                                        log.warn("GPS projection skipped for ride {}: route geometry is null or blank", targetRide.getId());
                                    }

                                    rideRepository.save(targetRide);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to process location update: {}", e.getMessage(), e);
                    }
                }

                // Branch 2: Text message (Chat ID diagnostic, Route link submission, END command, or check-in reply)
                String text = messageNode.path("text").asText(null);
                if (text != null && !text.isBlank()) {
                    if (isChatIdCommand(text)) {
                        processChatIdCommand(fromChatId);
                    } else {
                        String googleMapsUrl = extractGoogleMapsUrl(text);
                        if (googleMapsUrl != null) {
                            processRouteSubmission(fromChatId, googleMapsUrl);
                        } else if (isEndCommand(text)) {
                            processEndCommand(fromChatId);
                        } else {
                            long messageDate = messageNode.has("date") ? messageNode.path("date").asLong() : 0;
                            List<ReplyRecord> list = chatReplies.computeIfAbsent(fromChatId, k -> new CopyOnWriteArrayList<>());
                            list.add(new ReplyRecord(text, messageDate));
                            if (list.size() > 20) {
                                list.remove(0);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Telegram update poll failed: {}", e.getMessage());
        }
    }

    public boolean isChatIdCommand(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        return trimmed.equalsIgnoreCase("chat id");
    }

    public void processChatIdCommand(String fromChatId) {
        if (fromChatId == null || fromChatId.isBlank()) {
            return;
        }
        sendTo(fromChatId, "Your Telegram Chat ID is:\n" + fromChatId);
    }

    public boolean isEndCommand(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.equalsIgnoreCase("END")
                || trimmed.equalsIgnoreCase("/end")
                || trimmed.equalsIgnoreCase("END RIDE")
                || trimmed.equalsIgnoreCase("/endride")) {
            return true;
        }
        return trimmed.toLowerCase().startsWith("/end@");
    }

    public void processEndCommand(String fromChatId) {
        if (fromChatId == null || fromChatId.isBlank()) {
            return;
        }

        // Clean up any buffered check-in reply records for this chat
        chatReplies.remove(fromChatId);

        // Check if chat ID corresponds to a registered rider
        Rider rider = (riderRepository != null) ? riderRepository.findAll().stream()
                .filter(r -> fromChatId.equals(r.getTelegramChatId()))
                .findFirst()
                .orElse(null) : null;

        if (rider == null) {
            log.warn("END command received from unregistered chat ID: {}", fromChatId);
            sendTo(fromChatId, "You are not registered as a rider. Please register or configure your account before submitting commands.");
            return;
        }

        boolean cleared = false;
        if (rideService != null) {
            cleared = rideService.endAndClearRealRidesForRider(fromChatId);
        } else if (rideRepository != null) {
            List<Ride> realRides = rideRepository.findAll().stream()
                    .filter(r -> !isDemoRide(r))
                    .filter(r -> r.getRider() != null && fromChatId.equals(r.getRider().getTelegramChatId()))
                    .toList();
            if (!realRides.isEmpty()) {
                rideRepository.deleteAll(realRides);
                cleared = true;
            }
        }

        if (cleared) {
            log.info("Real ride successfully ended and cleared for rider {}", rider.getName());
            sendTo(fromChatId, "Ride ended and reset. You can now send a new Google Maps route link.");
        } else {
            log.info("END command received for rider {}, but no active real ride was found", rider.getName());
            sendTo(fromChatId, "No active ride found. Please send your Google Maps route link.");
        }
    }

    public String extractGoogleMapsUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = GOOGLE_MAPS_URL_PATTERN.matcher(text.trim());
        if (matcher.find()) {
            String url = matcher.group();
            while (url.endsWith(".") || url.endsWith(",") || url.endsWith(";") || url.endsWith(")") || url.endsWith("]")) {
                url = url.substring(0, url.length() - 1);
            }
            return url;
        }
        return null;
    }

    public void processRouteSubmission(String fromChatId, String googleMapsUrl) {
        if (fromChatId == null || fromChatId.isBlank()) {
            return;
        }

        // 1. Identify the rider
        Rider rider = (riderRepository != null) ? riderRepository.findAll().stream()
                .filter(r -> fromChatId.equals(r.getTelegramChatId()))
                .findFirst()
                .orElse(null) : null;

        if (rider == null) {
            log.warn("Route submission rejected: chat ID {} is not associated with any registered rider", fromChatId);
            sendTo(fromChatId, "You are not registered as a rider. Please register or configure your account before submitting a route.");
            return;
        }

        // 2. Active ride check (ignoring isDemo == true rides)
        List<Ride> activeRides = (rideRepository != null)
                ? rideRepository.findByStatus(Ride.RideStatus.ACTIVE)
                : Collections.emptyList();

        boolean hasActiveRide = activeRides.stream()
                .filter(r -> !isDemoRide(r))
                .anyMatch(r -> r.getRider() != null && fromChatId.equals(r.getRider().getTelegramChatId()));

        if (hasActiveRide) {
            log.info("Route submission rejected for rider {}: active ride already exists", rider.getName());
            sendTo(fromChatId, "You already have an active ride in progress. Complete or end your current ride before submitting a new route.");
            return;
        }

        if (rideService == null) {
            log.error("RideService is not initialized in AlertService");
            sendTo(fromChatId, "Service temporarily unavailable. Please try again later.");
            return;
        }

        GoogleMapsRouteParser parser = (googleMapsRouteParser != null) ? googleMapsRouteParser : new GoogleMapsRouteParser();

        // 3. Parse Google Maps route and create Ride via RideService
        try {
            List<Waypoint> waypoints = parser.parseRoute(googleMapsUrl);
            Ride ride = rideService.createRideForRiderStrict(rider, waypoints);

            // 4. Send confirmation message
            Waypoint origin = waypoints.get(0);
            Waypoint destination = waypoints.get(waypoints.size() - 1);

            String originName = (origin.getName() != null && !origin.getName().isBlank())
                    ? origin.getName()
                    : String.format(Locale.US, "%.4f,%.4f", origin.getLatitude(), origin.getLongitude());

            String destName = (destination.getName() != null && !destination.getName().isBlank())
                    ? destination.getName()
                    : String.format(Locale.US, "%.4f,%.4f", destination.getLatitude(), destination.getLongitude());

            double distanceKm = (ride.getTotalDistanceMeters() != null)
                    ? (ride.getTotalDistanceMeters() / 1000.0)
                    : 0.0;

            int checkpointCount = Math.max(0, waypoints.size() - 1);

            String confirmationText = String.format(Locale.US,
                    "Route accepted.\n\n%s → %s\n%.1f km\n%d checkpoint%s\n\nShare your Live Location when you start riding.",
                    originName,
                    destName,
                    distanceKm,
                    checkpointCount,
                    checkpointCount == 1 ? "" : "s"
            );

            sendTo(fromChatId, confirmationText);
            log.info("Route successfully created for rider {} from Telegram link: {} -> {} ({} km, {} checkpoints)",
                    rider.getName(), originName, destName, distanceKm, checkpointCount);

        } catch (RouteParseException e) {
            log.warn("Failed to parse Google Maps URL from chat {}: {}", fromChatId, e.getMessage());
            sendTo(fromChatId, "Could not parse Google Maps route link. Please share a valid directions link with at least 2 waypoints.");
        } catch (IllegalStateException e) {
            log.error("Route calculation failed for rider {}: {}", rider.getName(), e.getMessage());
            sendTo(fromChatId, "Route calculation failed. Unable to reach routing service. Please try again later.");
        } catch (Exception e) {
            log.error("Unexpected error creating route for chat {}: {}", fromChatId, e.getMessage(), e);
            sendTo(fromChatId, "An error occurred while creating your route. Please try again.");
        }
    }

    public synchronized String fetchReply(Checkpoint checkpoint) {
        if (checkpoint.getConfirmationSentAt() == null || checkpoint.getRide().getRider().getTelegramChatId() == null) {
            return null;
        }

        String targetChatId = checkpoint.getRide().getRider().getTelegramChatId();
        long confirmationEpoch = checkpoint.getConfirmationSentAt().getEpochSecond();

        pollTelegramUpdates();

        List<ReplyRecord> replies = chatReplies.get(targetChatId);
        if (replies == null || replies.isEmpty()) {
            return null;
        }

        String latestReply = null;
        for (ReplyRecord record : replies) {
            if (record.epochSecond >= confirmationEpoch && record.text != null && !record.text.isBlank()) {
                latestReply = record.text;
            }
        }
        return latestReply;
    }

    public static boolean isDemoRide(Ride r) {
        if (r == null) return false;
        return r.isDemo();
    }
}
