package com.atulit.seatbooking.web;

import com.atulit.seatbooking.dto.BookingView;
import com.atulit.seatbooking.security.AuthenticatedUser;
import com.atulit.seatbooking.service.SeatBookingService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final SeatBookingService bookingService;

    public BookingController(SeatBookingService bookingService) {
        this.bookingService = bookingService;
    }

    /** Everything the signed-in customer has booked, newest first. */
    @GetMapping
    public List<BookingView> mine(@AuthenticationPrincipal Jwt jwt) {
        return bookingService.myBookings(AuthenticatedUser.idOf(jwt));
    }

    @GetMapping("/{reference}")
    public BookingView get(@PathVariable String reference, @AuthenticationPrincipal Jwt jwt) {
        return bookingService.findBooking(reference, AuthenticatedUser.idOf(jwt));
    }
}
