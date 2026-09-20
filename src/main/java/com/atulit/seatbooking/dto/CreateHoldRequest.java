package com.atulit.seatbooking.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Note what is no longer here: customerRef.
 *
 * <p>Identity now comes from the bearer token, never from the request body. A client
 * that could name its own customer could name somebody else's.
 */
public record CreateHoldRequest(@NotEmpty List<Long> seatIds) {
}
