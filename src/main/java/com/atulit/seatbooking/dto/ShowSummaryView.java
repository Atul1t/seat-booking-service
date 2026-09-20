package com.atulit.seatbooking.dto;

import java.time.Instant;

public record ShowSummaryView(Long id,
                              String title,
                              Instant startsAt,
                              long totalSeats,
                              long availableCount) {
}
