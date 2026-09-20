package com.atulit.seatbooking.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateShowRequest(
        @NotBlank String title,
        @NotNull Instant startsAt,
        @Min(1) @Max(26) int rows,
        @Min(1) @Max(50) int seatsPerRow) {
}
