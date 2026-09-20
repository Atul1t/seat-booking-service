package com.atulit.seatbooking;

import com.atulit.seatbooking.domain.AppUser;
import com.atulit.seatbooking.repository.AppUserRepository;
import com.atulit.seatbooking.domain.SeatStatus;
import com.atulit.seatbooking.domain.AppUser;
import com.atulit.seatbooking.repository.AppUserRepository;
import com.atulit.seatbooking.domain.Show;
import com.atulit.seatbooking.dto.HoldView;
import com.atulit.seatbooking.dto.SeatMapView;
import com.atulit.seatbooking.exception.HoldNotActiveException;
import com.atulit.seatbooking.exception.SeatUnavailableException;
import com.atulit.seatbooking.service.SeatBookingService;
import com.atulit.seatbooking.service.ShowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of the whole project.
 *
 * <p>A single-threaded test cannot tell a correct implementation from a broken one here:
 * read-check-write passes every sequential test and still sells the same seat twice in
 * production. These tests put real threads in real transactions against a real database
 * and assert the invariant directly.
 *
 * <p>To see them fail, delete the {@code @Lock(PESSIMISTIC_WRITE)} annotation from
 * {@code SeatRepository.lockSeatsForUpdate} and run them again.
 *
 * <p>These run against a real PostgreSQL rather than the in-memory H2 the other test
 * classes use. That is deliberate: row locking is the database's behaviour, not the
 * application's, so proving it on a different engine than production proves nothing.
 * Requires Docker to be running.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers
class ConcurrentHoldTest {

    /**
     * Started once for this class and discarded afterwards. {@code @ServiceConnection}
     * hands Spring the container's url, username and password, so no datasource config
     * is needed in the profile.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final int THREADS = 50;
    private static final int TIMEOUT_SECONDS = 60;

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

    @BeforeEach
    void createUsers() {
        userId = newUser("racer");
        otherUserId = newUser("other-racer");
    }

    private Show showWithSeats(String title, int rows, int seatsPerRow) {
        return showService.createShow(
                title, Instant.now().plus(1, ChronoUnit.DAYS), rows, seatsPerRow);
    }

    private List<Long> seatIds(Show show) {
        return showService.getSeatMap(show.getId()).seats().stream()
                .map(seat -> seat.id())
                .toList();
    }

    /** Runs {@code task} on {@code threads} threads released at the same instant. */
    private static void raceAll(int threads, Runnable task) throws InterruptedException {
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGun.await();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGun.countDown();
            assertThat(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("all %d threads finished within %ds (a hang here means deadlock)",
                            threads, TIMEOUT_SECONDS)
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyOneOfFiftySimultaneousRequestsGetsTheLastSeat() throws InterruptedException {
        Show show = showWithSeats("Oppenheimer", 1, 1);
        Long theOnlySeat = seatIds(show).get(0);

        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        raceAll(THREADS, () -> {
            try {
                bookingService.hold(show.getId(), userId, List.of(theOnlySeat));
                won.incrementAndGet();
            } catch (SeatUnavailableException expected) {
                lost.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        assertThat(unexpected)
                .as("no thread should fail for a reason other than the seat being taken")
                .isEmpty();
        assertThat(won.get()).as("exactly one winner").isEqualTo(1);
        assertThat(lost.get()).as("everyone else is told the seat is gone")
                .isEqualTo(THREADS - 1);

        SeatMapView map = showService.getSeatMap(show.getId());
        assertThat(map.availableCount()).isZero();
        assertThat(map.seats().get(0).status()).isEqualTo(SeatStatus.HELD);
    }

    /**
     * Two seats, and every thread wants both — but half ask in the order (A1, A2) and
     * half in the order (A2, A1).
     *
     * <p>If the service locked seats in the order the caller supplied them, thread X could
     * hold A1 while waiting for A2 and thread Y hold A2 while waiting for A1: a textbook
     * deadlock. The service sorts seat ids before locking, so every transaction contends
     * on the lowest shared seat first and one simply waits. A hang here is the bug.
     */
    @Test
    void overlappingRequestsInOppositeOrderDoNotDeadlock() throws InterruptedException {
        Show show = showWithSeats("Dune", 1, 2);
        List<Long> ids = seatIds(show);
        Long first = ids.get(0);
        Long second = ids.get(1);

        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger sequence = new AtomicInteger();

        raceAll(THREADS, () -> {
            boolean reversed = sequence.getAndIncrement() % 2 == 0;
            List<Long> requested = reversed ? List.of(second, first) : List.of(first, second);
            try {
                bookingService.hold(show.getId(), userId, requested);
                won.incrementAndGet();
            } catch (SeatUnavailableException expected) {
                lost.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        assertThat(unexpected).isEmpty();
        assertThat(won.get()).isEqualTo(1);
        assertThat(lost.get()).isEqualTo(THREADS - 1);
        assertThat(showService.getSeatMap(show.getId()).availableCount()).isZero();
    }

    /**
     * A double-clicked "Pay now" button must not produce two bookings. The hold row is
     * locked on confirm, so the second attempt finds the hold already CONFIRMED.
     */
    @Test
    void simultaneousConfirmationsOfOneHoldProduceOneBooking() throws InterruptedException {
        Show show = showWithSeats("Tenet", 1, 2);
        HoldView hold = bookingService.hold(show.getId(), userId, seatIds(show));

        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        raceAll(20, () -> {
            try {
                bookingService.confirm(hold.holdId(), userId);
                confirmed.incrementAndGet();
            } catch (HoldNotActiveException expected) {
                rejected.incrementAndGet();
            } catch (Throwable t) {
                unexpected.add(t);
            }
        });

        assertThat(unexpected).isEmpty();
        assertThat(confirmed.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(19);

        SeatMapView map = showService.getSeatMap(show.getId());
        assertThat(map.seats()).allSatisfy(seat ->
                assertThat(seat.status()).isEqualTo(SeatStatus.BOOKED));
    }
}
