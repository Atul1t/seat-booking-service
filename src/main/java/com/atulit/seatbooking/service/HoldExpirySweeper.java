package com.atulit.seatbooking.service;

import com.atulit.seatbooking.domain.HoldStatus;
import com.atulit.seatbooking.domain.SeatHold;
import com.atulit.seatbooking.repository.SeatHoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Puts abandoned holds back on sale.
 *
 * <p>Without this, a customer who opens checkout and closes the tab would take those
 * seats out of circulation permanently. This is the reaper pattern: a periodic sweep
 * that reclaims resources whose owner never came back.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    private final SeatHoldRepository seatHoldRepository;
    private final SeatBookingService seatBookingService;
    private final Clock clock;

    public HoldExpirySweeper(SeatHoldRepository seatHoldRepository,
                             SeatBookingService seatBookingService,
                             Clock clock) {
        this.seatHoldRepository = seatHoldRepository;
        this.seatBookingService = seatBookingService;
        this.clock = clock;
    }

    /**
     * Scheduled entry point.
     *
     * <p>{@code @Transactional} belongs here rather than only on {@link #sweep()}: the
     * scheduler calls this method through the Spring proxy, but this method's own call to
     * {@code sweep()} is a plain self-invocation that the proxy never sees. Annotating
     * only the inner method would leave the scheduled run with no transaction at all.
     */
    @Scheduled(fixedDelayString = "${booking.expiry-sweep-interval-ms}")
    @Transactional
    public void sweepOnSchedule() {
        int released = sweep();
        if (released > 0) {
            log.info("Expired {} abandoned hold(s)", released);
        }
    }

    /**
     * Releases every hold whose window has closed, and returns how many were actually
     * expired by this run.
     */
    @Transactional
    public int sweep() {
        Instant now = clock.instant();
        List<SeatHold> stale =
                seatHoldRepository.findByStatusAndExpiresAtBefore(HoldStatus.ACTIVE, now);

        int expired = 0;
        for (SeatHold candidate : stale) {
            // Re-read under lock: another transaction may have confirmed this hold
            // between the query above and now, in which case it must be left alone.
            SeatHold locked = seatHoldRepository.lockById(candidate.getId()).orElse(null);
            if (locked != null && locked.getStatus() == HoldStatus.ACTIVE) {
                seatBookingService.expireInPlace(locked);
                expired++;
            }
        }
        return expired;
    }
}
