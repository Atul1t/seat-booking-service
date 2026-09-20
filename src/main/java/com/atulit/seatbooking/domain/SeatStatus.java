package com.atulit.seatbooking.domain;

/**
 * Lifecycle of a single seat for a single show.
 *
 * <p>AVAILABLE -> HELD happens when a customer starts checkout.
 * HELD -> BOOKED happens on confirmation.
 * HELD -> AVAILABLE happens when the hold is released or expires.
 * BOOKED is terminal in this version of the service.
 */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    BOOKED
}
