package com.atulit.seatbooking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One physical seat for one show.
 *
 * <p>This row is the unit of contention in the whole service. Two customers racing for
 * the same seat are, at the database level, two transactions trying to lock this row.
 * Everything in {@code SeatBookingService} is built around taking that lock in a
 * predictable order.
 */
@Entity
@Table(
        name = "seats",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_seat_per_show",
                columnNames = {"show_id", "row_label", "seat_number"}
        )
)
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @Column(name = "row_label", nullable = false, length = 8)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeatStatus status = SeatStatus.AVAILABLE;

    /** Set while the seat is HELD or BOOKED; null when AVAILABLE. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_id")
    private SeatHold hold;

    protected Seat() {
        // for JPA
    }

    public Seat(Show show, String rowLabel, int seatNumber) {
        this.show = show;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.status = SeatStatus.AVAILABLE;
    }

    public void holdFor(SeatHold hold) {
        this.status = SeatStatus.HELD;
        this.hold = hold;
    }

    public void markBooked() {
        this.status = SeatStatus.BOOKED;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
        this.hold = null;
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }

    public String label() {
        return rowLabel + seatNumber;
    }

    public Long getId() {
        return id;
    }

    public Show getShow() {
        return show;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public SeatHold getHold() {
        return hold;
    }
}
