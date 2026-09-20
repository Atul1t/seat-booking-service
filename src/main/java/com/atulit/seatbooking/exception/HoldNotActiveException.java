package com.atulit.seatbooking.exception;

import com.atulit.seatbooking.domain.HoldStatus;

/** The hold exists but has already been confirmed or released. Maps to HTTP 409. */
public class HoldNotActiveException extends RuntimeException {

    public HoldNotActiveException(Long holdId, HoldStatus status) {
        super("Hold " + holdId + " is not active (status: " + status + ")");
    }
}
