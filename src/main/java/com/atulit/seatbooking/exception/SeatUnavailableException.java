package com.atulit.seatbooking.exception;

import java.util.List;

/**
 * Thrown when at least one requested seat is already held or booked.
 *
 * <p>This is the exception 49 of the 50 racing threads in {@code ConcurrentHoldTest}
 * receive. It maps to HTTP 409 Conflict.
 */
public class SeatUnavailableException extends RuntimeException {

    private final List<String> seats;

    public SeatUnavailableException(List<String> seats) {
        super("Seats already taken: " + String.join(", ", seats));
        this.seats = List.copyOf(seats);
    }

    public List<String> getSeats() {
        return seats;
    }
}
