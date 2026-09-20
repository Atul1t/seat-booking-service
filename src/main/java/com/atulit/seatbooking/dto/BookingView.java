package com.atulit.seatbooking.dto;

import com.atulit.seatbooking.domain.Booking;
import com.atulit.seatbooking.domain.Seat;

import java.time.Instant;
import java.util.List;

public record BookingView(String reference,
                          Long showId,
                          String customerName,
                          List<String> seats,
                          Instant confirmedAt) {

    /** Must be called inside the transaction: it walks the lazy seat collection. */
    public static BookingView of(Booking booking) {
        return new BookingView(
                booking.getReference(),
                booking.getShow().getId(),
                booking.getUser().getDisplayName(),
                booking.getHold().getSeats().stream().map(Seat::label).sorted().toList(),
                booking.getConfirmedAt());
    }
}
