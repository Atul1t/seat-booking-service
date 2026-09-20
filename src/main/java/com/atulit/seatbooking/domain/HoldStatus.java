package com.atulit.seatbooking.domain;

public enum HoldStatus {
    /** Seats are reserved and the customer can still confirm. */
    ACTIVE,
    /** Turned into a booking. */
    CONFIRMED,
    /** Customer abandoned it, or it was cancelled explicitly. */
    RELEASED,
    /** Timed out before confirmation; seats went back to the pool. */
    EXPIRED
}
