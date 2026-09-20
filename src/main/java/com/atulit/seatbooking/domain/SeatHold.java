package com.atulit.seatbooking.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A temporary claim on one or more seats while a customer completes checkout.
 *
 * <p>Holds exist so that a customer picking seats does not lose them mid-payment, but
 * an abandoned checkout must not lock seats forever. Hence {@link #expiresAt}.
 */
@Entity
@Table(name = "seat_holds")
public class SeatHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HoldStatus status = HoldStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @OneToMany(mappedBy = "hold", cascade = CascadeType.PERSIST)
    private List<Seat> seats = new ArrayList<>();

    protected SeatHold() {
        // for JPA
    }

    public SeatHold(Show show, AppUser user, Instant createdAt, Instant expiresAt) {
        this.show = show;
        this.user = user;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = HoldStatus.ACTIVE;
    }

    public boolean isExpiredAt(Instant now) {
        return status == HoldStatus.ACTIVE && now.isAfter(expiresAt);
    }

    public void confirm() {
        this.status = HoldStatus.CONFIRMED;
    }

    public void release() {
        this.status = HoldStatus.RELEASED;
    }

    public void expire() {
        this.status = HoldStatus.EXPIRED;
    }

    public void addSeat(Seat seat) {
        seats.add(seat);
        seat.holdFor(this);
    }

    public Long getId() {
        return id;
    }

    public Show getShow() {
        return show;
    }

    public AppUser getUser() {
        return user;
    }

    /**
     * Ownership is compared by id rather than by entity identity, because {@code user}
     * is a lazy proxy here and the caller's instance comes from a different load.
     */
    public boolean isOwnedBy(Long userId) {
        return user.getId().equals(userId);
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public List<Seat> getSeats() {
        return seats;
    }
}
