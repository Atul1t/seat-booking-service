package com.atulit.seatbooking.repository;

import com.atulit.seatbooking.domain.Seat;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByShowIdOrderByRowLabelAscSeatNumberAsc(Long showId);

    /** Total seats in a show, for the availability counter on the browse screen. */
    long countByShowId(Long showId);

    long countByShowIdAndStatus(Long showId, com.atulit.seatbooking.domain.SeatStatus status);

    /**
     * Takes a row-level write lock on the requested seats and returns them in id order.
     *
     * <p>This is the heart of the service. In PostgreSQL this becomes
     * {@code SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE}: any other transaction
     * asking for an overlapping set of seats blocks here until this one commits or rolls
     * back, which is what makes the availability check that follows trustworthy.
     *
     * <p>The {@code ORDER BY id} is not cosmetic. If one transaction locked seat 5 then
     * seat 9, while another locked 9 then 5, they would deadlock. Locking every set in
     * ascending id order means two overlapping requests always contend on their lowest
     * shared seat first, so one simply waits instead of deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id in :seatIds order by s.id")
    List<Seat> lockSeatsForUpdate(@Param("seatIds") Collection<Long> seatIds);

    /** Same lock, used by the expiry sweeper which works from a hold rather than a seat list. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.hold.id = :holdId order by s.id")
    List<Seat> lockSeatsOfHold(@Param("holdId") Long holdId);
}
