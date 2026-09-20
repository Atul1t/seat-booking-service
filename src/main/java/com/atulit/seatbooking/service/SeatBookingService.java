package com.atulit.seatbooking.service;

import com.atulit.seatbooking.domain.AppUser;
import com.atulit.seatbooking.domain.Booking;
import com.atulit.seatbooking.domain.HoldStatus;
import com.atulit.seatbooking.domain.Seat;
import com.atulit.seatbooking.domain.SeatHold;
import com.atulit.seatbooking.domain.Show;
import com.atulit.seatbooking.dto.BookingView;
import com.atulit.seatbooking.dto.HoldView;
import com.atulit.seatbooking.exception.ForbiddenException;
import com.atulit.seatbooking.exception.HoldExpiredException;
import com.atulit.seatbooking.exception.HoldNotActiveException;
import com.atulit.seatbooking.exception.NotFoundException;
import com.atulit.seatbooking.exception.SeatUnavailableException;
import com.atulit.seatbooking.repository.AppUserRepository;
import com.atulit.seatbooking.repository.BookingRepository;
import com.atulit.seatbooking.repository.SeatHoldRepository;
import com.atulit.seatbooking.repository.SeatRepository;
import com.atulit.seatbooking.repository.ShowRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Hold / confirm / release, and the concurrency guarantees around them.
 *
 * <p>The rule the whole service exists to enforce: <strong>a seat is never sold twice.</strong>
 *
 * <p>The naive version of this code reads the seats, checks they are available, then writes.
 * That is a classic check-then-act race: two requests can both read AVAILABLE before either
 * writes, and both then believe they won. No amount of application-level checking fixes it,
 * because the gap between the read and the write is where the bug lives.
 *
 * <p>The fix is to make the database serialise the contending transactions, by taking a
 * row-level write lock on the seats <em>before</em> checking availability. The second
 * transaction then blocks until the first commits, and re-reads the seat as HELD.
 * See {@link SeatRepository#lockSeatsForUpdate}.
 */
@Service
public class SeatBookingService {

    /** Guards against someone holding an entire theatre with one request. */
    public static final int MAX_SEATS_PER_HOLD = 10;

    private static final String REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int REFERENCE_LENGTH = 8;

    private final AppUserRepository userRepository;
    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final BookingRepository bookingRepository;
    private final Clock clock;
    private final Duration holdDuration;
    private final SecureRandom random = new SecureRandom();

    public SeatBookingService(AppUserRepository userRepository,
                              ShowRepository showRepository,
                              SeatRepository seatRepository,
                              SeatHoldRepository seatHoldRepository,
                              BookingRepository bookingRepository,
                              Clock clock,
                              @Value("${booking.hold-duration}") Duration holdDuration) {
        this.userRepository = userRepository;
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.seatHoldRepository = seatHoldRepository;
        this.bookingRepository = bookingRepository;
        this.clock = clock;
        this.holdDuration = holdDuration;
    }

    /**
     * Reserves seats for a short window so the customer can complete checkout.
     *
     * @throws NotFoundException         if the show or any seat id does not exist
     * @throws SeatUnavailableException  if any requested seat is already held or booked
     */
    @Transactional
    public HoldView hold(Long showId, Long userId, List<Long> requestedSeatIds) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new NotFoundException("Show " + showId + " not found"));
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User " + userId + " not found"));

        List<Long> seatIds = requestedSeatIds.stream().distinct().sorted().toList();
        if (seatIds.isEmpty()) {
            throw new IllegalArgumentException("At least one seat must be requested");
        }
        if (seatIds.size() > MAX_SEATS_PER_HOLD) {
            throw new IllegalArgumentException(
                    "At most " + MAX_SEATS_PER_HOLD + " seats can be held in one request");
        }

        // Everything after this line is serialised per seat. Two overlapping requests
        // queue here; they do not both proceed.
        List<Seat> seats = seatRepository.lockSeatsForUpdate(seatIds);

        // Deliberately not filtered by show in the locked query: adding a join there
        // would make the database lock the show row too, which would serialise every
        // booking for the show instead of only the ones competing for the same seats.
        boolean allBelongToShow = seats.stream()
                .allMatch(seat -> seat.getShow().getId().equals(showId));
        if (seats.size() != seatIds.size() || !allBelongToShow) {
            throw new NotFoundException("One or more seats do not belong to show " + showId);
        }

        Instant now = clock.instant();

        // Reclaim any seat whose hold has already lapsed but which the sweeper has not
        // reached yet, so availability never depends on a background job staying alive.
        //
        // Only the seat rows are touched. Writing to the SeatHold row here would take
        // locks in the order seat -> hold, the reverse of the order confirm() and the
        // sweeper use, and opposing lock orders are exactly how deadlocks happen. The
        // hold row is left ACTIVE for the sweeper to settle; confirm() re-checks expiry
        // itself, so a lapsed hold still can never be confirmed.
        seats.stream()
                .filter(seat -> seat.getHold() != null && seat.getHold().isExpiredAt(now))
                .forEach(Seat::release);

        List<String> unavailable = seats.stream()
                .filter(seat -> !seat.isAvailable())
                .map(Seat::label)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new SeatUnavailableException(unavailable);
        }

        SeatHold hold = new SeatHold(show, user, now, now.plus(holdDuration));
        // Flushed so the hold has an id before the seats point at it.
        seatHoldRepository.saveAndFlush(hold);

        seats.forEach(hold::addSeat);
        seatRepository.saveAll(seats);

        return HoldView.of(hold);
    }

    /**
     * Turns an active hold into a booking.
     *
     * <p>The hold row is locked first so two concurrent confirmations of the same hold
     * cannot both produce a booking; the second sees status CONFIRMED and is rejected.
     *
     * <p>{@code noRollbackFor} is load-bearing. The expired-hold branch below releases the
     * seats and then throws, to produce a 410. Spring rolls a transaction back by default
     * when a RuntimeException escapes a {@code @Transactional} method, which would quietly
     * undo that release and leave the seats held until the sweeper ran. At that point in
     * the method the release is the only pending change, so committing it is exactly right.
     */
    @Transactional(noRollbackFor = HoldExpiredException.class)
    public BookingView confirm(Long holdId, Long userId) {
        SeatHold hold = seatHoldRepository.lockById(holdId)
                .orElseThrow(() -> new NotFoundException("Hold " + holdId + " not found"));

        requireOwner(hold, userId, holdId);

        // A hold that timed out must look the same to the caller whether the sweeper
        // noticed first (status already EXPIRED) or this request noticed (still ACTIVE
        // but past expiresAt). Both are 410. 409 is reserved for a hold that was
        // confirmed or cancelled, which is a different problem for the caller: retrying
        // will never help, whereas a 410 means "pick your seats again".
        if (hold.getStatus() == HoldStatus.EXPIRED) {
            throw new HoldExpiredException(holdId);
        }
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new HoldNotActiveException(holdId, hold.getStatus());
        }

        Instant now = clock.instant();
        if (hold.isExpiredAt(now)) {
            // Release immediately rather than waiting for the sweeper, so the seats go
            // back on sale the moment we notice. Locks are taken in the same order the
            // sweeper uses (hold, then seats by id), so the two cannot deadlock.
            expireInPlace(hold);
            throw new HoldExpiredException(holdId);
        }

        List<Seat> seats = seatRepository.lockSeatsOfHold(holdId);
        seats.forEach(Seat::markBooked);
        seatRepository.saveAll(seats);

        hold.confirm();
        Booking booking = bookingRepository.save(new Booking(newReference(), hold, now));
        return BookingView.of(booking);
    }

    /** Customer abandoned checkout, or cancelled explicitly. Seats go back on sale. */
    @Transactional
    public void release(Long holdId, Long userId) {
        SeatHold hold = seatHoldRepository.lockById(holdId)
                .orElseThrow(() -> new NotFoundException("Hold " + holdId + " not found"));

        requireOwner(hold, userId, holdId);

        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new HoldNotActiveException(holdId, hold.getStatus());
        }

        List<Seat> seats = seatRepository.lockSeatsOfHold(holdId);
        seats.forEach(Seat::release);
        seatRepository.saveAll(seats);
        hold.release();
    }

    /**
     * Releases the seats of a hold that timed out. Assumes the caller already holds the
     * lock on {@code hold}.
     */
    void expireInPlace(SeatHold hold) {
        List<Seat> seats = seatRepository.lockSeatsOfHold(hold.getId());
        seats.forEach(Seat::release);
        seatRepository.saveAll(seats);
        hold.expire();
    }

    /**
     * Returns 403 rather than 404 for someone else's hold. That is a deliberate
     * trade-off: 403 is honest and easy to debug, but it confirms the hold exists,
     * which 404 would not. For seat holds that leak is harmless; for something
     * sensitive you would return 404 and say nothing.
     */
    private void requireOwner(SeatHold hold, Long userId, Long holdId) {
        if (!hold.isOwnedBy(userId)) {
            throw new ForbiddenException("Hold " + holdId + " belongs to another customer");
        }
    }

    @Transactional(readOnly = true)
    public BookingView findBooking(String reference, Long userId) {
        Booking booking = bookingRepository.findByReference(reference)
                .orElseThrow(() -> new NotFoundException("Booking " + reference + " not found"));
        if (!booking.isOwnedBy(userId)) {
            throw new ForbiddenException("Booking " + reference + " belongs to another customer");
        }
        return BookingView.of(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingView> myBookings(Long userId) {
        return bookingRepository.findByUserIdOrderByConfirmedAtDesc(userId).stream()
                .map(BookingView::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public HoldView findHold(Long holdId, Long userId) {
        SeatHold hold = seatHoldRepository.findById(holdId)
                .orElseThrow(() -> new NotFoundException("Hold " + holdId + " not found"));
        requireOwner(hold, userId, holdId);
        return HoldView.of(hold);
    }

    private String newReference() {
        StringBuilder sb = new StringBuilder("BK");
        for (int i = 0; i < REFERENCE_LENGTH; i++) {
            sb.append(REFERENCE_ALPHABET.charAt(random.nextInt(REFERENCE_ALPHABET.length())));
        }
        return sb.toString();
    }
}
