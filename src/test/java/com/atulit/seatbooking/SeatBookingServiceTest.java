package com.atulit.seatbooking;

import com.atulit.seatbooking.domain.AppUser;
import com.atulit.seatbooking.repository.AppUserRepository;
import com.atulit.seatbooking.domain.HoldStatus;
import com.atulit.seatbooking.domain.AppUser;
import com.atulit.seatbooking.repository.AppUserRepository;
import com.atulit.seatbooking.domain.SeatStatus;
import com.atulit.seatbooking.domain.AppUser;
import com.atulit.seatbooking.repository.AppUserRepository;
import com.atulit.seatbooking.domain.Show;
import com.atulit.seatbooking.dto.BookingView;
import com.atulit.seatbooking.dto.HoldView;
import com.atulit.seatbooking.dto.SeatMapView;
import com.atulit.seatbooking.dto.SeatView;
import com.atulit.seatbooking.exception.ForbiddenException;
import com.atulit.seatbooking.exception.HoldNotActiveException;
import com.atulit.seatbooking.exception.NotFoundException;
import com.atulit.seatbooking.exception.SeatUnavailableException;
import com.atulit.seatbooking.service.SeatBookingService;
import com.atulit.seatbooking.service.ShowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class SeatBookingServiceTest {

    @Autowired
    private ShowService showService;

    @Autowired
    private SeatBookingService bookingService;


    @Autowired
    private AppUserRepository users;

    private Long userId;
    private Long otherUserId;

    /** Created straight through the repository; these tests are not about login. */
    private Long newUser(String name) {
        return users.save(new AppUser(
                name + "-" + UUID.randomUUID() + "@test.local",
                "hash-not-used-here",
                name,
                Instant.now())).getId();
    }

    private Show show;
    private List<SeatView> seats;

    @BeforeEach
    void setUp() {
        userId = newUser("atulit");
        otherUserId = newUser("someone-else");
        show = showService.createShow(
                "Interstellar", Instant.now().plus(1, ChronoUnit.DAYS), 2, 3);
        seats = showService.getSeatMap(show.getId()).seats();
    }

    private Long seatId(String label) {
        return seats.stream()
                .filter(s -> s.label().equals(label))
                .findFirst()
                .orElseThrow()
                .id();
    }

    @Test
    void showIsLaidOutAsAGrid() {
        SeatMapView map = showService.getSeatMap(show.getId());

        assertThat(map.seats()).hasSize(6);
        assertThat(map.availableCount()).isEqualTo(6);
        assertThat(map.seats().stream().map(SeatView::label))
                .containsExactly("A1", "A2", "A3", "B1", "B2", "B3");
    }

    @Test
    void holdReservesTheRequestedSeats() {
        HoldView hold = bookingService.hold(
                show.getId(), userId, List.of(seatId("A1"), seatId("A2")));

        assertThat(hold.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.seats()).containsExactly("A1", "A2");

        SeatMapView map = showService.getSeatMap(show.getId());
        assertThat(map.availableCount()).isEqualTo(4);
        assertThat(map.seats().stream()
                .filter(s -> s.status() == SeatStatus.HELD)
                .map(SeatView::label))
                .containsExactly("A1", "A2");
    }

    @Test
    void holdingAnAlreadyHeldSeatIsRejected() {
        bookingService.hold(show.getId(), userId, List.of(seatId("A1")));

        assertThatThrownBy(() ->
                bookingService.hold(show.getId(), otherUserId, List.of(seatId("A1"))))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessageContaining("A1");
    }

    @Test
    void aPartiallyUnavailableRequestReservesNothing() {
        bookingService.hold(show.getId(), userId, List.of(seatId("A2")));

        assertThatThrownBy(() -> bookingService.hold(
                show.getId(), otherUserId, List.of(seatId("A1"), seatId("A2"))))
                .isInstanceOf(SeatUnavailableException.class);

        // A1 must still be free: the whole request is atomic, not best-effort.
        SeatMapView map = showService.getSeatMap(show.getId());
        assertThat(map.seats().stream()
                .filter(s -> s.label().equals("A1"))
                .findFirst().orElseThrow()
                .status())
                .isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void confirmTurnsAHoldIntoABooking() {
        HoldView hold = bookingService.hold(
                show.getId(), userId, List.of(seatId("B1"), seatId("B2")));

        BookingView booking = bookingService.confirm(hold.holdId(), userId);

        assertThat(booking.reference()).startsWith("BK");
        assertThat(booking.seats()).containsExactly("B1", "B2");
        assertThat(bookingService.findBooking(booking.reference(), userId).reference())
                .isEqualTo(booking.reference());

        SeatMapView map = showService.getSeatMap(show.getId());
        assertThat(map.seats().stream()
                .filter(s -> s.status() == SeatStatus.BOOKED)
                .map(SeatView::label))
                .containsExactly("B1", "B2");
    }

    @Test
    void aHoldCannotBeConfirmedTwice() {
        HoldView hold = bookingService.hold(show.getId(), userId, List.of(seatId("A3")));
        bookingService.confirm(hold.holdId(), userId);

        assertThatThrownBy(() -> bookingService.confirm(hold.holdId(), userId))
                .isInstanceOf(HoldNotActiveException.class);
    }

    @Test
    void bookedSeatsCannotBeHeldAgain() {
        HoldView hold = bookingService.hold(show.getId(), userId, List.of(seatId("A1")));
        bookingService.confirm(hold.holdId(), userId);

        assertThatThrownBy(() ->
                bookingService.hold(show.getId(), otherUserId, List.of(seatId("A1"))))
                .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    void releasingAHoldPutsTheSeatsBackOnSale() {
        HoldView hold = bookingService.hold(
                show.getId(), userId, List.of(seatId("A1"), seatId("A2")));

        bookingService.release(hold.holdId(), userId);

        assertThat(showService.getSeatMap(show.getId()).availableCount()).isEqualTo(6);
        // And someone else can now take them.
        assertThat(bookingService.hold(show.getId(), otherUserId, List.of(seatId("A1")))
                .seats()).containsExactly("A1");
    }

    @Test
    void anotherCustomerCannotConfirmYourHold() {
        HoldView hold = bookingService.hold(show.getId(), userId, List.of(seatId("A1")));

        assertThatThrownBy(() -> bookingService.confirm(hold.holdId(), otherUserId))
                .isInstanceOf(ForbiddenException.class);

        // ... and the hold is untouched, so the rightful owner can still confirm it.
        assertThat(bookingService.confirm(hold.holdId(), userId).seats())
                .containsExactly("A1");
    }

    @Test
    void anotherCustomerCannotCancelYourHold() {
        HoldView hold = bookingService.hold(show.getId(), userId, List.of(seatId("A2")));

        assertThatThrownBy(() -> bookingService.release(hold.holdId(), otherUserId))
                .isInstanceOf(ForbiddenException.class);

        assertThat(showService.getSeatMap(show.getId()).availableCount()).isEqualTo(5);
    }

    @Test
    void anotherCustomerCannotReadYourBooking() {
        HoldView hold = bookingService.hold(show.getId(), userId, List.of(seatId("A3")));
        var booking = bookingService.confirm(hold.holdId(), userId);

        assertThatThrownBy(() -> bookingService.findBooking(booking.reference(), otherUserId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void myBookingsOnlyShowsMine() {
        HoldView mine = bookingService.hold(show.getId(), userId, List.of(seatId("B1")));
        bookingService.confirm(mine.holdId(), userId);

        HoldView theirs = bookingService.hold(show.getId(), otherUserId, List.of(seatId("B2")));
        bookingService.confirm(theirs.holdId(), otherUserId);

        assertThat(bookingService.myBookings(userId)).hasSize(1);
        assertThat(bookingService.myBookings(userId).get(0).seats()).containsExactly("B1");
        assertThat(bookingService.myBookings(otherUserId)).hasSize(1);
    }

    @Test
    void unknownSeatsAreRejected() {
        assertThatThrownBy(() -> bookingService.hold(show.getId(), userId, List.of(999_999L)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void anEmptyRequestIsRejected() {
        assertThatThrownBy(() -> bookingService.hold(show.getId(), userId, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateSeatIdsInOneRequestAreCollapsed() {
        Long a1 = seatId("A1");

        HoldView hold = bookingService.hold(show.getId(), userId, List.of(a1, a1, a1));

        assertThat(hold.seats()).containsExactly("A1");
    }
}
