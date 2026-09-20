package com.atulit.seatbooking.dto;

import com.atulit.seatbooking.domain.Show;

import java.time.Instant;

public record ShowView(Long id, String title, Instant startsAt) {

    public static ShowView of(Show show) {
        return new ShowView(show.getId(), show.getTitle(), show.getStartsAt());
    }
}
