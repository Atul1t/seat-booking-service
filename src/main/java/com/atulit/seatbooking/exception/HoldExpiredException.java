package com.atulit.seatbooking.exception;

/** The hold timed out before the customer confirmed. Maps to HTTP 410 Gone. */
public class HoldExpiredException extends RuntimeException {

    public HoldExpiredException(Long holdId) {
        super("Hold " + holdId + " expired before it was confirmed");
    }
}
