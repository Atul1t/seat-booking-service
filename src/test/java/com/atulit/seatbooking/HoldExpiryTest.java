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
import com.atulit.seatbooking.dto.HoldView;
import com.atulit.seatbooking.dto.SeatMapView;
import com.atulit.seatbooking.exception.HoldExpiredException;
import com.atulit.seatbooking.service.HoldExpirySweeper;
import com.atulit.seatbooking.service.SeatBookingService;
import com.atulit.seatbooking.service.ShowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Abandoned checkouts must not take seats out of circulation forever.
 *
 * <p>These tests move a {@link MutableClock} instead of sleeping, so the two-minute hold
 * window is exercised in microseconds.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(HoldExpiryTest.FixedClockConfig.class)
class HoldExpiryTest {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        }
    }

    @Autowired
    private MutableClock clock;

    @Autowired
    private ShowService showService;

    @Autowired
    private SeatBookingService bookingService;

    @Autowired
    private HoldExpirySweeper sweeper;

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

    @BeforeEach
    void createUsers() {
        userId = newUser("atulit");
        otherUserId = newUser("someone-else");
    }

    private Show newShow(String title) {
        return showService.createShow(
                title, Instant.now().plus(1, ChronoUnit.DAYS), 1, 2);
    }

    private List<Long> seatIds(Show show) {
        return showService.getSeatMap(show.getId()).seats().stream()
                .map(seat -> seat.id())
                .toList();
    }

    @Test
    void anAbandonedHoldIsSweptAndItsSeatsGoBackOnSale() {
        Show show = newShow("Arrival");
        HoldView hold = bookingService.hold(show.getId(), userId, seatIds(show));
        assertThat(showService.getSeatMap(show.getId()).availableCount()).isZero();

        clock.advance(Duration.ofMinutes(3));
        sweeper.sweep();

        assertThat(bookingService.findHold(hold.holdId(), userId).status())
                .isEqualTo(HoldStatus.EXPIRED);
        SeatMapView map = showService.getSeatMap(show.getId());
        assertThat(map.availableCount()).isEqualTo(2);
        assertThat(map.seats()).allSatisfy(seat ->
                assertThat(seat.status()).isEqualTo(SeatStatus.AVAILABLE));
    }

    @Test
    void aHoldInsideItsWindowIsLeftAlone() {
        Show show = newShow("Sicario");
        HoldView hold = bookingService.hold(show.getId(), userId, seatIds(show));

        clock.advance(Duration.ofSeconds(90));
        sweeper.sweep();

        assertThat(bookingService.findHold(hold.holdId(), userId).status())
                .isEqualTo(HoldStatus.ACTIVE);
        assertThat(showService.getSeatMap(show.getId()).availableCount()).isZero();
    }

    @Test
    void confirmingAnExpiredHoldFailsAndReleasesTheSeatsImmediately() {
        Show show = newShow("Blade Runner 2049");
        HoldView hold = bookingService.hold(show.getId(), userId, seatIds(show));

        clock.advance(Duration.ofMinutes(5));

        assertThatThrownBy(() -> bookingService.confirm(hold.holdId(), userId))
                .isInstanceOf(HoldExpiredException.class);

        // Not left for the sweeper: the seats are on sale again right away.
        assertThat(showService.getSeatMap(show.getId()).availableCount()).isEqualTo(2);
        assertThat(bookingService.findHold(hold.holdId(), userId).status())
                .isEqualTo(HoldStatus.EXPIRED);
    }

    /**
     * The case a real clock exposed but a controlled one did not: the sweeper reaches
     * the hold before the customer does. The customer's experience is identical either
     * way, so the status code must be too.
     */
    @Test
    void aHoldTheSweeperAlreadyExpiredStillReportsAsExpired() {
        Show show = newShow("Stalker");
        HoldView hold = bookingService.hold(show.getId(), userId, seatIds(show));

        clock.advance(Duration.ofMinutes(5));
        sweeper.sweep();

        assertThatThrownBy(() -> bookingService.confirm(hold.holdId(), userId))
                .isInstanceOf(HoldExpiredException.class);
    }

    @Test
    void confirmedBookingsSurviveTheSweeper() {
        Show show = newShow("Prisoners");
        HoldView hold = bookingService.hold(show.getId(), userId, seatIds(show));
        bookingService.confirm(hold.holdId(), userId);

        clock.advance(Duration.ofHours(1));
        sweeper.sweep();

        SeatMapView map = showService.getSeatMap(show.getId());
        assertThat(map.availableCount()).isZero();
        assertThat(map.seats()).allSatisfy(seat ->
                assertThat(seat.status()).isEqualTo(SeatStatus.BOOKED));
    }

    @Test
    void aLapsedHoldDoesNotBlockTheNextCustomerEvenIfTheSweeperNeverRuns() {
        Show show = newShow("Nocturnal Animals");
        List<Long> seats = seatIds(show);
        HoldView abandoned = bookingService.hold(show.getId(), userId, seats);

        clock.advance(Duration.ofMinutes(3));
        // Deliberately no sweeper.sweep() here: a customer must not be turned away
        // just because the reaper has not run yet.

        HoldView next = bookingService.hold(show.getId(), userId, seats);
        assertThat(next.seats()).containsExactly("A1", "A2");

        // And the abandoned hold cannot claw the seats back off the new customer.
        assertThatThrownBy(() -> bookingService.confirm(abandoned.holdId(), userId))
                .isInstanceOf(HoldExpiredException.class);
        assertThat(showService.getSeatMap(show.getId()).availableCount()).isZero();
    }

    @Test
    void seatsFreedByTheSweeperCanBeBookedBySomeoneElse() {
        Show show = newShow("Enemy");
        bookingService.hold(show.getId(), userId, seatIds(show));

        clock.advance(Duration.ofMinutes(3));
        sweeper.sweep();

        HoldView second = bookingService.hold(show.getId(), userId, seatIds(show));
        assertThat(second.seats()).containsExactly("A1", "A2");
    }
}
