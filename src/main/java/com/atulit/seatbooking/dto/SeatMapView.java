package com.atulit.seatbooking.dto;

import java.time.Instant;
import java.util.List;

public record SeatMapView(Long showId,
                          String title,
                          Instant startsAt,
                          long availableCount,
                          List<SeatView> seats) {
}
