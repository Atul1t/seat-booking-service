package com.atulit.seatbooking.exception;

/** Authenticated, but not allowed to touch this particular thing. Maps to HTTP 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
