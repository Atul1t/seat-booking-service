package com.atulit.seatbooking.web;

import com.atulit.seatbooking.dto.BookingView;
import com.atulit.seatbooking.dto.CreateHoldRequest;
import com.atulit.seatbooking.dto.HoldView;
import com.atulit.seatbooking.security.AuthenticatedUser;
import com.atulit.seatbooking.service.SeatBookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HoldController {

    private final SeatBookingService bookingService;

    public HoldController(SeatBookingService bookingService) {
        this.bookingService = bookingService;
    }

    /** Reserve seats for the configured hold window. 409 if any seat is already taken. */
    @PostMapping("/api/shows/{showId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldView hold(@PathVariable Long showId,
                         @Valid @RequestBody CreateHoldRequest request,
                         @AuthenticationPrincipal Jwt jwt) {
        return bookingService.hold(showId, AuthenticatedUser.idOf(jwt), request.seatIds());
    }

    @GetMapping("/api/holds/{holdId}")
    public HoldView get(@PathVariable Long holdId, @AuthenticationPrincipal Jwt jwt) {
        return bookingService.findHold(holdId, AuthenticatedUser.idOf(jwt));
    }

    /** Turn the hold into a booking. 410 if it expired, 403 if it is not yours. */
    @PostMapping("/api/holds/{holdId}/confirm")
    public BookingView confirm(@PathVariable Long holdId, @AuthenticationPrincipal Jwt jwt) {
        return bookingService.confirm(holdId, AuthenticatedUser.idOf(jwt));
    }

    @DeleteMapping("/api/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable Long holdId, @AuthenticationPrincipal Jwt jwt) {
        bookingService.release(holdId, AuthenticatedUser.idOf(jwt));
    }
}
