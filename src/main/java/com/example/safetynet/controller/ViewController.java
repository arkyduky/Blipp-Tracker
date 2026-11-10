package com.example.safetynet.controller;

import com.example.safetynet.model.Ride;
import com.example.safetynet.model.Rider;
import com.example.safetynet.repository.RideRepository;
import com.example.safetynet.repository.RiderRepository;
import com.example.safetynet.service.AlertService;
import com.example.safetynet.service.RideService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Controller
public class ViewController {

    private final RideService rideService;
    private final RideRepository rideRepository;
    private final RiderRepository riderRepository;
    private AlertService alertService;

    public ViewController(RideService rideService, RideRepository rideRepository) {
        this(rideService, rideRepository, null, null);
    }

    public ViewController(RideService rideService, RideRepository rideRepository, AlertService alertService) {
        this(rideService, rideRepository, null, alertService);
    }

    public ViewController(RideService rideService, RideRepository rideRepository, RiderRepository riderRepository) {
        this(rideService, rideRepository, riderRepository, null);
    }

    @Autowired
    public ViewController(RideService rideService,
                          RideRepository rideRepository,
                          @Autowired(required = false) RiderRepository riderRepository,
                          @Autowired(required = false) AlertService alertService) {
        this.rideService = rideService;
        this.rideRepository = rideRepository;
        this.riderRepository = riderRepository;
        this.alertService = alertService;
    }

    public void setAlertService(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/")
    public String showSetupForm() {
        return "setup";
    }

    @PostMapping("/start")
    public String startRide(
            @RequestParam String name,
            @RequestParam String telegramChatId,
            @RequestParam String emergencyContactChatId,
            @RequestParam int stationaryToleranceSeconds,
            @RequestParam int gracePeriodSeconds,
            Model model) {

        Rider rider = new Rider();
        rider.setName(name);
        rider.setTelegramChatId(telegramChatId);
        rider.setEmergencyContactChatId(emergencyContactChatId);
        rider.setStationaryToleranceSeconds(stationaryToleranceSeconds);
        rider.setGracePeriodSeconds(gracePeriodSeconds);

        try {
            if (rideService != null) {
                rider = rideService.registerRider(rider);
            } else if (riderRepository != null) {
                Rider existing = riderRepository.findAll().stream()
                        .filter(r -> telegramChatId.equals(r.getTelegramChatId()))
                        .findFirst()
                        .orElse(null);
                if (existing != null) {
                    existing.setName(name);
                    existing.setEmergencyContactChatId(emergencyContactChatId);
                    existing.setStationaryToleranceSeconds(stationaryToleranceSeconds);
                    existing.setGracePeriodSeconds(gracePeriodSeconds);
                    rider = riderRepository.save(existing);
                } else {
                    rider = riderRepository.save(rider);
                }
            }
            if (alertService != null && telegramChatId != null && !telegramChatId.isBlank()) {
                alertService.sendRouteSubmissionPrompt(telegramChatId.trim());
            }
        } catch (Exception e) {
            model.addAttribute("error", "Registration failed: " + e.getMessage());
            model.addAttribute("name", name);
            model.addAttribute("telegramChatId", telegramChatId);
            model.addAttribute("emergencyContactChatId", emergencyContactChatId);
            model.addAttribute("stationaryToleranceSeconds", stationaryToleranceSeconds);
            model.addAttribute("gracePeriodSeconds", gracePeriodSeconds);
            return "setup";
        }

        return "redirect:/live";
    }

    @GetMapping("/ride/{id}")
    public String showRide(@PathVariable Long id) {
        return "redirect:/ride/" + id + "/live";
    }

    @GetMapping({"/live", "/live-monitor"})
    public String showStandaloneLiveMonitor(Model model) {
        List<Ride> activeRides = (rideRepository != null)
                ? rideRepository.findByStatus(Ride.RideStatus.ACTIVE)
                : Collections.emptyList();
        Ride activeRealRide = activeRides.stream()
                .filter(r -> !AlertService.isDemoRide(r))
                .max(Comparator.comparing(Ride::getId))
                .orElse(null);
        if (activeRealRide != null && alertService != null) {
            alertService.maybeSendRouteSubmissionPrompt(activeRealRide);
        }
        model.addAttribute("ride", null);
        return "location";
    }

    @GetMapping({"/ride/{id}/live", "/ride/{id}/live-location"})
    public String showLocationPage(@PathVariable Long id, Model model) {
        Ride ride = rideRepository.findById(id).orElseThrow();
        if (ride.isDemo()) {
            return "redirect:/live";
        }
        if (alertService != null) {
            alertService.maybeSendRouteSubmissionPrompt(ride);
        }
        model.addAttribute("ride", ride);
        return "location";
    }
}

