package com.atulit.seatbooking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A confirmed purchase. One booking per hold, enforced by the unique FK on hold_id.
 */
@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String reference;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hold_id", nullable = false, unique = true)
    private SeatHold hold;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    protected Booking() {
        // for JPA
    }

    public Booking(String reference, SeatHold hold, Instant confirmedAt) {
        this.reference = reference;
        this.hold = hold;
        this.show = hold.getShow();
        this.user = hold.getUser();
        this.confirmedAt = confirmedAt;
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public SeatHold getHold() {
        return hold;
    }

    public Show getShow() {
        return show;
    }

    public AppUser getUser() {
        return user;
    }

    public boolean isOwnedBy(Long userId) {
        return user.getId().equals(userId);
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
