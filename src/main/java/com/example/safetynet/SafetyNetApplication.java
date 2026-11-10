package com.example.safetynet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SafetyNetApplication {
    public static void main(String[] args) {
        SpringApplication.run(SafetyNetApplication.class, args);
    }
}
