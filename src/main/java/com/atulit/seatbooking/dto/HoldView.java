package com.atulit.seatbooking.dto;

import com.atulit.seatbooking.domain.HoldStatus;
import com.atulit.seatbooking.domain.Seat;
import com.atulit.seatbooking.domain.SeatHold;

import java.time.Instant;
import java.util.List;

public record HoldView(Long holdId,
                       Long showId,
                       String customerName,
                       HoldStatus status,
                       List<String> seats,
                       Instant expiresAt) {

    /** Must be called inside the transaction: it walks the lazy seat collection. */
    public static HoldView of(SeatHold hold) {
        return new HoldView(
                hold.getId(),
                hold.getShow().getId(),
                hold.getUser().getDisplayName(),
                hold.getStatus(),
                hold.getSeats().stream().map(Seat::label).sorted().toList(),
                hold.getExpiresAt());
    }
}
