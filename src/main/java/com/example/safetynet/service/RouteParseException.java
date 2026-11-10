package com.example.safetynet.service;

/**
 * Exception thrown when a Google Maps shared route URL cannot be parsed
 * into valid, ordered geographic waypoints.
 */
public class RouteParseException extends RuntimeException {

    public RouteParseException(String message) {
        super(message);
    }

    public RouteParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
