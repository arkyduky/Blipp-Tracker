package com.example.safetynet.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Rider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String telegramChatId;
    private String emergencyContactChatId;
    
    // Dynamic settings with defaults
    private int stationaryToleranceSeconds = 25;
    private int gracePeriodSeconds = 40;

    public Rider() {
        // Default constructor required by JPA
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTelegramChatId() {
        return telegramChatId;
    }

    public void setTelegramChatId(String telegramChatId) {
        this.telegramChatId = telegramChatId;
    }

    public String getEmergencyContactChatId() {
        return emergencyContactChatId;
    }

    public void setEmergencyContactChatId(String emergencyContactChatId) {
        this.emergencyContactChatId = emergencyContactChatId;
    }

    public int getStationaryToleranceSeconds() {
        return stationaryToleranceSeconds;
    }

    public void setStationaryToleranceSeconds(int stationaryToleranceSeconds) {
        this.stationaryToleranceSeconds = stationaryToleranceSeconds;
    }

    public int getGracePeriodSeconds() {
        return gracePeriodSeconds;
    }

    public void setGracePeriodSeconds(int gracePeriodSeconds) {
        this.gracePeriodSeconds = gracePeriodSeconds;
    }
}
