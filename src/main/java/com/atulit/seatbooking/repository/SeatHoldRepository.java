package com.atulit.seatbooking.repository;

import com.atulit.seatbooking.domain.HoldStatus;
import com.atulit.seatbooking.domain.SeatHold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    /**
     * Locks the hold row so that two concurrent confirm requests for the same hold
     * cannot both create a booking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from SeatHold h where h.id = :id")
    Optional<SeatHold> lockById(@Param("id") Long id);

    List<SeatHold> findByStatusAndExpiresAtBefore(HoldStatus status, Instant cutoff);
}
