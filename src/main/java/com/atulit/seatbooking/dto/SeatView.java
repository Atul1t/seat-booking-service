package com.atulit.seatbooking.dto;

import com.atulit.seatbooking.domain.Seat;
import com.atulit.seatbooking.domain.SeatStatus;

public record SeatView(Long id, String label, String row, int number, SeatStatus status) {

    public static SeatView of(Seat seat) {
        return new SeatView(seat.getId(), seat.label(), seat.getRowLabel(),
                seat.getSeatNumber(), seat.getStatus());
    }
}
